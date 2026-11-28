package ai.opencode.feature.drawer.motion

import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.MotionTunables
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeMinTapTarget
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.rememberHighContrast
import ai.opencode.feature.drawer.R
import ai.opencode.feature.session.DrawerBadge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The session header's drawer button and its badge dot.
 *
 * Used by the session header: `:feature:session` sits below `:feature:drawer`, so Home passes
 * [DrawerButton] into the session as a slot rather than the session importing it.
 */

private object DrawerBadgeColors {
    fun blue(isDark: Boolean) = if (isDark) Color(0xFF0A84FF) else Color(0xFF007AFF)
}

@Composable
private fun isDarkTheme(): Boolean = OpenCodeTheme.colors.isDark

/**
 * The session header's drawer button: a plain top-app-bar icon that opens the drawer.
 *
 * @param size The button's face.
 */
@Composable
fun DrawerButton(
    badge: DrawerBadge?,
    onOpenSessions: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = OpenCodeMinTapTarget,
) {
    val openSessionsDescription = stringResource(R.string.drawer_button_open_sessions_description)
    val attentionState = stringResource(R.string.drawer_button_attention_state)
    val unreadState = stringResource(R.string.drawer_button_unread_state)
    Box(modifier.size(size), contentAlignment = Alignment.CenterStart) {
        Surface(
            shape = OpenCodeShape.control,
            color = Color.Transparent,
            contentColor = OpenCodeTheme.colors.text,
            modifier = Modifier.size(size),
        ) {
            Box(
                Modifier
                    .size(size)
                    .clickable(role = Role.Button, onClickLabel = null, onClick = onOpenSessions)
                    .semantics {
                        contentDescription = openSessionsDescription
                        stateDescription = when (badge) {
                            DrawerBadge.Attention -> attentionState
                            DrawerBadge.Unread -> unreadState
                            null -> ""
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                OpenCodeIcon(SFSymbol.Line3Horizontal, contentDescription = null)
            }
        }
        // Outside the button's surface.
        Box(Modifier.size(size), contentAlignment = Alignment.TopEnd) {
            DrawerBadgeDot(badge)
        }
    }
}

/**
 * The drawer button's dot: another session needs input (orange) or is unread (blue). It arrives on
 * `MotionRole.MicroIn` and leaves on `microOut`; one already showing when the button appears
 * (opening or restoring a session) is simply there, and a change of colour while it shows lands in
 * place.
 */
@Composable
fun DrawerBadgeDot(badge: DrawerBadge?, modifier: Modifier = Modifier) {
    val reducedMotion = rememberReduceMotion()
    val highContrast = rememberHighContrast()
    val isDark = isDarkTheme()
    val orange = OpenCodeTheme.colors.warning
    val background = MaterialTheme.colorScheme.background
    // The last colour shown, so the dot keeps it while it leaves.
    var lastBadge by remember { mutableStateOf(badge) }
    if (badge != null) lastBadge = badge
    val scale = if (reducedMotion) 1f else MotionTunables.microInScale.value
    // A 10 dp slot, offset (-3, 3) into the button's corner.
    Box(modifier.offset(x = (-3).dp, y = 3.dp).size(10.dp)) {
        AnimatedVisibility(
            visible = badge != null,
            enter = fadeIn(MotionRole.MicroIn.animation(MotionPhase.Enter, reducedMotion)) +
                scaleIn(MotionRole.MicroIn.animation(MotionPhase.Enter, reducedMotion), initialScale = scale),
            exit = fadeOut(MotionRole.MicroIn.animation(MotionPhase.Exit, reducedMotion)) +
                scaleOut(MotionRole.MicroIn.animation(MotionPhase.Exit, reducedMotion), targetScale = scale),
        ) {
            Canvas(Modifier.size(10.dp)) {
                val color = if (lastBadge == DrawerBadge.Attention) orange else DrawerBadgeColors.blue(isDark)
                val radius = size.minDimension / 2
                drawCircle(color, radius = radius)
                if (highContrast) {
                    drawCircle(background, radius = radius - 0.625.dp.toPx(), style = Stroke(1.25.dp.toPx()))
                }
            }
        }
    }
}
