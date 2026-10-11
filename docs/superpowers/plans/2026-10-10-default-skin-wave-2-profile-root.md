# Default Skin Wave 2: Perfil Android and root promotion

Status: implementation in progress.
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
