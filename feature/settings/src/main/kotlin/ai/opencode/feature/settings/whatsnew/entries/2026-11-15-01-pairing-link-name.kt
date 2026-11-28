package ai.opencode.feature.settings.whatsnew.entries

import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry.Companion.smaller
import ai.opencode.feature.settings.whatsnew.WhatsNewShipped

internal val WhatsNewShipped.pairingLinkName: WhatsNewEntry
    get() = smaller(
        id = "pairing-link-name",
        day = "2026-11-15",
        title = localized(R.string.settings_whatsnew_entry_pairing_link_name_title),
        symbol = "server.rack",
        summary = localized(R.string.settings_whatsnew_entry_pairing_link_name_summary),
    )
