package ai.opencode.feature.session

import ai.opencode.core.diagnostics.LabMarks
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticEffect
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionTransition
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.motion.transitionBlur
import ai.opencode.core.strings.localized
import ai.opencode.core.strings.localizedPlural
import ai.opencode.core.tuning.InternalBuild
import ai.opencode.core.ui.AccessibilityFocusRequest
import ai.opencode.core.ui.OpenCodeFonts
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeRadius
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeSurfaces
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.RollingNumber
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.accessibilityFocusRequest
import ai.opencode.core.ui.openCodeHairline
import ai.opencode.core.ui.rememberAccessibilityFocusRequest
import ai.opencode.core.ui.appFormattingLocale
import ai.opencode.core.ui.openCodeTapTarget
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import ai.opencode.feature.transcript.list.TranscriptBorrowedDrag
import ai.opencode.feature.transcript.list.passesDragsToTranscript
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeShell
import androidx.activity.compose.BackHandler
import androidx.annotation.PluralsRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// IOS reads `SessionFeature` state inside the presentation; here every view takes immutable values
// and action lambdas so `SessionView` can wire it (`activeSubagents`, `backgroundShells`,
// `waitingSessionIDs`, `shellGlyphProbe`, and `.subagentTapped` / `.shellOutputTapped`).
// SessionView presents the shell output sheet.
//
// Liquid Glass → the desktop's chip and popover: the capsule keeps its place and the panel opens
// just above it, their leading edges aligned. Lengths are dp.

// MARK: Anchor

/**
 * The background-activity capsule's bounds, so the panel can open above it. Create one per session
 * screen with [rememberBackgroundWorkAnchor], mark the capsule's place with
 * [backgroundActivityAnchor], and hand the same anchor to [BackgroundWorkPresentation].
 */
@Stable
class BackgroundWorkAnchor {
    /** Root coordinates in px; null while the capsule's place isn't laid out. */
    internal var bounds by mutableStateOf<Rect?>(null)

    /** The capsule's place as laid out now (read at draw time, after this frame's layout). */
    internal var coordinates: androidx.compose.ui.layout.LayoutCoordinates? = null

    /**
     * What moves the capsule's place frame by frame (the request panel's room growing above the
     * composer), read while the surface draws so it redraws in the frame its place moves.
     */
    var motion: () -> Float = { 0f }
}

@Composable
fun rememberBackgroundWorkAnchor(): BackgroundWorkAnchor = remember { BackgroundWorkAnchor() }

/**
 * Marks the capsule the background-work panel anchors to. The panel never keeps a capsule, or its
 * touch target, where the place last was.
 */
fun Modifier.backgroundActivityAnchor(anchor: BackgroundWorkAnchor): Modifier = this then BackgroundActivityAnchorElement(anchor)

private data class BackgroundActivityAnchorElement(val anchor: BackgroundWorkAnchor) : ModifierNodeElement<BackgroundActivityAnchorNode>() {
    override fun create() = BackgroundActivityAnchorNode(anchor)

    override fun update(node: BackgroundActivityAnchorNode) {
        if (node.anchor === anchor) return
        node.release()
        node.anchor = anchor
    }
}

private class BackgroundActivityAnchorNode(var anchor: BackgroundWorkAnchor) : Modifier.Node(), GlobalPositionAwareModifierNode {
    private var marked: LayoutCoordinates? = null

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        marked = coordinates
        anchor.coordinates = coordinates
        anchor.bounds = coordinates.boundsInRoot()
    }

    override fun onDetach() = release()

    /** Clears the mark unless another place has taken it since. */
    fun release() {
        val mine = marked ?: return
        marked = null
        if (anchor.coordinates !== mine) return
        anchor.coordinates = null
        anchor.bounds = null
    }
}

// MARK: Copy and sizes

/** The capsule's copy. */
object BackgroundWorkSummary {
    /** “1 subagent · 2 shells”, as the capsule reads. */
    fun summary(agents: List<OpenCodeSession>, shells: List<OpenCodeShell>, scripts: List<BackgroundScript> = emptyList()): String =
        summary(agents.size, shells.size, scripts.size)

    fun summary(agents: Int, shells: Int, scripts: Int = 0): String {
        val parts = listOfNotNull(
            if (agents > 0) localizedPlural(R.plurals.session_background_work_subagent_count, agents, agents) else null,
            if (scripts > 0) localizedPlural(R.plurals.session_background_work_script_count, scripts, scripts) else null,
            if (shells > 0) localizedPlural(R.plurals.session_background_work_shell_count, shells, shells) else null,
        )
        return parts.reduceOrNull { a, b -> localized(R.string.session_background_work_summary, a, b) } ?: ""
    }
}

private const val PILL_GLYPH_GAP = 5f

private val countsStyle: TextStyle
    get() = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")

// MARK: Capsule

/**
 * The background-work capsule's place in the composer pill row: its size, invisible. The capsule
 * itself is drawn, and tapped, in [BackgroundWorkPresentation] above, with its panel. Put
 * [backgroundActivityAnchor] on this.
 */
@Composable
fun BackgroundWorkCapsuleSpace(agents: Int, shells: Int, modifier: Modifier = Modifier, scripts: Int = 0) {
    BackgroundWorkCapsuleLabel(
        agents = agents,
        shells = shells,
        scripts = scripts,
        modifier = modifier.padding(vertical = BackgroundWorkMotion.capsuleInset.dp).alpha(0f).clearAndSetSemantics { },
        sizingOnly = true,
    ) { Box(Modifier.width(18.dp)) }
}

/**
 * The capsule's glyph and counts, laid out like a composer pill's.
 *
 * @param sizingOnly An invisible layout copy: its counts don't roll.
 */
@Composable
fun BackgroundWorkCapsuleLabel(
    agents: Int,
    shells: Int,
    modifier: Modifier = Modifier,
    scripts: Int = 0,
    sizingOnly: Boolean = false,
    leading: @Composable () -> Unit,
) {
    Row(
        // The glyph's ink as far from the surface's leading edge as from its top and bottom,
        // concentric with the capsule's end.
        modifier
            .heightIn(min = 34.dp)
            .padding(start = BackgroundWorkTunables.glyphLeading.dp, end = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(PILL_GLYPH_GAP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(glyphWidth(agents, shells).dp).clearAndSetSemantics { }, contentAlignment = Alignment.CenterStart) { leading() }
        if (sizingOnly) {
            Text(BackgroundWorkSummary.summary(agents, shells, scripts), style = countsStyle, maxLines = 1, softWrap = false)
        } else {
            BackgroundWorkCounts(agents, shells, Modifier.offset(y = BackgroundWorkTunables.countNudge.dp), scripts)
        }
    }
}

/** One 18 dp disc, or the orb and the shell disc side by side. */
fun glyphWidth(agents: Int, shells: Int): Float = if (agents > 0 && shells > 0) 36 + BackgroundWorkTunables.pairGap else 18f

/**
 * “1 subagent · 2 shells”, animated where it stands as the counts change: digits roll, a group,
 * or the dot between groups opens or closes its own width on the resize spring
 * while it fades and blurs, and the rest slides over. Under Reduce Motion the words cross-fade.
 */
@Composable
fun BackgroundWorkCounts(agents: Int, shells: Int, modifier: Modifier = Modifier, scripts: Int = 0) {
    val reduceMotion = rememberReduceMotion()
    // What a group last counted, shown while it closes.
    var lastAgents by remember { mutableIntStateOf(max(1, agents)) }
    var lastShells by remember { mutableIntStateOf(max(1, shells)) }
    var lastScripts by remember { mutableIntStateOf(max(1, scripts)) }
    if (agents > 0 && agents != lastAgents) lastAgents = agents
    if (shells > 0 && shells != lastShells) lastShells = shells
    if (scripts > 0 && scripts != lastScripts) lastScripts = scripts
    if (reduceMotion) {
        Crossfade(BackgroundWorkSummary.summary(agents, shells, scripts), modifier, animationSpec = BackgroundWorkMotion.fade(), label = "BackgroundWorkCounts") {
            Text(it, style = countsStyle, maxLines = 1, softWrap = false)
        }
        return
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Opening(agents > 0) { CountGroup(if (agents > 0) agents else lastAgents, R.plurals.session_background_work_subagent_count) }
        // Desktop's order: subagents · scripts · shells.
        Opening(agents > 0 && scripts > 0) { Text(" · ", style = countsStyle, maxLines = 1, softWrap = false) }
        Opening(scripts > 0) { CountGroup(if (scripts > 0) scripts else lastScripts, R.plurals.session_background_work_script_count) }
        Opening((agents > 0 || scripts > 0) && shells > 0) { Text(" · ", style = countsStyle, maxLines = 1, softWrap = false) }
        Opening(shells > 0) { CountGroup(if (shells > 0) shells else lastShells, R.plurals.session_background_work_shell_count) }
    }
}

@Composable
private fun CountGroup(count: Int, @PluralsRes textRes: Int) {
    // The whole phrase (“2 shells”) comes from the plural resource; the number inside it rolls.
    val text = pluralStringResource(textRes, count, count)
    val number = count.toString()
    val numberAt = text.indexOf(number)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (numberAt < 0) {
            Text(text, style = countsStyle, maxLines = 1, softWrap = false)
            return@Row
        }
        val before = text.substring(0, numberAt)
        val after = text.substring(numberAt + number.length)
        if (before.isNotEmpty()) Text(before, style = countsStyle, maxLines = 1, softWrap = false)
        RollingNumber(count, style = countsStyle)
        if (after.isNotEmpty()) Text(after, style = countsStyle, maxLines = 1, softWrap = false)
    }
}

/**
 * Opens to its own width or closes to none, clipped to it, fading and blurring as it goes on
 * `swap`: quickly out, so what the clip crosses is already faint, and in a little behind the opening.
 */
@Composable
private fun Opening(isOpen: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = isOpen,
        enter = expandHorizontally(BackgroundWorkMotion.resize(), expandFrom = Alignment.Start) +
            fadeIn(Motion.swap.animation(MotionPhase.Enter)),
        exit = shrinkHorizontally(BackgroundWorkMotion.resize(), shrinkTowards = Alignment.Start) +
            fadeOut(Motion.swap.animation(MotionPhase.Exit)),
    ) {
        Box(Modifier.blurringWith(this)) { content() }
    }
}

/** Blurs 3 dp while arriving or leaving, never at rest. */
private fun Modifier.blurringWith(scope: AnimatedVisibilityScope): Modifier = composed {
    val blur by scope.transition.animateFloat(label = "blur") { if (it == EnterExitState.Visible) 0f else 3f }
    transitionBlur(blur.dp)
}

/**
 * The capsule's glyph: its most telling subagent's orb, the shell glyph driven by its shells'
 * combined output, or both side by side, the same disc placed alike.
 */
@Composable
fun BackgroundWorkGlyph(
    agents: List<OpenCodeSession>,
    shells: List<OpenCodeShell>,
    waiting: Set<String>,
    probe: ShellGlyphProbe?,
    modifier: Modifier = Modifier,
    /** Held still (the session is covered). */
    isPaused: Boolean = false,
    scripts: List<BackgroundScript> = emptyList(),
) {
    val reduceMotion = rememberReduceMotion()
    // Swapped in place as subagents come and go, like the words beside it. The disc's dot field
    // sits a little high in its frame: dropped, it is concentric with the capsule's end.
    Row(modifier.offset(y = BackgroundWorkTunables.orbGlyphNudgeY.dp), horizontalArrangement = Arrangement.spacedBy(BackgroundWorkTunables.pairGap.dp)) {
        val swap = MotionTransition.swap(reduceMotion)
        AnimatedVisibility(agents.isNotEmpty(), enter = swap.enter, exit = swap.exit) {
            AgentActivityGlyph(
                source = AgentActivitySource.summary(agents, emptyList(), waiting),
                isPaused = isPaused,
            )
        }
        // Scripts alone show the plain busy orb, as the desktop's chip does; their rows keep `</>`.
        AnimatedVisibility(agents.isEmpty() && shells.isEmpty() && scripts.isNotEmpty(), enter = swap.enter, exit = swap.exit) {
            AgentActivityGlyph(source = AgentActivitySource.Running, isPaused = isPaused)
        }
        AnimatedVisibility(shells.isNotEmpty(), enter = swap.enter, exit = swap.exit) {
            val ids = shells.map { it.id }
            ShellGlyph(
                shellIDs = ids,
                key = "capsule|" + ids.joinToString(","),
                probe = probe,
                isPaused = isPaused,
            )
        }
    }
}

/** A script's or a shell's row mark, in the 18 dp footprint of the orb so rows line up under the capsule's glyph. */
@Composable
private fun RowGlyph(symbol: SFSymbol) {
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        OpenCodeIcon(symbol, contentDescription = null, size = 16.dp, tint = OpenCodeTheme.colors.secondaryText)
    }
}

// MARK: Presentation

/** What the panel last showed. When the work ends, the capsule and its rows leave the session at once; the panel closes over what it had. */
private class Retained {
    var capsule: Rect? = null
    var agents: List<OpenCodeSession> = emptyList()
    var shells: List<OpenCodeShell> = emptyList()
    var scripts: List<BackgroundScript> = emptyList()
}

/**
 * The background-work capsule and its panel. Wraps [content] (the session screen) and draws the
 * capsule in the place marked with [backgroundActivityAnchor], and the panel just above it.
 *
 * @param anchor the capsule's place, shared with [BackgroundWorkCapsuleSpace].
 * @param sessionID the session shown; a change resets the panel at once (the drawer swaps sessions in place).
 * @param agents running subagents (`SessionFeature.State.activeSubagents`).
 * @param shells background shells (`SessionFeature.State.backgroundShells`).
 * @param waitingSessionIDs subagents waiting on the user (`waitingSessionIDs`).
 * @param probe where shells' output is read (`shellGlyphProbe`).
 * @param isPresented the panel is open; [onPresentedChange] asks to change it (the capsule, a
 *   tap outside, a pull down, Back, or the work ending).
 * @param isCovered a panel grown out of the composer (the model list, the level picker) stands
 *   over the capsule's place, so it hides like the pills.
 * @param isPaused the session is covered (a pushed page, Settings): the glyphs hold still and stop probing.
 * @param inCall a voice call starts: the panel closes.
 * @param onToggle before the capsule toggles the panel (the composer gives up focus).
 * @param onOpenSubagent a subagent row tapped (`.subagentTapped(id)`); the panel closes first.
 * @param onOpenShell a shell row tapped (`.shellOutputTapped(id)`); the panel closes first.
 */
@Composable
fun BackgroundWorkPresentation(
    anchor: BackgroundWorkAnchor,
    sessionID: String?,
    agents: List<OpenCodeSession>,
    shells: List<OpenCodeShell>,
    waitingSessionIDs: Set<String>,
    probe: ShellGlyphProbe?,
    isPresented: Boolean,
    onPresentedChange: (Boolean) -> Unit,
    onOpenSubagent: (String) -> Unit,
    onOpenShell: (String) -> Unit,
    modifier: Modifier = Modifier,
    scripts: List<BackgroundScript> = emptyList(),
    onStopScript: (String) -> Unit = {},
    onStopSubagent: (String) -> Unit = {},
    onStopShell: (String) -> Unit = {},
    stoppingIDs: Set<String> = emptySet(),
    isCovered: Boolean = false,
    isPaused: Boolean = false,
    inCall: Boolean = false,
    transcript: TranscriptBorrowedDrag? = null,
    onToggle: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val hasWork = agents.isNotEmpty() || shells.isNotEmpty() || scripts.isNotEmpty()
    val reduceMotion = rememberReduceMotion()
    val density = LocalDensity.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var stage by remember { mutableStateOf(BackgroundWorkStage.closed) }
    // Landed open: accessibility meets the panel.
    var settled by remember { mutableStateOf(false) }
    val retained = remember { Retained() }
    // The session whose work the panel shows.
    var panelSession by remember { mutableStateOf<String?>(null) }
    // Each open, close, or reset starts a new motion; a superseded one's completion must not settle or close the panel mid-flight.
    var motion by remember { mutableIntStateOf(0) }
    // A session that opens on running work shows its capsule at once.
    var capsuleShown by remember { mutableStateOf(hasWork) }
    var capsuleMounted by remember { mutableStateOf(hasWork) }
    // TalkBack's way back to the capsule once the panel has closed.
    var capsuleFocus by remember { mutableStateOf<Int?>(null) }
    val capsuleFocusRequest = rememberAccessibilityFocusRequest(capsuleFocus)
    // 0 = closed, 1 = open.
    val progress = remember { Animatable(0f) }
    val capsuleAlpha = remember { Animatable(if (hasWork) 1f else 0f) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var overlayWidth by remember { mutableFloatStateOf(0f) }
    // How far a finger has pulled the panel down (px), and how fast it let go (px/s).
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var releaseVelocity by remember { mutableFloatStateOf(0f) }
    var panelSize by remember { mutableStateOf(Size.Zero) }
    // How far the capsule gives (px) as the panel lands in it.
    val landing = remember { Animatable(0f) }
    val panelGap = BackgroundWorkMotion.panelGap * density.density

    val latestPresented by rememberUpdatedState(isPresented)
    val latestHasWork by rememberUpdatedState(hasWork)
    val latestSession by rememberUpdatedState(sessionID)
    val latestCovered by rememberUpdatedState(isCovered)
    val latestOnPresentedChange by rememberUpdatedState(onPresentedChange)

    fun setCapsuleShown(shown: Boolean, animated: Boolean, completion: (() -> Unit)? = null) {
        capsuleShown = shown
        scope.launch {
            try {
                val spec = if (shown) Motion.microIn.animation<Float>(reduceMotion = reduceMotion) else Motion.microOut.animation(reduceMotion = reduceMotion)
                if (animated) capsuleAlpha.animateTo(if (shown) 1f else 0f, spec) else capsuleAlpha.snapTo(if (shown) 1f else 0f)
                completion?.invoke()
            } catch (_: CancellationException) {
            }
        }
    }

    // The drawer swaps sessions in place: nothing of the last session's panel carries over to the next.
    fun reset() {
        motion += 1
        scope.launch { scrollState.scrollTo(0) }
        if (latestPresented) onPresentedChange(false)
        stage = BackgroundWorkStage.closed
        settled = false
        capsuleShown = latestHasWork
        capsuleMounted = latestHasWork
        scope.launch {
            progress.snapTo(0f)
            capsuleAlpha.snapTo(if (latestHasWork) 1f else 0f)
        }
        retained.capsule = null
        retained.agents = emptyList()
        retained.shells = emptyList()
        retained.scripts = emptyList()
        panelSession = null
    }

    // Mid-close, it turns back from where it is.
    fun open() {
        panelSession = latestSession
        motion += 1
        val current = motion
        settled = false
        scope.launch { scrollState.scrollTo(0) }
        dragOffset = 0f
        releaseVelocity = 0f
        stage = BackgroundWorkStage.panel
        scope.launch {
            try {
                progress.animateTo(1f, BackgroundWorkMotion.present(opening = true, reduceMotion = reduceMotion))
                if (current == motion && latestPresented && stage == BackgroundWorkStage.panel) settled = true
            } catch (_: CancellationException) {
            }
        }
    }

    // The work ended with the panel closed: the capsule fades, and the overlay leaves once it has.
    fun dismissCapsule() {
        motion += 1
        val current = motion
        setCapsuleShown(false, animated = true) {
            if (current == motion && !latestHasWork) capsuleMounted = false
        }
    }

    fun close() {
        if (stage == BackgroundWorkStage.closed) return
        // Opening a subagent swaps the session before the close begins.
        if (latestSession != panelSession) return reset()
        settled = false
        motion += 1
        val current = motion
        // The rows are kept (stage `capsule`) until the panel has left.
        stage = BackgroundWorkStage.capsule
        if (InternalBuild.isDebug) LabMarks.mark("background_work_close")
        if (!latestHasWork) setCapsuleShown(false, animated = true)
        // A pull let go keeps its speed on the way into the capsule.
        val left = panelSize.height + panelGap - dragOffset
        val velocity =
            if (releaseVelocity != 0f && left > 0f) Motion.panelClose.initialVelocity(-releaseVelocity / left, reduceMotion) else progress.velocity
        releaseVelocity = 0f
        val landsAt = BackgroundWorkMotion.mergeTail * density.density
        // Closed again before it had risen, nothing lands.
        var landed = reduceMotion || progress.value * (panelSize.height + panelGap) <= landsAt
        scope.launch {
            try {
                progress.animateTo(0f, BackgroundWorkMotion.present(opening = false, reduceMotion = reduceMotion), velocity) {
                    if (!landed && value * left <= landsAt) {
                        landed = true
                        scope.launch { landing.animateTo(0f, BackgroundWorkMotion.land(), BackgroundWorkMotion.landKick * density.density) }
                    }
                }
                if (current == motion && !latestPresented && stage == BackgroundWorkStage.capsule) {
                    stage = BackgroundWorkStage.closed
                    if (!latestHasWork) capsuleMounted = false else if (!latestCovered) capsuleFocus = current
                }
            } catch (_: CancellationException) {
            }
        }
    }

    OnChange(isPresented) { _, presented ->
        if (presented) {
            if (stage != BackgroundWorkStage.panel) open()
        } else if (stage == BackgroundWorkStage.panel) {
            close()
        }
    }
    OnChange(hasWork) { _, work ->
        if (work) {
            // Work returning mid-fade starts a capsule at rest.
            motion += 1
            if (!latestPresented) {
                stage = BackgroundWorkStage.closed
                scope.launch { progress.snapTo(0f) }
            }
            capsuleMounted = true
            setCapsuleShown(true, animated = true)
        } else if (latestPresented) {
            // The presented change closes it.
            onPresentedChange(false)
        } else if (stage != BackgroundWorkStage.closed) {
            // Mid-close: the capsule leaves with the panel.
            close()
        } else {
            dismissCapsule()
        }
    }
    // Opening taps, closing is quiet, like the attachment menu.
    HapticEffect(Haptic.tap, isPresented) { _, open -> open }
    OnChange(sessionID) { _, _ -> reset() }
    OnChange(inCall) { _, call -> if (call) onPresentedChange(false) }
    OnChange(isCovered) { _, covered -> if (covered && latestPresented) onPresentedChange(false) }
    BackHandler(enabled = stage == BackgroundWorkStage.panel && isPresented) { onPresentedChange(false) }

    val isPanel = stage == BackgroundWorkStage.panel

    val dragCloses = BackgroundWorkMotion.dragCloses * density.density
    val pastDragCloses by remember(dragCloses) { derivedStateOf { dragOffset > dragCloses } }
    HapticEffect(Haptic.threshold, pastDragCloses) { _, past -> past }
    val panelDrag = remember(density, reduceMotion) {
        object : NestedScrollConnection {
            var settle: Job? = null

            // The list's pixels are the panel's own, which shrink as it nears the capsule.
            fun scale(): Float =
                if (reduceMotion) 1f else PanelMerge(panelSize, panelGap, (retained.capsule?.width ?: 0f) * density.density, dragOffset, 1f, BackgroundWorkMotion.mergeTail * density.density).scale

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || dragOffset <= 0f || available.y >= 0f) return Offset.Zero
                settle?.cancel()
                val scale = scale()
                val moved = max(available.y * scale, -dragOffset)
                dragOffset += moved
                return Offset(0f, moved / scale)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                settle?.cancel()
                dragOffset += available.y * scale()
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (dragOffset <= 0f) return Velocity.Zero
                val velocity = available.y * scale()
                val thrown = dragOffset > BackgroundWorkMotion.throwFrom * density.density &&
                    velocity / density.density > BackgroundWorkMotion.throwCloses
                if (dragOffset > dragCloses || thrown) {
                    releaseVelocity = velocity
                    latestOnPresentedChange(false)
                } else {
                    settle = scope.launch {
                        animate(dragOffset, 0f, animationSpec = Motion.release.animation(reduceMotion = reduceMotion)) { value, _ -> dragOffset = value }
                    }
                }
                return available
            }
        }
    }

    Box(modifier.onGloballyPositioned { origin = it.positionInRoot(); overlayWidth = it.size.width.toFloat() }) {
        content()

        // The capsule's frame (dp, overlay-local), or while the panel is up or closing, where it last was.
        // The place's top (root px) this composition laid the capsule out from.
        val composedTop = anchor.bounds?.top
        val live = anchor.bounds?.let { b ->
            Rect((b.left - origin.x) / density.density, (b.top - origin.y) / density.density, (b.right - origin.x) / density.density, (b.bottom - origin.y) / density.density)
        }
        if (live != null) retained.capsule = live
        val capsule = live ?: if (stage == BackgroundWorkStage.closed && !capsuleMounted) null else retained.capsule
        if (capsule == null) return@Box

        // The rows the work has, or once it has ended, the rows it last had.
        if (hasWork) {
            retained.agents = agents
            retained.shells = shells
            retained.scripts = scripts
        }
        val rowAgents = retained.agents
        val rowShells = retained.shells
        val rowScripts = retained.scripts

        // A tap anywhere else closes the panel, and goes no further.
        if (isPanel) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { onPresentedChange(false) } }
                    .clearAndSetSemantics { },
            )
        }

        val pill = Rect(capsule.left, capsule.top + BackgroundWorkMotion.capsuleInset, capsule.right, max(capsule.top + BackgroundWorkMotion.capsuleInset, capsule.bottom - BackgroundWorkMotion.capsuleInset))
        // Like the pills beside it, the capsule hides under a panel grown out of the composer.
        val hidden = isCovered
        val hiddenAlpha by animateFloatAsState(if (hidden) 0f else 1f, Motion.reducedFadeSpec(), label = "hidden")
        val summary = BackgroundWorkSummary.summary(rowAgents, rowShells, rowScripts)
        val showWorkLabel = stringResource(R.string.session_background_work_show_label)
        val stateLabel = stringResource(
            if (isPresented) R.string.session_background_work_expanded_description else R.string.session_background_work_collapsed_description,
        )
        val surfaceColor = OpenCodeSurfaces.color
        val borderColor = OpenCodeTheme.colors.border

        // The panel: just above the capsule, their leading edges in line. Its rows stay composed
        // (held still, out of sight and touch) while it is closed, so opening never composes them
        // in its first frame.
        val rowsOpen = stage != BackgroundWorkStage.closed
        val safeTop = WindowInsets.safeDrawing.getTop(density) / density.density - origin.y / density.density
        val widthDp = overlayWidth / density.density
        val margin = if (isRtl) widthDp - pill.right else pill.left
        val maxWidth = max(pill.width, min(BackgroundWorkMotion.panelMaxWidth, widthDp - 2 * margin))
        val minWidth = min(maxWidth, max(pill.width, BackgroundWorkMotion.panelMinWidth))
        val panelBottom = pill.top - BackgroundWorkMotion.panelGap
        val maxHeight = max(96f, min(BackgroundWorkMotion.panelMaxHeight, panelBottom - max(0f, safeTop) - 56f))
        val canAct = isPanel && !hidden

        // The capsule and the panel's resting bottom edge as they are placed (px).
        val d = density.density
        val pillPx = Rect(
            Offset((pill.left * d).roundToInt().toFloat(), (pill.top * d).roundToInt().toFloat()),
            Size((pill.width * d).roundToInt().toFloat(), (pill.height * d).roundToInt().toFloat()),
        )
        val restBottom = (panelBottom * d).roundToInt().toFloat()
        // While the panel is up, the capsule and the panel share one surface and one border.
        // Reduce Motion fades the panel in its place, as a popover of its own.
        val merges = rowsOpen && !reduceMotion
        fun merge(size: Size): PanelMerge = PanelMerge(
            size = size,
            gap = pillPx.top - restBottom,
            capsuleWidth = pillPx.width,
            drag = dragOffset,
            progress = progress.value,
            tail = BackgroundWorkMotion.mergeTail * d,
        )
        fun outline(size: Size, merge: PanelMerge): MergedOutline = MergedOutline(
            pill = pillPx,
            top = restBottom - size.height + merge.slide,
            width = size.width * merge.scale,
            height = size.height * merge.scale,
            sunk = merge.sunk,
            radius = OpenCodeRadius.card.value * d,
            pillRadius = OpenCodeRadius.fill.value * d,
            fuse = BackgroundWorkMotion.mergeFuse * d,
            fillet = BackgroundWorkMotion.mergeFillet * d,
        )
        val outlinePath = remember { Path() }

        if (merges) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = hiddenAlpha * capsuleAlpha.value
                        translationY = landing.value
                        shadowElevation = lerp(OpenCodeSurfaces.raisedShadow.toPx(), OpenCodeSurfaces.popoverShadow.toPx(), progress.value)
                        shape = MergedShape(outline(panelSize, merge(panelSize)))
                        clip = false
                    }
                    .drawBehind {
                        outline(panelSize, merge(panelSize)).addTo(outlinePath)
                        drawPath(outlinePath, surfaceColor)
                    },
            )
        }

        // The capsule keeps its place whether the panel is open or not.
        Box(
            Modifier
                .graphicsLayer {
                    alpha = hiddenAlpha * capsuleAlpha.value
                    translationY = landing.value
                }
                .layout { measurable, _ ->
                    val placeable = measurable.measure(Constraints.fixed((pill.width * density.density).roundToInt(), (pill.height * density.density).roundToInt()))
                    layout(0, 0) { placeable.place((pill.left * density.density).roundToInt(), (pill.top * density.density).roundToInt()) }
                }
                // The capsule rides its place in the frame the place moves: the bounds above are
                // last frame's, so the drawing makes up the difference from where the place is
                // laid out now. The next composition catches up.
                .drawWithContent {
                    anchor.motion()
                    val now = anchor.coordinates?.takeIf { it.isAttached }?.positionInRoot()
                    val dy = if (stage == BackgroundWorkStage.closed && composedTop != null && now != null) now.y - composedTop else 0f
                    if (dy == 0f) drawContent() else translate(top = dy) { this@drawWithContent.drawContent() }
                }
                .then(if (merges) Modifier else Modifier.shadow(OpenCodeSurfaces.raisedShadow, OpenCodeShape.control, clip = false))
                .clip(OpenCodeShape.control)
                .then(if (merges) Modifier else Modifier.background(surfaceColor).openCodeHairline(OpenCodeShape.control))
                .accessibilityFocusRequest(capsuleFocusRequest)
                .then(
                    // A capsule fading with the work, or gone, takes no touch.
                    if (!hidden && capsuleShown) {
                        Modifier
                            // A vertical drag on the capsule scrolls the transcript.
                            .passesDragsToTranscript(transcript)
                            .clickable(onClickLabel = showWorkLabel) {
                                onToggle()
                                onPresentedChange(!isPresented)
                            }
                            .testTag("background-activity")
                            .semantics {
                                contentDescription = summary
                                stateDescription = stateLabel
                            }
                    } else {
                        Modifier.clearAndSetSemantics { }
                    },
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            BackgroundWorkCapsuleLabel(rowAgents.size, rowShells.size, Modifier.clearAndSetSemantics { }, scripts = rowScripts.size) {
                BackgroundWorkGlyph(rowAgents, rowShells, waitingSessionIDs, probe, isPaused = isPaused, scripts = rowScripts)
            }
        }

        Box(
            Modifier
                .layout { measurable, _ ->
                    val placeable = measurable.measure(
                        Constraints(minWidth = (minWidth * d).roundToInt(), maxWidth = (maxWidth * d).roundToInt(), maxHeight = (maxHeight * d).roundToInt()),
                    )
                    layout(0, 0) {
                        val x = if (isRtl) (pill.right * d).roundToInt() - placeable.width else (pill.left * d).roundToInt()
                        // Closed, far outside the screen: it takes no touch and draws nothing.
                        if (rowsOpen) placeable.place(x, (panelBottom * d).roundToInt() - placeable.height) else placeable.place(-100_000, 0)
                    }
                }
                .graphicsLayer {
                    if (reduceMotion) {
                        alpha = hiddenAlpha * progress.value
                        translationY = dragOffset
                    } else {
                        val merge = merge(size)
                        // The last of the rows fade as they reach the capsule.
                        alpha = hiddenAlpha * capsuleAlpha.value * min(1f, merge.left / BackgroundWorkMotion.mergeTail.dp.toPx())
                        translationY = merge.slide + landing.value
                        scaleX = merge.scale
                        scaleY = merge.scale
                        transformOrigin = TransformOrigin(if (isRtl) 1f else 0f, 0f)
                        shape = PanelClipShape(outline(size, merge), merge)
                        clip = true
                    }
                }
                .onSizeChanged { panelSize = it.toSize() }
                .then(
                    if (reduceMotion) {
                        Modifier
                            .shadow(OpenCodeSurfaces.popoverShadow, OpenCodeSurfaces.popoverShape, clip = false)
                            .clip(OpenCodeSurfaces.popoverShape)
                            .background(surfaceColor)
                            .openCodeHairline(OpenCodeSurfaces.popoverShape)
                    } else {
                        // Rows soften into the capsule's edge, which has no line to cut them.
                        Modifier.drawWithContent {
                            drawContent()
                            val merge = merge(size)
                            val fused = outline(size, merge).fused
                            if (fused > 0f) {
                                val band = BackgroundWorkMotion.mergeFade.dp.toPx() / merge.scale
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        listOf(surfaceColor.copy(alpha = 0f), surfaceColor),
                                        startY = merge.visibleHeight - band,
                                        endY = merge.visibleHeight,
                                    ),
                                    topLeft = Offset(0f, merge.visibleHeight - band),
                                    size = Size(size.width, band),
                                    alpha = fused,
                                )
                            }
                        }
                    },
                )
                .nestedScroll(panelDrag)
                .pointerInput(Unit) { detectTapGestures { } }
                .then(if (settled && !hidden) Modifier.testTag("background-work-panel") else Modifier.clearAndSetSemantics { }),
            propagateMinConstraints = true,
        ) {
            BackgroundWorkPanel(
                agents = rowAgents,
                waitingSessionIDs = waitingSessionIDs,
                shells = rowShells,
                scripts = rowScripts,
                stoppingIDs = stoppingIDs,
                onStopScript = { id -> if (canAct) onStopScript(id) },
                onStopSubagent = { id -> if (canAct) onStopSubagent(id) },
                onStopShell = { id -> if (canAct) onStopShell(id) },
                scrollState = scrollState,
                isLive = rowsOpen && !isPaused,
                // Open, a row arriving or leaving moves the panel's top.
                animatesSize = isPanel,
                // Landed open, TalkBack moves into the panel.
                focusRequest = rememberAccessibilityFocusRequest(if (settled && !hidden) motion else null),
                onOpenSubagent = { id ->
                    if (canAct) {
                        onPresentedChange(false)
                        onOpenSubagent(id)
                    }
                },
                onOpenShell = { id ->
                    if (canAct) {
                        onPresentedChange(false)
                        onOpenShell(id)
                    }
                },
            )
        }

        // The one border around both, over the rows.
        if (merges) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = hiddenAlpha * capsuleAlpha.value
                        translationY = landing.value
                    }
                    .drawBehind {
                        val merged = outline(panelSize, merge(panelSize))
                        merged.addTo(outlinePath)
                        val stroke = OpenCodeSurfaces.hairline.toPx()
                        clipPath(outlinePath) { drawPath(outlinePath, borderColor, style = Stroke(stroke * 2)) }
                        if (merged.seam > 0f) {
                            drawLine(
                                color = borderColor,
                                start = Offset(merged.seamStart, pillPx.top),
                                end = Offset(merged.seamEnd, pillPx.top),
                                strokeWidth = stroke * 2,
                                alpha = merged.seam,
                            )
                        }
                    },
            )
        }
    }
}

/**
 * The panel on its way into the capsule under it. It slides down [slide] px from its place, shrinking
 * to the capsule's width, and only its top [visibleHeight] px (its own, before scaling) still shows above the capsule.
 */
private class PanelMerge(size: Size, gap: Float, capsuleWidth: Float, drag: Float, progress: Float, tail: Float) {
    val slide: Float
    val scale: Float
    val visibleHeight: Float

    /** How far it still has to go (px). */
    val left: Float

    /** How far its bottom edge has gone under the capsule's top (px); negative while they are apart. */
    val sunk: Float

    init {
        val travel = size.height + gap
        slide = min(travel, lerp(travel, drag, progress))
        left = travel - slide
        // As narrow as the capsule for the last [tail] px, so the two end as one shape.
        val narrowed = (slide / max(1f, travel - tail)).coerceIn(0f, 1f)
        scale = if (size.width > 0f) lerp(1f, capsuleWidth / size.width, narrowed) else 1f
        visibleHeight = min(size.height, left / scale)
        sunk = size.height * scale - left
    }
}

/**
 * The capsule and the panel above it as one outline (px, overlay-local). Apart, they are two rounded
 * rectangles. Once the panel's bottom edge meets the capsule's top, the corners between them close
 * over [fuse] px more of travel, and the step between their widths rounds by [fillet].
 */
private class MergedOutline(
    private val pill: Rect,
    private val top: Float,
    width: Float,
    height: Float,
    sunk: Float,
    radius: Float,
    pillRadius: Float,
    fuse: Float,
    fillet: Float,
) {
    private val hasPanel = width > 0f && height > 0f
    private val merged = hasPanel && sunk >= 0f

    /** 0 as the two touch, 1 once they are one. */
    val fused: Float = if (merged) (sunk / fuse).coerceIn(0f, 1f) else 0f

    // The corners where the two meet shrink away over the first half, then the step rounds.
    private val pinch = if (merged) max(0f, 1f - 2f * fused) else 0f
    private val rounding = max(0f, 2f * fused - 1f)

    private val capsuleRadius = min(pillRadius, min(pill.width, pill.height) / 2)
    private val bottom = if (merged) pill.top else top + height
    private val right = if (merged) max(pill.right, pill.left + width) else pill.left + width

    /** The panel above the capsule, and how much wider it is. */
    private val rise = bottom - top
    private val step = right - pill.right

    private val apart = min(radius, min(width, height) / 2)
    private val capsulePinch = capsuleRadius * pinch
    private val crown = lerp(capsuleRadius, radius, (rise / (2 * radius)).coerceIn(0f, 1f))

    /** The panel's corners: near is the side in line with the capsule's leading edge. */
    val bottomNear: Float = if (merged) min(radius * pinch, rise / 2) else apart
    private val stepCorner = min(radius, step / 2)
    private val closingCorner = min(radius * pinch, step + capsulePinch)
    val bottomFar: Float = if (merged) min(rise / 2, max(stepCorner, closingCorner)) else apart
    val topNear: Float = if (!merged) apart else if (bottomNear > 0f) min(crown, rise - bottomNear) else crown
    val topFar: Float = if (!merged) apart else if (bottomFar > 0f) min(crown, rise - bottomFar) else crown

    private val filletRadius =
        if (merged) max(0f, min(fillet * rounding, min(step - bottomFar, pill.height - capsuleRadius))) else 0f

    /** The line where the two edges met, fading as they become one: its opacity and ends. */
    val seam: Float = pinch
    val seamStart: Float = pill.left + max(bottomNear, capsulePinch)
    val seamEnd: Float = pill.right - capsulePinch

    fun addTo(path: Path) {
        path.rewind()
        if (!merged) {
            path.addRoundRect(RoundRect(pill, CornerRadius(capsuleRadius)))
            if (hasPanel) path.addRoundRect(RoundRect(pill.left, top, right, bottom, CornerRadius(apart)))
            return
        }
        val near = pill.left
        path.moveTo(near + topNear, top)
        path.corner(right - topFar, top + topFar, topFar, 270f)
        if (step > 0f || bottomFar > 0f) {
            path.corner(right - bottomFar, pill.top - bottomFar, bottomFar, 0f)
            if (filletRadius > 0f) {
                path.corner(pill.right + filletRadius, pill.top + filletRadius, filletRadius, 270f, -90f)
            } else {
                path.corner(pill.right - capsulePinch, pill.top + capsulePinch, capsulePinch, 270f)
            }
        }
        path.corner(pill.right - capsuleRadius, pill.bottom - capsuleRadius, capsuleRadius, 0f)
        path.corner(near + capsuleRadius, pill.bottom - capsuleRadius, capsuleRadius, 90f)
        if (capsulePinch > 0f || bottomNear > 0f) {
            path.corner(near + capsulePinch, pill.top + capsulePinch, capsulePinch, 180f)
            path.corner(near + bottomNear, pill.top - bottomNear, bottomNear, 90f)
        }
        path.corner(near + topNear, top + topNear, topNear, 180f)
        path.close()
    }

    /** A quarter turn of radius [r] about ([cx], [cy]) from [start] degrees, or the corner itself. */
    private fun Path.corner(cx: Float, cy: Float, r: Float, start: Float, sweep: Float = 90f) {
        if (r < 0.01f) return lineTo(cx, cy)
        arcTo(Rect(cx - r, cy - r, cx + r, cy + r), start, sweep, forceMoveTo = false)
    }
}

private class MergedShape(val outline: MergedOutline) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(Path().also(outline::addTo))
}

/** The panel's part of [outline], in the panel's own pixels: what still shows of its rows. */
private class PanelClipShape(val outline: MergedOutline, val merge: PanelMerge) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val scale = merge.scale
        return Outline.Rounded(
            RoundRect(
                left = 0f,
                top = 0f,
                right = size.width,
                bottom = merge.visibleHeight,
                topLeftCornerRadius = CornerRadius(outline.topNear / scale),
                topRightCornerRadius = CornerRadius(outline.topFar / scale),
                bottomRightCornerRadius = CornerRadius(outline.bottomFar / scale),
                bottomLeftCornerRadius = CornerRadius(outline.bottomNear / scale),
            ),
        )
    }
}

/** Runs [action] with the old and new value when [value] changes, never on first composition. */
@Composable
private fun <T> OnChange(value: T, action: (old: T, new: T) -> Unit) {
    val previous = remember { arrayOfNulls<Any?>(1).also { it[0] = value } }
    val latest by rememberUpdatedState(action)
    LaunchedEffect(value) {
        @Suppress("UNCHECKED_CAST")
        val old = previous[0] as T
        previous[0] = value
        if (old != value) latest(old, value)
    }
}

// MARK: Panel

/**
 * Running subagents, scripts and background shells, one compact row each, as wide as its longest
 * row within the width it is given. [BackgroundWorkPresentation] supplies its surface and place.
 *
 * @param agents running subagents (tap: [onOpenSubagent] with its session ID).
 * @param waitingSessionIDs subagents waiting on the user: their orbs show Waiting for Input.
 * @param shells background shells, with their command and running time (tap: [onOpenShell]).
 * @param scrollState the list's scroll position; scrolls once the rows outgrow the panel.
 * @param isLive the orbs and clocks run.
 * @param animatesSize a change in the rows moves the panel's edges instead of jumping.
 * @param focusRequest moves TalkBack's focus onto the first row, once.
 */
@Composable
fun BackgroundWorkPanel(
    agents: List<OpenCodeSession>,
    shells: List<OpenCodeShell>,
    onOpenSubagent: (String) -> Unit,
    onOpenShell: (String) -> Unit,
    modifier: Modifier = Modifier,
    scripts: List<BackgroundScript> = emptyList(),
    onStopScript: (String) -> Unit = {},
    onStopSubagent: (String) -> Unit = {},
    onStopShell: (String) -> Unit = {},
    stoppingIDs: Set<String> = emptySet(),
    waitingSessionIDs: Set<String> = emptySet(),
    scrollState: ScrollState = rememberScrollState(),
    isLive: Boolean = true,
    animatesSize: Boolean = false,
    focusRequest: AccessibilityFocusRequest? = null,
) {
    val firstID = agents.firstOrNull()?.id ?: scripts.firstOrNull()?.id ?: shells.firstOrNull()?.id
    val resize = if (animatesSize) Modifier.animateContentSize(BackgroundWorkMotion.resize(), Alignment.BottomStart) else Modifier
    Column(modifier.then(resize).width(IntrinsicSize.Max).verticalScroll(scrollState).padding(BackgroundWorkRowMetrics.listPadding.dp)) {
        for (agent in agents) {
            key("subagent", agent.id) {
                BackgroundWorkRow(
                    title = agent.title,
                    accessibilityHint = stringResource(R.string.session_background_work_open_subagent_label),
                    onTap = { onOpenSubagent(agent.id) },
                    stopLabel = stringResource(R.string.session_background_work_stop_subagent_label),
                    onStop = { onStopSubagent(agent.id) },
                    isStopping = agent.id in stoppingIDs,
                    modifier = Modifier
                        .testTag("background-subagent-${agent.id}")
                        .then(if (agent.id == firstID) Modifier.accessibilityFocusRequest(focusRequest) else Modifier),
                ) {
                    AgentActivityGlyph(
                        source = AgentActivitySource.Subagent(
                            SubagentActivity(sessionID = agent.id, phase = SubagentActivity.Phase.running, needsInput = agent.id in waitingSessionIDs),
                        ),
                        isPaused = !isLive,
                    )
                }
            }
        }
        for (script in scripts) {
            key("script", script.id) {
                val stopLabel = stringResource(R.string.session_background_work_stop_script_label)
                BackgroundWorkRow(
                    title = script.label,
                    isCommand = true,
                    startedAt = script.startedAt,
                    isLive = isLive,
                    accessibilityHint = stopLabel,
                    onTap = null,
                    stopLabel = stopLabel,
                    onStop = { onStopScript(script.id) },
                    isStopping = script.id in stoppingIDs,
                    modifier = Modifier
                        .testTag("background-script-${script.id}")
                        .then(if (script.id == firstID) Modifier.accessibilityFocusRequest(focusRequest) else Modifier),
                ) {
                    RowGlyph(SFSymbol.ChevronLeftForwardslashChevronRight)
                }
            }
        }
        for (shell in shells) {
            key("shell", shell.id) {
                BackgroundWorkRow(
                    title = shell.title,
                    isCommand = true,
                    startedAt = shell.startedAt,
                    isLive = isLive,
                    accessibilityHint = stringResource(R.string.session_background_work_show_shell_label),
                    onTap = { onOpenShell(shell.id) },
                    stopLabel = stringResource(R.string.session_background_work_stop_shell_label),
                    onStop = { onStopShell(shell.id) },
                    isStopping = shell.id in stoppingIDs,
                    modifier = Modifier
                        .testTag("background-shell-${shell.id}")
                        .then(if (shell.id == firstID) Modifier.accessibilityFocusRequest(focusRequest) else Modifier),
                ) {
                    RowGlyph(SFSymbol.Terminal)
                }
            }
        }
    }
}

private object BackgroundWorkRowMetrics {
    const val listPadding = 4f
    const val rowHeight = 32f

    /** A row's glyph stands under the capsule's, and its title under the capsule's counts. */
    val leading: Float get() = BackgroundWorkTunables.glyphLeading - listPadding
    const val stopSlot = 34f
}

@Composable
private fun BackgroundWorkRow(
    title: String,
    accessibilityHint: String,
    onTap: (() -> Unit)?,
    modifier: Modifier = Modifier,
    isCommand: Boolean = false,
    startedAt: Instant? = null,
    isLive: Boolean = true,
    stopLabel: String? = null,
    onStop: (() -> Unit)? = null,
    isStopping: Boolean = false,
    leading: @Composable () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = BackgroundWorkRowMetrics.rowHeight.dp)
            .clip(RoundedCornerShape(OpenCodeRadius.sm))
            .then(if (onTap != null) Modifier.clickable(role = Role.Button, onClickLabel = accessibilityHint, onClick = onTap) else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = title
                if (onTap != null) {
                    onClick(label = accessibilityHint) { onTap(); true }
                    if (onStop != null && stopLabel != null && !isStopping) {
                        customActions = listOf(CustomAccessibilityAction(stopLabel) { onStop(); true })
                    }
                } else if (onStop != null && !isStopping) {
                    onClick(label = stopLabel ?: accessibilityHint) { onStop(); true }
                }
            }
            .padding(start = BackgroundWorkRowMetrics.leading.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(18.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(PILL_GLYPH_GAP.dp))
        Text(
            title,
            style = if (isCommand) OpenCodeTextStyles.footnote.copy(fontFamily = OpenCodeFonts.monospace) else OpenCodeTextStyles.subheadline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clearAndSetSemantics { },
        )
        if (startedAt != null) {
            Box(Modifier.padding(start = 12.dp).clearAndSetSemantics { }) {
                ShellElapsedTime(
                    since = startedAt,
                    isPaused = !isLive,
                    style = OpenCodeTextStyles.caption1.copy(fontFeatureSettings = "tnum"),
                    color = OpenCodeTheme.colors.secondaryText,
                )
            }
        }
        if (onStop != null) {
            BackgroundWorkStop(
                label = stopLabel,
                onStop = onStop,
                isStopping = isStopping,
                // The row's semantics carry Open's custom Stop action; a script row's own Stop stays its button.
                modifier = if (onTap != null) Modifier.clearAndSetSemantics { } else Modifier,
            )
        }
    }
}

@Composable
private fun BackgroundWorkStop(label: String?, onStop: () -> Unit, isStopping: Boolean, modifier: Modifier = Modifier) {
    val interactions = remember { MutableInteractionSource() }
    Box(
        modifier
            .heightIn(max = BackgroundWorkRowMetrics.rowHeight.dp)
            .width(BackgroundWorkRowMetrics.stopSlot.dp)
            .clickable(interactionSource = interactions, indication = null, enabled = !isStopping, role = Role.Button, onClickLabel = label, onClick = onStop)
            .openCodeTapTarget(accessibility = false),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(OpenCodeShape.control)
                .indication(interactions, LocalIndication.current),
            contentAlignment = Alignment.Center,
        ) {
            if (isStopping) {
                CircularProgressIndicator(Modifier.size(12.dp), color = OpenCodeTheme.colors.secondaryText, strokeWidth = 1.5.dp)
            } else {
                OpenCodeIcon(SFSymbol.Xmark, contentDescription = null, size = 16.dp, tint = OpenCodeTheme.colors.secondaryText)
            }
        }
    }
}

// MARK: Elapsed time

/**
 * Running time as the desktop writes it (`42s`, `12m 03s`, `1h 02m`), ticking each second.
 *
 * @param since when the work started.
 * @param until when it ended; a fixed time then.
 * @param isPaused hidden or moving: shows the time once instead of ticking.
 */
@Composable
fun ShellElapsedTime(
    since: Instant,
    modifier: Modifier = Modifier,
    until: Instant? = null,
    isPaused: Boolean = false,
    style: TextStyle = OpenCodeTextStyles.subheadline.copy(fontFeatureSettings = "tnum"),
    color: Color = Color.Unspecified,
) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(since, until, isPaused) {
        now = Instant.now()
        if (until != null || isPaused) return@LaunchedEffect
        while (true) {
            // Ticks on the whole seconds since it started.
            val elapsedMillis = Duration.between(since, Instant.now()).toMillis()
            delay(1000 - Math.floorMod(elapsedMillis, 1000L) + 1)
            now = Instant.now()
        }
    }
    val seconds = max(0L, Duration.between(since, until ?: now).seconds)
    Text(
        ShellElapsedTimeFormat.compact(seconds),
        style = style,
        color = color,
        maxLines = 1,
        modifier = modifier.clearAndSetSemantics { contentDescription = ShellElapsedTimeFormat.spoken(seconds) },
    )
}

object ShellElapsedTimeFormat {
    fun compact(seconds: Long): String {
        val locale = appFormattingLocale()
        val h = (seconds / 3600).toInt()
        val m = ((seconds % 3600) / 60).toInt()
        val s = (seconds % 60).toInt()
        if (seconds < 60) return localized(R.string.session_background_work_elapsed_seconds, s)
        if (seconds < 3600) return localized(R.string.session_background_work_elapsed_minutes, m, "%02d".format(locale, s))
        return localized(R.string.session_background_work_elapsed_hours, h, "%02d".format(locale, m))
    }

    fun spoken(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        val parts = listOfNotNull(
            if (h > 0) localizedPlural(R.plurals.session_shell_elapsed_hours, h.toInt(), h.toInt()) else null,
            if (m > 0) localizedPlural(R.plurals.session_shell_elapsed_minutes, m.toInt(), m.toInt()) else null,
            if (s > 0) localizedPlural(R.plurals.session_shell_elapsed_seconds, s.toInt(), s.toInt()) else null,
        )
        return when (parts.size) {
            0 -> localizedPlural(R.plurals.session_shell_elapsed_seconds, 0, 0)
            1 -> parts[0]
            2 -> localized(R.string.session_list_two, parts[0], parts[1])
            else -> localized(R.string.session_list_three, parts[0], parts[1], parts[2])
        }
    }
}
