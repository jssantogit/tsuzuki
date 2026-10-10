# Default Skin mockup

Open [index.html](index.html) directly in a browser. It remains the single runnable entry for this design family, with relative assets under `assets/` and no build or package installation. The initial composition now shows the main Biblioteca surface. Its board contains six review frames: Biblioteca padrão, filtros avançados ativos, busca ativa, Filtros bottom sheet, nenhum resultado and biblioteca vazia. The frame labels sit outside the product UI.

The Biblioteca states use the approved Default Skin direction and the existing Android behavior as their product reference. Search filters the current library locally. Status and advanced filters remain active together; advanced selections apply immediately, and clearing them preserves status and search. The Filtros sheet presents ORIGEM, conditional LISTA, FORMATO and CATEGORIA. A cover tap gives an accessible signal that navigation would open the existing `CanonicalTitleScreen`; this mockup does not draw that detail screen.

Kitsu, Manga, the example custom list and Sem categoria are fixtures for showing active filter states. The seven local covers are illustrative grid fixtures; they do not form a canonical catalogue, assert a real user's library, or prove a cover belongs to a particular canonical work or Provider. Runtime origins, custom lists and user categories remain dynamic. Canonical artwork is preferred when available. Fixture content is not a product promise or permanent data contract.

Earlier boards and their local navigation remain in the source. Keep those source blocks and module order when editing the mockup; later boards reuse earlier components. The project-root `index.html` is the Open Design delivery copy, while this repository path remains the design-family source entry. The documentation copies delivered in Open Design mirror these notes and the current specification.

This is visual evidence, not Android implementation. The mockup does not execute Android operations or prove Provider availability, canonical identity matching, preference persistence, scheduling, or navigation. Confirm the adaptive grid, system insets, font scaling, TalkBack behavior, touch targets and animations/transitions in the real Android implementation before treating those details as settled. No screenshot, rendering or device-verification evidence is claimed by this document update.

- `assets/`: CSS/JS, local font files, real cover/brand assets and lookup snapshots needed by or useful for the preserved prototype.
- `history/personalizacao/`: previous HTML preserved with relative links to shared font assets. It is historical evidence, not another current entry.

Global Aquisição and Folder Catalog code remains historical/unapproved; do not infer current requirements from it. [Current specification](../design.md) resolves status.

Local Segoe UI/Bahnschrift files came from the OpenDesign prototype. Their provenance and existing licensing notes are unchanged: they are presentation inputs, not an Android font choice, an open-source license claim or permission for separate font distribution. Brand masters remain in [docs/brand](../../../brand/README.md); the local lockup is a prototype copy/transposition of the official asset. Covers and service marks are referential sample content, not Tsuzuki-owned artwork. Acquisition provenance and limitations remain documented in the tension records.
