# Default Skin Wave 4: Library and Collections consumption

Baseline: `4d993383e023ebd1a33456dc636ce53d12e76ed2`.
Branch: `design/default-skin-consolidation`. Status: implemented; affected CI GREEN.
Android visual and manual navigation acceptance remain outstanding.

Promote approved Library, Collections Index, Collection View, Folder Catalog and
Direct List presentation. Runtime remains canonical. Preserve collection definition
administration, List Builder and the existing ExecuteCollectionList consumption.

Move the existing browse screens/models/components from Home to Collections once;
Home and Index share those destinations. Direct List extends the same binding with
an explicit list preference and fails closed rather than falling back. Collection
View only projects root folders and never executes lists.

Library retains its filter/status owner and artwork resolution. Add only the original
inventory count needed to distinguish genuine empty from filtered no-results.

Boundaries: no Provider/P2P/torrent/Reader/runtime changes, no Canonical Details
visual promotion, no Auth/Profile/Home Hero/Search/Settings changes. No APK or main
merge, and no Wave 5 work.

Sequence: behavioral RED contracts and CI evidence; minimal runtime change and
approved presentation; integration/caller migration; affected CI; bounded Impeccable
comparison and correction; final evidence with unavailable Android checks disclosed.
Parent owns i18n and Home integration. Library, browse and Index have separate owners.

Required verification: formatter, Library and browse contracts, CollectionsScreenModel,
affected Home tests and planner-selected downstream compilation/tests. Local SDK is
currently absent; attempt focused checks and record actual execution limits. A green
planner-only run cannot substitute for execution of behavioral test shards.

## Delivered ownership and contracts

- Library stays on CanonicalLibraryScreenModel and canonicalLibraryDestination.
  Success exposes totalItemCount from the original observed inventory; filtered items
  remain separate. Genuine empty and filtered no-results have distinct localized copy
  and contextual recovery that preserves unrelated state.
- Contextual search, light status rail, immediate advanced sheet choices, advanced-only
  badge and at most two removable chips plus +N reproduce the approved Library.
  Adaptive cover-only grid retains canonical/source/local artwork resolution.
- CollectionBrowseScreenModels, browse presentations and FolderTile moved from Home
  to Collections. Home and Index navigate to the same CollectionBrowseScreen and
  FolderCatalogScreen. No Home-specific browse wrappers remain.
- Index card body opens consumption; explicit pencil edits USER Collections.
  Import/export remain in global overflow; editors, List Builder and model-owned
  persistence/reorder/tombstones remain intact. Old private embedded consumption rows
  were removed. Initial collection/folder arguments translate into shared destinations.
- Collection View presents root Folders in two columns, 16:9, cover-free with labels
  below. It observes navigation projection and does not execute Lists.
- Folder Catalog uses existing ExecuteCollectionList with CACHE_FIRST/VISIBLE and
  ResidualPageCursor. Zero enabled Lists means no execution; one selects and executes
  without selector; multiple select deterministically by sortOrder/id and show a rail.
  Only selected List executes; switching/rebinding cancels and rejects stale results.
- DirectListScreen binds the same FolderCatalogScreenModel with preferredListId.
  Missing/disabled requested List fails closed, with no fallback, subfolders or selector.
- CatalogItem -> MaterializeCanonicalTitleFromCatalog.execute ->
  FolderCatalogEvent.OpenCanonicalTitle -> CanonicalTitleScreen(resolved canonical id).
  Materialization failure leaves browse usable and emits no navigation.

## Behavioral verification

Nineteen tests added: four Library state contracts; ten browse contracts; three
Collection card action contracts; one Home shared-destination contract; one domain
root-only/non-executing navigation projection contract. Four existing browse tests
were preserved and moved. Existing Library/filter/artwork and CollectionsScreenModel
tests remain. Home and Library destination contract files also moved into the Tsuzuki
test packages so affected CI actually selects them.

RED commit: de6d48103. CI run
https://github.com/jssantogit/tsuzuki/actions/runs/38111557486 failed App test compilation
on the missing new state/binding/navigation contracts. This was compile-time RED,
not executed failing assertions. Format passed.

Implementation commit: c7b2514cf. CI run
https://github.com/jssantogit/tsuzuki/actions/runs/38112056783 passed App/Tsuzuki tests,
Domain/Tsuzuki tests, Format and CI Gate. Logs confirm app compileDebugKotlin and
testDebugUnitTest execution, including all 14 browse tests, Collection card actions,
Library state/filter tests, CollectionsScreenModel and TsuzukiHomeScreenModel.
Domain logs confirm root/nested projection and navigation without List execution.
Standalone compile/release/native/backend/migration jobs were skipped by routing;
those skips are not execution evidence.

Local focused app Gradle test attempt stopped at configuration because Android SDK
is absent (ANDROID_HOME/ANDROID_SDK_ROOT unset and default SDK path missing).
No local Android compilation or tests executed. Cached repository ktlint formatter
and git diff --check passed. CI spotlessCheck is the authoritative format check.

Local attempted command:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*CanonicalLibrary*Test' --tests '*CollectionBrowseScreenModelTest' --tests '*CollectionsScreenModelTest' --tests '*CollectionCardNavigationTest' --tests '*TsuzukiHomeScreenModelTest' --tests '*TsuzukiNavigationContractTest' --tests '*TsuzukiLibraryContractTest' --offline --console=plain
```

CI executed commands: `./gradlew spotlessCheck`,
`./gradlew :app:testDebugUnitTest --tests '*tsuzuki*'`, and in the implementation run
`./gradlew :domain:testDebugUnitTest --tests '*tsuzuki*'`.

Polish confirmation run
https://github.com/jssantogit/tsuzuki/actions/runs/38112458380 passed Format but failed
app compileDebugKotlin because SuggestionChip has icon, not trailingIcon, in the
project's Material3 version. No behavioral assertions ran in that failed job.
Focused correction 7b43c4e3c changed only that argument; no runtime behavior changed.

Final code confirmation: 7b43c4e3cd6bac6a85f126f0cbf5dd3349a2c88e. CI run
https://github.com/jssantogit/tsuzuki/actions/runs/38112627945 passed Format,
App/Tsuzuki tests and CI Gate. Logs confirm executed app compileDebugKotlin,
all five TsuzukiNavigationContractTest cases (including Home shared destination),
both TsuzukiLibraryContractTest cases (including canonical Details tap), and successful
testDebugUnitTest. Domain tests were already GREEN at c7b2514cf and no domain code or
tests changed afterward. This final evidence commit changes documentation only.

## Impeccable gate and acceptance limits

After behavioral CI passed, one bounded source review compared Library's six states,
Index, Collection View, nested Folders, Folder Catalog 0/1/N Lists and catalogue
loading/error/empty/pagination, plus Direct List, against design.md and approved
mockup/assets. Corrections: bounded/ellipsized active chips preserve +N, real remove
icon, neutral advanced badge and full-frame Library cover containment. Confirmation
review found these previous source findings resolved in 1cdee9b7f. No redesign.

Code comparison is complete. Android screenshots/contrast measurements, effective
font scaling, TalkBack, keyboard/IME, physical Back, drag reorder, actual insets and
manual navigation acceptance were NOT RUN. Source support for adaptive grids,
theme colors, minimum targets and Scaffold insets cannot certify those results.
No emulator or physical phone was automated. No APK was generated.

Changes are confined to promoted UI/models/tests/i18n and strict Home caller adapters
(including the existing Settings Home preview call site, without Settings IA change).
Provider/P2P/Reader/Canonical Details runtime and visuals were not changed. No main
merge. Canonical Details visual promotion remains Wave 5; stop after Wave 4 report.
