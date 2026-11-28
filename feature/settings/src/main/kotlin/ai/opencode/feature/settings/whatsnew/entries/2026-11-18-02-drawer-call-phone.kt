package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.drawerCallPhone: WhatsNewEntry
    get() = smaller(
        id = "drawer-call-phone",
        day = "2026-11-18",
        title = localized(R.string.settings_whatsnew_entry_drawer_call_phone_title),
        symbol = "phone.fill",
        summary = localized(R.string.settings_whatsnew_entry_drawer_call_phone_summary),
    )
