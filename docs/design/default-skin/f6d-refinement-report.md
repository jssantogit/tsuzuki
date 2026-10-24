# Tsuzuki F6D — implementation and acceptance evidence

Draft review surface: https://github.com/jssantogit/tsuzuki/pull/142 . Canonical promotion: NO. Physical acceptance: PENDING.

## Git baseline and delivery

- Source: `origin/design/default-skin-consolidation`, fetched before work.
- Baseline SHA `cde46e656875d834628ca9898141600a2b0fd9a0`, tree `b5c25b6a42cdeed2c87626e046e233bf5a47ad28`. No newer canonical commit was present after fetch.
- Original primary checkout: SHA `c6b45874106f6b0a833e91972bea1dcd9a05ffd7`, tree `bdbc34e4db3d6240adb31e168090765764aa9548`; original untracked `.codex-remote-attachments/` and `.github/scripts/__pycache__/` preserved. It was not fast-forwarded or edited.
- Independent sibling worktree `Tsuzuki-f6d`, implementation branch `design/default-skin-f6d-refinement`. Not main or canonical. No force push, merge, reset, APK trigger or promotion.
- F6C's two-column sub-320dp exception is deliberately superseded. Existing cinematic heights, single Home Details navigation, 126dp horizontal posters and all domain/reading contracts otherwise remain.

Immutable implementation checkpoints (SHA / tree / message):

```text
3fe15eda6721afff46416d44a5c040c864284b04 8dd357edd2a08e054f02826abc2e18f5a0a00298 test: reproduce F6D stale library year with unchanged identity
2a82dfa619b50383a774f510d162074f19c0bef2 a6d7158563377adaab536dbfbfa5a6cadc5d9dee feat: refine Default Skin heroes grids and natural scroll for F6D [ci-full]
8956f590a39683e4a748a58c431a884e6ce40f52 5f997c9107d959ee0eb7f0023adbc6c27c0aada8 fix: type snapshot batch merge and preserve browse status titles [ci-full]

```

The final documentation/test checkpoint and exact-commit CI are also recorded on PR #142 and in Current State; no report embeds its own self-referential Git hash.

## F6D-A through F6D-J

| Area | Before | After / preserved contracts |
| --- | --- | --- |
| A — Home CTA | Full-width dominant 52dp-default button | Scoped 48dp minimum with 200–240dp content-sized width, 24dp horizontal padding; centered, single localized Conferir obra, same Details callback/carousel/indicators. Shared default remains 52dp. Larger type may grow vertically. |
| B — Details | Year/type on artwork; genres/status below; widely separated information | Visual Hero contains title and up to three nonblank real genres in original order. Year/type, all provider ratings including Tsuzuki, editorial status and synopsis follow below, then original read/library/contextual errors/actions and tabs. Long titles can grow; absent/failed artwork removes the cinematic minimum. |
| C — Synopsis | Two-line excerpt plus separate Ler sinopse navigation | Three-line inline text expands/collapses in place, keyed saveable state per work, localized accessibility action, no modal/tab change or operational mutation. Editorial Details tab remains. |
| D — Details toolbar | Abrupt canvas switch after80px, often a white light-theme strip | Transparent over the actual scrolling artwork; measured visual-Hero height drives a bounded continuous fade as the visual edge approaches the toolbar. No duplicate image/request/effect. Back/menu keep contrasting existing surface-backed touch controls and existing insets. |
| E — Collection consumption | Pinned large title, cumulative title/grid spacing, oversized catalog cells | Back remains fixed; full-span title and real List tabs scroll with the grid, 12dp rhythm/gap and subtle underline. Status surfaces retain a contextual toolbar title. Collection→Folder→List semantics, enabled-list selection, cancellation, children, pagination and error/loading/empty navigation remain owned by existing runtime. |
| F — Phone grids | Library allowed fewer columns below320dp; catalog adaptive cells often yielded two | Shared `defaultSkinWorkGridColumnCount` returns exactly3 below600dp; wider surfaces may expand.16dp outer gutters/12dp gap, cell-sized2:3 Crop frames, two-line titles and optional factual year. Horizontal rails remain horizontal. Cover-free16:9 folder navigation tiles retain their distinct two-column gallery; they are not work-poster grids. No separate vertical Discovery/selection work grid exists in the inspected Tsuzuki presentation paths. |
| G — Search | Title and query field outside result/Discovery lazy lists | One persistent LazyColumn contains keyed title/query item, recent searches, Discovery/results/format rails and status items. Same query, callbacks, IME actions and horizontal rails; no new scroll-to-top effect tied to query. |
| H — Headers | Inconsistent ownership in Search and browse consumption | Corrected those confirmed cases; retained useful persistent controls and already-scrolling Settings/Profile headers. Audit below. |
| I — Library years | Read-once local year map keyed only by inventory IDs | Batched SQLDelight table observation projects factual years into the same inventory; fingerprint-aware stream responds to local metadata/config changes. No remote resolution, per-card request, polling or migration. |
| J — Rhythm/cards/themes | Earlier/heavier Hero fades; title height shifted Search ratings; irregular year line | Later/lighter fades while retaining a canvas edge, denser Details editorial/Collections spacing, Search two-line title slot and factual date parser, Library reserved secondary text line. Existing Default Skin light/dark palette and Crop renderer retained; image/text contrast still requires the physical checkpoint. |

Home Collection artwork preview was investigated: `HomeFolderTile` exposes only folder ID/title; the existing projection contains no local canonical-cover preview. Preserved the Default Skin fallback instead of executing Lists, adding network or broadening the projection just to decorate navigation.

## Confirmed year bug and observation strategy

The old `CanonicalLibraryScreenModel` stored `metadataIdentity` and `startYears` inside the collector. It called `ResolveCanonicalMetadata.cached(ids)` only when the IDs changed. `ResolveCanonicalMetadata.execute` in Details writes factual snapshots through `upsertIfNewer`; unchanged inventory IDs therefore left the Library map stale even when another inventory emission occurred.

RED commit `3fe15eda6`: https://github.com/jssantogit/tsuzuki/actions/runs/39996232149 executed627 App Tsuzuki tests; exactly the unchanged-ID/year regression failed. This is executed behavior, not merely a suspected source pattern.

The correction adds `CanonicalMetadataSnapshotRepository.observeMany(ids)`. The SQLDelight implementation subscribes to the existing get-many query, splits ID sets into bounded900-ID batches, combines their maps and removes equal emissions. Decode failures remain omitted without deleting rows. No schema change. `ResolveCanonicalMetadata.observeCached` filters snapshots against the current configuration fingerprint, also listens to registry changes, and never calls live resolution. The Library combines this local stream with existing inventory/artwork/progress/filter state; source-cover fallback still uses `allowNetwork=false`.

Regressions cover empty→factual local SQL snapshot, unchanged IDs receiving a new year, missing/invalid dates, mismatched fingerprints, stable filter/progress behavior, and zero calls to the resolver's remote execute method. Existing provider/provenance date checks remain intact. App test updates emit metadata without emitting a new Library list, which is stronger than the original reproduction.

## Header audit

| Surface / owner | Classification and decision |
| --- | --- |
| Home / TsuzukiHomeScreen | Already correct: Hero and section headings are list content. Preserve. |
| Search / TsuzukiSearchScreen | Should accompany scroll: moved title and field into the sole vertical list. |
| Library / CanonicalLibraryHeader + status/active filters | Fixed intentionally: compact control row owns contextual search/filter and the persistent status rail. Preserve filter/search/back owner; no speculative restructuring. |
| Collections consumption / CollectionBrowseScreen | Should accompany scroll: title is first full-span grid item; back remains fixed; status-only surfaces retain title. |
| Folder/Direct List / FolderCatalogReady | Should accompany scroll: title, child navigation and List selector use the existing single grid owner; selected List/pagination unchanged. |
| Collections administration / CollectionsAdminRootPage | Fixed intentionally: existing compact navigation/management header and contextual actions. Preserve approved administration, not part of consumption-grid restructuring. |
| Profile / TsuzukiProfileScreen | Already correct: identity header and settings affordance live inside verticalScroll. |
| Settings root / SettingsMainScreen | Already correct: SettingsPageHeader is a LazyColumn item. |
| Main preference subpages / PreferenceScreen + SearchableSettings | Already correct: header is a ScrollbarLazyColumn item, including Appearance, Library/Reading, Data/Storage and Advanced preference destinations. |
| Account / SettingsTsuzukiAccountScreen | Already correct: header is a LazyColumn item; auth flow untouched. |
| Integrations root/detail | Fixed intentionally: existing contextual back/title above service status/config list. Preserve closed Settings/Integration contract absent device evidence. |
| Details | Should collapse: transparent-to-canvas fade tracks measured visual Hero; editorial/chapters/tab operations untouched. |

## Verification and limitations

- Lightweight local: focused cached ktlint1.8.0 parser/formatter and `git diff --check`; no local Gradle or Android-heavy run.
- Whole-branch independent read-only review found one contextual-title regression, subsequently corrected. No additional material defect was confirmed. Its unexercised-device cases remain pending, not evidence of passing behavior.
- CI attempt https://github.com/jssantogit/tsuzuki/actions/runs/39997703158 stopped at Kotlin type inference for the empty map combining snapshot batches. Format/Domain/Core/SQLDelight passed; App/Data/Release/Native did not complete acceptance. Correction explicitly types that map; no gate weakened.
- Corrected-code full CI: https://github.com/jssantogit/tsuzuki/actions/runs/39998397849 — SUCCESS: App/Data/Domain/Core, Format, SQLDelight, Supabase, Release Compile, Native Package Gate and CI Gate all passed. Standalone Compile was planner-skipped; Android Kotlin compilation executed inside App and release tasks.
- New focused JVM contracts: F6DDetailsPolicyTest (genre selection and continuous toolbar fraction), expanded CanonicalLibraryGridPolicyTest (narrow/phone/tablet geometry), CanonicalLibraryScreenModelTest (local reactivity/no remote execute), ResolveCanonicalMetadataCachingTest (local provenance/fingerprint updates), and CanonicalMetadataSnapshotRepositoryImplTest (SQLDelight emission).
- Existing Home/Details/rating/navigation/Collection screen-model tests and updated superseded source architecture contracts run in App/Domain CI. Source-symbol assertions are not counted as Android layout proof.
- Added opt-in `F6DInlineSynopsisTest` for expansion/collapse/restoration and absent text; `F6DScrollBehaviorTest` for Search header/query scroll and empty Collection title. **These Android tests were not executed.** Updating the old harness callback signature does not reopen or run it.
- No screenshots, rendered harness, emulator, personal-phone automation or real-provider probes were run. Actual narrow/font-scaled layout, rotation, light/dark image contrast, TalkBack, focus/IME, status/navigation bar appearance and perceived scroll/jank require manual physical acceptance on the user's phone. The new instrumentation also remains an explicit later gate; do not claim executed behavior.
- APK workflow/distribution artifact: NOT_RUN. Mandatory full-CI Release/Native gates internally assemble temporary packages for verification; they do not publish an installable artifact. No `[apk]` marker, APK branch or APK upload was requested.
- Protected resolver/Reader/source-selection/download/torrent/Debrid/P2P/Provider Platform/auth/sync/Rating/progress/storage semantics and unrelated migrations remain untouched.

## Changed paths

- `app/src/androidTest/java/eu/kanade/presentation/tsuzuki/DefaultSkinRenderedValidationInstrumentedTest.kt`
- `app/src/androidTest/java/eu/kanade/presentation/tsuzuki/F6DInlineSynopsisTest.kt`
- `app/src/main/java/eu/kanade/presentation/tsuzuki/collections/CollectionBrowseScreens.kt`
- `app/src/main/java/eu/kanade/presentation/tsuzuki/components/DefaultSkinCatalogLayout.kt`
- `app/src/main/java/eu/kanade/presentation/tsuzuki/components/DefaultSkinPrimitives.kt`
- `app/src/main/java/eu/kanade/presentation/tsuzuki/components/TsuzukiDefaultSkinCover.kt`
- `app/src/main/java/eu/kanade/presentation/tsuzuki/detail/CanonicalDetailsHero.kt`
- `app/src/main/java/eu/kanade/presentation/tsuzuki/detail/CanonicalTitleDetailScreen.kt`
- `app/src/main/java/eu/kanade/presentation/tsuzuki/detail/InlineSynopsis.kt`
- `app/src/main/java/eu/kanade/presentation/tsuzuki/home/TsuzukiHomeScreen.kt`
- `app/src/main/java/eu/kanade/presentation/tsuzuki/library/CanonicalLibraryScreen.kt`
- `app/src/main/java/eu/kanade/presentation/tsuzuki/search/TsuzukiSearchScreen.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/library/CanonicalLibraryScreenModel.kt`
- `app/src/test/java/eu/kanade/presentation/tsuzuki/DefaultSkinVisualContractTest.kt`
- `app/src/test/java/eu/kanade/presentation/tsuzuki/detail/F6DDetailsPolicyTest.kt`
- `app/src/test/java/eu/kanade/presentation/tsuzuki/library/CanonicalLibraryGridPolicyTest.kt`
- `app/src/test/java/eu/kanade/tachiyomi/ui/tsuzuki/library/CanonicalLibraryScreenModelTest.kt`
- `data/src/main/java/tachiyomi/data/tsuzuki/integration/CanonicalMetadataSnapshotRepositoryImpl.kt`
- `data/src/test/java/tachiyomi/data/tsuzuki/integration/CanonicalMetadataSnapshotRepositoryImplTest.kt`
- `docs/superpowers/plans/2026-10-23-default-skin-f6d-refinement.md`
- `domain/src/main/java/tachiyomi/domain/tsuzuki/integration/interactor/ResolveCanonicalMetadata.kt`
- `domain/src/main/java/tachiyomi/domain/tsuzuki/integration/repository/CanonicalMetadataSnapshotRepository.kt`
- `domain/src/test/java/tachiyomi/domain/tsuzuki/integration/interactor/ResolveCanonicalMetadataCachingTest.kt`

Additional delivery evidence: `app/src/androidTest/java/eu/kanade/presentation/tsuzuki/F6DScrollBehaviorTest.kt`, this report, `f6d-orchestra-field-test.md`, the F6D implementation plan and focused scroll instrumentation.

## Acceptance matrix

PASS below refers to implemented source contracts and the verified checkpoint's JVM/CI evidence. It does not assert executed Android interaction, device layout, image contrast or TalkBack. Physical acceptance and the opt-in Android tests remain pending. The closing delivery commit adds evidence/test-discovery hardening and must pass its own exact-commit full gate before delivery; the immutable run is linked from PR #142 / Current State and the final response.

```text
GIT BASELINE: PASS
HOME CTA: PASS
HOME HERO COMPOSITION: PASS
DETAILS HERO HIERARCHY: PASS
DETAILS THREE GENRES: PASS
DETAILS METADATA COMPOSITION: PASS
SYNOPSIS INLINE EXPANSION: PASS
DETAILS TOOLBAR CONTINUITY: PASS
LIGHT/DARK: PASS
COLLECTIONS HIERARCHY: PASS
THREE-COLUMN PHONE GRIDS: PASS
NARROW PHONE RESPONSIVENESS: PASS
SEARCH SCROLL: PASS
HEADER AUDIT: PASS
LIBRARY YEAR REACTIVITY: PASS
LIBRARY NO NETWORK FANOUT: PASS
RATINGS PRESERVED: PASS
NAVIGATION PRESERVED: PASS
LOCAL PREFLIGHT: PASS
FULL CI: PASS
CANONICAL BRANCH PROMOTION: NO
APK: NOT_RUN
PHYSICAL ACCEPTANCE: PENDING
F6D SOURCE-READY: YES
```

No Android visual or physical gate is closed by this source matrix. If the final delivery CI is not green, source-ready must be reported NO for that delivery even though the verified8956f590a checkpoint remains green.


Test-discovery note: the new Data coroutine test is explicitly `runBlocking<Unit>` so its JUnit method has a void return; a green shard without the named test appearing is insufficient proof that this particular regression ran. Verify its named PASSED line in the final delivery gate. Domain test execution was observed in39997703158; unchanged inputs were Gradle-cached in39998397849. Data/App were executed in39998397849. No Android instrumentation execution is inferred from either.
