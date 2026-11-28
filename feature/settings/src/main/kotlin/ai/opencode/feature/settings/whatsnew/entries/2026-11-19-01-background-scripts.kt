package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.backgroundScripts: WhatsNewEntry
    get() = smaller(
        id = "background-scripts",
        day = "2026-11-19",
        title = localized(R.string.settings_whatsnew_entry_background_scripts_title),
        symbol = "chevron.left.forwardslash.chevron.right",
        summary = localized(R.string.settings_whatsnew_entry_background_scripts_summary),
    )
