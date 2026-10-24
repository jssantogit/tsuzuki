# Default Skin Wave 5: Canonical Details

Baseline: `799984804d8db70598e84cfd1dc90a52f6129069`.
Branch: `design/default-skin-consolidation`; local and fetched remote match baseline.
Status: implementation complete; final affected CI and Impeccable source gate passed.
Physical Android experience acceptance remains outstanding.

Promote only Canonical Details against the approved design.md and canonical-details
mockup/assets. Current runtime owns identity, metadata, chapter inventory, progress,
Library mutations, materialization and reading. No new Details runtime.

## Ownership and boundaries

- Runtime: CanonicalTitleScreenModel and existing Screen adapter. Reuse
  CanonicalReadingStartResolver for primary CTA and GetCanonicalReadingStart for
  cached presentation context. Existing OpenCanonicalChapter remains the gateway.
- Pure chapter presentation: real loaded collection, canonical IDs, contextual window,
  reversible ordering, exact jump, positional scrubber and lower-region Back modes.
- Presentation: focused single-artwork Hero, textual title fallback, editorial metadata,
  all-rating rail, read/bookmark actions, contextual/full/editorial regions and sheets.
- Parent: integration, i18n, plan, characterization of reused resolver, formatter and CI.

Do not touch Reader implementation, Provider Platform, matching, acquisition, P2P,
torrent, Debrid, other promoted root screens or Settings IA. No APK, main merge,
mockup fixtures in production, new integrations, title-logo synthesis or Wave 6.

## Discovery decisions

No title-logo or recommendations owner was found in app/domain/data runtime searches.
Use accessible textual title; omit Recommendations rather than manufacture runtime data.
Existing artwork resolver supplies canonical/source fallback for one decorative artwork.

Progress contains read, lastPageRead and lastVariantId, but no page total. A real
partial progress indicator represents that categorical state; no invented fraction.
Timestamp-only records and Library READING status do not imply resume.

Existing count-derived outline slots and materializer remain in the model. Presentation
rails/full list/jump/scrubber use observed chapters, excluding inferredFromCount slots;
reported chapter counts are not availability evidence. No new numbered identities.

Refresh tests target cached metadata loss on failed secondary refresh. Preserve usable
state during enrichment/degradation; total initial failure stays recoverable.

## Execution and evidence

1. Commit tests before production; record CI RED and exact missing contracts/defects.
2. Implement minimal runtime and pure chapter behavior; execute affected App/Domain CI.
3. Only after behavioral GREEN, implement approved presentation and run affected CI.
4. Run one bounded Impeccable source gate, one correction batch/confirmation if needed.
5. Record final commits, executed/skipped jobs and unavailable physical Android checks.

Local SDK is absent: default Android SDK path missing and environment variables unset.
Local Android compilation cannot be claimed. CI is authoritative; planner-only green or
skipped compile is not executed test/Android behavior. No phone automation/live probes.

### RED evidence

- `2f6ec3a14`: CI `38136815213` executed 548 App Tsuzuki tests, with exactly
  two new refresh regressions failing: useful synopsis became null after failed
  enrichment; a thrown secondary failure replaced Loaded with Error. Format and
  Domain Tsuzuki passed, including the irregular-identity reading characterization.
- `8b8f22bf3`: CI `38137206178` confirmed compile-time RED for absent chapter
  presentation APIs, progress/read CTA state/actions and Providers routing/copy.
  Format passed; new assertion contracts could not run before implementation.
- `8c27536de`: minimal production implementation published for behavioral GREEN.
  Visual composition remains unchanged until this gate passes.
- Local focused Gradle attempt stopped at missing SDK location, before Android
  compilation/tests. Cached ktlint formatting and diff checks ran successfully.
- CI `38137792867` compiled Android and executed 577 App Tsuzuki tests. One
  absence-artwork fixture failed because relaxed mocks returned an empty string;
  the fixture now explicitly returns null for absent artwork and source fallback.
  Assertions and production semantics remain intact.
- `a502a2799`: CI `38138121132` executed 580 tests and reproduced three additional
  regressions: timestamp-only progress shifted never-started context, degraded
  empty inventory lost resume signals, and an empty progressive stage unloaded
  valid chapter rows. The previously diagnosed fixture was the fourth failure.
  All three production corrections remain scoped to the existing ScreenModel.
- `54bcaf243`: CI `38138486075` passed Format, App Tsuzuki and CI Gate after
  those corrections. Android Kotlin compilation ran inside the App test job;
  standalone compile, release/native packaging, migrations and backend jobs were
  planner-skipped. This GREEN releases the approved visual promotion.
- New test inventory: 34 App tests and one Domain reading characterization.
- `abfc809ed`: initial approved visual promotion. CI `38139419035` passed
  Format but stopped at Android compilation: nested maxWidth DSL receiver,
  incorrect Sort icon namespace and incorrect AutoMirrored Back namespace.
  No unit tests ran in that attempt. A single focused API correction batch follows.

### Impeccable source gate

Compared design.md, index.html and canonical-details.js/css against the promoted
Hero, chapter components and lower-region wiring after behavioral GREEN.
Covered progress/never-started, all ratings, editorial, full list, scrubber,
artwork absence/failure, no chapters and degraded refresh; Recommendations is
omitted because runtime has no owner, rather than treated as fixture evidence.

Bounded corrections: application theme selection rather than device theme,
native icon/API receivers, faithful rating scales and overflow cue, Webtoon
editorial format preservation, scrubber bubble/track separation and vertical
tracking, read chapters receding instead of current markers, pending-download
and special-chapter accessibility, horizontal safe areas and jump IME/focus.
No domain/sourcing/Reader changes. Source confirmation completed after the correction
batch. Final CI `38140023931` passed against `f5d2b1200167b67fa44d794edd041e7089eb9acc`:
580 App Tsuzuki tests passed; app Android Kotlin and unit-test Kotlin compilation ran
inside that test job. Format and CI Gate passed. Standalone compile, release/native
packaging, migrations and backend remained planner-skipped. No extra broad test run
was needed after this gate.

Physical Android remains required to accept actual font scaling, TalkBack,
long-work drag/scroll, image quality, system chrome/insets and Reader navigation.
No emulator/instrumented behavior was executed during this wave.

Final evidence and acceptance limits: `docs/design/default-skin/wave-5-canonical-details-report.md`.
