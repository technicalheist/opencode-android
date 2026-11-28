package ai.opencode.feature.system.notifications

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class InAppNoticeTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test fun needsInputStatusesAreTheOnlyOnesClearedOnAnswer() {
        fun content(status: String) = SessionPushNotifications.Delivered(tag = "ses_a", sessionID = "ses_a", status = status)
        assertTrue(SessionPushNotifications.isNeedsInput(content("permission")))
        assertFalse(SessionPushNotifications.isNeedsInput(content("succeeded")))
        assertEquals("ses_a", SessionPushNotifications.sessionID(content("succeeded")))
    }
}
