# Wave 7 — Settings Android Promotion Report

## Status

Implementation-accepted in code on `design/default-skin-consolidation`.

Verified implementation HEAD: `accbe9d4fe277c2f4206a72aeb993d670f35ca28`

Verification marker commit: `be0df96615cdb1247ab77c0defbe5298216dac3e`

CI: v2.1 run #3977 (`38153102370`)

Physical/device acceptance remains pending. No APK was requested or generated. No merge to `main` was performed.

## Scope accepted

Wave 7 promotes the approved Default Skin Settings information architecture to Android while preserving the existing runtime owners instead of creating duplicate settings state.

The public root is:

`Conta → Geral → Conteúdo → Downloads → Dados e armazenamento → Privacidade e segurança → Diagnóstico → Avançado → Sobre`

Accepted implementation contracts include:

- `Conta` owns the Tsuzuki account/login/register/recovery/sync/sign-out surface and keeps the existing account ScreenModel/runtime contracts.
- `Geral` routes through focused Personalization and Reading hubs rather than exposing the inherited Mihon settings taxonomy as the primary product IA.
- `Conteúdo` keeps Providers and tracking/integration surfaces as the public content-extensibility path.
- `Downloads` retains the existing download preference owners while grouping the surface around network/files, performance, cleanup, automatic downloads, and reading-ahead behavior.
- `Dados e armazenamento` promotes storage location, storage usage, cache/temp files, backup creation/restoration, and library export into focused routes around the existing runtime owners.
- `Privacidade e segurança` owns device authentication, secure-screen/privacy controls, notification privacy, global direct-P2P consent, and conditional telemetry. Direct-P2P consent is not duplicated in Advanced.
- `Diagnóstico` promotes real log capture/clear/share and technical diagnostics without fake state.
- `Avançado` remains the owner of genuinely advanced operational preferences rather than becoming a dumping ground for public Settings families.
- `Sobre` is promoted as its own final public family.
- public Settings no longer expose Add-ons as a first-class family; Provider remains the public extensibility terminology.
- Settings search remains attached to canonical surfaces rather than reviving superseded navigation.

## Localization and presentation follow-up

This wave also replaced several newly introduced hardcoded labels with existing localized resources where there was an exact semantic match, especially across Reading, Diagnostics, and Personalization.

Some Default-Skin-specific copy still has no dedicated Moko resource and remains explicit localization debt rather than being mapped to semantically incorrect inherited strings. Examples include some root/group labels, visual-preset copy, parts of storage/download copy, direct-P2P/telemetry explanatory copy, and the Diagnostics title.

This debt is not treated as a runtime blocker for Wave 7 acceptance, but it should be completed before broader localization certification.

## Verification

The final full verification ran against the exact implementation snapshot through the temporary `tsuzuki/default-skin-wave7-verify` CI branch.

CI v2.1 #3977 passed:

- Change Planner;
- Format / Spotless;
- Tests — App, including Settings information-architecture, Account, Search, Visual Preset, P2P-consent ownership, and related Default Skin contracts;
- Tests — Core Common;
- Tests — Domain;
- Tests — Data;
- SQLDelight migrations;
- Supabase backend;
- Release Compile;
- Native Package Gate, including ABI and 16 KiB compatibility;
- final CI Gate.

The App gate compiled both debug production and unit-test Kotlin before completing `testDebugUnitTest` successfully.

The separate compile matrix was planner-skipped in full mode because release/native gates provided the applicable compile/package coverage; it is not being counted as an independently executed success.

## Diff discipline

Compared with the Wave 6 accepted code snapshot `86c55f93fdbb7961803d204984f3cb6f286fc6c7`, the Wave 7 implementation snapshot is 41 commits ahead with no divergence.

The delta is confined to Settings presentation/infrastructure, the associated LogoHeader seam, and Settings-focused tests. Reader, Provider/P2P acquisition, matching, hydration, and unrelated runtime behavior were not reopened by this wave.

## Remaining acceptance

Source/code acceptance does not replace physical acceptance. Waves 1–7 still need the accumulated real-device pass after the Default Skin integration is complete.

The next Default Skin implementation slice is final integration / legacy-surface cleanup: verify navigation, search, deep links and back behavior across the promoted surfaces; prevent retired Mihon/Extensions/Add-ons public paths from resurfacing; remove only obsolete parallel UI paths that are proven unused; then move to the accumulated APK/device acceptance cycle when explicitly requested.
