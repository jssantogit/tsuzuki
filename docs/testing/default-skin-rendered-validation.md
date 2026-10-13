# Default Skin rendered validation

This opt-in Android instrumentation suite distinguishes four evidence layers. Passing a lower layer never implies a higher one.

| Layer | What it establishes | What it does not establish |
| --- | --- | --- |
| Source contract | Stable implementation boundaries and forbidden regressions | Measured geometry, color, density, or visual hierarchy |
| Unit/behavior | Pure rules and callbacks, including Integration status derivation | Compose layout or rendered appearance |
| Layout/semantics | Actual Compose semantics and measured node bounds in a running Android view tree | Pixel appearance, physical display behavior, or approval against the design |
| Render artifact | Screenshot pixels produced by a disposable emulator and deterministic fixtures | Golden approval or physical-device acceptance |
| Physical device | Human inspection on the target device against approved references | Not covered by this automated suite |

## Available infrastructure and chosen layer

The application already has Android instrumentation (`AndroidJUnitRunner`, AndroidX JUnit, UiAutomator) and Compose production UI. It does not have a Compose UI-test rule dependency, Robolectric, Paparazzi, Roborazzi, screenshot goldens, or an existing screenshot approval workflow. The suite therefore uses a debug-only host Activity, the public Compose `ViewRootForTest`/`SemanticsOwner` APIs, and `UiDevice` screenshots. It introduces no visual-test framework or dependency.

The instrumentation runs only when CI v2.1 is manually dispatched with `default_skin_rendered=true`. It runs on the workflow's disposable API 35 emulator. The runner disables and verifies airplane mode before tests. Local PNG fixtures are written to the app cache and the evidence bundle is written under app-owned external files. CI pulls that directory from the disposable emulator and fails closed unless all seven instrumented tests, required valid PNGs, and measured CSV files exist. Appearance changes only the disposable test app's theme preferences and restores their previous values in `finally`; the Integration test temporarily sets and restores MAL's local Client ID and makes no network request. The bundle is an inspection artifact, not a checked-in golden or an automatic approval.

## Production compositions exercised

| Target | Rendered production composition | Deterministic assertions/artifacts | Approved reference used for later human review |
| --- | --- | --- | --- |
| Settings macrogrouping | `SettingsMainScreen.Content` inside Voyager | Six tagged headings/surfaces; all 13 current destinations contained by their intended group; seven internal dividers; measured label/surface and inter-group rhythm; light/dark screenshots and CSV | `docs/design/default-skin/design.md`, `docs/design/default-skin/mockups/index.html`, and the relevant `docs/design/default-skin/tensions/*-tensions.md` files for Settings destinations |
| Appearance | `SettingsAppearanceScreen.Content` inside Voyager | Real preview nodes and selectable radio semantics; compact measured preview ratio; exactly one selected theme; Theme Mode preference rendered for System/Light/Dark with selected-option semantics; AMOLED retained value and disabled state in Light mode; light/dark and per-mode screenshots plus CSV | `docs/design/default-skin/tensions/aparencia-tensions.md`, `docs/design/default-skin/mockups/index.html`, and `docs/design/default-skin/design.md` |
| Integrations | Production `IntegrationSettingRow` and `IntegrationToggleRow` | MAL incomplete/configured status from the local Client ID, Kitsu active status without local configuration, disabled Komga status; root navigation role/callback and informational status; absence of root switch semantics; detail switch callback/state; CSV | `docs/design/default-skin/tensions/integracoes-tensions.md`, `docs/design/default-skin/mockups/index.html`, and `docs/design/default-skin/design.md` |
| Search artwork | Production `SearchCompactCard` and its shared compact artwork composition | Exact 2:3 raw frame, foreground/decorative bounds, semantics/click ownership, local exact/wide/tall/whitespace/failed/missing fixtures; wide-fixture edge-color pixel probe; six screenshots and CSV | `docs/design/default-skin/tensions/search-tensions.md`, `docs/design/default-skin/mockups/index.html`, and `docs/design/default-skin/mockups/assets/search/SOURCES.md` |
| Canonical Details Hero | Production `CanonicalDetailsHero` with local `CanonicalTitleScreenState.Loaded` fixtures | Unclipped frame/art/fade bounds; title→metadata→following-content flow; absent/failed-art collapse; distinct dark/light artwork pixel probes; five screenshots and CSV | `docs/design/default-skin/tensions/canonical-details-tensions.md`, `docs/design/default-skin/mockups/index.html`, and `docs/design/default-skin/mockups/assets/canonical-details.css` |
| Semantic palette | Production `SettingsCanvas`, `SettingsGroup`, and Default Skin palette | Light/dark screenshot samples for actual canvas/surface pixels against role values; measured bounds and CSV | `docs/design/default-skin/design.md` and the active surface-specific tension/mockup for each palette sample |

For unclipped geometry, the test snapshots `Rect(positionInRoot, size)` from each public `SemanticsNode`; `boundsInRoot` is also retained as the viewport-clipped/accessibility-visible bound. The test performs snapshots on the main thread and waits for the current fixture identity before measurement. This avoids mistaking parent clipping for a correctly sized child.

The Settings test exercises the root composable and real routes, but it does not validate every destination's internals. Appearance renders the actual content for each Theme Mode but does not recreate the test Activity or validate platform window-bar behavior. Integrations tests the actual production root/detail row composables in isolation, not the entire screen's navigation/view-model lifecycle. Search exercises the real reusable compact card, not the discovery/search data pipeline. Details exercises the actual Hero, not the entire detail navigation/runtime screen. These are explicit test boundaries.

## Running and reviewing evidence

Run on a configured disposable emulator:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=eu.kanade.presentation.tsuzuki.DefaultSkinRenderedValidationInstrumentedTest
```

Then retrieve the app-owned directory from that disposable emulator (the CI helper resolves the installed instrumentation target package rather than guessing it):

```bash
adb shell pm list instrumentation
adb pull /sdcard/Android/data/<resolved-target-applicationId>/files/default-skin-rendered <local-output-dir>
```

Compare the screenshots and measurements with the references above. Generated renders are not accepted merely because they match current source. Any visual discrepancy remains a human review finding; physical-device review remains a separate gate.

## Remaining limits

- There is no screenshot/golden framework or deterministic golden-diff lane.
- UI screenshots here are emulator renders, not proof on the user's physical device/display.
- Screen density, system bars, font scaling, dynamic configuration, and device-specific rendering still require physical review where relevant.
- Source contracts remain structural guards only. They are not visual acceptance.
