package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.fullscreenViewer: WhatsNewEntry
    get() = feature(
        id = "fullscreen-viewer",
        day = "2026-11-14",
        title = localized(R.string.settings_whatsnew_entry_fullscreen_viewer_title),
        symbol = "photo.on.rectangle",
        tint = Tint.blue,
        summary = localized(R.string.settings_whatsnew_entry_fullscreen_viewer_summary),
        details = listOf(localized(R.string.settings_whatsnew_entry_fullscreen_viewer_detail_1)),
        example = localized(R.string.settings_whatsnew_entry_fullscreen_viewer_example),
    )
