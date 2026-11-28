package ai.opencode.core.sharing

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Live persistence: AppStorage in one DataStore file, File as atomic JSON, Keychain as AES-256-GCM
 * with an Android Keystore key in `noBackupFilesDir`. Writes are async on one serial I/O thread, in
 * order.
 */
internal class LiveSharedBytes private constructor(private val context: Context) : SharedBytes {
    private val io = Executors.newSingleThreadExecutor { Thread(it, "opencode-shared-io") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
    ) {
        File(context.filesDir, "datastore/app-storage.preferences_pb")
    }
    @Volatile private var preferences: Preferences? = null

    /**
     * File values waiting for the writer, newest only, by path: several changes before the writer
     * gets to a file encode and write it once.
     */
    private val waitingFiles = HashMap<String, () -> ByteArray>()

    override fun read(kind: SharedStorageKind, id: String): ByteArray? = when (kind) {
        SharedStorageKind.AppStorage -> appStorage()[stringPreferencesKey(id)]?.encodeToByteArray()
        // A value still waiting for the writer is the newest one.
        is SharedStorageKind.File -> waitingFile(file(kind.directory, id))?.invoke()
            ?: file(kind.directory, id).takeIf(File::exists)?.readBytes()
        SharedStorageKind.Keychain -> Keychain.read(keychainFile(id))
        SharedStorageKind.InMemory -> null
    }

    private fun waitingFile(file: File): (() -> ByteArray)? = synchronized(waitingFiles) { waitingFiles[file.path] }

    override fun writeEncoded(kind: SharedStorageKind, id: String, encode: () -> ByteArray) {
        if (kind == SharedStorageKind.InMemory) return
        if (kind !is SharedStorageKind.File) return write(kind, id, encode())
        val target = file(kind.directory, id)
        val alreadyQueued = synchronized(waitingFiles) { waitingFiles.put(target.path, encode) != null }
        if (alreadyQueued) return
        io.execute {
            val latest = synchronized(waitingFiles) { waitingFiles.remove(target.path) } ?: return@execute
            try {
                writeAtomically(target, latest())
            } catch (error: Exception) {
                AppLog.app.error { "Couldn’t write shared value: ${error::class.java.simpleName}" }
            }
        }
    }

    override fun write(kind: SharedStorageKind, id: String, data: ByteArray?) {
        if (kind == SharedStorageKind.InMemory) return
        // A direct write replaces any value still waiting, so it lands last.
        if (kind is SharedStorageKind.File) synchronized(waitingFiles) { waitingFiles.remove(file(kind.directory, id).path) }
        io.execute {
            try {
                when (kind) {
                    SharedStorageKind.AppStorage -> runBlocking {
                        dataStore.edit { prefs ->
                            val key = stringPreferencesKey(id)
                            if (data == null) prefs.remove(key) else prefs[key] = data.decodeToString()
                        }
                    }
                    is SharedStorageKind.File -> writeAtomically(file(kind.directory, id), data)
                    SharedStorageKind.Keychain -> writeAtomically(keychainFile(id), data?.let(Keychain::seal))
                    SharedStorageKind.InMemory -> Unit
                }
            } catch (error: Exception) {
                AppLog.app.error { "Couldn’t write shared value (${data?.size ?: 0} bytes): ${error::class.java.simpleName}" }
            }
        }
    }

    override fun flush() {
        val done = java.util.concurrent.CountDownLatch(1)
        io.execute { done.countDown() }
        done.await(5, TimeUnit.SECONDS)
    }

    /** The preferences snapshot: loaded once (blocking), then kept current by observing DataStore. */
    private fun appStorage(): Preferences {
        preferences?.let { return it }
        return synchronized(this) {
            preferences ?: runBlocking(Dispatchers.IO) { dataStore.data.first() }.also { loaded ->
                preferences = loaded
                scope.launch { dataStore.data.collect { preferences = it } }
            }
        }
    }

    private fun file(directory: FileDirectory, name: String): File = File(
        when (directory) {
            FileDirectory.Documents -> File(context.filesDir, "documents")
            FileDirectory.Caches -> context.cacheDir
            FileDirectory.ApplicationSupport -> File(context.filesDir, "support")
        },
        name,
    )

    private fun keychainFile(id: String) = File(File(context.noBackupFilesDir, "keychain"), "$id.bin")

    private fun writeAtomically(target: File, data: ByteArray?) {
        if (data == null) {
            target.delete()
            return
        }
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeBytes(data)
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    /** AES-GCM sealing with a Keystore key that never leaves secure hardware. */
    private object Keychain {
        private const val ALIAS = "opencode-keychain-v1"
        private const val IV_BYTES = 12

        private fun key(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return generator.generateKey()
        }

        fun seal(plain: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            return cipher.iv + cipher.doFinal(plain)
        }

        fun read(file: File): ByteArray? {
            if (!file.exists()) return null
            return try {
                val sealed = file.readBytes()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
                cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
            } catch (error: Exception) {
                AppLog.app.error { "Couldn’t read keychain item: ${error::class.java.simpleName}" }
                null
            }
        }
    }

    companion object {
        fun create(): LiveSharedBytes = LiveSharedBytes(dependency(AppContext).applicationContext)
    }
}
