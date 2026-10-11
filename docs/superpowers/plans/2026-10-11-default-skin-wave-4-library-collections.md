# Default Skin Wave 4: Library and Collections consumption

Baseline: `4d993383e023ebd1a33456dc636ce53d12e76ed2`.
Branch: `design/default-skin-consolidation`. Status: discovery and RED contracts.

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
