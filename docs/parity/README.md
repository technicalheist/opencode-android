# Parity tracking

The Android app started as a port of the iOS app (`/Users/vogel/dev/opencode-ios`) and may now
differ from it on purpose. These files map each iOS source file to its Android counterpart, so a
change on one side can be compared with the other. When comparing, read the Swift; iOS docs drift.

Each area has one file listing **every** iOS source file it owns:

| File | Module | iOS folders |
| --- | --- | --- |
| [app.md](app.md) | `:app` | `OpenCode/App/` |
| [core.md](core.md), [core-ui.md](core-ui.md) | `:core` | `OpenCode/Core/` |
| [server.md](server.md) | `:server` | `OpenCode/Server/` (+ server-facing Core files) |
| [session.md](session.md) | `:feature:session` | `OpenCode/Session/` |
| [transcript.md](transcript.md) | `:feature:transcript` | `OpenCode/Transcript/` |
| [composer.md](composer.md) | `:feature:composer` | `OpenCode/Composer/` |
| [drawer.md](drawer.md) | `:feature:drawer` | `OpenCode/Drawer/` |
| [settings.md](settings.md) | `:feature:settings` | `OpenCode/Settings/` |
| [voice.md](voice.md) | `:feature:voice` | `OpenCode/Voice/` |
| [media.md](media.md) | `:feature:media` | `OpenCode/Media/`, `OpenCode/Browser/` |
| [system.md](system.md) | `:feature:system` | `OpenCode/System/`, `OpenCodeShare/`, `OpenCodeWidgets/`, `OpenCodeNotifications/` |
| [devtools.md](devtools.md) | `:devtools` | `OpenCode/Tuning/`, `OpenCode/Diagnostics/` (reference only) |

## Status values

- `todo`: not started.
- `sync: <old status>`: the iOS file changed after the synced commit; port the change, then
  restore the status (see [Keeping up with iOS](#keeping-up-with-ios)).
- `removed in <sha>: <old status>`: iOS deleted the file in that commit; delete or re-home the
  Kotlin, then drop the row.
- `partial: <what is missing>`: some behaviour ported; say exactly what is not.
- `done`: every behaviour in the Swift file exists in Kotlin (logic, copy, constants, logging,
  haptics, error states). Visuals are Material equivalents, not pixel copies.
- `n/a: <reason>`: iOS-only plumbing (UIKit hosting seams, Liquid Glass helpers, Metal
  scaffolding, App Group, XCUITest lab hooks). Always say what it maps to on Android, or why
  nothing is needed.

## Rules

1. Update the row in the same commit as the port. One row per Swift file; when one Swift file
   becomes several Kotlin files, list them all; when several Swift files fold into one Kotlin
   file, point each row at it.
2. A type a lower module needs moves down (to `:core`, `:server` or the lowest feature that uses
   it); note the move in both areas' files. Never add an upward or cyclic module dependency.
3. `scripts/ios-sync.py check` must pass: every iOS source file at the synced (or target) commit
   has a row, and every row's Lines matches iOS.

## Keeping up with iOS

[sync.json](sync.json) records the iOS commit these tables describe (`synced`) and, during a
sync, the commit being caught up to (`target`). The first port was taken from iOS `062f3732`
(2026-10-24); every Lines count in these tables matched that commit exactly.
`scripts/ios-sync.py` (iOS checkout in `$OPENCODE_IOS`) moves it forward:

1. `scripts/ios-sync.py status --to origin/main`: what changed, per area.
2. `scripts/ios-sync.py plan --to <sha>` writes `sync/<from>-<to>.md`: every changed iOS file
   with its diff size, the iOS commits that touched it and the Kotlin it maps to, plus the iOS
   tests to port. Port from the net diff (`scripts/ios-sync.py diff <ios path>`), not commit by
   commit: iOS often lands a feature and reworks it a few commits later.
3. `scripts/ios-sync.py stale` marks the rows (`sync:`, `todo`, `removed in`) and records the
   target. Commit the plan and the marked tables together.
4. Port area by area, as in the first port: each commit brings a set of rows and their tests
   back to `done`/`partial`/`n/a` and ticks them in the plan.
5. When `scripts/ios-sync.py check` is clean with nothing waiting, `scripts/ios-sync.py advance`
   makes the target the synced commit.

## Tests

Every iOS unit test (`OpenCodeTests/`, 185 files, 3,126 `@Test`s, of which 173 files hold tests and
the rest are support) is either ported into the module that owns its code or deleted with a reason
in that module's `## Tests` section. A test ported in halves (say a reducer half and a wire half)
counts in each module. In a `## Tests` row, "→ `<module>`" means the test is ported in that module
under the same name; anything not ported anywhere is listed as deleted with its reason. Checked on
2026-10-26 by matching every iOS test name against the Kotlin test functions: 2,858 iOS tests ported,
268 deleted, none unaccounted for.

Synced to iOS fc25a553 (2026-11-02): iOS has 3,539 tests; 3,228 have a Kotlin test of the same
name and 311 don't. The 74 that were ported at 062f3732 and have no Kotlin test now were all deleted
from iOS too. The 53 new iOS tests (since 062f3732) without a Kotlin test of the same name are each
named with a reason in their module's Tests row or in the sync plans under `sync/`. The first three
columns' totals are still the 062f3732 audit (the syncs updated some module rows); the JVM column is
recounted from Gradle at the fc25a553 sync.

| Module | iOS test files covered | iOS tests ported | Deleted with reason | JVM tests (Gradle) |
| --- | ---: | ---: | ---: | ---: |
| `:app` | 22 | 126 | 27 | 141 |
| `:core` | 19 | 112 | 74 | 138 |
| `:server` | 36 | 230 | 3 | 259 (2 opt-in live checks skipped) |
| `:devtools` | 4 | 14 | 10 | 17 |
| `:feature:media` | 17 | 178 | 15 | 183 |
| `:feature:voice` | 34 | 499 | 16 | 700 |
| `:feature:system` | 9 | 88 | 2 | 103 |
| `:feature:transcript` | 35 | 282 | 58 | 283 (1 env-gated metric skipped) |
| `:feature:composer` | 41 | 267 | 40 | 280 |
| `:feature:settings` | 17 | 98 | 10 | 99 |
| `:feature:session` | 59 | 707 | 41 | 756 |
| `:feature:drawer` | 49 | 422 | 12 | 487 |
| **Total** | 173 distinct (at 062f3732) | 2,858 distinct (at 062f3732) | 268 (at 062f3732) | 3,446 |

"Deleted" counts whole iOS tests, each charged to the module whose section records it: UIKit and
SwiftUI layout probes, Tuning Panel and Mac playgrounds, MetricKit, Touch Probe, Team Feedback and
Scrawl, Metal shaders, ActivityKit authorization, App Group, Info.plist checks, env-gated benchmarks,
and migrations from iOS builds Android never had. Sub-asserts dropped inside a ported test aren't
counted. A JVM count can exceed the iOS count because parameterized iOS tests and framework halves
(`…FrameworkTests` under Robolectric) are separate Kotlin tests.

Robolectric 4.18.1 (test-only) runs the tests that need Android framework classes (Bitmap/ImageDecoder
pixels, `Intent`, `android.icu`, `Patterns`, `LruCache`, `MimeTypeMap`, AppSearch, the main looper,
`Vibrator`) in `:app`, `:core`, `:feature:media`, `:feature:voice`, `:feature:system`,
`:feature:transcript`, `:feature:composer`, `:feature:settings`, `:feature:session` and `:feature:drawer`.
