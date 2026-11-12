# MangaAll Host-to-Reader emulator gate

Isolated, stackable validation branch from Host #196; no main merge, signing or provider publication.

The `mangaAllSyntheticJourney_reconcilesCanonicalChaptersAndReaderPages` instrumentation test exercises a local SCRIPT fixture with the unified MangaAll identity, source-scoped work/chapter ID, canonical refresh/reconciliation, selection revalidation and the Provider HTTP Reader byte loader. It does not use the signed real MangaAll package or tap the actual UI; separate Android Reader UI source-switch suites must be explicitly included in the CI acceptance gate.

Do not claim that Android page rendering or the full canonical workflow is validated solely from these fixtures. A fully integrated UI tap & Reader rendered-pixel smoke remains an open gate.
