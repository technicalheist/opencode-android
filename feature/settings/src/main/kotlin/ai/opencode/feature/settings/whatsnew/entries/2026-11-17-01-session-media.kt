package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.sessionMedia: WhatsNewEntry
    get() = feature(
        id = "session-media",
        day = "2026-11-17",
        title = localized(R.string.settings_whatsnew_entry_session_media_title),
        symbol = "photo.on.rectangle",
        tint = Tint.indigo,
        summary = localized(R.string.settings_whatsnew_entry_session_media_summary),
        details = listOf(localized(R.string.settings_whatsnew_entry_session_media_detail_1)),
        example = localized(R.string.settings_whatsnew_entry_session_media_example),
    )
