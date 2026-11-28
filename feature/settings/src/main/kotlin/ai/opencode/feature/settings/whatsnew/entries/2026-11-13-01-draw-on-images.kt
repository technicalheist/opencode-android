package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.feature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Tint
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.drawOnImages: WhatsNewEntry
    get() = feature(
        id = "draw-on-images",
        day = "2026-11-13",
        title = localized(R.string.settings_whatsnew_entry_draw_on_images_title),
        symbol = "pencil",
        tint = Tint.red,
        summary = localized(R.string.settings_whatsnew_entry_draw_on_images_summary),
        details = listOf(localized(R.string.settings_whatsnew_entry_draw_on_images_detail_1)),
        example = localized(R.string.settings_whatsnew_entry_draw_on_images_example),
    )
