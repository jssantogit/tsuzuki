# Canonical Details — design tensions

Status: refined board for human review; **not visually closed**. The [current specification](../design.md#canonical-details--visual-review) owns the composition. Hero/modes and navigation architecture are settled; this pass does not reopen them.

## Durable visual direction and superseded composition

One Hero artwork replaces backdrop plus foreground cover. Centered title/logo, editorial year/format pair, separate status and genres live within a long atmospheric fade. Complete ratings use a light horizontal rail; no +N in Details. Compact chapter cards gain breathing room, while All Chapters remains a continuous row list with restrained current-row accent and partial progress. These are explicit review requirements, not competing conceptual alternatives.

The previous foreground-cover composition, concatenated editorial line and wrapped ratings anatomy are preserved only as [historical source](../mockups/history/canonical-details-cover-hero/README.md). Older rejected labels, page-count captions and explanatory chapter-selection copy remain superseded. Search and Settings are not reopened.

## Genuine unresolved tensions

- **Trustworthy Provider-neutral artwork/title-logo sourcing:** official assets are available for the fixture but current runtime availability, attribution, trust and asynchronous replacement rules need a separate implementation decision. Work identity must not become the identity of the supplying Provider. Text remains the universal fallback.
- **Recommendations sourcing and identity:** fully designed fixture interface; runtime sourcing, canonical resolution/deduplication, ranking, cache and APIs remain TBD. No Provider capability or algorithm is asserted.
- **Irregular long collections:** scrubber follows ordered collection position, including fractional/special/gapped identities. Android must settle virtualization, responsive geometry, accessibility and recenter behavior. The reserved side lane protects row actions but needs device acceptance.
- **Partial metadata/artwork updates:** omit missing fields and retain useful cache/local content. Android must avoid disruptive shifts and preserve readable contrast during asynchronous enrichment.
- **Broader Library status management:** bookmark membership remains sufficient here; broader status editing is a separate product concern.

## Fixture boundary

Fullmetal Alchemist uses official adaptation artwork (1600×1050) and official title-logo (170×96) from its series website; One Piece uses existing official Toei adaptation artwork (540×432). These are mockup assets, not manga catalogue metadata or runtime sourcing evidence. Cover assets remain in recommendation cards only, not foreground Hero posters. The no-backdrop frame uses a clean neutral background and textual title.

Scores, reading progress, download state, Library membership, the synthetic irregular collection and recommendations are demonstration data. Seven ratings exercise complete horizontal inventory; real branding/scales do not make the displayed scores live. Existing Android functionality remains the reference for canonical identity, local/cache loading and operational reading, not these fixture values.

## Review and future implementation

Review the one-artwork Hero/fade, title identity, editorial hierarchy, discoverability of rating overflow, reading priority, rail breathing room, continuous list scanning and scrubber action separation. Recommendations is fixture-only and Canonical Details remains open until human approval. No Android code or runtime identity semantics were changed.


## Bounded refinement: artwork integration and inspectable states

A dedicated backdrop remains preferable; cover-only artwork is an atmospheric fallback, never evidence of runtime backdrop availability. Controlled blur and a softer fade preserve the single-artwork Hero language without a second poster. Legibility across actual artwork and font scaling still needs implementation-time review. The Details fixture starts at an editorial scroll position to expose real mode content rather than presenting another apparently identical Hero. Scrubber feedback identifies collection destinations during dragging; numerical labels do not imply continuous numbering. This pass changes neither metadata hierarchy nor chapter-list architecture. Visual closure still requires human approval.
