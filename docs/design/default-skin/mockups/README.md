# Default Skin mockup

Open [index.html](index.html) directly in a browser. It remains the single runnable entry for this design family, with relative assets under `assets/` and no build or package installation. The initial composition shows the five Entry Flow frames, including Redefinir senha. Use “Ver Home e boards anteriores” to reach Home and the preserved boards for Reader, Canonical Details, Search and earlier surfaces. Search remains an approved reference; the latest Entry Flow, Home, Reader and Canonical Details boards await visual review. Biblioteca contains six frames: Biblioteca padrão, filtros avançados ativos, busca ativa, Filtros bottom sheet, nenhum resultado and biblioteca vazia. The frame labels sit outside the product UI.

The Biblioteca states use the approved Default Skin direction and the existing Android behavior as their product reference. Search filters the current library locally. Status and advanced filters remain active together; advanced selections apply immediately, and clearing them preserves status and search. The Filtros sheet presents ORIGEM, conditional LISTA, FORMATO and CATEGORIA. A cover tap opens the local Canonical Details representation; the Canonical Details module now represents that deep surface locally without resolving real runtime identities.

Kitsu, Manga, the example custom list and Sem categoria are fixtures for showing active filter states. The seven local covers are illustrative grid fixtures; they do not form a canonical catalogue, assert a real user's library, or prove a cover belongs to a particular canonical work or Provider. Runtime origins, custom lists and user categories remain dynamic. Canonical artwork is preferred when available. Fixture content is not a product promise or permanent data contract.

Earlier boards and their local navigation remain in the source. Keep those source blocks and module order when editing the mockup; later boards reuse earlier components. The project-root `index.html` is the Open Design delivery copy, while this repository path remains the design-family source entry. The documentation copies delivered in Open Design mirror these notes and the current specification.

This is visual evidence, not Android implementation. The mockup does not execute Android operations or prove Provider availability, canonical identity matching, preference persistence, scheduling, or navigation. Confirm the adaptive grid, system insets, font scaling, TalkBack behavior, touch targets and animations/transitions in the real Android implementation before treating those details as settled. No screenshot, rendering or device-verification evidence is claimed by this document update.

- `assets/`: CSS/JS, local font files, real cover/brand assets and lookup snapshots needed by or useful for the preserved prototype.
- `history/personalizacao/`: previous HTML preserved with relative links to shared font assets. It is historical evidence, not another current entry.

Global Aquisição and Folder Catalog code remains historical/unapproved; do not infer current requirements from it. [Current specification](../design.md) resolves status.

Local Segoe UI/Bahnschrift files came from the OpenDesign prototype. Their provenance and existing licensing notes are unchanged: they are presentation inputs, not an Android font choice, an open-source license claim or permission for separate font distribution. Brand masters remain in [docs/brand](../../../brand/README.md); the local lockup is a prototype copy/transposition of the official asset. Covers and service marks are referential sample content, not Tsuzuki-owned artwork. Acquisition provenance and limitations remain documented in the tension records.

## Busca and Biblioteca review board

The entry opens eight Busca frames and two Biblioteca empty-state review frames. The other four Biblioteca frames remain preserved in the module, hidden in this review composition. Busca is approved as a preserved reference. Biblioteca receives only the final approved polish. All frames stay reachable by scrolling, including narrow viewports; the board labels are presentation outside the app.

`assets/search-main.js` and `search-main.css` compose the Search board after the existing Biblioteca module. They reuse the existing Android shell and local artwork. Keep this module order. Search submits on Enter, clears to Discovery, executes recent queries and supports retry. The results frame begins with useful partial cards; local timers add cards and rating enrichment without replacing useful results with a full-screen spinner. Results now use populated format rails, sharing the exact 148 px card with Discovery. Titles use their natural one/two-line height and clamp with ellipsis; tap reveals the full title. Each result includes only cover, title, ratings and optional year. The former grid is superseded. These are local fixtures, not live Provider results or real scores. Configurar Providers and cover taps give destination feedback; their Android flows are not implemented here.

Search rating slots preserve native branding and scales. The Tsuzuki Rating mark comes from the existing `tsuzuki_rating.webp` asset. Artwork keeps its measured natural ratio; it is neither stretched nor cropped. Distinct IDs never merge by title; permanent provenance has been removed. Unknown-format grouping and unresolved ambiguity remain product tensions. A fixture does not certify an identity match.

Biblioteca uses the same `biblioteca-main.js/css`: normal header has search/filter icons, contextual search has clear/exit, and exiting retains status and advanced filters. Its badge excludes status. Grid, sheet and selection semantics remain unchanged. See [Search tensions](../tensions/search-tensions.md) and [Biblioteca tensions](../tensions/biblioteca-tensions.md).

No Compose or runtime change is included. No preview, rendering or post-generation test is claimed.


## Revisão final de Search

Discovery mantém seções editoriais; resultados usam Manga, Manhwa e One-shot nesta fixture. Grupos ausentes são omitidos. O frame de título longo usa uma obra real; o card não aumenta de altura. Rails e cards já exibidos persistem durante enriquecimento de ratings. A navegação inferior permanece fora da área rolável. Os dois estados vazios da Biblioteca demonstram foreground primário, supporting copy secundária e ações disponíveis. Seu header, grid, sheet e filtros não foram redesenhados. Fontes de novas capas e dimensões: [assets/search/SOURCES.md](assets/search/SOURCES.md). Search está aprovado como referência preservada.

## Canonical Details — single-artwork review board

`assets/canonical-details.js` / `.css` present ten focused review frames A–J: progress/title-logo, never started, many ratings, Details, Recommendations, continuous full chapter list, active scrubber, no dedicated backdrop, no chapters and degraded refresh. **Canonical Details awaits human visual approval.** Search remains approved and unchanged; Biblioteca's existing polish is preserved.

The Hero has one decorative artwork, no foreground cover. Official title-logo or accessible textual title leads a centered year/format pair, status, genres and all ratings in a horizontal rail. A long fade ends in the exact background. Compact recommendation cards still use Search's compact ratings rules; Details never uses +N. The many-ratings fixture includes seven illustrative values with native marks/scales and horizontal overflow.

Modes replace only the lower region. Ver todos opens continuous rows; Back restores the detail. Current rows/cards include a thin partial-progress indicator, read state recedes without repeated labels. Jump and sort use sheets. The active scrubber shows Cap. 642 by looking up that identity in the irregular fixture collection; dragging updates its label and release recenters/hides it. Fractions, specials and gaps are synthetic navigation examples, not a real publication inventory. Recommendations opens another local canonical-detail fixture with a return path. Reading, download, refresh and Library actions do not execute Android.

The degraded frame preserves useful content, offers retry and does not pretend that a secondary failure unloads the work. No chapters omits reading, not the Hero or bookmark. The existing total-error state remains recoverable. No-backdrop uses a clean background/textual title without another poster.

### Official fixture sources and geometry

| Asset | Intrinsic dimensions | Source |
| --- | --- | --- |
| `assets/canonical-details/fullmetal-title-logo.png` | 170×96 | [Official title-logo](https://www.fullmetalalchemistusa.com/img/common/header/logo.png) |
| `assets/canonical-details/fullmetal-hero-character.png` | 1600×1050 | [Official artwork](https://www.fullmetalalchemistusa.com/img/top/bg/character.png) |
| `assets/canonical-details/one-piece-backdrop.jpg` | 540×432 | [Toei artwork](https://www.toei-animation.com/wp-content/uploads/2019/02/one_piece_product.jpg) |
| `assets/canonical-details/fullmetal-alchemist-backdrop.jpg` | 900×591 | [Earlier official fixture, preserved](https://www.fullmetalalchemistusa.com/og.jpg) |

Dimensions were read from input files before styles. Decorative Hero cropping is intentional; title-logo keeps its full natural frame. The [Fullmetal Alchemist site](https://www.fullmetalalchemistusa.com/) and [Toei page](https://www.toei-animation.com/catalog/one-piece/) establish fixture provenance only, not permission for product redistribution or current manga/runtime sourcing.

The previous composition survives in [history](history/canonical-details-cover-hero/README.md), not as another current board. Preserve module order: Biblioteca, Search, Canonical Details. The entry and all referenced assets use local relative paths in both project copies. No rendering, preview or post-generation tests were performed. See the [specification](../design.md#canonical-details--visual-review) and [tensions](../tensions/canonical-details-tensions.md).

## Reader review board
The entry opens thirteen Reader frames. reader-main.js/css load after previous modules and reuse the supplied iPhone shell and staged layout primitives. Existing boards remain preserved; Ver board anterior is a presentation control outside the handset.

Center click / Enter / Space on the canvas toggles chrome. Page count appears once. Drag/scroll the action rail; frame 03 starts scrolled. Brightness, chapters/jump, fit, crop and source fixtures use closable cards. Screen-on and protection show selected states. Previous/next and jump update the session. Retry/source switch preserve position. P2P review explicitly simulates the existing global permission flow.

The canvas is fictional authored Travessia, not a real manga page/catalog work. No source availability, actual brightness, wake lock, native protection, canonical binding or network operation is proved. Visual review remains pending; no Android changes. See [Reader tensions](../tensions/reader-tensions.md) and [specification](../design.md#reader--session-surface--visual-review).
### Reader — floating refinement
All thirteen states remain in the entry: clean canvas, floating chrome, scrolled tools, Brilho, chapter list, source list, page recovery, source recovery, P2P, Ajuste, Corte and active screen-on/protection. Top controls are a back bubble, an expanded title/chapter pill and a right-side eye bubble for reading protection; visual protection also appears last in the tools rail, sharing state with the top eye control. Two inset bottom pills separate progress (count once) from horizontal actions. Drag the tools with touch or mouse, or scroll with a trackpad; frame 03 starts at the later tools.

Contextual cards stay white in the light theme, with a subtle neutral border and soft shadow above white pages. Chapters and source alternatives are navigable lists with selected states; the jump field is compact. Page failure uses a bounded recovery card instead of a white band. Provider Aurora / Provider Nami / Provider Sora and their language/availability are fictional fixtures, not installed Providers or evidence of canonical matching. Browser controls simulate session behavior only. Reader remains pending visual approval; previous boards and Android are unchanged.

Light-theme refinement: contextual cards remain white with a subtle border and soft shadow. Opening a tool card adds a 32% dark neutral scrim over the rest of the Reader; closing it restores the undimmed canvas and chrome. The top back, title/chapter and eye-protection controls share a 48 px base height. The bottom tools pill uses 16 px horizontal and 4 px vertical inner padding, 20 px icons and 13 px labels with 16 px line height; each action retains a 48 px minimum touch height. Its scrollbar stays hidden and touch/trackpad/mouse scrolling remains available. A 16 px gap separates progress and tools pills. Architecture and Android remain unchanged; the visual board still requires human review.


### Reader — final visual polish
The top intentionally bridges its two 48 px circular controls with an elastic title/chapter pill: left-aligned title, smaller chapter, one-line ellipsis and equal 8 px gaps. The center pill fills available width; it never hugs short titles. Floating white surfaces have a neutral outline and controlled shadow so contrast does not depend on white, black or colorful page content. Contextual cards remain white above the canvas scrim.

Tools remain ordered Capítulos / Anterior / Próximo / Brilho / Ajuste / Corte / Fonte / Tela ligada / Proteção visual. The hidden-scrollbar rail has subtle edge fades, maintains mouse/touch scrolling and has no Mais action. Session toggles use a restrained tonal row treatment, stronger foreground and a small positional mark, not nested pills. The active frame starts at the rail end so screen-on and visual protection are both visible. Progress count remains exclusive to the separate progress pill.

Proteção visual means a session page-comfort tint, never a padlock, content lock or visibility toggle. Both eye controls share Desativada / Quente / Fria / Suave choices and the active state; the mockup tints only the illustrative reading page. A thirteenth frame shows the small protection card. No persistent preference changes.

Capítulos contains a vertically scrollable collection around the selected chapter, including a fractional entry, a special and a numbering gap. The secondary jump resolves an existing collection identity instead of synthesizing an integer chapter. Sources use distinct Provider / language / current-or-available rows, with concise selection marks. Aurora, Nami and Sora are fictional demonstration Providers, not compatibility evidence. Local page recovery overlays the retained illustrative page instead of replacing it; source recovery preserves loaded content and P2P remains explicit and actionable.

Reader still requires human visual approval. Android, runtime, persistent Settings and previous boards are unchanged. Native comfort-filter implementation and runtime source/recovery availability remain implementation work; this pass establishes only the visual contract and local fixtures.

## Home: transição profunda e módulos opcionais

A entrada atual apresenta onze frames de Home. Abrir index.html diretamente no navegador a partir desta pasta. Os módulos assets/home-main.css e assets/home-main.js são editáveis; tokens, dados das obras e configurações dos frames estão centralizados. O botão externo Ver Reader e boards anteriores mantém acesso às referências sem adicionar controles de apresentação à interface do app.

Frames: completa, transição profunda, Continue lendo focado, várias Collections, sem Hero, sem Continue lendo, Collections-first, muitas Collections, título longo sem logo, title-logo oficial e novo usuário. Rolar dentro do telefone revela o conteúdo completo. Os frames de transição e scroll longo iniciam em uma posição contextual.

Ler e os cards de Continue lendo abrem retomada local demonstrativa; Detalhes abre resumo canônico local. Folder tiles abrem catálogo de consumo, com retorno à galeria da Collection. Rails horizontais aceitam touch, trackpad e foco de teclado. Criar Collection altera somente o estado em memória daquele frame. Nenhuma rede, persistência ou leitura em Provider real é executada.

Artworks, title-logo e capas oficiais são assets locais reaproveitados, com fontes em design.md. Recorte é reservado ao Hero atmosférico; capas de retomada mantêm o frame completo. Não foram sintetizados logos. Progresso e organização pessoal são fixtures. A Home refinada depende de revisão visual; Android e os módulos anteriores permanecem intocados.

### Home — polish final

Os onze frames foram preservados. Continue lendo explicita capítulo e página com barra proporcional; títulos extremos ficam limitados a três linhas; tiles de Folder usam grafismo abstrato; fade tem perfis locais de artwork. Assets: `assets/home-main.css` e `assets/home-main.js`. Progresso e Collections continuam fixtures. Home aguarda revisão humana; sem implementação Android e sem preview ou testes após a gravação.


### Continue lendo — correção do componente horizontal

A apresentação anterior com mini posters verticais está superseded. Continue lendo usa uma rail horizontal de cards horizontais: capa pequena inteira à esquerda; título, capítulo atual, página atual / total quando conhecido e barra de progresso à direita. As barras compartilham geometria e marcador proporcional à posição de leitura. O card inteiro retoma diretamente a leitura, sem botões internos nem passagem por Details. Não inclui ratings, gêneros, status editorial ou Provider. Esta família de retomada permanece distinta do catálogo e das pastas 16:9. O componente compartilhado atualiza todos os frames em que aparece, sem alterar Hero, Collections, navegação ou estados vazios. Progresso do mockup é demonstrativo; Android permanece fora do escopo.


## Entry Flow / primeiro acesso

A entrada atual apresenta cinco frames: Welcome, Entrar, Criar conta, Esqueci minha senha e Redefinir senha. `assets/entry-flow.js` e `entry-flow.css` reutilizam o shell iPhone fornecido e o lockup oficial local. Welcome e exclusivo da primeira instalação no contrato do produto; o board o mostra para revisão, sem simular uma tela recorrente de startup.

Visitante e login demonstrativo levam a Home existente. Cadastro mostra confirmação de email e recuperação retorna uma resposta generica. Campos possuem validação local, revelação de senha, estados de processamento e navegação de retorno. Nenhum email e enviado, conta criada ou credencial verificada com servidor. Email e senha não são persistidos. O marcador de conclusão em sessionStorage e somente demonstrativo.

A ação Ver Home e boards anteriores e apresentação externa ao produto. A referencia permite retornar a revisão de Entry Flow. Os modulos anteriores permanecem intactos. Design e tensoes: [especificação](../design.md) e [Entry Flow](../tensions/entry-flow-tensions.md). Android permanece fora do escopo. Revisão visual pendente.

Redefinir senha demonstra a etapa após o link de recuperação, com nova senha, confirmação, erro de divergência, salvamento simulado e sucesso com retorno a Entrar. Nenhum token é validado ou senha alterada no serviço real.