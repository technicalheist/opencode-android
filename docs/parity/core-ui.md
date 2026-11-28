# Parity: core-ui

Module: `:core` (`ai.opencode.core.ui`, `ai.opencode.core.motion`, `core/src/main/res`). iOS
files: `OpenCode/Core/Theme/`, `OpenCode/Core/Motion/`, `OpenCode/Core/RollingNumber/`,
`Glass.swift`, `TapTarget.swift`, `BackSwipe.swift`. Non-visual Core files are in
[core.md](core.md).

Status values: `todo`, `partial: <what is missing>`, `done`, `n/a: <reason and what it maps to>`.

**Visual divergence (on purpose):** behaviour, tokens' names and APIs port iOS, but the look is
the OpenCode desktop design system (`packages/ui`, theme `oc-2`), not iOS's SF / Liquid Glass:

- Fonts: Inter (variable; Normal 440, Medium 530, SemiBold 600, Bold 670) for UI text,
  OpenMono (Regular and Bold, no programming ligatures; Medium draws as Regular and SemiBold as
  Bold; italic is the system's slant, as no italic file is bundled) for code, tool output,
  commands and onboarding's brand marks (`res/font`; refresh with `scripts/update-openmono.py`).
  Both are OFL: the licence texts are in `core/src/main/assets/licenses/`, so every APK carries
  them.
  `OpenCodeTextStyles` keep the iOS names at the desktop's sizes (body 15, subheadline 14,
  footnote 13, headline 16 medium, titles medium with tight tracking), all in sp.
- Colour: `AppTheme.OpenCode` is `BrandPalette` (desktop v2 neutrals, monochrome contrast
  accent, blue links; five light values darkened just enough for WCAG AA) and is the default
  theme. Curated themes keep their palettes; System is Material You. For curated themes
  `surfaceContainerLow`/`surfaceContainer` are the desktop floating surface (canvas in light;
  in dark the code band lifted a third of the way to the user bubble, `darkFloatingFill`, about
  `#222222` in `BrandPalette`) and `surfaceTint` is transparent, so Material sheets, menus and
  tooltips are flat. `OpenCodeColors.signal` (the link blue; desktop `--v2-background-bg-accent`)
  colours unread and new-item marks, since the brand `accent` is neutral; `OpenCodeColors.danger`
  (`diffRemovedText`, desktop `--v2-state-fg-danger`) colours failure text and glyphs, while
  `destructive` stays systemRed for destructive actions and fills. `OpenCodeColors.strongBorders`: hairlines use `borderStrong` under
  Increase Contrast or Android's High contrast text (`rememberHighContrastText`).
  Desktop accents for controls (`DesktopAccent`, exact `theme.css` hex): `selection`
  (`--v2-background-bg-accent` `#3B5CF6`, white `onSelection`) fills on switches,
  radio/check marks and slider tracks (segmented controls stay neutral, as on the desktop); `focusRing` (`--v2-border-border-focus`
  `#7698FD`) is the text field focus border; `rowDivider` (`--v2-border-border-base`, black or
  white at 10 %) parts rows in a card. Named themes use their own accent (contrast-picked
  foreground), System the dynamic primary. Primary buttons stay neutral.
- Shapes: `OpenCodeRadius` xs 4, sm 6, fill/control 8, card/tile 10, panel 12, sheet 16;
  Material `Shapes` extraSmall 6, small 6, medium 8, large 10, extraLarge 16;
  `OpenCodeShape.control` (8) for buttons, pills and fields; capsule/circle only for true
  capsules (grabbers, dots, avatars).
- Surfaces (`ui/OpenCodeSurfaces.kt`): floating fill + 1 dp hairline (`openCodeHairline`)
  + small shadow; `OpenCodeAlertDialog` (desktop dialog over Material's `AlertDialog`).
- Button menus (`ui/OpenCodeButtonMenu.kt`): `OpenCodeButtonMenu`, a Material menu (`DropdownMenuPopup`,
  Material's motion) 6 dp above the button it's placed in, lined up with its leading edge, its
  popup not focusable so the keyboard stays up; placed from the button's `boundsInWindow`. The
  composer's + and agent menus use it. Android-only.
- Errors use one of four shapes. In a form or next to what failed: `OpenCodeErrorText`
  (`danger`, read out by TalkBack) or `OpenCodeTextField`'s error state. A pane that has nothing
  to show (failed to load, empty, no results): `OpenCodeContentUnavailable`, a 48 dp glyph, a
  bold `title3`, a `subheadline` description and a primary action. Needing a decision:
  `OpenCodeAlertDialog`. Anything else: a notice (below). `OpenCodeColors.warning` (systemOrange)
  colours what needs attention without having failed. Android-only; iOS draws each by hand.
- Notices (`ui/OpenCodeNotice.kt`): `OpenCodeNotice` is how a failure outside a form or a dialog
  is shown: a floating chip with a glyph (`danger` for a failure), what failed, why, and an
  action word. `OpenCodeNoticePresence` brings it in and out. Android-only; the session's
  connection pill, its request-load error and the drawer's failure notice use it.
- Controls: buttons 48 dp, 8 dp corners; `OpenCodeSecondaryButton` is the neutral hairline
  button; `OpenCodeIconButton(style = Circle)` is a 10 dp rounded square with a hairline.
  `OpenCodeSwitch` (`ui/OpenCodeFormControls.kt`) is the desktop switch (`forms/switch`): a
  40 × 24 dp track and an 18 dp thumb with concentric corners (7 / 4 dp, 3 dp inset), no
  checkmark, 150 ms slides, half opacity disabled, press widens the thumb (desktop hover).
  Off is light like the desktop (quiet `surfaceContainerHighest` track, light thumb with a
  faint edge); its outline (row colour 85 % of the way to `tertiaryText`, full under high
  contrast) carries the 3:1 contrast. On is `selection` / `onSelection`; System uses Material You.
  `OpenCodeSheetHandle` is every bottom sheet's drag handle: the desktop resize handle's
  40 × 4 dp pill in `tertiaryText` over Material's `DragHandle` (its band and TalkBack
  actions stay); custom grabbers use `OpenCodeSheetHandleDefaults`. Sheets take
  `OpenCodeShape.sheetTop` (16 dp top corners, `OpenCodeRadius.sheet`).
  `OpenCodeTextField` / `OpenCodeSearchField` (`ui/OpenCodeTextField.kt`) are the desktop text
  input (`forms/text-input`) over `BasicTextField`: the label sits above the field (no floating
  label), placeholder shown while empty, 48 dp minimum height, 8 dp corners, 12 dp padding,
  callout text. Fill is white in light (with a 1 dp shadow, desktop `elevation-input`) and a
  step above the sheet in dark; a 1 dp `outline` border (`tertiaryText` under high contrast).
  Focus is a 1.5 dp `primary` ring, error a `error` ring plus a footnote note under the field
  (TalkBack reads it via `error()`), disabled half opacity. Custom bare fields inside their own
  containers (composer, drawer search, browser bar, share dock) keep their containers; the
  model picker's search rows use `Modifier.openCodeFieldContainer`.
- Call orb (`feature/voice` `LivingOrbView`, `WispOrbShaders`): unchanged from iOS (the
  desktop has no orb), so it keeps iOS's blue-black glass, dots, glow and shadow on the call card.
- Call card (`feature/voice` `FloatingCall`, `VoiceCallSurfaceDefaults`): one step off the
  page (`card`: 5% toward the text colour in light, 6% in dark) with a hairline and a soft
  20 dp shadow, so it separates from the canvas in both themes; the controls are 12 dp rounded
  squares (`controlFill` + hairline, ink when on, theme red for End) instead of tonal circles.
- Title bars (`ui/OpenCodeTopBar.kt`, Android-only): the desktop's settings/session header
  instead of Material's `TopAppBar`s: flat canvas, 52 dp row, headline title (start or
  centred), bare back arrow (`OpenCodeTopBarBackButton`), 1 dp bottom hairline that fades in
  once content scrolls under it (always shown without a scroll behaviour). `OpenCodeLargeTopBar`
  is the large-title variant (title1 under the row; Material's `exitUntilCollapsed` behaviour
  scrolls it away and fades the small title in), used by Settings pages.
- Scrolling only when content overflows (`ui/OpenCodeScrolling.kt`, Android-only): a page whose
  content fits doesn't move on a drag. `rememberOpenCodeLargeTopBarScrollBehavior(listState)`
  collapses the large title only while the list can scroll (or the bar is already collapsed),
  and `rememberOverscrollIfContentScrolls(state)` drops the stretch overscroll while it can't.
- Camera screens (pairing scanner, composer camera) are always dark and keep the phone-camera
  conventions where the desktop's shapes look worse over a live photo: round translucent
  controls with a faint white hairline, and four bold round-capped viewfinder corners (5 dp,
  44 dp arms, following the 16 dp window radius just outside the window, scene dimmed outside it). Their bars, panels and hint text use the desktop tokens.
- Welcome illustration: the phone mock depicts the restyled Android app (flat header, desktop
  greys, hairline composer) rather than the iOS app's glass chrome.
Paths below are relative to `core/src/main/kotlin/ai/opencode/core/`.

## Files

| iOS file | Lines | Kotlin file(s) | Status | Notes |
| --- | ---: | --- | --- | --- |
| `OpenCode/Core/Theme/Theme.swift` | 794 | `ui/AppTheme.kt`, `ui/OpenCodeColors.kt`, `ui/OpenCodeTheme.kt`, `ui/ThemeSharedKeys.kt` | done | `AppTheme` (iOS raw values, `@Serializable`), `AppearanceColorScheme`, `ThemeRGBA` (WCAG, OKLab/OKLCH, gamut mapping, `highContrastForeground`), `ThemeVariant`/`ThemeDefinition`, `ThemePalette.system` + `definition()`. Every dynamic provider (`canvas`, `groupedCanvas`, `groupedCard`, `codeBand`, `userBubble`, `inlineCodeFill`, text tiers, `bodyText(tone:)`, `border`, `accent`, Send button, `link`, syntax, diff) → `OpenCodeColors.resolve` with the same Increase Contrast and System-theme branches (Increase Contrast = `rememberHighContrast`, the system contrast level from Android 14; Android 13 has none, so only High contrast text strengthens hairlines there) (System uses the UIKit values: `label` alpha code band, `tertiarySystemFill`, label/background Send button, systemGreen/Red diff fills). Material `onPrimary`/`onTertiary`/`onError` are white for the System theme (UIKit labels tint-filled and destructive controls white) and `highContrastForeground` for theme palettes; destructive primary buttons use white. Increase Contrast = system contrast ≥ medium (`UiModeManager.getContrast`). Keys `appearance-theme` / `appearance-color-scheme`; Android divergence: `appearance-theme` defaults to `oc-2` (the desktop palette, `BrandPalette`) instead of `.system`. `applyAppAppearance()` → `OpenCodeAppTheme {}`; its window `overrideUserInterfaceStyle` (which restyles the status bar) → `OpenCodeAppTheme` sets the activity window's light/dark status and navigation bar content from the effective appearance, not the system night mode. `SettingsThemeSurface` → `OpenCodeColors.resolve(theme, isDark, highContrast)` (same values). Android divergence: the System theme follows the wallpaper (Material You dynamic colour, `ui/DynamicSystemTheme.kt`): the `ColorScheme` is the dynamic one and the iOS tokens are mapped from its roles (syntax and diff colours keep the System values); the Appearance swatches and previews read it through `rememberThemeDefinition`. The curated themes keep their fixed palettes. Not ported: UIKit trait plumbing (`AppThemeTrait`, `applyToWindows`; Compose recomposes), DEBUG `-lab-theme`/`-lab-appearance` overrides, legacy key clean-up (key never existed on Android), the 0.22 s window cross-fade on theme change (constant kept as `ThemePalette.THEME_CHANGE_CROSSFADE_SECONDS`) Sync 03adf07a (03adf07a): n/a: iOS's theme change crossfades the window on `Motion.swap`; Android changes theme without a window crossfade. |
| `OpenCode/Core/Theme/ThemePalette+Generated.swift` | 397 | `ui/ThemePaletteGenerated.kt` (generated by `scripts/generate-themes-android.py`) | done | Every value copied from the generated Swift (6 themes × light/dark × 28 tokens); rerun the script when iOS regenerates |
| `OpenCode/Core/Motion/MotionConstants.swift` | 197 | `motion/MotionConstants.kt`, `motion/OpenCodeMotionScheme.kt` | done | All 9 springs (`MotionSpring(duration, bounce)` → exact `spring(ζ, ω²)`), fades, delays, stagger, handover ratio, blur radii, rises; `animation(reduceMotion:delay:)`, `easeOut`/`easeInOut` with SwiftUI's curves; Reduce Motion → 0.2 s ease-in-out (`rememberReduceMotion()` = Remove animations). Android divergence: the M3 `MotionScheme` (`OpenCodeMotionScheme`) is Material 3 Expressive's own (`MotionScheme.expressive()`), so Material components move like the rest of Android; Reduce Motion still turns every spec into the 0.2 s fade. Android divergence: transition blurs (SwiftUI `.blur(radius:)` in a transition, `MotionBlurReveal`, `MorphReveal`, the text focus sweep, caption words) are off through `motion/TransitionBlur.kt` (`transitionBlur`, `transitionBlurEffect`, `TransitionBlur.enabled`); the blur maths and their ported tests stay. Backdrop blurs are unchanged. Android-only `motion/PageTransitions.kt`: Material shared axis X (forward, backward, predictive back) and fade through, for page changes (Settings). Every value is a live `Tunable` (`MotionTunables`, iOS keys/labels/defaults/ranges, `core/.../tuning/`), read through `Motion.*` getters; springs and tweens follow Developer Mode slow motion (`AnimationTimeScale`: ω × s, durations ÷ s); `MotionTunables.register()` = iOS `Motion.register(in:)` Sync 440b77f0 (85945779): the role vocabulary's tunables under iOS's keys (`motion.springs.*`, `motion.fades.*`, `motion.geometry.*`, `motion.ambient.*`; `Motion.sectionTitles`), the 14 role constants (`Motion.press`…), `Motion.register` (drops overrides on retired keys, `isRetiredKey`); deprecated aliases keep today's numbers (`fadeStandard`/`fadeFast`/`reducedFade`, `drawerSlide`; Android-only call sites keep fixed `morphClose`, `settle`, `microPop`); `morphOpen`, `handoffDelay`, `foldHandoverRatio` deleted (dead on Android too). Design-system gallery lists the roles. |
| `OpenCode/Core/Motion/SpringSolver.swift` | 223 | `motion/SpringSolver.kt` | done | Same closed-form critically/under/over-damped solutions, epsilons, `retarget`, `step`, `parameters`; `(duration, bounce)` init is `SpringSolver.of(...)` or `SpringSolver(MotionSpring)` (JVM overload clash) Sync 440b77f0 (1385761f): doc only (iOS's “the one spring in the app” note; Android's KDoc already names the flights and gesture drivers). |
| `OpenCode/Core/Motion/PresenceGate.swift` | 367 | `motion/MotionPresence.kt` | done | No `PresenceGate`: native on purpose, `AnimatedVisibility` keeps a view mounted while it animates out. `MotionBlurReveal` maths and `Modifier.motionBlurReveal` (0.02/0.98 blur gate, rise, Reduce Motion opacity-only). Sync 440b77f0 (85945779): `MotionPresence` (`AnimatedVisibility` with the role's pose as fade + scale; Reduce Motion fades only), `MotionRollDirection`, `MotionTransition.micro/swap/roll` (AnimatedContent `ContentTransform`s; Reduce Motion → the reduced fade) and `MotionSwap` (AnimatedContent). `UIKitPresenceGate` n/a (no UIKit). |
| `OpenCode/Core/Motion/MotionDisplayLink.swift` | 90 | `motion/MotionFrameLoop.kt` | done | `motionFrameLoop {}` on Compose's frame clock (`withFrameNanos`, display rate up to 120 Hz), `dt = min(delta, maxFrames × frame) × scale`, first frame one frame long, `Continue`/`Stop`; `rememberFrameDurationSeconds()`. No explicit 80–120 Hz range request (Android picks the rate per window; Compose drives at the display rate). the scale is read every frame from `AnimationTimeScale.shared.scale` unless `timeScale` pins it (Developer Mode slow motion, `core/.../tuning/AnimationTimeScale.kt`) Android perf: `rememberReduceMotion()` reads one app-wide observer of the animator scale instead of registering one per caller (rows paid a settings-service round trip each as they scrolled in). |
| `OpenCode/Core/RollingNumber/RollingNumber.swift` | 925 | `ui/RollingNumberModel.kt`, `ui/RollingNumber.kt` | done | Model ported line for line (keys, parse with locale separators over Swift `Character`s = grapheme clusters, unwrap and settle with Swift's half-away-from-zero rounding, stagger, 240 Hz semi-implicit springs stepped on `AnimationTimeScale`, hold/rise/blur envelopes, alignment, settle). Rendering: Compose `Canvas` + `TextMeasurer`, tabular digits, edge fade mask, clipped to its line like iOS's Canvas (callers don't clip), baseline alignment, plain-text cross-fade fallback (Reduce Motion, RTL, not resumed, too narrow, unrollable; one line, wrapping only at accessibility font scales as iOS's `fixedSize`). Smear drawn from a 0.45 band radius (shader's cut-off) and arrival/exit blur above 0.3, as iOS. Presets `Native/Snappy/Gentle/Lively`; `RollingNumberTunables` (`ui/RollingNumberTunables.kt`: `numbers.*` keys, `numbers.presets` feels, `register`, `style()`) is the default `motion`. Arrival/exit blur drawn as the same vertical smear (iOS: isotropic per-glyph blur). `isRollingNumberSizingOnly` environment not needed (Compose measures without drawing) Android perf: glyph metrics are shared per style (`SharedRollingMetrics`, the 8 most recent styles, colour not in the key), so a row scrolling in doesn't measure its digits again. Sync 03adf07a (03adf07a): the roll runs on `MotionTunables.layoutSpring` (duration and bounce) with places `MotionTunables.staggerStep` (0.02) apart; the Snappy/Gentle/Lively feels and their presets are gone (`Native` stays for Catch Up's finish); `RollingNumberTunables` keeps only blur and edge fade.Formats and parses in `appFormattingLocale()` (`ui/AppFormattingLocale.kt`: English in the configuration's region, iOS `Locale.current` in an English-only bundle) |
| `OpenCode/Core/RollingNumber/RollingNumberShaders.metal` | 33 | `ui/RollingNumber.kt` (`drawRollingFrame`) | done | Same intent without a shader: per-band vertical Gaussian (`exp(-2.5t²)`, radius from reel speed) as a 9-tap additive offscreen composite (`BlendMode.Plus`) |
| `OpenCode/Core/Glass.swift` | 30 | `ui/OpenCodeButtons.kt`, `ui/OpenCodeShapes.kt` | done | `openCodePrimaryButton()` → `OpenCodePrimaryButton` (M3 filled, accent; red only for `Destructive`); `openCodeSecondaryButton()` → `OpenCodeSecondaryButton` (base surface with a hairline: the desktop's neutral button); shared 48 dp height for peers, 8 dp corners. `GlassGeometry.radius` → `OpenCodeShapeGeometry.radius` (iOS removed the unused `GlassShape.clamped` wrapper in 18771799; `OpenCodeShapeGeometry.clamped` went with it). `openCodeGlass(in:interactive:tint:)`: n/a, Liquid Glass maps to the desktop's flat floating surfaces (`OpenCodeSurfaces`: fill + hairline + small shadow), never blur; iOS 27 availability gates: n/a |
| `OpenCode/Core/TapTarget.swift` | 53 | `ui/TapTarget.kt` | done | `Modifier.openCodeTapTarget(minSize = 48.dp, accessibility = true)`: centred ≥48 dp rectangle, one merged TalkBack node (Android's 48 dp ≙ iOS 44 pt). `openCodeGlassCircle(_:tint:)` → `OpenCodeIconButton(style = Circle, size, tint)` (desktop neutral icon button: 10 dp rounded square, hairline; filled when tinted); `Standard` style for bare toolbar glyphs Sync ae029f85 (86ca6803, 6a173ff1): `openCodeTapTarget(lowered:)` and `openCodeAccessibilityTarget(lowered:)` aren't added: their only callers are the composer's pickers, which lower themselves with an offset pair (`targetDrop`, see composer.md) since Compose moves touch and semantics bounds together. |
| `OpenCode/Core/BackSwipe.swift` | 392 | `ui/BackSwipeRules.kt` | done | `edgeWidth` 28, hold 0.45 s, decision distance 8, handle reach 24, `decision(...)`, `yieldsToHold`, release rule `goesBack` (250/s flick, else halfway) for the in-app drawer pan. n/a: `BackPanGestureRecognizer`, `SystemBackSwipeGate`, scroller/control hit tests (system back and predictive back are Android's; nested scrolling already arbitrates). `selectionHandleClaims` for the drawer pan is `DrawerPanRegions.selectionHandleClaims` in `:feature:drawer`, fed by `DrawerPanExclusion.updateSelectionHandles` / `Modifier.drawerPanSelectionHandles` (`ui/DrawerSeams.kt`). `BackSwipeSpring` → `SpringSolver(Motion.drawerSlide)` + `motionFrameLoop` in the drawer feature Sync ae029f85 (665f985d): comment-only (`selectionHandleReach` now also names the reply selection's handles; Android `TranscriptReplySelection.HANDLE_REACH` is 24 dp). Sync 03adf07a (03adf07a): `BackSwipeRules.goesBack` commits by `MotionRelease.shouldCommit` (the shared release rule). n/a: iOS's MotionDisplayLink stepping of the release (the session's page stack follows system predictive back). |
| `OpenCode/Core/Motion/AmbientClock.swift` | 96 | `motion/AmbientClock.kt` | done | `AmbientClock` (breath/sweep periods floored at 0.2 s, `phase` wraps negatives, `breath` = 0.5 − 0.5 cos, `sweep(fraction)`, `Sample.settled`), `AmbientRate` (1/60, 1/30). `AmbientTimelineView` → `AmbientTimeline` composable: a `withFrameNanos` loop at the rate's interval on `AnimationTimeScale`'s media time, paused (settled sample) under Reduce Motion, when inactive, or when the lifecycle is below RESUMED (iOS: scene not active). Sync 440b77f0 (85945779): new. |
| `OpenCode/Core/Motion/MotionPress.swift` | 57 | `motion/MotionPress.kt` | done | `MotionPress` (`reducedOpacity` 0.72; scale `pressScale` or opacity under Reduce Motion) and `Modifier.motionPressEffect(isPressed)` / `Modifier.motionPress(interactionSource)` animated with `Motion.press` (enter while pressed, exit on release). `MotionPressButtonStyle` → `motionPress` on a clickable's interaction source; the UIKit `apply(isPressed:to:)` is n/a (no UIKit). `OpenCodePrimaryButton`, `OpenCodeSecondaryButton` and `OpenCodeIconButton` apply `motionPress` over their ripple, as iOS's glass button styles press. Sync 440b77f0 (85945779): new. |
| `OpenCode/Core/Motion/MotionReduce.swift` | 115 | `motion/MotionReduce.kt` | done | `MotionReduce` (offset Dp/Float/DpOffset, scale, blur, rotation → neutral under Reduce Motion), `MotionPose` (identity, microIn, swap, roll, entering/leaving, reduced, scaled(by)) and `Modifier.motionPose` (blur via `transitionBlur`, graphicsLayer scale/offset/alpha). Sync 440b77f0 (85945779): new. |
| `OpenCode/Core/Motion/MotionRelease.swift` | 110 | `motion/MotionRelease.kt` | done | `MotionRelease` (commit at 0.5, flick ± `flickVelocity` decides, `relativeVelocity`), `MotionRubberBand` (stretch/excess exact inverse, slope, band/unband over a closed range). Same maths, in Double. Android divergence: no `MotionFlightGeometry`, as nothing on Android flies one frame into another. Sync 440b77f0 (85945779): new. |
| `OpenCode/Core/Motion/MotionRole.swift` | 333 | `motion/MotionRole.kt`, `motion/MotionConstants.kt` | done | `MotionPhase`, `MotionCurve` (spring/easeOut/easeInOut → `MotionSpring.spec()` or a `tween` with SwiftUI's curves) and `MotionRole`'s 14 cases (Kotlin enum `Press`…`Reduced`; `Motion.press`… are the lower-case constants) with `curve(phase, reduceMotion)`, `animation(phase, reduceMotion, delaySeconds)` (delay dropped under Reduce Motion), `staggered`, `staggerDelay`, `spring`, `initialVelocity` (Compose passes velocity to `animateTo`, so iOS's `animation(_:initialVelocity:)` becomes a velocity filter), and the `SpringSolver(role:…)` factory / `retarget(role:)` extension (velocity 0 under Reduce Motion). UIKit and Core Animation variants are n/a (one Compose stack). Sync 440b77f0 (85945779): new. |
| `OpenCode/Core/Motion/RenderServerLoop.swift` | 113 | — | n/a: a Core Animation loop re-added after suspension or cell reuse; Compose's infinite transitions run on the frame clock and restart with a reused composition, so the drawer ring and Server Flow sweep can't be dropped | Sync ae029f85 (a599a6a4, 76dd31fd). Sync 440b77f0 (85945779): the new `breath`/`sweep` factories on the shared ambient periods stay n/a with the loop; Compose ambient loops read `AmbientClock` through `AmbientTimeline`. |

## Design-system API (for area agents)

- Theme: wrap each window's root in `OpenCodeAppTheme { }` (reads the persisted choice); previews use `OpenCodeTheme(theme, appearance) { }`. Colours: `OpenCodeTheme.colors.<iOS name>` (e.g. `groupedCard`, `secondaryText`, `bodyText(0.92)`, `syntaxKeyword`); Material roles: `MaterialTheme.colorScheme` (mapping table in `OpenCodeColors.kt`).
- Type: `MaterialTheme.typography.*` (role mapping in `OpenCodeTypography.kt`) or the iOS names in `OpenCodeTextStyles` (`footnote`, `subheadline`, …), Inter at desktop sizes; code in `OpenCodeFonts.monospace` (OpenMono), the app's one mono family.
- Shapes and spacing: `OpenCodeShape.control` for controls, `capsule/circle` for true capsules, `card/panel/sheet`, `OpenCodeRadius.*`, `OpenCodeSpacing.*`.
- Surfaces: `OpenCodeSurfaces.color/border/dialogShape/popoverShape/raisedShadow/popoverShadow`, `Modifier.openCodeHairline(shape)`, `OpenCodeAlertDialog` instead of `AlertDialog`; Material `Button`s take `shape = OpenCodeShape.control`. Failures outside a form or dialog: `OpenCodeNotice` in an `OpenCodeNoticePresence`.
- Motion: `Motion.<name>.spec()` / `.animation(reduceMotion)`, `Motion.easeOut(Motion.fadeStandard, …)`, `rememberReduceMotion()`, `SpringSolver` + `motionFrameLoop {}` for custom drivers, `MotionPresence`, `Modifier.motionBlurReveal`.
- Title bars: `OpenCodeTopBar(title = { Text(...) }, navigation = { OpenCodeTopBarBackButton(onBack) }, actions = { ... }, centerTitle, scrollBehavior)` or `OpenCodeLargeTopBar("Title", rememberOpenCodeLargeTopBarScrollBehavior(listState))` (lists also take `overscrollEffect = rememberOverscrollIfContentScrolls(listState)`) instead of Material `TopAppBar`s; put the behaviour's `nestedScrollConnection` on the `Scaffold`.
- Controls: `OpenCodePrimaryButton` / `OpenCodeSecondaryButton` (`role = Destructive` for red), `OpenCodeIconButton(SFSymbol.X, "Label", onClick)`, `Modifier.openCodeTapTarget()` after `clickable`; `OpenCodeSwitch(checked, onCheckedChange)` instead of Material's `Switch` (pass `null` when the row is the toggleable); sheets take `dragHandle = { OpenCodeSheetHandle() }` and `shape = OpenCodeShape.sheetTop` (both the default in `OpenCodeBottomSheet`); `OpenCodeTextField(value, onValueChange, label = "…", placeholder = "…")` and `OpenCodeSearchField` instead of Material's `OutlinedTextField` / `TextField`.
- Icons: `OpenCodeIcon(SFSymbol.ArrowUpCircleFill, contentDescription)` or `OpenCodeIcon("arrow.up", …)` / `sfSymbol(name)` for names held as strings. New icon: add a line to `scripts/material-symbols.txt`, run `python3 scripts/fetch-material-symbols.py`, add the `SFSymbol` entry and a row below.
- TalkBack focus (`ui/AccessibilityFocus.kt`, moved down from `:feature:composer`'s `AttachmentMenu.kt`; one helper where VoiceOver moves by itself): `Modifier.accessibilityFocusRequest(request)` moves TalkBack's focus onto the element once per `AccessibilityFocusRequest` (after a window on top hands focus back); a request is spent by its move, so a recomposition, a remount or a session moved into Catch Up's stack never replays it (QA W-05); `rememberAccessibilityFocusRequest(event)` makes a new one for each new non-null `event`; `LocalAccessibilityFocusMoves` false (Catch Up's inert preview card) turns every move off; a move refused because the element isn't in the accessibility tree yet (fading in) tries again each frame for up to 1 s (QA AF-02: Catch Up's Leave was always refused and only TalkBack's own pick landed there); `Modifier.accessibilityFocusTarget(target)` + `AccessibilityFocusTarget.focus()` move it when the caller decides. Both mark their semantics node (ahead of any `clearAndSetSemantics`), find it in the view's tree and perform `ACTION_ACCESSIBILITY_FOCUS` through the view's accessibility node provider. Compose has no public accessibility-focus API and no public node → id mapping (`requireSemanticsInfo` is internal), so the tree comes from `RootForTest` in this one file.
- Gallery: `DesignSystemGallery()` (`ui/DesignSystemGallery.kt`).
- Brand artwork (`ui/ProviderIcon.kt`, moved down from Session so Settings and Composer can draw it): iOS `ProviderIcons.xcassets` SVGs ship as `core/src/main/assets/provider-icons/<id>.svg` (`provider-*`, 98) and `mcp-icons/<name>.svg` (`mcp-*`, 14), drawn with Coil SVG. `ProviderIcon(providerID, modifier, canonical, tint)` + `ProviderIcon.assetName/available` (iOS `ProviderIcon`, `Session/SessionView.swift`: own → canonical → Console upstream → `provider-synthetic`, template-tinted, TalkBack hidden); `BrandAssets.exists/path(asset)` answers iOS `UIImage(named:) != nil` by iOS asset name; `TemplateAssetImage(asset, modifier, tint)` draws one as a template image.

## SF Symbol → Material Symbol

Every SF Symbol name used in `OpenCode/`, `OpenCodeShare/`, `OpenCodeWidgets/` and
`OpenCodeNotifications/` (string literals checked against the system SF Symbols list, minus
literals that aren't symbols). `(fill)` is the filled variant. Weak matches: `star.slash` →
`star_half` (Material has no "unfavourite" star), `capsule` → `toggle_off`, `macmini`/`macstudio`
→ `desktop_mac`, `tortoise.fill` → `slow_motion_video`, `arrow.triangle.pull` → `merge_type`.
Android divergence: `chevron.backward` (every Back button) is the Android back arrow `arrow_back`,
and `ellipsis` (every overflow menu) is the vertical `more_vert`, as on other Android apps.

| SF Symbol | Material Symbol (Rounded) | Drawable | Kotlin | iOS files |
| --- | --- | --- | --- | --- |
| `antenna.radiowaves.left.and.right` | `cell_tower` | `ms_cell_tower` | `SFSymbol.AntennaRadiowavesLeftAndRight` | WhatsNewList |
| `antenna.radiowaves.left.and.right.slash` | `signal_disconnected` | `ms_signal_disconnected` | `SFSymbol.AntennaRadiowavesLeftAndRightSlash` | TuningPanelView |
| `app` | `square` | `ms_square` | `SFSymbol.App` | AppLog, WhatsNewList |
| `app.badge` | `app_badging` | `ms_app_badging` | `SFSymbol.AppBadge` | WhatsNewList |
| `app.dashed` | `select` | `ms_select` | `SFSymbol.AppDashed` | ExperimentalFeatures |
| `apple.terminal` | `terminal` | `ms_terminal` | `SFSymbol.AppleTerminal` | WhatsNewList |
| `archivebox` | `archive` | `ms_archive` | `SFSymbol.Archivebox` | InboxSidebarList |
| `arrow.clockwise` | `refresh` | `ms_refresh` | `SFSymbol.ArrowClockwise` | FileAttachmentViews, RunError, ServerSwitcher, TranscriptEntryView, TranscriptUserMessage, WhatsNewList |
| `arrow.counterclockwise` | `replay` | `ms_replay` | `SFSymbol.ArrowCounterclockwise` | OnboardingPlaygroundControls, TuningPanelView |
| `arrow.down` | `arrow_downward` | `ms_arrow_downward` | `SFSymbol.ArrowDown` | ComposerViews, ShellOutputView, WhatsNewList |
| `arrow.down.circle` | `arrow_circle_down` | `ms_arrow_circle_down` | `SFSymbol.ArrowDownCircle` | AgentImageGallery |
| `arrow.down.circle.fill` | `arrow_circle_down` (fill) | `ms_arrow_circle_down_fill` | `SFSymbol.ArrowDownCircleFill` | VoiceProviderViews, VoiceProviderPage |
| `arrow.down.right.and.arrow.up.left` | `close_fullscreen` | `ms_close_fullscreen` | `SFSymbol.ArrowDownRightAndArrowUpLeft` | TuningPanelView |
| `arrow.left.and.right` | `arrow_range` | `ms_arrow_range` | `SFSymbol.ArrowLeftAndRight` | WhatsNewList (Wider Composer While Typing) |
| `arrow.left.and.right.text.vertical` | `arrow_range` | `ms_arrow_range` | `SFSymbol.ArrowLeftAndRightTextVertical` | AgentFileViewer, PastedTextViews |
| `arrow.left.arrow.right` | `swap_horiz` | `ms_swap_horiz` | `SFSymbol.ArrowLeftArrowRight` | ConnectionCard |
| `arrow.right` | `arrow_forward` | `ms_arrow_forward` | `SFSymbol.ArrowRight` | AppView |
| `arrow.triangle.2.circlepath` | `sync` | `ms_sync` | `SFSymbol.ArrowTriangle2Circlepath` | AttachmentCameraView |
| `arrow.triangle.branch` | `call_split` | `ms_call_split` | `SFSymbol.ArrowTriangleBranch` | SessionSheetView, SessionView+Header, WhatsNewList |
| `arrow.triangle.swap` | `swap_horiz` | `ms_swap_horiz` | `SFSymbol.ArrowTriangleSwap` | WhatsNewList (Switch Sessions by Voice) |
| `arrow.triangle.merge` | `merge` | `ms_merge` | `SFSymbol.ArrowTriangleMerge` | LinkPreviewViews |
| `arrow.triangle.pull` | `merge_type` | `ms_merge_type` | `SFSymbol.ArrowTrianglePull` | LinkPreviewViews, MarkdownBlockView |
| `arrow.trianglehead.2.clockwise` | `autorenew` | `ms_autorenew` | `SFSymbol.ArrowTrianglehead2Clockwise` | ConnectionCard, OnboardingPlaygroundView |
| `arrow.turn.down.right` | `subdirectory_arrow_right` | `ms_subdirectory_arrow_right` | `SFSymbol.ArrowTurnDownRight` | SessionLinkChips, WhatsNewView |
| `arrow.turn.up.right` | `turn_right` | `ms_turn_right` | `SFSymbol.ArrowTurnUpRight` | AppShortcuts, OpenCodeClient+Models, WhatsNewList |
| `arrow.up` | `arrow_upward` | `ms_arrow_upward` | `SFSymbol.ArrowUp` | BrowserView, ComposerViews, DictationViews, OnboardingPlaygroundView, QueuedMessagesSheet, SessionView+ComposerControls, ShareView |
| `arrow.up.circle` | `arrow_circle_up` | `ms_arrow_circle_up` | `SFSymbol.ArrowUpCircle` | ServerTitleView |
| `arrow.up.circle.fill` | `arrow_circle_up` (fill) | `ms_arrow_circle_up_fill` | `SFSymbol.ArrowUpCircleFill` | TraceTriggers |
| `arrow.up.forward.app` | `open_in_new` | `ms_open_in_new` | `SFSymbol.ArrowUpForwardApp` | LinkMenu, ShareView, WhatsNewList |
| `arrow.up.right` | `arrow_outward` | `ms_arrow_outward` | `SFSymbol.ArrowUpRight` | MarkdownBlockView |
| `arrow.up.right.square` | `open_in_new` | `ms_open_in_new` | `SFSymbol.ArrowUpRightSquare` | LinkMenu |
| `arrow.uturn.backward` | `undo` | `ms_undo` | `SFSymbol.ArrowUturnBackward` | RunErrorRow, SessionView+Header, TranscriptUserMessage, TuningPanelView, WhatsNewList |
| `arrow.uturn.forward` | `redo` | `ms_redo` | `SFSymbol.ArrowUturnForward` | RunErrorRow |
| `at` | `alternate_email` | `ms_alternate_email` | `SFSymbol.At` | ExperimentalFeatures, VoiceTranscript, WhatsNewList |
| `bell` | `notifications` | `ms_notifications` | `SFSymbol.Bell` | HomeView+Drawer, SessionSheetView, SessionView+Header |
| `bell.badge` | `notifications_unread` | `ms_notifications_unread` | `SFSymbol.BellBadge` | OnboardingPlaygroundView, WhatsNewList |
| `bell.badge.fill` | `notifications_unread` (fill) | `ms_notifications_unread_fill` | `SFSymbol.BellBadgeFill` | SettingsView |
| `bell.slash` | `notifications_off` | `ms_notifications_off` | `SFSymbol.BellSlash` | HomeView+Drawer, SessionView+Header, WhatsNewList |
| `bell.slash.fill` | `notifications_off` (fill) | `ms_notifications_off_fill` | `SFSymbol.BellSlashFill` | SessionSheetView |
| `bolt` | `bolt` | `ms_bolt` | `SFSymbol.Bolt` | WhatsNewList |
| `bolt.badge.automatic.fill` | `flash_auto` (fill) | `ms_flash_auto_fill` | `SFSymbol.BoltBadgeAutomaticFill` | AttachmentCameraLenses |
| `bolt.fill` | `bolt` (fill) | `ms_bolt_fill` | `SFSymbol.BoltFill` | AttachmentCameraLenses |
| `bolt.slash.fill` | `flash_off` (fill) | `ms_flash_off_fill` | `SFSymbol.BoltSlashFill` | AttachmentCameraLenses |
| `book.closed` | `book` | `ms_book` | `SFSymbol.BookClosed` | LinkPreviewViews, ServerDetailsTrays |
| `bubble.left` | `chat_bubble` | `ms_chat_bubble` | `SFSymbol.BubbleLeft` | AppShortcuts, OpenCodeControls, QuickActions, SystemEntities |
| `bubble.left.and.bubble.right` | `forum` | `ms_forum` | `SFSymbol.BubbleLeftAndBubbleRight` | MarkLoadingView, TranscriptListRows, WhatsNewList |
| `bubble.left.and.text.bubble.right` | `question_answer` | `ms_question_answer` | `SFSymbol.BubbleLeftAndTextBubbleRight` | LinkMenu |
| `bubble.left.fill` | `chat_bubble` (fill) | `ms_chat_bubble_fill` | `SFSymbol.BubbleLeftFill` | SessionLinkChips |
| `button.programmable` | `adjust` | `ms_adjust` | `SFSymbol.ButtonProgrammable` | AppShortcuts, ExperimentalFeatures |
| `calendar` | `calendar_today` | `ms_calendar_today` | `SFSymbol.Calendar` | WhatsNewList |
| `camera` | `photo_camera` | `ms_photo_camera` | `SFSymbol.Camera` | AttachmentCameraView, AttachmentPicker, OnboardingPlaygroundView, ServerConnectView |
| `camera.fill` | `photo_camera` (fill) | `ms_photo_camera_fill` | `SFSymbol.CameraFill` | PairingScanner |
| `camera.metering.unknown` | `center_focus_weak` | `ms_center_focus_weak` | `SFSymbol.CameraMeteringUnknown` | PairingScanner |
| `camera.shutter.button` | `photo_camera` | `ms_photo_camera` | `SFSymbol.CameraShutterButton` | WhatsNewList |
| `camera.viewfinder` | `center_focus_strong` | `ms_center_focus_strong` | `SFSymbol.CameraViewfinder` | AttachmentMenu |
| `capsule` | `toggle_off` | `ms_toggle_off` | `SFSymbol.Capsule` | BackgroundWorkPanel, GlassMorphTrace, WhatsNewList |
| `captions.bubble` | `closed_caption` | `ms_closed_caption` | `SFSymbol.CaptionsBubble` | FloatingCall |
| `captions.bubble.fill` | `closed_caption` (fill) | `ms_closed_caption_fill` | `SFSymbol.CaptionsBubbleFill` | FloatingCall |
| `character.cursor.ibeam` | `text_select_start` | `ms_text_select_start` | `SFSymbol.CharacterCursorIbeam` | WhatsNew/Entries |
| `checklist` | `checklist` | `ms_checklist` | `SFSymbol.Checklist` | WhatsNewList |
| `checkmark` | `check` | `ms_check` | `SFSymbol.Checkmark` | AppView, DraftProjects, ModelListContent, ModelPickerOverlays, ModelPickerSheet, OnboardingPlaygroundView, PastedTextViews, SessionView+ModelPicker, SettingsServers, SettingsView, ShareView, ShellOutputView, TraceTriggers, TranscriptEntryView, TuningPanelView, WhatsNewView |
| `checkmark.circle` | `check_circle` | `ms_check_circle` | `SFSymbol.CheckmarkCircle` | LinkPreviewViews, OnboardingPlaygroundControls, WhatsNewList |
| `checkmark.circle.fill` | `check_circle` (fill) | `ms_check_circle_fill` | `SFSymbol.CheckmarkCircleFill` | AppearanceSettingsView, RequestDockView, SettingsView, ShellOutputView, TraceTriggers |
| `checkmark.seal` | `verified` | `ms_verified` | `SFSymbol.CheckmarkSeal` | WhatsNew/Entries |
| `checkmark.seal.fill` | `verified` (fill) | `ms_verified_fill` | `SFSymbol.CheckmarkSealFill` | WhatsNew/Entries |
| `checkmark.square.fill` | `check_box` (fill) | `ms_check_box_fill` | `SFSymbol.CheckmarkSquareFill` | MarkdownBlockView, RequestDockView |
| `chevron.backward` | `arrow_back` | `ms_arrow_back` | `SFSymbol.ChevronBackward` | AppView, BrowserView, CatchUpChrome, OnboardingPlaygroundView, SessionView, SettingsView, WhatsNewView |
| `chevron.compact.left` | `chevron_left` | `ms_chevron_left` | `SFSymbol.ChevronCompactLeft` | TuningPanelView |
| `chevron.compact.right` | `chevron_right` | `ms_chevron_right` | `SFSymbol.ChevronCompactRight` | TuningPanelView |
| `chevron.down` | `keyboard_arrow_down` | `ms_keyboard_arrow_down` | `SFSymbol.ChevronDown` | AgentPicker, FloatingCall, LinkPreviewViews, ModelPickerOverlays, OnboardingPlaygroundControls, RequestDockView, ServerSwitcher, SessionView+ComposerControls, ShareView, TranscriptUserMessage |
| `chevron.left` | `chevron_left` | `ms_chevron_left` | `SFSymbol.ChevronLeft` | AttachmentMenu, BrowserView, DictationViews, RequestDockView |
| `chevron.left.forwardslash.chevron.right` | `code` | `ms_code` | `SFSymbol.ChevronLeftForwardslashChevronRight` | AgentFileViewer, AttachmentFileType, MermaidDiagramView |
| `chevron.right` | `chevron_right` | `ms_chevron_right` | `SFSymbol.ChevronRight` | BackgroundWorkPanel, BrowserView, FileAttachmentViews, ServerDetailsView, ServerTitleView, SessionSheetView, SessionView+ComposerControls, SessionView+Header, SettingsView, TranscriptEntryView, TuningPanelView |
| `chevron.up` | `keyboard_arrow_up` | `ms_keyboard_arrow_up` | `SFSymbol.ChevronUp` | DictationViews, LinkPreviewViews, OnboardingPlaygroundView, TranscriptUserMessage |
| `chevron.up.chevron.down` | `unfold_more` | `ms_unfold_more` | `SFSymbol.ChevronUpChevronDown` | AttachmentMenu, ModelPickerOverlays, ModelPickerSheet, SessionView+ModelPicker, SettingsRows, SettingsView, TuningPanelView |
| `arrowtriangle.down.fill` | `arrow_drop_down` | `ms_arrow_drop_down` | `SFSymbol.ArrowtriangleDownFill` | SettingsRows (Android: Settings menu rows end in Material's dropdown arrow instead of iOS's up-and-down chevron) |
| `circle` | `radio_button_unchecked` | `ms_radio_button_unchecked` | `SFSymbol.Circle` | ProjectIcon, RequestDockView |
| `circle.dotted.circle` | `motion_photos_on` | `ms_motion_photos_on` | `SFSymbol.CircleDottedCircle` | WhatsNewList |
| `circle.fill` | `circle` (fill) | `ms_circle_fill` | `SFSymbol.CircleFill` | DrawerListSessionCell, WhatsNewView |
| `circle.hexagongrid` | `hive` | `ms_hive` | `SFSymbol.CircleHexagongrid` | WhatsNew/Entries |
| `circle.hexagongrid.fill` | `hive` (fill) | `ms_hive_fill` | `SFSymbol.CircleHexagongridFill` | ExperimentalFeatures, ExperimentalSettingsView |
| `clipboard` | `content_paste` | `ms_content_paste` | `SFSymbol.Clipboard` | ServerConnectView |
| `clock` | `schedule` | `ms_schedule` | `SFSymbol.Clock` | WhatsNewList |
| `clock.arrow.circlepath` | `history` | `ms_history` | `SFSymbol.ClockArrowCirclepath` | ExperimentalFeatures |
| `clock.badge.exclamationmark` | `history_toggle_off` | `ms_history_toggle_off` | `SFSymbol.ClockBadgeExclamationmark` | ShellOutputView |
| `cpu` | `memory` | `ms_memory` | `SFSymbol.Cpu` | ExperimentalSettingsView, MetricKitClient, ModelListContent, ModelPickerSheet, RunError, SessionView+ModelPicker, SettingsView, WhatsNewList |
| `desktopcomputer` | `desktop_windows` | `ms_desktop_windows` | `SFSymbol.Desktopcomputer` | ShareView, WhatsNewList |
| `diamond` | `diamond` | `ms_diamond` | `SFSymbol.Diamond` | TranscriptListRows |
| `die.face.5` | `casino` | `ms_casino` | `SFSymbol.DieFace5` | TuningPanelView |
| `doc` | `draft` | `ms_draft` | `SFSymbol.Doc` | AgentFileReference, AttachmentFileType |
| `doc.on.clipboard` | `content_paste` | `ms_content_paste` | `SFSymbol.DocOnClipboard` | AppView, AttachmentPicker, PairingScanner, WhatsNewList |
| `doc.on.doc` | `content_copy` | `ms_content_copy` | `SFSymbol.DocOnDoc` | LinkMenu, LinkPreviewViews, MermaidDiagramView, PastedTextViews, QueuedMessagesSheet, TraceTriggers, TuningPanelView |
| `doc.plaintext` | `article` | `ms_article` | `SFSymbol.DocPlaintext` | PastedTextViews, QueuedMessagesSheet, TranscriptUserMessage |
| `doc.richtext` | `description` | `ms_description` | `SFSymbol.DocRichtext` | AgentFileReference, AgentFileViewer, AttachmentFileType |
| `doc.text` | `description` | `ms_description` | `SFSymbol.DocText` | AttachmentFileType |
| `doc.text.magnifyingglass` | `plagiarism` | `ms_plagiarism` | `SFSymbol.DocTextMagnifyingglass` | TranscriptEntryView |
| `doc.zipper` | `folder_zip` | `ms_folder_zip` | `SFSymbol.DocZipper` | AttachmentFileType |
| `dot.radiowaves.left.and.right` | `sensors` | `ms_sensors` | `SFSymbol.DotRadiowavesLeftAndRight` | SettingsView |
| `ellipsis` | `more_vert` | `ms_more_vert` | `SFSymbol.Ellipsis` | OnboardingPlaygroundView, SessionSwitcher, SessionView+Header, SettingsView, TuningPanelView, VoiceCallLiveActivity, VoiceCallLogView |
| `ellipsis.circle` | `pending` | `ms_pending` | `SFSymbol.EllipsisCircle` | SettingsServers |
| `ellipsis.circle.fill` | `pending` (fill) | `ms_pending_fill` | `SFSymbol.EllipsisCircleFill` | SessionRunLiveActivity |
| `envelope.badge` | `mark_email_unread` | `ms_mark_email_unread` | `SFSymbol.EnvelopeBadge` | HomeView+Drawer, SessionView+Header |
| `envelope.badge.fill` | `mark_email_unread` (fill) | `ms_mark_email_unread_fill` | `SFSymbol.EnvelopeBadgeFill` | SessionSheetView |
| `envelope.open` | `drafts` | `ms_drafts` | `SFSymbol.EnvelopeOpen` | DrawerListSupportCells, HomeView+Drawer, SessionSheetView, SessionView+Header |
| `exclamationmark` | `priority_high` | `ms_priority_high` | `SFSymbol.Exclamationmark` | VoiceProviderViews |
| `exclamationmark.bubble` | `feedback` | `ms_feedback` | `SFSymbol.ExclamationmarkBubble` | SessionView+Header, WhatsNewList |
| `exclamationmark.circle` | `error` | `ms_error` | `SFSymbol.ExclamationmarkCircle` | ServerFlowTitle (ServerMenuStatus) |
| `exclamationmark.bubble.fill` | `feedback` (fill) | `ms_feedback_fill` | `SFSymbol.ExclamationmarkBubbleFill` | SessionRunLiveActivity |
| `exclamationmark.circle.fill` | `error` (fill) | `ms_error_fill` | `SFSymbol.ExclamationmarkCircleFill` | RequestDockView, ServerDetailsView, SettingsView, TranscriptEntryView, TranscriptUserMessage |
| `exclamationmark.triangle` | `warning` | `ms_warning` | `SFSymbol.ExclamationmarkTriangle` | AgentFileViewer, DraftProjects, DrawerListSupportCells, FileAttachmentViews, ModelListContent, ModelPickerSheet, ModelVisibilitySettings, OnboardingPlaygroundControls, ServerDetailsView, SessionView, SessionView+ModelPicker, SettingsServers |
| `exclamationmark.triangle.fill` | `warning` (fill) | `ms_warning_fill` | `SFSymbol.ExclamationmarkTriangleFill` | AppView, SessionView+Composer, TraceTriggers, VoiceView |
| `eye` | `visibility` | `ms_visibility` | `SFSymbol.Eye` | AppView, SettingsView, TuningPanelView |
| `eye.slash` | `visibility_off` | `ms_visibility_off` | `SFSymbol.EyeSlash` | AppView, ExperimentalFeatures, SettingsView, TuningPanelView, WhatsNewView |
| `film` | `movie` | `ms_movie` | `SFSymbol.Film` | AgentFileReference, AttachmentFileType |
| `flask` | `science` | `ms_science` | `SFSymbol.Flask` | WhatsNew/Entries |
| `flask.fill` | `science` (fill) | `ms_science_fill` | `SFSymbol.FlaskFill` | ExperimentalSettingsView (`ExperimentalSettingsRow`) |
| `folder` | `folder` | `ms_folder` | `SFSymbol.Folder` | AttachmentPicker, DraftProjects, SystemEntities, WhatsNewList |
| `folder.badge.plus` | `create_new_folder` | `ms_create_new_folder` | `SFSymbol.FolderBadgePlus` | DraftProjects |
| `folder.fill` | `folder` (fill) | `ms_folder_fill` | `SFSymbol.FolderFill` | DraftProjects |
| `function` | `function` | `ms_function` | `SFSymbol.Function` | LiveProtocol, RealtimeProtocol, SyntaxHighlighter, VoiceDelegator, WhatsNewList |
| `gearshape` | `settings` | `ms_settings` | `SFSymbol.Gearshape` | ServerSwitcher, WhatsNewList |
| `gearshape.2.fill` | `manufacturing` (fill) | `ms_manufacturing_fill` | `SFSymbol.Gearshape2Fill` | SettingsView |
| `gearshape.fill` | `settings` (fill) | `ms_settings_fill` | `SFSymbol.GearshapeFill` | HomeView+Drawer |
| `gift` | `redeem` | `ms_redeem` | `SFSymbol.Gift` | WhatsNewList, WhatsNewView |
| `globe` | `language` | `ms_language` | `SFSymbol.Globe` | InlineLinkIcons, LinkMenu, LinkPreviewViews |
| `hammer.fill` | `construction` (fill) | `ms_construction_fill` | `SFSymbol.HammerFill` | SettingsView (Developer row) |
| `hand.draw` | `draw` | `ms_draw` | `SFSymbol.HandDraw` | WhatsNewList |
| `hand.draw.fill` | `draw` (fill) | `ms_draw_fill` | `SFSymbol.HandDrawFill` | TraceTriggers, WhatsNewList |
| `hand.point.up.left` | `pan_tool_alt` | `ms_pan_tool_alt` | `SFSymbol.HandPointUpLeft` | WhatsNewList |
| `hand.point.up.left.fill` | `pan_tool_alt` (fill) | `ms_pan_tool_alt_fill` | `SFSymbol.HandPointUpLeftFill` | TraceTriggers |
| `hand.raised.fill` | `back_hand` (fill) | `ms_back_hand_fill` | `SFSymbol.HandRaisedFill` | RunErrorRow, ShellOutputView, VoiceCallCenterButton, WhatsNewList |
| `hand.tap` | `touch_app` | `ms_touch_app` | `SFSymbol.HandTap` | WhatsNewList |
| `hand.tap.fill` | `touch_app` (fill) | `ms_touch_app_fill` | `SFSymbol.HandTapFill` | TraceTriggers |
| `hand.wave` | `waving_hand` | `ms_waving_hand` | `SFSymbol.HandWave` | WelcomePreviewExperiment, WhatsNew/Entries |
| `hourglass` | `hourglass` | `ms_hourglass` | `SFSymbol.Hourglass` | WhatsNewList |
| `info.circle` | `info` | `ms_info` | `SFSymbol.InfoCircle` | ServerTitleView, SessionView+Header, SettingsView |
| `key` | `key` | `ms_key` | `SFSymbol.Key` | AgentFileReference, GeminiLiveClient, LinkPreview, ServerTitleView, TeamFeedback, VoiceDelegatorWorkspace, WhatsNewList |
| `key.fill` | `key` (fill) | `ms_key_fill` | `SFSymbol.KeyFill` | ServerConnectionPill, DrawerServerNoteRow |
| `keyboard` | `keyboard` | `ms_keyboard` | `SFSymbol.Keyboard` | MotionAudit, SettingsServers, TranscriptListViewController+ScrollFollow, TranscriptListViewController+Tracing, TranscriptScrollTrace, WhatsNewList |
| `keyboard.chevron.compact.down` | `keyboard_hide` | `ms_keyboard_hide` | `SFSymbol.KeyboardChevronCompactDown` | WhatsNewList |
| `keyboard.fill` | `keyboard` (fill) | `ms_keyboard_fill` | `SFSymbol.KeyboardFill` | SettingsView |
| `laptopcomputer` | `laptop_mac` | `ms_laptop_mac` | `SFSymbol.Laptopcomputer` | ServerTitleView, WhatsNewList |
| `laptopcomputer.and.iphone` | `devices` | `ms_devices` | `SFSymbol.LaptopcomputerAndIphone` | WhatsNewList |
| `line.3.horizontal` | `menu` | `ms_menu` | `SFSymbol.Line3Horizontal` | DrawerButton, OnboardingPlaygroundView, SessionSwitcher |
| `line.horizontal.star.fill.line.horizontal` | `new_releases` | `ms_new_releases` | `SFSymbol.LineHorizontalStarFillLineHorizontal` | WhatsNewList |
| `link` | `link` | `ms_link` | `SFSymbol.Link` | ExperimentalFeatures, InlineLinkIcons, ProblemReport, ShareView, TranscriptDragSource, TuningPanelView, WhatsNewList |
| `list.bullet` | `format_list_bulleted` | `ms_format_list_bulleted` | `SFSymbol.ListBullet` | WhatsNewList |
| `list.bullet.rectangle` | `list_alt` | `ms_list_alt` | `SFSymbol.ListBulletRectangle` | WhatsNewList |
| `location` | `near_me` | `ms_near_me` | `SFSymbol.Location` | AttachmentPicker |
| `location.fill` | `near_me` (fill) | `ms_near_me_fill` | `SFSymbol.LocationFill` | SharedLocation |
| `lock.fill` | `lock` (fill) | `ms_lock_fill` | `SFSymbol.LockFill` | AppView, BrowserView, DictationViews |
| `lock.open.fill` | `lock_open` (fill) | `ms_lock_open_fill` | `SFSymbol.LockOpenFill` | DictationViews |
| `lock.slash` | `no_encryption` | `ms_no_encryption` | `SFSymbol.LockSlash` | ConnectionCard |
| `macmini` | `desktop_mac` | `ms_desktop_mac` | `SFSymbol.Macmini` | ServerTitleView |
| `macstudio` | `desktop_mac` | `ms_desktop_mac` | `SFSymbol.Macstudio` | ServerTitleView |
| `macwindow.badge.plus` | `add_to_queue` | `ms_add_to_queue` | `SFSymbol.MacwindowBadgePlus` | HomeView+Drawer |
| `magnifyingglass` | `search` | `ms_search` | `SFSymbol.Magnifyingglass` | DraftProjects, DrawerSearchPill, ModelListContent, ShareView |
| `mappin.circle.fill` | `location_on` (fill) | `ms_location_on_fill` | `SFSymbol.MappinCircleFill` | SharedLocation |
| `mic` | `mic` | `ms_mic` | `SFSymbol.Mic` | ComposerViews, SessionSwitcher, WhatsNewList |
| `mic.fill` | `mic` (fill) | `ms_mic_fill` | `SFSymbol.MicFill` | DictationLiveActivity, DictationViews, FloatingCall, VoiceCallLiveActivity, VoiceView, WhatsNewList |
| `mic.slash` | `mic_off` | `ms_mic_off` | `SFSymbol.MicSlash` | — (iOS's last use went with the Call Tray, ea61212b) |
| `mic.slash.fill` | `mic_off` (fill) | `ms_mic_off_fill` | `SFSymbol.MicSlashFill` | FloatingCall, VoiceCallLiveActivity, VoiceView |
| `music.note` | `music_note` | `ms_music_note` | `SFSymbol.MusicNote` | Audio in Calls (Experimental), VoiceCallRows |
| `minus.circle.fill` | `do_not_disturb_on` (fill) | `ms_do_not_disturb_on_fill` | `SFSymbol.MinusCircleFill` | AppView |
| `moon.zzz` | `bedtime` | `ms_bedtime` | `SFSymbol.MoonZzz` | ServerTitleView |
| `network` | `public` | `ms_public` | `SFSymbol.Network` | AppLog, MetricKitClient, ServerTitleView, WhatsNewList |
| `network.slash` | `public_off` | `ms_public_off` | `SFSymbol.NetworkSlash` | ConnectionCard |
| `number` | `tag` | `ms_tag` | `SFSymbol.Number` | SessionLinkChips, WhatsNewList |
| `paintpalette` | `palette` | `ms_palette` | `SFSymbol.Paintpalette` | WhatsNewList |
| `paintpalette.fill` | `palette` (fill) | `ms_palette_fill` | `SFSymbol.PaintpaletteFill` | SettingsView |
| `paperclip` | `attach_file` | `ms_attach_file` | `SFSymbol.Paperclip` | TranscriptUserMessage |
| `paperplane` | `send` | `ms_send` | `SFSymbol.Paperplane` | WhatsNew/Entries |
| `pause.fill` | `pause` (fill) | `ms_pause_fill` | `SFSymbol.PauseFill` | FloatingCall, VoiceCallCenterButton, VoiceCallLogView |
| `pencil` | `edit` | `ms_edit` | `SFSymbol.Pencil` | AppView, RunError, ServerTitleView, SessionView+Header, SettingsServers, TranscriptUserMessage |
| `pencil.and.scribble` | `signature` | `ms_signature` | `SFSymbol.PencilAndScribble` | TraceTriggers |
| `person.2` | `group` | `ms_group` | `SFSymbol.Person2` | WhatsNewList |
| `person.crop.circle` | `account_circle` | `ms_account_circle` | `SFSymbol.PersonCropCircle` | ExperimentalFeatures, ServerDetailsTrays |
| `person.crop.circle.badge.checkmark` | `how_to_reg` | `ms_how_to_reg` | `SFSymbol.PersonCropCircleBadgeCheckmark` | TraceTriggers |
| `person.fill` | `person` (fill) | `ms_person_fill` | `SFSymbol.PersonFill` | VoiceProviderViews, VoiceProviderPage |
| `phone` | `call` | `ms_call` | `SFSymbol.Phone` | AgentImageGallery, AppView, ServerVoiceClient, WhatsNewList |
| `phone.and.waveform.fill` | `phone_in_talk` (fill) | `ms_phone_in_talk_fill` | `SFSymbol.PhoneAndWaveformFill` | WhatsNewList |
| `phone.circle.fill` | `call` (fill) | `ms_call_fill` | `SFSymbol.PhoneCircleFill` | ExperimentalFeatures |
| `phone.connection` | `phone_in_talk` | `ms_phone_in_talk` | `SFSymbol.PhoneConnection` | WhatsNew/Entries |
| `phone.down.fill` | `call_end` (fill) | `ms_call_end_fill` | `SFSymbol.PhoneDownFill` | FloatingCall, VoiceCallLiveActivity, VoiceView |
| `phone.fill` | `call` (fill) | `ms_call_fill` | `SFSymbol.PhoneFill` | ExperimentalSettingsView, FloatingCall, SessionView+ComposerControls |
| `photo` | `image` | `ms_image` | `SFSymbol.Photo` | AgentFileReference, AttachmentFileType, ExperimentalFeatures, TranscriptImageViewer, WhatsNewList |
| `photo.badge.exclamationmark` | `broken_image` | `ms_broken_image` | `SFSymbol.PhotoBadgeExclamationmark` | AgentImageGallery, TranscriptEntryView |
| `photo.badge.plus` | `add_photo_alternate` | `ms_add_photo_alternate` | `SFSymbol.PhotoBadgePlus` | — |
| `photo.on.rectangle` | `photo_library` | `ms_photo_library` | `SFSymbol.PhotoOnRectangle` | AttachmentMenu, AttachmentPicker, WhatsNewList |
| `photo.on.rectangle.angled` | `photo_library` | `ms_photo_library` | `SFSymbol.PhotoOnRectangleAngled` | WhatsNewList |
| `pin` | `keep` | `ms_keep` | `SFSymbol.Pin` | DrawerStyle, HomeView+Drawer, SessionSheetView, SessionView+Header, VoiceActionClaim, WhatsNewList |
| `pin.fill` | `keep` (fill) | `ms_keep_fill` | `SFSymbol.PinFill` | DrawerStyle, SessionSheetView |
| `pin.slash` | `keep_off` | `ms_keep_off` | `SFSymbol.PinSlash` | HomeView+Drawer, SessionView+Header |
| `pip.enter` | `picture_in_picture_alt` | `ms_picture_in_picture_alt` | `SFSymbol.PipEnter` | AgentFileViewer (AVKit’s Picture in Picture button) |
| `platter.filled.top.iphone` | `mobile_layout` | `ms_mobile_layout` | `SFSymbol.PlatterFilledTopIphone` | SessionLiveActivityExperiment, WhatsNew/Entries |
| `play.fill` | `play_arrow` (fill) | `ms_play_arrow_fill` | `SFSymbol.PlayFill` | FileAttachmentViews, LinkPreviewViews, SettingsView, VoiceCallLogView |
| `play.rectangle.on.rectangle` | `video_library` | `ms_video_library` | `SFSymbol.PlayRectangleOnRectangle` | DiagnosticsView |
| `plus` | `add` | `ms_add` | `SFSymbol.Plus` | AppView, AttachmentPicker, FloatingCall, OpenCodeClient+Models, ServerTitleView, SessionSwitcher, ShareView, WhatsNewList |
| `plus.circle` | `add_circle` | `ms_add_circle` | `SFSymbol.PlusCircle` | ExperimentalFeatures, WhatsNewList |
| `point.3.connected.trianglepath.dotted` | `hub` | `ms_hub` | `SFSymbol.Point3ConnectedTrianglepathDotted` | LinkPreviewViews |
| `power` | `power_settings_new` | `ms_power_settings_new` | `SFSymbol.Power` | TuningPanelView |
| `qrcode` | `qr_code` | `ms_qr_code` | `SFSymbol.Qrcode` | ServerTitleView, WhatsNewList |
| `qrcode.viewfinder` | `qr_code_scanner` | `ms_qr_code_scanner` | `SFSymbol.QrcodeViewfinder` | AppView, SettingsServers, WhatsNewList |
| `questionmark.folder` | `folder_off` | `ms_folder_off` | `SFSymbol.QuestionmarkFolder` | AgentFileViewer |
| `record.circle` | `radio_button_checked` | `ms_radio_button_checked` | `SFSymbol.RecordCircle` | TraceTriggers |
| `rectangle.bottomhalf.inset.filled` | `bottom_panel_open` | `ms_bottom_panel_open` | `SFSymbol.RectangleBottomhalfInsetFilled` | ExperimentalFeatures, WhatsNewList |
| `rectangle.compress.vertical` | `compress` | `ms_compress` | `SFSymbol.RectangleCompressVertical` | RunError, SessionSheetView, SessionView+Header |
| `rectangle.on.rectangle` | `filter_none` | `ms_filter_none` | `SFSymbol.RectangleOnRectangle` | AttachmentFileType |
| `rectangle.split.1x2` | `splitscreen` | `ms_splitscreen` | `SFSymbol.RectangleSplit1x2` | WhatsNewList |
| `rectangle.split.2x1` | `vertical_split` | `ms_vertical_split` | `SFSymbol.RectangleSplit2x1` | WhatsNewList |
| `rectangle.stack` | `stacks` | `ms_stacks` | `SFSymbol.RectangleStack` | CatchUpFinish, DrawerListSupportCells, WhatsNewList |
| `return` | `keyboard_return` | `ms_keyboard_return` | `SFSymbol.Return` | SyntaxHighlighter, WhatsNewList |
| `safari` | `explore` | `ms_explore` | `SFSymbol.Safari` | BrowserView, ExperimentalFeatures, LinkMenu, VoiceCallLogView, WhatsNewList |
| `selection.pin.in.out` | `text_select_start` | `ms_text_select_start` | `SFSymbol.SelectionPinInOut` | TranscriptUserMessage |
| `server.rack` | `dns` | `ms_dns` | `SFSymbol.ServerRack` | AppView, ExperimentalFeatures, ServerDetailsTrays, ServerDetailsView, WhatsNewList |
| `sidebar.left` | `left_panel_open` | `ms_left_panel_open` | `SFSymbol.SidebarLeft` | PlaygroundHostView, SessionView, WhatsNewList |
| `slash.circle` | `block` | `ms_block` | `SFSymbol.SlashCircle` | LinkPreviewViews |
| `slider.horizontal.3` | `tune` | `ms_tune` | `SFSymbol.SliderHorizontal3` | SettingsServers, TuningPanelView, WhatsNewList |
| `slider.horizontal.below.rectangle` | `instant_mix` | `ms_instant_mix` | `SFSymbol.SliderHorizontalBelowRectangle` | PlaygroundHostView |
| `smallcircle.filled.circle` | `radio_button_checked` | `ms_radio_button_checked` | `SFSymbol.SmallcircleFilledCircle` | LinkPreviewViews |
| `sparkle` | `star_shine` (fill) | `ms_star_shine_fill` | `SFSymbol.Sparkle` | VoiceProviderViews, VoiceProviderPage |
| `sparkles` | `auto_awesome` | `ms_auto_awesome` | `SFSymbol.Sparkles` | ModelSwitchRule, SettingsView, WhatsNewEntries, WhatsNewList |
| `speaker.fill` | `volume_mute` (fill) | `ms_volume_mute_fill` | `SFSymbol.SpeakerFill` | SoundEffectsSettings |
| `speaker.wave.1` | `volume_down` | `ms_volume_down` | `SFSymbol.SpeakerWave1` | WhatsNewList |
| `speaker.wave.2` | `volume_up` | `ms_volume_up` | `SFSymbol.SpeakerWave2` | WhatsNew/Entries |
| `speaker.wave.2.fill` | `volume_up` (fill) | `ms_volume_up_fill` | `SFSymbol.SpeakerWave2Fill` | SoundEffectsExperiment, WhatsNew/Entries |
| `speaker.wave.3.fill` | `volume_up` (fill) | `ms_volume_up_fill` | `SFSymbol.SpeakerWave3Fill` | SoundEffectsSettings (Material has no three-wave speaker) |
| `square` | `check_box_outline_blank` | `ms_check_box_outline_blank` | `SFSymbol.Square` | AgentImageGallery, MarkdownBlockView, ProjectIcon, RequestDockView |
| `square.and.arrow.down` | `download` | `ms_download` | `SFSymbol.SquareAndArrowDown` | TranscriptImageViewer |
| `square.and.arrow.up` | `share` | `ms_share` | `SFSymbol.SquareAndArrowUp` | AgentFileViewer, BrowserView, DiagnosticsView, LinkMenu, SessionSheetView, SessionView+Header, ShellOutputView, TranscriptImageViewer, VoiceCallLogView, WhatsNewList |
| `square.and.pencil` | `edit_square` | `ms_edit_square` | `SFSymbol.SquareAndPencil` | AppShortcuts, HomeView+Drawer, OpenCodeControls, SystemEntities, WhatsNewList |
| `square.grid.2x2` | `grid_view` | `ms_grid_view` | `SFSymbol.SquareGrid2x2` | WhatsNewList |
| `square.on.square` | `content_copy` | `ms_content_copy` | `SFSymbol.SquareOnSquare` | AppView, LinkPreviewViews, RunErrorRow, SessionView+Header, ShellOutputView, TranscriptEntryView, TranscriptUserMessage |
| `square.stack` | `layers` | `ms_layers` | `SFSymbol.SquareStack` | ExperimentalFeatures, WhatsNewList |
| `star` | `star` | `ms_star` | `SFSymbol.Star` | ModelListContent, ModelPickerSheet, SessionView+ModelPicker |
| `star.fill` | `star` (fill) | `ms_star_fill` | `SFSymbol.StarFill` | ModelListContent, ModelPickerOverlays, ModelPickerSheet, SessionView+ModelPicker |
| `star.slash` | `star_half` | `ms_star_half` | `SFSymbol.StarSlash` | ModelListContent, ModelPickerOverlays, ModelPickerSheet, SessionView+ModelPicker |
| `stop.fill` | `stop` (fill) | `ms_stop_fill` | `SFSymbol.StopFill` | ComposerViews, DictationLiveActivity, DictationViews, SessionView+ComposerControls, SettingsView |
| `switch.2` | `toggle_on` | `ms_toggle_on` | `SFSymbol.Switch2` | WhatsNewList |
| `tablecells` | `table` | `ms_table` | `SFSymbol.Tablecells` | AttachmentFileType |
| `terminal` | `terminal` | `ms_terminal` | `SFSymbol.Terminal` | ShellOutputView, TranscriptDragSource |
| `text.alignleft` | `format_align_left` | `ms_format_align_left` | `SFSymbol.TextAlignleft` | WhatsNewList |
| `text.badge.plus` | `post_add` | `ms_post_add` | `SFSymbol.TextBadgePlus` | AppShortcuts, OpenCodeClient+Models |
| `text.bubble` | `chat` | `ms_chat` | `SFSymbol.TextBubble` | AppShortcuts, BrowserView, PlaygroundHostView, SessionView+Composer, WhatsNewList |
| `text.cursor` | `text_select_start` | `ms_text_select_start` | `SFSymbol.TextCursor` | WhatsNew/Entries |
| `text.line.first.and.arrowtriangle.forward` | `vertical_align_top` | `ms_vertical_align_top` | `SFSymbol.TextLineFirstAndArrowtriangleForward` | ExperimentalFeatures |
| `text.line.last.and.arrowtriangle.forward` | `vertical_align_bottom` | `ms_vertical_align_bottom` | `SFSymbol.TextLineLastAndArrowtriangleForward` | WhatsNewList |
| `arrow.down.to.line` | `vertical_align_bottom` | `ms_vertical_align_bottom` | `SFSymbol.ArrowDownToLine` | What’s New (steadier following) |
| `text.quote` | `format_quote` | `ms_format_quote` | `SFSymbol.TextQuote` | WhatsNewList |
| `text.word.spacing` | `wrap_text` | `ms_wrap_text` | `SFSymbol.TextWordSpacing` | AgentFileViewer, PastedTextViews |
| `textformat` | `text_format` | `ms_text_format` | `SFSymbol.Textformat` | WhatsNewList |
| `textformat.size` | `format_size` | `ms_format_size` | `SFSymbol.TextformatSize` | WhatsNewList |
| `tortoise.fill` | `slow_motion_video` | `ms_slow_motion_video` | `SFSymbol.TortoiseFill` | ConnectionCard |
| `trash` | `delete` | `ms_delete` | `SFSymbol.Trash` | AppView, DiagnosticsView, HomeView+Drawer, QueuedMessagesSheet, ServerTitleView, SessionView+Header, SettingsServers, TranscriptUserMessage, VoiceCallLogView |
| `tray` | `inbox` | `ms_inbox` | `SFSymbol.Tray` | QueuedMessagesSheet |
| `tray.and.arrow.down` | `move_to_inbox` | `ms_move_to_inbox` | `SFSymbol.TrayAndArrowDown` | SessionTranscriptView |
| `tray.full` | `all_inbox` | `ms_all_inbox` | `SFSymbol.TrayFull` | RequestDockView |
| `tray.full.fill` | `all_inbox` (fill) | `ms_all_inbox_fill` | `SFSymbol.TrayFullFill` | TraceTriggers |
| `video` | `videocam` | `ms_videocam` | `SFSymbol.Video` | AttachmentMenu |
| `video.fill` | `videocam` (fill) | `ms_videocam_fill` | `SFSymbol.VideoFill` | FloatingCall |
| `waveform` | `graphic_eq` | `ms_graphic_eq` | `SFSymbol.Waveform` | AgentFileReference, AgentFileViewer, AppShortcuts, AttachmentFileType, ComposerViews, ExperimentalFeatures, OnboardingPlaygroundView, OpenCodeControls, QuickActions, SettingsView, TranscriptUserMessage, VoiceCallLogView, WhatsNewList |
| `waveform.and.magnifyingglass` | `troubleshoot` | `ms_troubleshoot` | `SFSymbol.WaveformAndMagnifyingglass` | TraceTriggers |
| `waveform.path.ecg` | `monitor_heart` | `ms_monitor_heart` | `SFSymbol.WaveformPathEcg` | ConnectionCard, DiagnosticsView |
| `wifi` | `wifi` | `ms_wifi` | `SFSymbol.Wifi` | WhatsNewList |
| `wifi.exclamationmark` | `wifi_tethering_error` | `ms_wifi_tethering_error` | `SFSymbol.WifiExclamationmark` | ConnectionCard, VoiceCallCenterButton |
| `wifi.slash` | `wifi_off` | `ms_wifi_off` | `SFSymbol.WifiSlash` | VoiceProviderViews, VoiceProviderPage |
| `xmark` | `close` | `ms_close` | `SFSymbol.Xmark` | AttachmentCameraView, BrowserView, DrawerSearchPill, OnboardingPlaygroundControls, RequestDockView, RequestReviewView, ServerTitleView, ShareView, ShellOutputView, SinceYouLeft, TranscriptImageViewer, TuningPanelView, VoiceView |
| `xmark.circle` | `cancel` | `ms_cancel` | `SFSymbol.XmarkCircle` | LinkPreviewViews, TuningPanelView |
| `xmark.circle.fill` | `cancel` (fill) | `ms_cancel_fill` | `SFSymbol.XmarkCircleFill` | ModelListContent, SessionView+Attachments, SharedLocation, ShellOutputView, TranscriptEntryView |
