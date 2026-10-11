# Default Skin Wave 2: Perfil Android and root promotion

Status: implementation complete; affected CI accepted. Manual Android UX acceptance pending.
Branch: `design/default-skin-consolidation`
Starting HEAD: `9e52d271a5a7eaf104a8f10e256beadf3a8c6e16`

Implement the approved sparse Perfil composition using Wave 1.5 runtime truth.
Count completed unified canonical titles once across all origins; retain currently
reading and persisted read-chapter counts. Pages, identity persistence, personal
Favorites and completion history remain unsupported and are omitted.

Replace only Settings with Profile as root index 3. Push existing Settings main,
Account and Tracking destinations through Voyager. Reuse Continue Reading's
ordering/visibility/progress pipeline with limit 5 and extract existing artwork
enrichment for concrete Home/Profile reuse. Preserve the shell and saveable state.

Scope: Profile domain/app/presentation, focused tests, localized resources, Home
artwork reuse and root replacement. No MainActivity, Provider/P2P, authentication,
Reader, schema, global theme, unrelated design or later waves.

Acceptance: focused behavioral tests then CI v2.1 affected planner-selected jobs;
one approved-composition Impeccable review. No APK/full CI by default. Runtime
scroll, navigation and phone/tablet visual acceptance remain manual if not executed.

## Implementation and bounded review

- `ObserveProfile` projects reading/completed counts from one unified-library
  subscription. Chapters retain the existing persisted read aggregate.
- Profile uses lifecycle collection, `WhileSubscribed`, `rememberScrollState`
  and `rememberPagerState` inside the unchanged Voyager tab/saveable-state shell.
- Gear, guest/confirmation account actions and tracking management push the actual
  `SettingsMainScreen`, `SettingsTsuzukiAccountScreen` and `SettingsTrackingScreen`.
  The obsolete Settings tab was removed, without removing Settings itself.
- Shared `ObserveContinueReadingArtwork` extracts Home's local-only cover lookup
  and fallback enrichment. The existing observer owns order, visibility, chapter
  checkpoints and the Profile limit of five. Reader opens the canonical chapter.
- Neutral account icon, supporting credential email, two equal statistics and
  compact actual tracker connection summary; no fixtures, inferred identity,
  pages, personal Favorites, history or percentage denominator.
- One focused Impeccable/source review corrected neighbor-card preview by using
  pager outer padding and full artwork by opting Profile into `ContentScale.Fit`.
  Shared cover callers keep the default `Crop`. No Android render was executed.

## Verification evidence

- RED: [38101649095](https://github.com/jssantogit/tsuzuki/actions/runs/38101649095)
  at `4a2c4273a`; Domain test compilation reported missing `countCompletedTitles`
  and `completedTitles`. Compile-time RED, not executed failing assertions.
  Format also identified two test layout adjustments, corrected in implementation.
- Local: cached ktlint 1.8.0 formatter passed on all changed Kotlin files;
  `git diff --check` passed. No local Gradle build or test was used.
- Added 13 behavioral cases: six canonical completion projections, reactive
  completed snapshot, five-item ordering, empty/artwork reading enrichment,
  two Profile state/pipeline cases and absence of Settings from exact root contract.
  Existing Wave 1.5 capability/identity and Home artwork tests remain in scope.
- Affected CI: [38102174629](https://github.com/jssantogit/tsuzuki/actions/runs/38102174629)
  at `4a2cb8fd3`; planner selected Format, Domain Tsuzuki and full App tests because
  the shared MangaCover presentation API changed. Format, Domain Tsuzuki, full App
  tests (including app production/test compilation) and CI Gate all passed.
  No post-implementation CI correction was required. The final documentation-only
  commit does not change the verified code revision.
- No APK, full verification request, emulator, physical-device automation or live
  provider probe. UI visibility, scroll/carousel restoration, Settings/Tracking
  return, dark/AMOLED and phone/tablet visual acceptance require manual Android
  execution; source review and unit tests do not certify those runtime behaviors.

Final diff review confirmed exact roots Home/Search/Library/Profile, existing gear
and Tracking routes, no retained Settings root, and no unsupported populated modules.
MainActivity, Provider/P2P, authentication, Reader and persistence schema are unchanged.
Stop at Wave 2; no future phases were implemented.
