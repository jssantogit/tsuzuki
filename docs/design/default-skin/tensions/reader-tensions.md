# Reader — session design tensions

Conceptual architecture supplied and approved in the brief; visual board pending human review. This record concerns the reading session, not approved [Leitura preferences](leitura-tensions.md).

## Durable decisions
- Immersive canvas with center-tap floating chrome; the canvas does not resize when controls appear. Full-width app bars are superseded.
- Top uses a circular back bubble, an expanded flexible title/chapter pill and a right-side eye bubble for reading protection. Its selected state is visible and its accessible name remains explicit.
- Bottom uses separate progress and tools pills, with inset geometry, subtle elevation and light translucency.
- Progress count once; tools scroll horizontally by touch/trackpad/mouse drag, with natural continuation, no Mais button.
- Reader ≠ Settings. Persistent defaults stay in Configurações → Leitura.
- Deeper tools use small independent floating cards, not a large draggable panel. White light-theme surfaces, dark legible text, a subtle border and soft shadow distinguish them from white reading pages. The earlier dark card treatment is superseded.
- Chapters and sources use real navigable row lists, selected state and compact trailing affordance. Direct jump is subordinate to the chapter list. Source rows show only useful Provider/language/availability context for the same canonical chapter.
- Page recovery is a bounded floating card, never a white stripe spanning the canvas.
- Ajuste and Corte remain distinct; screen-on is a direct toggle; visual protection opens a compact page-tint choice card.
- Fonte presents verified alternatives for the same canonical chapter, preserving position and identity.
- P2P failure invokes the existing explicit global privacy permission flow, never implicit consent.
- Runtime internals stay outside chrome.

## Title in chrome
The brief approves a compact floating title/chapter pill while controls are visible, absent from the clean canvas. Long-title handling across reading modes remains a bounded refinement; it must not expand into an app bar. The top-right bubble is explicitly the visual-comfort action, represented by an eye icon; the middle pill expands into the remaining width.

## Card versus toggle
Brightness, chapters, fit and source use small cards. Crop has a small card in the illustrated state; a direct toggle may fit modes with simpler constraints. Screen-on is a direct toggle; visual protection uses a compact tonal-choice card. None becomes persistent preference UI.

## Recovery integration
Wording and action availability must reflect actual typed runtime errors. Mock retry succeeds locally, not as evidence of recovery. P2P review represents an existing global permission flow, not a new contract. External player is not illustrated because it is not established as part of standard canonical Reader.

## Fixture limits
Travessia is fictional authored reading content. Provider Aurora, Provider Nami and Provider Sora, their PT-BR/PT-PT/EN labels and availability are fictional local selector fixtures: no real Provider existence, compatibility or chapter evidence is asserted. Collection-based jump/navigation are demonstrative; Android must respect real identities, gaps and specials. Native protection, wake locks and actual display brightness are not executed by browser toggles.

See [Reader contract](../design.md#reader--session-surface--visual-review) and [mockup](../mockups/README.md).
## Floating refinement status
The requested floating composition, split pills, contextual contrast and list treatments are approved rules from the refinement brief. The thirteen revised frames remain subject to human visual review; this record does not mark the Reader milestone closed. Exact native overlay/elevation behavior remains Android implementation work. Session tools and persistent preferences retain their previous separation.

Light-theme refinement: contextual cards remain white with a subtle border and soft shadow. Opening a tool card adds a 32% dark neutral scrim over the rest of the Reader; closing it restores the undimmed canvas and chrome. The top back, title/chapter and eye-protection controls share a 48 px base height. The bottom tools pill uses 16 px horizontal and 4 px vertical inner padding, 20 px icons and 13 px labels with 16 px line height; each action retains a 48 px minimum touch height. Its scrollbar stays hidden and touch/trackpad/mouse scrolling remains available. A 16 px gap separates progress and tools pills. Architecture and Android remain unchanged; the visual board still requires human review.


### Reader — final visual polish
The top intentionally bridges its two 48 px circular controls with an elastic title/chapter pill: left-aligned title, smaller chapter, one-line ellipsis and equal 8 px gaps. The center pill fills available width; it never hugs short titles. Floating white surfaces have a neutral outline and controlled shadow so contrast does not depend on white, black or colorful page content. Contextual cards remain white above the canvas scrim.

Tools remain ordered Capítulos / Anterior / Próximo / Brilho / Ajuste / Corte / Fonte / Tela ligada / Proteção visual. The hidden-scrollbar rail has subtle edge fades, maintains mouse/touch scrolling and has no Mais action. Session toggles use a restrained tonal row treatment, stronger foreground and a small positional mark, not nested pills. The active frame starts at the rail end so screen-on and visual protection are both visible. Progress count remains exclusive to the separate progress pill.

Proteção visual means a session page-comfort tint, never a padlock, content lock or visibility toggle. Both eye controls share Desativada / Quente / Fria / Suave choices and the active state; the mockup tints only the illustrative reading page. A thirteenth frame shows the small protection card. No persistent preference changes.

Capítulos contains a vertically scrollable collection around the selected chapter, including a fractional entry, a special and a numbering gap. The secondary jump resolves an existing collection identity instead of synthesizing an integer chapter. Sources use distinct Provider / language / current-or-available rows, with concise selection marks. Aurora, Nami and Sora are fictional demonstration Providers, not compatibility evidence. Local page recovery overlays the retained illustrative page instead of replacing it; source recovery preserves loaded content and P2P remains explicit and actionable.

Reader still requires human visual approval. Android, runtime, persistent Settings and previous boards are unchanged. Native comfort-filter implementation and runtime source/recovery availability remain implementation work; this pass establishes only the visual contract and local fixtures.


Reader — listas compactas: remover chevrons das opções de capítulos, fontes e ajuste. Proteção visual não repete olhos nas opções; somente a opção selecionada mostra check. O olho permanece como identificação da ferramenta no chrome.
