package ai.opencode.feature.session

import ai.opencode.feature.composer.DictationDrag
import ai.opencode.feature.composer.DictationHoldTracker
import ai.opencode.core.R as CoreR
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.MotionTunables
import ai.opencode.core.motion.motionPress
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.feature.voice.DotScopeLevelSource
import ai.opencode.feature.voice.DotScopeMode
import ai.opencode.feature.voice.DotScopeView
import ai.opencode.feature.composer.DictationFeature
import ai.opencode.feature.composer.DictationLatency
import ai.opencode.feature.composer.DictationLevels
import ai.opencode.server.model.OpenCodeDelivery
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.indication
import androidx.compose.material3.ripple
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Hold-to-dictate and the recording bar.

typealias DictationHoldArm = DictationFeature.Hold.Arm

// `HoldToDictateButton`'s press is:feature:composer's `Modifier.micPressGesture`; the session's mic
// (`SessionViewComposerControls.kt`) wears it.

/**
 * One recording's view-only progress through its hold. Starts over with every press and when the
 * recording ends.
 */
data class DictationHoldVisuals(
    /** A sustained hold, not a tap: the circle has grown and the targets show. */
    val showsGuides: Boolean = false,
) {
    /** A new press: nothing from the last recording's hold carries over. */
    fun begin(): DictationHoldVisuals = DictationHoldVisuals()

    /** The recording ended. */
    fun end(): DictationHoldVisuals = DictationHoldVisuals()
}

/**
 * Identity-scoped retention for the bar's leaving hold and settled fold state, so switching
 * sessions or drafts never leaves the last one's bar on screen.
 */
data class DictationHoldRetention(
    val identity: SessionFeature.State.TaskIdentity? = null,
    val leavingHold: DictationFeature.Hold.Pose? = null,
    val settledRecording: Boolean? = false,
) {
    fun leavingHold(currentIdentity: SessionFeature.State.TaskIdentity): DictationFeature.Hold.Pose? =
        if (identity == currentIdentity) leavingHold else null

    fun shownHold(active: DictationFeature.Hold.Pose?, currentIdentity: SessionFeature.State.TaskIdentity): DictationFeature.Hold.Pose? =
        active ?: leavingHold(currentIdentity)

    fun settledRecording(recording: Boolean, currentIdentity: SessionFeature.State.TaskIdentity): Boolean? {
        if (identity != null && identity != currentIdentity) return recording
        return settledRecording
    }

    fun syncIdentity(
        newIdentity: SessionFeature.State.TaskIdentity,
        activeHold: DictationFeature.Hold.Pose?,
        recording: Boolean,
    ): DictationHoldRetention {
        if (identity == newIdentity) return this
        return copy(identity = newIdentity, leavingHold = activeHold, settledRecording = recording)
    }

    fun holdChanged(
        hold: DictationFeature.Hold.Pose?,
        currentIdentity: SessionFeature.State.TaskIdentity,
        recording: Boolean,
    ): DictationHoldRetention {
        var next = this
        if (identity == null) {
            next = next.copy(identity = currentIdentity)
        } else if (identity != currentIdentity) {
            return syncIdentity(currentIdentity, hold, recording)
        }
        if (hold != null) next = next.copy(leavingHold = hold)
        return next
    }
}

/**
 * Where the recording is, as both halves of the bar draw it (the bar's buttons and scope inside the
 * composer, and the hold layer's circle and lock target above it).
 */
data class DictationBarPose(
    val hold: DictationFeature.Hold.Pose,
    val phase: DictationFeature.Phase,
    val drag: DictationDrag,
    val reducedMotion: Boolean,
    val visuals: DictationHoldVisuals,
    val isLeaving: Boolean = false,
) {
    val showsHoldGuides: Boolean get() = visuals.showsGuides
    /** The circle under the finger at its full size, rather than the mic's. */
    val showsGrownCircle: Boolean get() = showsHoldGuides && !isLeaving

    val isLocked: Boolean get() = hold.isLocked
    /** Slid up and let go, rather than tapped. */
    val lockedBySliding: Boolean get() = isLocked && hold.armed == DictationHoldArm.Lock
    val isFinishing: Boolean get() = phase is DictationFeature.Phase.Finishing
    val isSending: Boolean get() = (phase as? DictationFeature.Phase.Finishing)?.send != null
    val isHolding: Boolean get() = !isLocked && !isFinishing && !isLeaving
    /** Let go with nothing armed: the circle flies off with the message. */
    val isSentFromHold: Boolean get() = !isLocked && isSending
    val cancelArmed: Boolean get() = isHolding && hold.armed == DictationHoldArm.Cancel
    val lockArmed: Boolean get() = isHolding && hold.armed == DictationHoldArm.Lock
    val guidesShown: Boolean get() = isHolding && (showsHoldGuides || hold.armed != null)

    /** Each target's feedback: its progress, blended by direction. */
    val lockProgress: Float
        get() {
            if (!isHolding) return 0f
            val progress = DictationHoldTracker.Progress.of(drag.translation)
            return min(1f, progress.lock) * DictationHoldTracker.weights(drag.translation).lock
        }
    val cancelProgress: Float
        get() {
            if (!isHolding) return 0f
            val progress = DictationHoldTracker.Progress.of(drag.translation)
            return min(1f, progress.cancel) * DictationHoldTracker.weights(drag.translation).cancel
        }

    /** How far the circle leans after the finger (dp). Still under Reduce Motion. */
    val follow: Offset
        get() {
            if (!isHolding || reducedMotion) return Offset.Zero
            val weights = DictationHoldTracker.weights(drag.translation)
            val up = max(drag.translation.y * 0.3f, -(DictationHoldTracker.lockDistance * 0.3f + 4))
            val left = max(drag.translation.x * 0.3f, -(DictationHoldTracker.cancelDistance * 0.42f + 6))
            return Offset(
                min(4f, if (left < 0) left * weights.cancel else left),
                min(4f, if (up < 0) up * weights.lock else up),
            )
        }

    companion object {
        const val holdDiameter = 68f
        const val targetDiameter = 38f
        const val spacing = 4f
        /** 48 dp, Android's minimum target. */
        const val slot = 48f
    }
}

val LocalDictationBarIsLeaving = compositionLocalOf { false }

/**
 * The bar and its hold layer fade (and scale from `swapScale`) in where they sit, with the
 * composer's fold (`layout` closing); leaving is state, not a removal, so it fades out in place on
 * `microOut` and stops taking touches, and [onLeft] runs once that fade has finished, to take it
 * out of the tree. Locked again mid-fade, it comes back from where it is (on `microIn`) and stays.
 */
@Composable
fun DictationBarPresence(
    isLeaving: Boolean,
    modifier: Modifier = Modifier,
    onLeft: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    val presence = remember { Animatable(0f) }
    val currentOnLeft by rememberUpdatedState(onLeft)
    LaunchedEffect(isLeaving) {
        val spec: FiniteAnimationSpec<Float> = when {
            isLeaving -> Motion.microIn.animation(MotionPhase.Exit, reduceMotion)
            presence.value == 0f -> Motion.layout.animation(MotionPhase.Exit, reduceMotion)
            else -> Motion.microIn.animation(MotionPhase.Enter, reduceMotion)
        }
        presence.animateTo(if (isLeaving) 0f else 1f, spec)
        // A cancelled fade (locked again) never gets here.
        if (isLeaving) currentOnLeft()
    }
    val p = presence.value
    val leftScale = MotionTunables.swapScale.value
    CompositionLocalProvider(LocalDictationBarIsLeaving provides isLeaving) {
        Box(
            modifier
                .alpha(p.coerceIn(0f, 1f))
                .scale(if (reduceMotion) 1f else leftScale + (1f - leftScale) * p)
                // Leaving, it stops taking touches (it has none of its own once the bar unlocks) and leaves TalkBack.
                .then(if (isLeaving) Modifier.clearAndSetSemantics { } else Modifier),
        ) { content() }
    }
}

/**
 * Recording takes the composer's control row: Cancel (the composer's own
 * + turned ×, beneath) · the scope · Stop (■, into the draft) · Send (↑). While the mic is held
 * only × and the scope show (the bar takes no touches); locked, the circle and lock target fade
 * out while Stop and Send fade and scale in in their own places.
 *
 * @param micCenter the mic's centre in the bar's own coordinates (dp), or null when unmeasured.
 */
@Composable
fun DictationScopeBar(
    pose: DictationBarPose,
    isRunning: Boolean,
    micCenter: Offset?,
    onStop: () -> Unit,
    onSend: (OpenCodeDelivery) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isLeaving = LocalDictationBarIsLeaving.current
    val p = pose.copy(isLeaving = isLeaving)
    val density = LocalDensity.current
    var width by remember { mutableFloatStateOf(0f) }
    var lockedAt by remember { mutableLongStateOf(-1L) }
    // ↑ became the delivery menu during a run; it stays one until the bar leaves.
    var offersDelivery by remember { mutableStateOf(isRunning) }
    var wasLeaving by remember { mutableStateOf(isLeaving) }
    val diameter = min(34f * density.fontScale, 44f)
    LaunchedEffect(Unit) { DictationLatency.mark("bar") }
    // Initial too: TalkBack opens the bar already locked, and the tap guard still applies.
    LaunchedEffect(p.isLocked) { if (p.isLocked) lockedAt = android.os.SystemClock.uptimeMillis() }
    LaunchedEffect(isRunning) { if (isRunning) offersDelivery = true }
    LaunchedEffect(isLeaving) {
        if (isLeaving) offersDelivery = isRunning
        // Locked again while the last bar was still fading out.
        if (wasLeaving && !isLeaving && p.isLocked) lockedAt = android.os.SystemClock.uptimeMillis()
        wasLeaving = isLeaving
    }
    val guarded: (() -> Unit) -> Unit = { action ->
        val recent = lockedAt >= 0 && android.os.SystemClock.uptimeMillis() - lockedAt < TAP_GUARD_MILLIS
        if (p.lockedBySliding || !recent) action()
    }
    val slot = DictationBarPose.slot
    val leadingInset = if (p.isHolding) 84f else 6 + slot + 2
    val trailingInset = if (p.isHolding && micCenter != null && width > 0) {
        max(6f, width - micCenter.x + 48 - min(0f, p.follow.x))
    } else {
        6 + slot * 2 + DictationBarPose.spacing + 2
    }
    val guidesAlpha by animateFloatAsState(
        if (p.guidesShown) 1f else 0f,
        Motion.microIn.animation(if (p.guidesShown) MotionPhase.Enter else MotionPhase.Exit, p.reducedMotion),
        label = "barGuides",
    )
    val scopeAlpha = if (p.cancelArmed) 0.28f else 1 - p.cancelProgress * 0.35f
    Box(
        modifier
            .heightIn(min = 48.dp)
            .onSizeChanged { width = it.width / density.density },
    ) {
        DotScopeView(
            mode = DotScopeMode.Dictation(leadingInset, trailingInset, DictationLevelSource),
            // 6 dp past the bar on every side, as its insets count.
            modifier = Modifier.matchParentSize().outset(6.dp).alpha(scopeAlpha),
        )
        Row(Modifier.matchParentSize(), verticalAlignment = Alignment.CenterVertically) {
            // The composer's × beneath takes these touches.
            Spacer(Modifier.width(slot.dp))
            // Locked, the bar owns every touch past the ×: a tap on a disabled ■ or ↑, or between
            // them, never falls through to the mic beneath. Holding, it takes none.
            Spacer(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .then(if (p.isLocked) Modifier.pointerInput(Unit) { detectTapGestures { } } else Modifier),
            )
            DictationLockedReveal(isLocked = p.isLocked, reducedMotion = p.reducedMotion) {
                val finishing = p.isFinishing && !p.isSending
                RoundBarButton(
                    symbol = SFSymbol.StopFill,
                    foreground = OpenCodeTheme.colors.text,
                    background = OpenCodeTheme.colors.variant.surfaceStrong.color,
                    diameter = diameter,
                    glyph = 12f,
                    showsProgress = finishing,
                    enabled = p.isLocked && !p.isFinishing,
                    // Not “Stop”: during a run that's the composer's Stop for the run.
                    label = if (finishing) stringResource(R.string.session_dictation_finishing_description) else stringResource(R.string.session_dictation_stop_recording_description),
                    hidden = !p.isLocked || p.isLeaving,
                    order = 0f,
                    hint = stringResource(R.string.session_dictation_stop_recording_hint),
                    onClick = { guarded(onStop) },
                )
            }
            Spacer(Modifier.width(DictationBarPose.spacing.dp))
            DictationLockedReveal(isLocked = p.isLocked, reducedMotion = p.reducedMotion) {
                val sendLabel = if (p.isSending) stringResource(R.string.session_sending_message_description) else stringResource(CoreR.string.common_send)
                val button: @Composable (Modifier, Boolean) -> Unit = { extra, tappable ->
                    RoundBarButton(
                        symbol = SFSymbol.ArrowUp,
                        foreground = OpenCodeTheme.colors.sendButtonForeground,
                        background = OpenCodeTheme.colors.sendButtonBackground,
                        diameter = diameter,
                        glyph = 18f,
                        showsProgress = p.isSending,
                        enabled = p.isLocked && !p.isSending,
                        label = sendLabel,
                        hidden = !p.isLocked || p.isLeaving,
                        order = 1f,
                        modifier = extra,
                        tappable = tappable,
                        onClick = { guarded { onSend(OpenCodeDelivery.Steer) } },
                    )
                }
                if (isRunning || offersDelivery) {
                    // Like the composer's Send mid-run: a fresh touch on ↑ opens the menu.
                    DeliveryMenu(
                        onSend = { delivery -> guarded { onSend(delivery) } },
                        enabled = p.isLocked && !p.isSending,
                        clickLabel = if (isRunning) stringResource(R.string.session_delivery_steer_label) else null,
                        longClickLabel = if (isRunning) stringResource(R.string.session_delivery_queue_label) else null,
                    ) { menuModifier ->
                        button(menuModifier, false)
                    }
                } else {
                    button(Modifier, true)
                }
            }
        }
        if (guidesAlpha > 0f) {
            OpenCodeIcon(
                SFSymbol.ChevronLeft,
                contentDescription = null,
                size = 12.dp,
                tint = OpenCodeTheme.colors.secondaryText,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 50.dp)
                    .alpha(guidesAlpha * (1 - p.cancelProgress * 0.6f)),
            )
        }
    }
}

/** After a tap opens the bar, a quick second tap on the mic would land on a bar button; they ignore taps this long. */
private const val TAP_GUARD_MILLIS = 400L

@Composable
private fun RoundBarButton(
    symbol: SFSymbol,
    foreground: Color,
    background: Color,
    diameter: Float,
    glyph: Float,
    showsProgress: Boolean,
    enabled: Boolean,
    label: String,
    hidden: Boolean,
    order: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** TalkBack's click label. */
    hint: String? = null,
    /** False inside a [DeliveryMenu], whose own gesture takes the tap and the hold. */
    tappable: Boolean = true,
) {
    val interactions = remember { MutableInteractionSource() }
    val reduceMotion = rememberReduceMotion()
    Box(
        modifier
            .then(
                if (enabled && tappable) {
                    Modifier.clickable(interactionSource = interactions, indication = null, role = Role.Button, onClickLabel = hint, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .openCodeTapTarget(minSize = DictationBarPose.slot.dp, accessibility = false)
            .then(
                if (hidden) {
                    Modifier.clearAndSetSemantics { }
                } else {
                    Modifier.semantics(mergeDescendants = true) {
                        contentDescription = label
                        traversalIndex = order
                        if (!enabled) disabled()
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.motionPress(interactions).size(diameter.dp).clip(OpenCodeShape.circle).background(background).indication(interactions, ripple()),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = ControlGlyph(if (showsProgress) null else symbol.sfName, glyph.dp, 0f),
                transitionSpec = { controlGlyphSwap(reduceMotion) },
                contentAlignment = Alignment.Center,
                label = "barButtonGlyph",
            ) { shown ->
                if (shown.symbol == null) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = foreground, strokeWidth = 2.dp)
                } else {
                    OpenCodeIcon(shown.symbol, contentDescription = null, size = shown.size, tint = foreground)
                }
            }
        }
    }
}

/**
 * Send during a run, in the composer and the dictation bar: a tap steers; touch and hold offers
 * Steer and Queue. The choice is one-shot.
 *
 * @param content the control, given the modifier that takes its taps.
 */
@Composable
fun DeliveryMenu(
    onSend: (OpenCodeDelivery) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** TalkBack's tap (Steer) and hold (the menu) labels. */
    clickLabel: String? = null,
    longClickLabel: String? = null,
    content: @Composable (Modifier) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val sendNow by rememberUpdatedState(onSend)
    Box(modifier) {
        content(
            Modifier
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(onLongPress = { expanded = true }, onTap = { sendNow(OpenCodeDelivery.Steer) })
                }
                .semantics {
                    role = Role.Button
                    if (enabled) {
                        onClick(label = clickLabel) { sendNow(OpenCodeDelivery.Steer); true }
                        onLongClick(label = longClickLabel) { expanded = true; true }
                    }
                },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.session_delivery_steer)) },
                leadingIcon = { OpenCodeIcon(SFSymbol.ArrowTurnUpRight, contentDescription = null, size = 20.dp) },
                onClick = { expanded = false; sendNow(OpenCodeDelivery.Steer) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.session_delivery_queue)) },
                leadingIcon = { OpenCodeIcon(SFSymbol.TextBadgePlus, contentDescription = null, size = 20.dp) },
                onClick = { expanded = false; sendNow(OpenCodeDelivery.Queue) },
            )
        }
    }
}

/**
 * Stop and Send appear in their own places once a recording locks: opacity and scale (0.6 → 1), so
 * however it's interrupted they settle fully drawn. Fade only under Reduce Motion.
 */
@Composable
private fun DictationLockedReveal(isLocked: Boolean, reducedMotion: Boolean, content: @Composable () -> Unit) {
    val p by animateFloatAsState(
        if (isLocked) 1f else 0f,
        Motion.microIn.animation(if (isLocked) MotionPhase.Enter else MotionPhase.Exit, reducedMotion),
        label = "lockedReveal",
    )
    Box(Modifier.alpha(p.coerceIn(0f, 1f)).scale(if (reducedMotion) 1f else 0.6f + 0.4f * p)) { content() }
}

/**
 * The parts of a held recording that leave the bar's row, drawn above the composer's surface: the
 * circle that grows out of the mic under the finger and the lock target above it. Locking fades
 * both out where they are. Purely visual.
 *
 * Coordinates are root px; [origin] is this layer's own root position, so it can draw at the mic
 * whatever the composer's layout.
 */
@Composable
fun DictationHoldLayer(
    pose: DictationBarPose,
    micFrame: Rect,
    barFrame: Rect,
    modifier: Modifier = Modifier,
) {
    val isLeaving = LocalDictationBarIsLeaving.current
    val p = pose.copy(isLeaving = isLeaving)
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val follower = remember { DictationLevelFollower() }
    var swell by remember { mutableFloatStateOf(0f) }
    val swells = p.isHolding && p.showsHoldGuides && !p.reducedMotion
    LaunchedEffect(swells) {
        if (!swells) {
            swell = 0f
            return@LaunchedEffect
        }
        while (true) withFrameNanos { now -> swell = follower.step(DictationLevels.level, now) }
    }
    LaunchedEffect(Unit) { DictationLatency.mark("hold_circle") }
    LaunchedEffect(p.guidesShown) { if (p.guidesShown) DictationLatency.mark("lock_target") }
    // Small things arrive on `microIn` and leave on `microOut`.
    fun <T> micro(arriving: Boolean): FiniteAnimationSpec<T> =
        Motion.microIn.animation(if (arriving) MotionPhase.Enter else MotionPhase.Exit, p.reducedMotion)
    val guides by animateFloatAsState(if (p.guidesShown) 1f else 0f, micro(p.guidesShown), label = "holdGuides")
    // Locked or sent, the circle and the target leave where they are.
    val shown = !p.isLocked && !p.isSentFromHold && !p.isLeaving
    val circleAlpha by animateFloatAsState(if (shown) 1f else 0f, micro(shown), label = "holdCircleAlpha")
    val circleScale by animateFloatAsState(
        if (p.reducedMotion || shown) 1f else if (p.isSentFromHold) 0.5f else 0.8f,
        micro(shown),
        label = "holdCircleScale",
    )
    val sentRise by animateFloatAsState(
        if (p.isSentFromHold && !p.reducedMotion) -36f else 0f,
        Motion.swap.animation(reduceMotion = p.reducedMotion),
        label = "holdSentRise",
    )
    val grown = p.showsGrownCircle
    val circleDiameter by animateFloatAsState(
        if (grown) DictationBarPose.holdDiameter else min(34f * density.fontScale, 44f),
        micro(grown),
        label = "holdCircleSize",
    )
    val follow = p.follow
    val followX by animateFloatAsState(follow.x, if (p.reducedMotion) tween(0) else MotionSpring(0.18).spec(), label = "followX")
    val followY by animateFloatAsState(follow.y, if (p.reducedMotion) tween(0) else MotionSpring(0.18).spec(), label = "followY")
    val armedFill by animateFloatAsState(
        if (p.lockArmed) 1f else 0f,
        micro(p.lockArmed),
        label = "lockArmed",
    )
    // The mic's centre (dp, in this layer); before it's measured, the bar's trailing slot.
    val center = with(density) {
        val c = if (micFrame == Rect.Zero) Offset(barFrame.right - (DictationBarPose.slot / 2).dp.toPx(), barFrame.center.y) else micFrame.center
        Offset((c.x - origin.x) / this.density, (c.y - origin.y) / this.density)
    }
    val lockY = center.y - DictationBarPose.holdDiameter / 2 - 30 - DictationBarPose.targetDiameter / 2
    Layout(
        modifier = modifier
            .onGloballyPositioned { origin = it.positionInRoot() }
            .clearAndSetSemantics { },
        content = {
            // 0: the chevron up.
            Box(Modifier.alpha(guides * (1 - p.lockProgress * 0.6f))) {
                OpenCodeIcon(SFSymbol.ChevronUp, contentDescription = null, size = 12.dp, tint = OpenCodeTheme.colors.secondaryText)
            }
            // 1: the lock target.
            Box(
                Modifier
                    .size(DictationBarPose.targetDiameter.dp)
                    .alpha(guides)
                    .scale(if (p.reducedMotion) 1f else 1f + 0.1f * armedFill)
                    .background(OpenCodeTheme.colors.variant.surfaceStrong.color, OpenCodeShape.circle),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.fillMaxSize().alpha(armedFill).background(OpenCodeTheme.colors.text, OpenCodeShape.circle))
                OpenCodeIcon(
                    if (p.lockArmed) SFSymbol.LockFill else SFSymbol.LockOpenFill,
                    contentDescription = null,
                    size = 15.dp,
                    tint = if (p.lockArmed) OpenCodeTheme.colors.canvas else OpenCodeTheme.colors.secondaryText,
                )
            }
            // 2: the circle under the finger.
            Box(
                Modifier
                    .size(circleDiameter.dp)
                    .scale(circleScale * (1 + swell * 0.1f))
                    .alpha(circleAlpha)
                    .background(OpenCodeTheme.colors.text, OpenCodeShape.circle),
                contentAlignment = Alignment.Center,
            ) {
                OpenCodeIcon(SFSymbol.MicFill, contentDescription = null, size = if (grown) 26.dp else 18.dp, tint = OpenCodeTheme.colors.canvas)
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            fun place(index: Int, x: Float, y: Float) {
                val placeable = placeables[index]
                placeable.place((x.dp.toPx() - placeable.width / 2f).roundToInt(), (y.dp.toPx() - placeable.height / 2f).roundToInt())
            }
            if (guides > 0f) {
                place(0, center.x, center.y - DictationBarPose.holdDiameter / 2 - 15 + followY * 0.5f)
                place(1, center.x, lockY)
            }
            place(2, center.x + followX, center.y + followY + sentRise)
        }
    }
}

/**
 * The live input level, eased for the hold circle's swell: quick to rise, slower to fall, never the
 * raw per-buffer value.
 */
class DictationLevelFollower {
    private var value = 0f
    private var lastNanos: Long? = null

    fun step(target: Float, nowNanos: Long): Float {
        val elapsed = min(0.1f, (nowNanos - (lastNanos ?: nowNanos)) / 1_000_000_000f)
        lastNanos = nowNanos
        val rate = if (target > value) 18f else 6f
        value += (target - value) * min(1f, elapsed * rate)
        return value
    }
}

/** Read by the dictation bar's scope once per frame. */
private val DictationLevelSource = DotScopeLevelSource { DictationLevels.level }

/** Lays the element out [by] past its incoming bounds on every side, without changing its own size. */
private fun Modifier.outset(by: androidx.compose.ui.unit.Dp): Modifier = layout { measurable, constraints ->
    val extra = (by * 2).roundToPx()
    val placeable = measurable.measure(
        Constraints.fixed(
            (constraints.maxWidth + extra).coerceAtLeast(0),
            (constraints.maxHeight + extra).coerceAtLeast(0),
        ),
    )
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(-by.roundToPx(), -by.roundToPx()) }
}
