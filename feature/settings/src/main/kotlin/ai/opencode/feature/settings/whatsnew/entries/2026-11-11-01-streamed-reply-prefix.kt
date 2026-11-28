package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.streamedReplyPrefix: WhatsNewEntry
    get() = smaller(
        id = "streamed-reply-prefix",
        day = "2026-11-11",
        title = localized(R.string.settings_whatsnew_entry_streamed_reply_prefix_title),
        symbol = "text.alignleft",
        summary = localized(R.string.settings_whatsnew_entry_streamed_reply_prefix_summary),
    )
