package ai.opencode.core.dependencies

import ai.opencode.core.R
import ai.opencode.core.strings.localized
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Moved down to `:core` because the app, the browser, the transcript and the composer all copy and
 * paste. The system clipboard, for reducers (through effects) and views; never touch
 * `ClipboardManager` elsewhere.
 */
class PasteboardClient(
    val setURL: suspend (String) -> Unit,
    val setString: suspend (String) -> Unit = {},
    /** The clipboard's text if it isn't blank, else its URL. Main thread. */
    val string: () -> String? = { null },
    val hasImages: () -> Boolean = { false },
    /** The clipboard's images (content URIs), for the composer's + menu Paste. Main thread. */
    val imageURIs: () -> List<Uri> = { emptyList() },
) {
    companion object Key : DependencyKey<PasteboardClient> {
        override val liveValue: PasteboardClient by lazy {
            val context = dependency(AppContext)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            PasteboardClient(
                setURL = { url -> withContext(Dispatchers.Main) { clipboard.setPrimaryClip(ClipData.newRawUri(localized(R.string.core_clipboard_url_label), url.toUri())) } },
                setString = { text -> withContext(Dispatchers.Main) { clipboard.setPrimaryClip(ClipData.newPlainText(localized(R.string.core_clipboard_text_label), text)) } },
                string = {
                    val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                    item?.text?.toString()?.takeIf { it.isNotBlank() } ?: item?.uri?.toString()
                },
                hasImages = {
                    val description = runCatching { clipboard.primaryClipDescription }.getOrNull()
                    description != null && (0 until description.mimeTypeCount).any { description.getMimeType(it).startsWith("image/") }
                },
                imageURIs = {
                    val clip = clipboard.primaryClip
                    val resolver = context.contentResolver
                    if (clip == null) emptyList() else (0 until clip.itemCount).mapNotNull { index ->
                        clip.getItemAt(index).uri?.takeIf { uri ->
                            val type = runCatching { resolver.getType(uri) }.getOrNull()
                            type?.startsWith("image/") ?: clip.description.hasMimeType("image/*")
                        }
                    }
                },
            )
        }
        override val testValue = PasteboardClient(
            setURL = { throw AssertionError("Unimplemented: PasteboardClient.setURL") },
            setString = {},
            string = { null },
            hasImages = { false },
            imageURIs = { emptyList() },
        )
    }
}

