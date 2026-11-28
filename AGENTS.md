# OpenCode Android: agent rules

<!-- tallow -->

The OpenCode app for Android (Kotlin, Jetpack Compose, Material 3 Expressive).
Lives at github.com/anomalyco/opencode-android. Work goes on a feature branch → squash-merged PR
into `dev`, the default branch.
CI (`.github/workflows/ci.yml`) runs `scripts/check.sh` on every PR and push to `dev`; a push to
`dev` also publishes the debug APK as the `dev-latest` pre-release.
`.github/workflows/release.yml` releases `dev` to Google Play at noon and 6 p.m. Central; run it
by hand to release sooner (`gh workflow run release.yml --ref dev -f bump=patch`). Changes under
`.github/` need Dax's review ([docs/releasing.md](docs/releasing.md)).

## The iOS app is a reference

- The app started as a port of the iOS app, and most of its code still mirrors it. Android may
  differ from iOS where that makes it a better Android app (for example, translations).
- iOS repo: `/Users/vogel/dev/opencode-ios` (read-only; never modify it). When porting or
  comparing, read the Swift, not just the docs: docs drift, the code doesn't.
- Ported code keeps iOS type and function names where they still make sense, so the two
  codebases diff side by side. Copy keeps one voice: the same UI vocabulary, “Couldn’t …” error
  copy with the typographic apostrophe, and Title Case rules.
- Track every iOS file in `docs/parity/<area>.md` (see [docs/parity/README.md](docs/parity/README.md))
  and update the row in the same commit. Where Android differs on purpose, say so in the row.
- Visuals follow the OpenCode desktop design system (`packages/ui` in the opencode repo), not
  iOS: Inter for UI text and OpenMono for code and tool output (bundled, OFL, with the licence
  texts shipped from `core/src/main/assets/licenses/`; refresh the OpenMono files with
  `scripts/update-openmono.py`), the
  desktop's type scale in sp, flat surfaces with hairline borders instead of tonal elevation
  or glass, small radii (`OpenCodeRadius`, `OpenCodeShape.control`), the desktop palette as
  the default theme (`BrandPalette`). Use `core/ui` tokens and `OpenCodeSurfaces`
  (`OpenCodeAlertDialog`, floating fill + `openCodeHairline`) rather than raw Material
  colours or capsules. Behaviour stays Android: Material components' structure, system back
  and predictive back, sheets, IME, TalkBack semantics, ≥48 dp targets and font scaling.
  SF Symbols → Material Symbols via `OpenCodeIcons`. No
  glass/blur imitation, no web views as the app shell, no React Native/Flutter/KMP UI.
- The transcript list's motion (scroll following, the send, row entrances and growth) is Android-native
  on purpose: a bottom-anchored LazyColumn with standard Compose animations. Don't port iOS's transcript
  motion (the `TranscriptListViewController` follower, row growth, send flights); its behaviour, flows
  and copy still follow iOS.
- The full-screen media viewer's motion (open, close, drag to dismiss, predictive back) is Android-native
  on purpose: an animated frame and rounded clip in `MediaPresentation.kt`. Don't port iOS's viewer flight;
  the drawing hand-off into the composer still follows iOS.
- The composer's autocomplete list motion is Android-native on purpose: a `LazyColumn` in
  `AutocompleteViews.kt` whose panel opens and closes on the panel roles, while filtering and the highlight
  land on the next frame. Don't port iOS's autocomplete simulation or chip flight; the list's rows, ordering,
  selection and copy still follow iOS.
- Catch Up's card stack motion (the swipe, settle, fling, Undo and preview) is Android-native on purpose: the drag
  writes the card's offset and Compose `animate`/`Animatable` jobs on motion roles in `CatchUpStackController.kt`
  move it; the swiped card flies off as itself. Entering and leaving Catch Up is one stage fade on
  `MotionRole.Swap` in `SessionDrawerController.kt`. Don't port iOS's card flights, mode morphs or snapshots; the
  stack's flows, gestures, haptics and copy still follow iOS.
- The session's page stack (subagent and browser pages) is Android-native on purpose: one animated
  shared-axis move in `SessionPageStack.kt` and system predictive back. Don't port iOS's full-width
  back pan or its stack motion; covered pages still stay composed so they keep their state.
- The composer's + menu is Android-native on purpose: a Material `DropdownMenu` hanging from + in
  `SessionView.kt` (`SessionAttachmentDropdown`), with Material's motion and a non-focusable popup so the
  keyboard stays up. Don't port iOS's glass surface, hold-to-open or slide-to-choose, or `TouchRecovery`;
  its rows, order, haptics and keyboard hand-back still follow iOS.

## Modules (fixed; never edit `settings.gradle.kts`)

| Module | Ports (iOS) | Depends on |
| --- | --- | --- |
| `:app` | `OpenCode/App/` (Application, MainActivity, `AppFeature`, `AppView`, connection screen, manual server form) | everything |
| `:core` | `OpenCode/Core/` (store, dependencies, shared keys, `AppLog`, haptics, design system, motion) | — |
| `:server` | `OpenCode/Server/` + `Core/ServerID*.swift` + server-typed shared keys | `:core` |
| `:feature:media` | `OpenCode/Media/`, `OpenCode/Browser/` (in-app browser), `Core/DecodedImageCache.swift` | core, server |
| `:feature:voice` | `OpenCode/Voice/`, `Core/AudioSessionOwner.swift`, voice haptic cues | core, server |
| `:feature:system` | `OpenCode/System/`, `OpenCodeShare/`, `OpenCodeWidgets/` (Glance), `OpenCodeNotifications/` (FCM bridge behind an interface) | core, server |
| `:feature:transcript` | `OpenCode/Transcript/` | media |
| `:feature:composer` | `OpenCode/Composer/` | media, voice |
| `:feature:settings` | `OpenCode/Settings/` (incl. Onboarding, What’s New) | voice, system, devtools |
| `:feature:session` | `OpenCode/Session/` | transcript, composer, voice, media, system |
| `:feature:drawer` | `OpenCode/Drawer/` (`HomeFeature`, drawer, Catch Up) | session, settings, system |
| `:devtools` | Android-native developer tools (not a port of iOS `Tuning/`/`Diagnostics/`) | core, server |

iOS is one module, so its files reference each other freely. Here the graph is acyclic: if you
need a type from a module above you, move the type down to the lowest module that needs it
(usually `:core` or `:server`) and note it in both parity files. Never add an upward edge.
Each module's public entry point (`<Feature>`, `<Feature>.State/Action`, `<Feature>View`) is
already wired by its parent; keep those names.

## Architecture (mirrors the iOS rules in its `docs/architecture.md`)

- **Store (`ai.opencode.core.store`)** is TCA as iOS uses it: `Reducer<S, A>.reduce(state, action)
  → Reduced(state, effect)`; `Effect.none/run/send/merge/concatenate`, `.cancellable(id,
  cancelInFlight)`, `Effect.cancel(id)`, `.debounce`; `scope`, `combine`, `ifLet` (presentation,
  `PresentationAction`, cancels child effects on dismissal), `forEach`, `onChange`; `Store`,
  `store.scope`, `collectState()`/`observe {}` in Compose; exhaustive `TestStore`. State is an
  immutable `data class`, actions a `sealed interface`. Never call `reduce` recursively; use
  private helpers returning `Reduced`. Durable state, navigation and cancellable I/O live in
  reducers.
- **Plain objects** (not reducers) own per-frame motion, audio meters, scroll physics and byte
  batching (`EventBatcher`), sending reducers coarse semantic events or bounded batches.
- **Dependencies (`ai.opencode.core.dependencies`)**: every service is a client with a
  `companion object Key : DependencyKey<T>` (`liveValue`, inert `testValue`), read with
  `dependency(Key)` / `by Dependency(Key)`, overridden with `withDependencies {}` or
  `TestStore(..) { this[Key] = .. }`. No business singletons; a process-wide instance stays
  private behind its key. Hot loops resolve a client once. Time/IDs come from `Clock`,
  `DateClient`, `UuidClient`; Android `Context` from `AppContext` (live values only).
- **Shared keys (`ai.opencode.core.sharing`)**: `shared(SharedKeys.x)` → `Shared<T>`
  (`.value`, `.flow`, `.set`, `.update`). Same names and defaults as iOS `SharedKeys.swift`.
  Mutate only from reducers, only when the value changes. Secrets use `SharedKey.keychain`.
- **Server**: every request goes through `OpenCodeClient` + `NetworkRetry` (one retry layer).
  REST snapshots are authoritative; SSE invalidates and projects. See `server/` KDoc and
  `docs/parity/server.md`.
- **No side effects in composables**: clipboard, URL opening, file I/O and network go through
  actions and dependencies. Composables get immutable values and an action lambda (or a scoped
  store); list rows never hold stores.

## Logging, haptics, UI rules

- Log only through `AppLog` (`send`, `network`, `stream`, `voice`, `app`, `perf`). Log IDs,
  counts, lengths, statuses, timings. Never tokens, credentials, server addresses, pairing codes,
  message text or file contents. Never call `android.util.Log` directly.
- Haptics: one vocabulary (`Haptic.tap, selection, confirm, cancel, threshold, snap, attention,
  success, warning, error`), played through `HapticsClient` (reducers) or `HapticEffect`
  (composables). Same rules as iOS (answer the press; navigation and closing stay quiet).
- Design system (`ai.opencode.core.ui`, `ai.opencode.core.motion`; details in
  `docs/parity/core-ui.md`): wrap roots in `OpenCodeAppTheme {}`; iOS semantic colours via
  `OpenCodeTheme.colors.*` (`border` follows high contrast), M3 roles via
  `MaterialTheme.colorScheme`; text styles under iOS names via `OpenCodeTextStyles.*` (desktop
  sizes) or `MaterialTheme.typography`; `OpenCodeShape`/`OpenCodeRadius`/`OpenCodeSpacing`;
  floating panels, dialogs and popovers via `OpenCodeSurfaces`; iOS springs via
  `Motion.*.spec()` (respects reduce motion).
- Icons: `OpenCodeIcon(SFSymbol.X, contentDescription)` or `OpenCodeIcon("sf.name", …)`. To add
  one: a line in `scripts/material-symbols.txt`, run `python3 scripts/fetch-material-symbols.py`,
  add the `SFSymbol` entry and the core-ui.md row (the coordinator commits shared files).
- Buttons: `OpenCodePrimaryButton` (Next/Send), `OpenCodeSecondaryButton` (Back/Skip); red only
  for destructive roles. A raw Material `Button` takes `shape = OpenCodeShape.control` (Material
  defaults to a capsule); dialogs use `OpenCodeAlertDialog`. Touch targets ≥ 48dp (`Modifier.openCodeTapTarget`, after `clickable`),
  `OpenCodeIconButton` for icon controls.
- Unfinished features stay behind Experimental, off by default, as on iOS.
- UI text lives in string resources, not Kotlin literals, so it can be translated
  ([docs/translating.md](docs/translating.md)): each module's `src/main/res/values/strings.xml`,
  with names prefixed by the module (`composer_`, `session_`, …) because resource names are
  global. Shared words (Cancel, Done, …) are `common_*` in
  `core/src/main/res/values/strings_common.xml`, read elsewhere as `CoreR.string.common_cancel`
  (`import ai.opencode.core.R as CoreR`). Composables read text with `stringResource`; reducers,
  models, enums and errors use `localized(R.string.x)` / `localizedPlural` (`ai.opencode.core.strings`),
  called in a function or `get()`, never in a `const val`, a top-level `val` or an enum
  constructor argument. One resource per sentence with positional placeholders (`%1$s`), never
  joined pieces; counts use `<plurals>`. Never compare, store or send translated text. Logs,
  model prompts, wire values and developer-only screens stay literals. Tests assert the English
  text: under unit tests `localized` returns it from the XML files. After adding or changing
  English UI text, run `python3 scripts/translate.py all` for the new strings (with `--redo
  <name>` when the meaning changed) and `python3 scripts/translations.py all`.

## Motion

Most of the app shouldn't move. Before adding an animation, ask how often someone will see it;
a one-frame change is not a bug by itself.

- **Frequency decides.** Seen hundreds of times a day (scrolling, selecting a row, switching
  sessions): no motion. Tens of times a day: barely noticeable, or none. Occasional (a dialog, a
  sheet, a card docking above the composer): the standard roles. Rare or first-time (pairing,
  onboarding): the place to spend craft.
- **Direct input is never animated.** Typing, pasting, dragging and search filtering apply on the
  next frame.
- **Nothing animates at load.** Content that is there when a screen opens, or comes from a cache,
  starts at rest. A row scrolling back into view doesn't replay an entrance.
- **Animate layout that something else moves.** When a card, pill, notice or section arrives or
  leaves, its neighbours slide instead of jumping, and the leaving one keeps drawing its last
  content until it's gone.
- **Switching views is instant.** Sessions, tabs and the content behind a segmented control swap
  on the next frame; only the indicator moves. Pushed pages, sheets, menus and dialogs keep
  Material's and the platform's own motion.
- **Lists animate only what the person just did.** Archive, delete and pin slide the neighbours.
  Loading, re-sorting and search results don't animate rows.
- **Press scale is for buttons** (`motionPress`). Rows, tabs and bubbles answer with their
  highlight.
- **Fix a jump with layout before motion.** A busy button keeps its width, an image reserves its
  box, and a placeholder has the size of what replaces it.
- **Reduce Motion keeps a short fade** (`Motion.reducedFadeSpec()`): drop travel and scale, but
  don't cut.
- **Use the vocabulary.** Specs come from `MotionRole`/`Motion`, never raw `tween` or `spring`
  numbers. An interrupted animation continues from where it is, and an exit is shorter than its
  entrance.

A PR that adds motion says how often the moment happens.

## Comments

- Default to no comment. Never explain a fix, a past bug or what the code used to do
  ("was X", "otherwise this would…", "iOS does Y, so here…"). That belongs in the commit
  message and PR description.
- Comment only what the current code can't say itself, in one short line, such as a
  non-obvious platform constraint. Don't restate the code.
- Don't match the surrounding comment density; existing comments aren't a template.
- Don't add comments pointing to the iOS counterpart; `docs/parity` tracks that mapping.

## Dependencies

All coordinates live in `gradle/libs.versions.toml`; modules reference catalog entries only.
Core set: kotlinx.coroutines, kotlinx.serialization, OkHttp (+ okhttp-sse), DataStore, Coil 3,
Compose/M3 (+ adaptive), Navigation3. Pre-declared for areas: Glance + WorkManager (system),
CameraX (composer), Media3 + androidx.browser/webkit (media), Stream WebRTC (voice; iOS uses
Google's libwebrtc), jlatexmath (transcript; iOS's Markdown and highlighter are
self-written, so port them first and use a library only where iOS did, e.g. SwiftMath →
jlatexmath), Firebase Messaging (push; keep it behind an interface until credentials exist).
Add a new library only with a one-line justification here.
- `com.google.zxing:core` (settings): decodes the pairing QR from CameraX frames, as iOS's
  AVFoundation QR metadata output does; pure Java, no Play services (ML Kit would need them).
- `androidx.media3:media3-exoplayer` (voice): plays an agent's audio on a call (iOS `CallAudioPlayer` uses AVAudioPlayerNode on the call's engine); ExoPlayer streams and decodes what MediaPlayer can't, on the call's voice-communication attributes.
- `androidx.profileinstaller:profileinstaller` (app): installs the packaged app and library Baseline Profiles for ART compilation, including sideloaded builds.
- `androidx.fragment:fragment` (app, constraint only): raises the fragment 1.1.0 that Firebase Messaging's Play services libraries pull in, which Google Play's SDK index flags as outdated; the app has no Fragments of its own.
- `androidx.navigationevent:navigationevent` (app, constraint only): raises the 1.1.x that Compose 1.13 and Navigation 3 pull in, which crashes when a focusable popup closes during a predictive back gesture; 1.2.0 ignores back events sent to a removed input.

## Check and run

- One check: `scripts/check.sh` (assembleDebug + lintDebug + testDebugUnitTest for every module;
  `scripts/check.sh :feature:composer` for one module). Gradle memory is capped in
  `gradle.properties` because ~10 agents build concurrently; don't raise it, don't run
  `./gradlew clean` on the whole project.
- JDK 17: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17`. SDK: `~/Library/Android/sdk`.
- Emulator: AVD `OpenCode_API_36` (Pixel 8, API 36). `scripts/emu.sh boot [port]` (headless,
  read-only, so several agents can each run one on their own even port), `scripts/emu.sh
  install [port]`, `scripts/emu.sh shot out.png [port]`, `scripts/emu.sh kill [port]`. Never use
  or modify `Rebase_API_36`.
- Fixture server: `python3 /Users/vogel/dev/opencode-ios/scripts/session-lab.py serve` (REST/SSE
  on 127.0.0.1:4110, control page at http://127.0.0.1:4110, see its `scripts/SESSION_LAB.md`).
  From the emulator connect to `http://10.0.2.2:4110` via Enter a Server Manually (cleartext
  is allowed for 10.0.2.2/localhost in debug builds only). Stop servers you start.

## Commits

- Commit every completed, verified change on a feature branch and open a PR into `dev` (never
  commit to `dev` directly), one coherent change per commit. The message says what changed,
  why, and what was verified (commands run, emulator checks); call out removed or replaced
  behaviour.
- PRs that change visuals should include videos or images of the change.
- In this shared checkout stage only the files you own (`git add <paths>`), never `git add -A`.
  Don't amend, rebase, stash, or reset other agents' work.
- Never commit secrets, `local.properties`, `google-services.json`, keystores, or private
  screenshots/logs.

## Tests

- Port the iOS unit tests (`/Users/vogel/dev/opencode-ios/OpenCodeTests/`) into each module's
  `src/test` (JVM, exhaustive `TestStore` like iOS's `TestStore`), keeping iOS test names so the
  suites diff side by side. When a ported test fails, work out whether the code is wrong or
  Android differs on purpose; for a deliberate difference, change or delete the test and note
  why in the parity row.
- Other new test files (beyond ports of iOS tests) still need the coordinator's approval.
- Isolation (iOS `TestIsolationGuard`): under JVM unit tests `DependencyValues.root` resolves
  `testValue`s, so even code outside a `TestStore` never reaches live storage or the network.
  Shared test support lives in Gradle test fixtures; feature modules get it through the
  convention plugin, `:core`/`:server` through their own `testFixtures`:
  - `ai.opencode.core.testing` (`:core` fixtures): `TestIsolationRule` (`@get:Rule val
    isolation = TestIsolationRule()`) fails a test that leaks process-wide state
    (`AnimationTimeScale.shared`, `TuningStore.shared`, shared keys written through the root,
    open `WireFixture`s, blocked network) and resets it; `TestIsolationGuard` (probes,
    `capturingViolations`, `Plugin`s via `META-INF/services`); `eventually { }`.
  - `ai.opencode.server.testing` (`:server` fixtures): `WireFixture` (iOS `WireFixture`: scripted
    replies for a unique `wire-<uuid>.invalid` host, captured requests). Every
    `OpenCodeTransport.testValue` pool runs behind `TestNetworkBlock`: fixture hosts are answered,
    loopback (`MockWebServer`) passes, anything else fails as an unreachable host and is reported.
  - Requests are sent once under tests (`NetworkRetry`) unless a test sets a policy.
  - Opt-in live checks (iOS `liveServerReadContract`) run only when `OPENCODE_LIVE_SERVER`,
    `OPENCODE_LIVE_PASSWORD`, `OPENCODE_LIVE_SESSION_ID` and `OPENCODE_LIVE_DIRECTORY` are set
    (e.g. against session-lab); otherwise they're skipped, so `scripts/check.sh` stays offline.
