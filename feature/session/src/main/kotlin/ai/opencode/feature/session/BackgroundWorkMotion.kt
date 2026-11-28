package ai.opencode.feature.session

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import androidx.compose.animation.core.FiniteAnimationSpec

/** Where the background-work panel is. The capsule stays in its place throughout. */
enum class BackgroundWorkStage {
    /** Only the capsule shows. */
    closed,

    /** The panel is leaving. */
    capsule,
    panel,
}

/** Shipped defaults; there's no tuning UI. */
object BackgroundWorkTunables {
    /**
     * The capsule's glyph frame from its leading edge, and its count lowered so the digits' caps
     * sit centred.
     */
    const val glyphLeading: Float = 8.25f
    const val countNudge: Float = 1f / 3f

    /** The orb's and the shell glyph's disc sits a little high in its frame. */
    const val orbGlyphNudgeY: Float = 1f / 3f

    /** Between the orb and the shell disc when both run. */
    const val pairGap: Float = 4f
}

object BackgroundWorkMotion {
    /** The panel rises out of the capsule on `panelOpen` and slides back into it on the shorter `panelClose`. */
    fun <T> present(opening: Boolean, reduceMotion: Boolean): FiniteAnimationSpec<T> =
        (if (opening) Motion.panelOpen else Motion.panelClose).animation(MotionPhase.Enter, reduceMotion)

    /** Reduce Motion's cross-fade, for the capsule's counts. */
    fun <T> fade(): FiniteAnimationSpec<T> = Motion.reduced.animation(reduceMotion = true)

    /**
     * The capsule following its counts, and the panel following its rows: surface, words and glyph
     * on one spring (`layout`), unbouncy so a word leaving is never cut by the surface coming back.
     */
    fun <T> resize(): FiniteAnimationSpec<T> = Motion.layout.animation()

    const val capsuleInset: Float = 5f

    /** Between the capsule and the panel above it (dp). */
    const val panelGap: Float = 8f

    /** The panel is as wide as its longest row, within these (dp), and scrolls past this height. */
    const val panelMinWidth: Float = 240f
    const val panelMaxWidth: Float = 360f
    const val panelMaxHeight: Float = 320f

    /** Let go, the panel closes when pulled down past [dragCloses] (dp), or past [throwFrom] (dp) and thrown down faster than [throwCloses] (dp/s). */
    const val dragCloses: Float = 56f
    const val throwFrom: Float = 12f
    const val throwCloses: Float = 550f

    /** Once the panel's bottom edge meets the capsule's top, the two become one outline over this much more travel (dp). */
    const val mergeFuse: Float = 14f

    /** The step from the panel's width to the capsule's rounds by this (dp). */
    const val mergeFillet: Float = 8f

    /** Over the last of its way in (dp), the panel is as narrow as the capsule and its rows fade out. */
    const val mergeTail: Float = 16f

    /** Rows soften over this much (dp) above the capsule's edge. */
    const val mergeFade: Float = 12f

    /** As the last of the panel lands, the capsule is pushed down at this speed (dp/s) and returns on [land]: a dip of about 2.5 dp. */
    const val landKick: Float = 150f

    fun <T> land(): FiniteAnimationSpec<T> = Motion.microIn.animation()
}
