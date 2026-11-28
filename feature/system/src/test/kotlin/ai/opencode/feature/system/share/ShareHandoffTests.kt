package ai.opencode.feature.system.share

import ai.opencode.core.files.ComposerFileStaging
import ai.opencode.core.files.AttachmentFileType
import ai.opencode.core.testing.TestIsolationRule
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

// `SharedImageEncoderTests` and the `MimeTypeMap` half of `SharedItemLoaderAndActivationTests`
// (`SharedItemLoaderAndActivationFrameworkTests`) run under Robolectric (real
// `ImageDecoder`/`Bitmap`/`ExifInterface` and `MimeTypeMap`).

class ShareHandoffFileTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun sharedFilesMoveIntoTheAppStagingDirectory() {
        val root = Files.createTempDirectory("share-root-").toFile()
        val staging = Files.createTempDirectory("share-staging-").toFile().also { it.delete() }
        try {
            val source = File(root, "source.pdf").apply { writeBytes("%PDF".toByteArray()) }
            val store = ShareHandoffStore(File(root, "Shares"))
            val id = UUID.randomUUID()
            val handoff = ShareHandoff(
                id = UUID.randomUUID(),
                createdAt = Instant.now(),
                serverID = "server",
                target = ShareHandoff.Target.NewSession(projectID = null),
                text = "",
                files = listOf(ShareHandoff.FileEntry(id = id, fileName = "file-0", name = "Q3 Report.pdf", mime = "application/pdf")),
            )
            store.save(handoff, images = emptyList(), files = listOf(source))

            val shares = store.consumeAll(now = Instant.now(), filesDirectory = staging)

            val file = requireNotNull(shares.firstOrNull()?.files?.firstOrNull())
            assertEquals("Q3 Report.pdf", file.name)
            assertEquals(4L, file.size)
            assertEquals(File(File(staging, id.toString().uppercase()), "Q3 Report.pdf"), file.url)
            assertArrayEquals("%PDF".toByteArray(), file.url.readBytes())
        } finally {
            root.deleteRecursively()
            staging.deleteRecursively()
        }
    }
}

class ShareHandoffStoreTests {
    @get:Rule val isolation = TestIsolationRule()

    private val root: File = Files.createTempDirectory("share-handoff-").toFile().also { it.delete() }
    private val store: ShareHandoffStore get() = ShareHandoffStore(root)
    private val now: Instant = Instant.ofEpochSecond(1_800_000_000)

    @After
    fun removeRoot() {
        root.deleteRecursively()
    }

    private fun handoff(
        id: UUID = UUID.randomUUID(),
        createdAt: Instant,
        target: ShareHandoff.Target = ShareHandoff.Target.Session(id = "ses_1", projectID = "proj_1"),
        images: List<ShareHandoff.Image> = emptyList(),
    ) = ShareHandoff(id = id, createdAt = createdAt, serverID = "server", target = target, text = "Look at this", images = images)

    private fun entries(): List<String> = root.list().orEmpty().sorted()

    @Test
    fun savedSharesRoundTripOldestFirstAndAreConsumedOnce() {
        val imageID = UUID.randomUUID()
        val newer = handoff(
            createdAt = now.minusSeconds(10), target = ShareHandoff.Target.NewSession(projectID = "proj_2"),
            images = listOf(ShareHandoff.Image(id = imageID, fileName = "image-0.jpg", mime = "image/jpeg")),
        )
        val older = handoff(createdAt = now.minusSeconds(60))
        store.save(newer, images = listOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01)))
        store.save(older, images = emptyList())

        val received = store.consumeAll(now = now)
        assertEquals(listOf(older, newer), received.map { it.handoff })
        assertEquals(listOf(ReceivedShare.Image(id = imageID, data = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01), mime = "image/jpeg")), received[1].images)
        assertTrue(store.consumeAll(now = now).isEmpty())
        assertTrue(entries().isEmpty())
    }

    @Test
    fun expiredUnreadableAndAbandonedEntriesAreDiscarded() {
        store.save(handoff(createdAt = now.minus(ShareHandoffStore.MAXIMUM_AGE).minusSeconds(1)), images = emptyList())
        val corrupt = File(root, UUID.randomUUID().toString().uppercase()).apply { mkdirs() }
        File(corrupt, "share.json").writeText("not json")
        val abandoned = File(root, ".staging-${UUID.randomUUID().toString().uppercase()}").apply { mkdirs() }
        abandoned.setLastModified(now.minusSeconds(2 * 60 * 60).toEpochMilli())
        val inProgress = File(root, ".staging-${UUID.randomUUID().toString().uppercase()}").apply { mkdirs() }
        inProgress.setLastModified(now.toEpochMilli())

        assertTrue(store.consumeAll(now = now).isEmpty())
        // Only the share sheet's still-fresh write survives.
        assertEquals(listOf(inProgress.name), entries())
    }

    @Test
    fun imageNamesCannotEscapeTheirShare() {
        root.mkdirs()
        File(root, "outside.jpg").writeBytes(byteArrayOf(1))
        var share = handoff(createdAt = now, images = listOf(ShareHandoff.Image(id = UUID.randomUUID(), fileName = "inside.jpg", mime = "image/jpeg")))
        store.save(share, images = listOf(byteArrayOf(2)))
        // Rewrite the manifest as a hostile writer would.
        share = share.copy(images = share.images + ShareHandoff.Image(id = UUID.randomUUID(), fileName = "../outside.jpg", mime = "image/jpeg"))
        File(File(root, share.id.toString().uppercase()), "share.json").writeText(ShareJson.encodeToString(ShareHandoff.serializer(), share))

        val received = store.consumeAll(now = now).first()
        assertEquals(listOf(listOf<Byte>(2)), received.images.map { it.data.toList() })
    }
}

/**
 * Android reads a share's one `content://` stream, named from `OpenableColumns.DISPLAY_NAME` or the
 * URI's last segment, and filters shares with the manifest's intent filters. What carries over is
 * the name each JSON share is staged under ([SharedItemLoader.resolvedFileName]); the
 * representation-order, sparse/FIFO-file, cancellation and precedence probes are provider
 * mechanics. The non-JSON `clip.mov` case and the staged copy's type read Android's `MimeTypeMap`:
 * [SharedItemLoaderAndActivationFrameworkTests].
 */
class SharedItemLoaderAndActivationTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun stagesJSONAcrossFileURLTransferableAndInMemoryRepresentationsWithoutRewritingBytes() {
        val json = "application/json"
        // 1a/6. A file's own name (no suggested name): the compound name keeps `.json`.
        assertEquals("opencode-diagnostics.txt.json", SharedItemLoader.resolvedFileName(suggested = null, fallbackName = "opencode-diagnostics.txt.json", type = json))
        // 2. Only the provider's temporary copy is named.
        assertEquals("JSON.json", SharedItemLoader.resolvedFileName(suggested = null, fallbackName = "JSON.json", type = json))
        // 3. A suggested `.txt` name for a JSON file gains `.json`.
        assertEquals("opencode-diagnostics.txt.json", SharedItemLoader.resolvedFileName(suggested = "opencode-diagnostics.txt", fallbackName = "JSON.json", type = json))
        // 4. A named in-memory JSON share keeps its name.
        assertEquals("synthetic-events.json", SharedItemLoader.resolvedFileName(suggested = "synthetic-events.json", fallbackName = null, type = json))
        // 5. Unnamed JSON data.
        assertEquals("file.json", SharedItemLoader.resolvedFileName(suggested = null, fallbackName = null, type = json))
    }
}

/**
 * The `MimeTypeMap` half of [SharedItemLoaderAndActivationTests]: the JSON file staged untouched
 * (case 1a's name, type and bytes) and a non-JSON share keeping its suggested name (Robolectric's
 * real `MimeTypeMap` on API 35).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedItemLoaderAndActivationFrameworkTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun chromeSharesTheURLNotItsChooserThumbnail() {
        val preview = Uri.parse("content://sender/thumbnail")
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://example.com/")
        intent.clipData = ClipData.newRawUri("Preview", preview)
        assertEquals(listOf(SharedItemLoader.Provider.Text("https://example.com/")), SharedItemLoader.providers(intent))

        intent.removeExtra(Intent.EXTRA_TEXT)
        assertEquals(listOf(SharedItemLoader.Provider.Stream(preview, "text/plain")), SharedItemLoader.providers(intent))
    }

    @Test
    fun multipleImagesInClipDataArePayloadEvenWithACaption() {
        val first = Uri.parse("content://photos/one")
        val second = Uri.parse("content://photos/two")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").putExtra(Intent.EXTRA_TEXT, "Both photos")
        intent.clipData = ClipData.newRawUri("Photos", first).apply { addItem(ClipData.Item(second)) }
        assertEquals(
            listOf(SharedItemLoader.Provider.Stream(first, null), SharedItemLoader.Provider.Stream(second, null), SharedItemLoader.Provider.Text("Both photos")),
            SharedItemLoader.providers(intent),
        )
    }

    @Test
    fun stagesJSONAcrossFileURLTransferableAndInMemoryRepresentationsWithoutRewritingBytes() {
        // Exact whitespace and key ordering must survive byte-for-byte without decoding/re-encoding.
        val rawJSON = "[\n  { \"seq\": 1, \"event\": \"synthetic_probe\" },\n  { \"seq\": 2, \"event\": \"done\" }\n]\n".toByteArray()
        val directory = Files.createTempDirectory("shared-items-").toFile()
        try {
            val source = File(directory, "opencode-diagnostics.txt.json").apply { writeBytes(rawJSON) }
            val name = SharedItemLoader.resolvedFileName(suggested = null, fallbackName = source.name, type = "application/json")
            val staged = ComposerFileStaging.stage(source, name = name, directory = File(directory, "ComposerFiles"))
            assertEquals("opencode-diagnostics.txt.json", staged.name)
            assertEquals("text/plain", staged.mime)
            assertEquals("JSON", AttachmentFileType.label(name = staged.name, mime = staged.mime))
            assertArrayEquals(rawJSON, staged.url.readBytes())
            // Non-JSON types keep their existing suggested name without extension reconciliation.
            assertEquals("clip.mov", SharedItemLoader.resolvedFileName(suggested = "clip.mov", fallbackName = "temp.mp4", type = "video/quicktime"))
        } finally {
            directory.deleteRecursively()
        }
    }
}

/**
 * API 34: Robolectric 4.16's native `ImageDecoder` shadows its file-descriptor source
 * (`jpeg(file)`) only through API 34.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharedImageEncoderTests {
    @get:Rule val isolation = TestIsolationRule()

    /** A 3000×2000 JPEG tagged as rotated, with camera and GPS metadata. */
    private fun cameraPhoto(): ByteArray {
        val bitmap = Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(51, 102, 204))
        val file = File.createTempFile("camera-", ".jpg")
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            bitmap.recycle()
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_MAKE, "TestCam")
                setAttribute(ExifInterface.TAG_MODEL, "Lens 1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "37/1,19/1,48/1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                saveAttributes()
            }
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    private fun exif(data: ByteArray) = ExifInterface(ByteArrayInputStream(data))

    private fun pixelSize(data: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        return options.outWidth to options.outHeight
    }

    @Test
    fun shareReencodingMatchesTheComposerAndStripsMetadata() {
        val original = cameraPhoto()
        assertNotNull(exif(original).getAttribute(ExifInterface.TAG_GPS_LATITUDE))

        val shared = SharedImageEncoder.jpeg(original)
        assertNotNull(shared)
        shared!!

        val file = File.createTempFile("share-", ".jpg")
        try {
            file.writeBytes(original)
            assertArrayEquals(shared, SharedImageEncoder.jpeg(file))
        } finally {
            file.delete()
        }

        val encoded = exif(shared)
        assertNull(encoded.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(encoded.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals(1, encoded.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1).let { if (it == ExifInterface.ORIENTATION_UNDEFINED) 1 else it })
        // The rotation is applied to the pixels, and the long edge is capped.
        assertEquals(1045 to SharedImageEncoder.MAX_PIXEL_SIZE, pixelSize(shared))
    }

    @Test
    fun oversizedSourcesAreRejected() {
        assertNull(SharedImageEncoder.jpeg(ByteArray(SharedImageEncoder.MAX_SOURCE_BYTES + 1)))
        assertNull(SharedImageEncoder.jpeg("not an image".toByteArray()))
    }

    @Test
    fun imageFilesPastTheFileCapAreRefused() {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(51, 102, 204))
        val file = File.createTempFile("share-", ".png")
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            // Sparse: grows the reported size without writing the bytes.
            Robolectric.setupContentProvider(FileBackedProvider::class.java, "share-test")
            val resolver = RuntimeEnvironment.getApplication().contentResolver
            val uri = Uri.parse("content://share-test" + file.path)
            RandomAccessFile(file, "rw").use { it.setLength(SharedImageEncoder.MAX_SOURCE_FILE_BYTES.toLong()) }
            assertNotNull(SharedImageEncoder.jpeg(file))
            assertNotNull(SharedImageEncoder.jpeg(resolver, uri))
            RandomAccessFile(file, "rw").use { it.setLength(SharedImageEncoder.MAX_SOURCE_FILE_BYTES + 1L) }
            assertNull(SharedImageEncoder.jpeg(file))
            assertNull(SharedImageEncoder.jpeg(resolver, uri))
        } finally {
            file.delete()
        }
    }
}

class FileBackedProvider : ContentProvider() {
    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(File(uri.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
    override fun getType(uri: Uri): String? = null
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
}
