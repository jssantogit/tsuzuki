# Tsuzuki Default Skin — approved specification

## Status and scope

**Settings milestone: visually complete, approved and closed.** This document consolidates the approved direction, including the final Settings work and the earlier Default Skin families. It supersedes contradictory iteration notes. Approval concerns architecture, presentation and relationships; it does not certify Android implementation, exact reusable tokens, accessibility or physical-device behavior.

The [editable mockup](mockups/index.html) and [usage notes](mockups/README.md) preserve the existing source. The initial board presents the five Entry Flow frames, including password reset. Home, Reader, Canonical Details and earlier boards remain accessible as references. Search, Canonical Details, Reader, Home and Entry Flow are visually approved. [Earlier detailed reference](references/history/default-skin-before-settings-closure.md) preserves pre-closure material; it is historical where newer decisions supersede it.

## Identity and product boundaries

- Canonical work/chapter identity is independent of Source, metadata and Provider identity. Preserve the Reader invariant in [DESIGN.md](../../../DESIGN.md).
- **Provider is the only public extensibility concept.** Providers may combine capabilities: catalogue, metadata, reading, HTTPS, torrent discovery, Debrid or direct P2P. Do not create exclusive Provider types or expose legacy Extensions/Add-ons as the new public model.
- Provider-specific credentials, endpoints and preferences are descriptor-driven and configured inside Provider Detail. Nyaa discovers torrents; that does not mean it downloads them.
- Acquisition coordination can exist internally. Do not expose global Debrid-only/P2P-only/Debrid-then-P2P route controls in this family. The old Aquisição board is a **non-approved exploration, superseded before consolidation**.
- Direct-P2P consent is Host-owned, global, opt-in and **off by default**, under Privacidade e segurança. Temporary acquisition files belong to Dados e armazenamento. P2P activity has no new public location defined by this milestone.
- Brand masters remain in [docs/brand](../../brand/README.md). Use the existing Tsuzuki lockup; never approximate the wordmark or substitute Mihon identity. Brand palette is not automatically the interface palette.

## Settings information architecture

Ordered first-level destinations: **Conta → Geral → Conteúdo → Downloads → Dados e armazenamento → Privacidade e segurança → Diagnóstico → Avançado → Sobre**. This is navigation order, not a sequential flow. Tracking manages external tracking accounts; Providers manages extensibility and Provider configuration. Do not restore the old flat Advanced organization.

| Destination | Responsibility |
|---|---|
| Conta | Tsuzuki account and account overview; external Tracking identity stays separate |
| Geral | General preferences and existing personalization/appearance drill-downs |
| Conteúdo | Existing content, Library, reading, Tracking and Provider responsibilities, with dedicated details |
| Downloads | Network/files, parallelism, auto-download and downloaded-chapter cleanup |
| Dados e armazenamento | Real storage-directory selection, usage, cache, temporary files, backup/restore and CSV export |
| Privacidade e segurança | Device authentication, notification privacy, secure screen, global direct-P2P permission, conditional telemetry |
| Diagnóstico | Logs, technical information and read-only technical tools |
| Avançado | Remaining legitimate system, network, maintenance and compatibility tools |
| Sobre | Tsuzuki identity/version, project/upstream attribution and generated licenses |

## Approved visual language and components

### Hierarchy and density

Settings is calm and low-density: light background, large title, generous spacing, small semantic groups and white rounded shared surfaces with restrained internal dividers. Group labels are uppercase. Omit redundant labels when a page has only one obvious group, as in Diagnóstico. Do not create dashboards, nested decorative cards, icon-per-heading decoration or attention-colored whole cards.

Deep screens place Back above the title and omit bottom navigation. Preserve reading order and normal vertical flow. Long titles and data wrap; warnings and lists grow naturally. Avoid fixed two-line warning structures or clipped long names.

### Rows and controls

| Behavior | Presentation and contract |
|---|---|
| Immediate boolean preference | Switch with persistent label; subtitle only where helpful |
| Navigation or contextual selection | Label, current value/summary and chevron |
| Immediate operation | Clickable row/text action without a navigation chevron; cookies/WebView are examples |
| External or Android-system action | Discreet external/system affordance; do not design a fake internal destination |
| Single selection | Selection sheet with one selected value and the real available options |
| Multi-selection | Ordinary selection rows/checkboxes; explicit confirmation where the flow requires it |
| Include/exclude | One tri-state per row: Neutro / Incluir / Excluir; staged edits with Limpar / Aplicar |
| Dependent preference | Visible disabled control with preserved value when the real parent is off; do not erase selection |
| Missing capability/build feature | Omit or disable according to the real contract, not a generic dependency rule |
| Contextual overflow | Floating anchored menu; never expands the source card's height; outside tap/Escape closes |
| Destructive action | Separate operation with established confirmation and restrained destructive treatment |
| Primary persistence/action CTA | Fully visible reserved footer outside scrolling content and above system safe area; disabled when selection is invalid |

Sheets have a close/cancel path, return focus to their trigger and keep long content scrollable. Cancel does not apply staged changes or grant consent. Supporting copy reflects the actual control state. Status is text separate from neutral capability chips; do not communicate solely by color.

### Prototype values versus implementation tokens

Observed light-role values in the mockup: background `#F7F7F8`, white groups `#FFFFFF`, primary text `#17181A`, secondary/section text `#50565C`, dividers `#D6D9DC`, icon surface `#E8EAED`. Older dark references use `#0B0C0D` background, `#1C1E1F` groups and `#F5F5F5` primary text. These are traceable visual evidence, not a new globally certified palette.

Observed CSS geometry: horizontal inset 16px; group gap 24px; row padding 12px; group radius 16px; icon-container radius 8px; deep header/content spacing 32px; title 36px/700, row 16px, supporting copy 14px, section label 12px/700. Segoe UI regular/bold and Bahnschrift are local prototype fonts, **not approved Android font-distribution or dp/sp contracts**. Exact global tokens, type family, contrast measurements and scaling still require implementation decisions and checks. Handset frame, system bars and board background are presentation infrastructure, not product tokens.

## Biblioteca principal

This is the main navigation surface for the user's unified library. It is distinct from **Configurações → Biblioteca**, which remains the approved Settings hub and its preference drill-downs. This addition does not reopen or change the visually complete Settings milestone, and it does not define the canonical work detail.

The main screen keeps the Default Skin direction: light background, low density, large title, generous whitespace and few semantic surfaces. The covers dominate the first view. Its persistent bottom navigation is **Início · Busca · Biblioteca · Perfil**, with Biblioteca selected. Search remains a local state of Biblioteca and does not change that selection.

The normal header presents the large “Biblioteca” title, search and advanced-filter actions, without a permanent search field. The field appears only in contextual search. The advanced-filter badge counts selected origin, list, each format and category, never status. A single light, horizontally scrollable status rail follows: **Todos · Lendo · Planejado · Concluído · Em pausa · Abandonado**. Selection is clear through type weight and a restrained indicator. With no advanced filters, the cover grid follows directly. When filters are active, a quiet contextual line shows at most two removable filter chips and a `+N` control that reopens Filtros; status never appears in this line.

The responsive grid uses naturally proportioned, full-frame covers with subtle corners and consistent gaps. Each cover is the complete tap target and signals navigation to the existing canonical detail. It carries no permanent title, status, source, format, progress, rating, menu, button or decorative badge. The seven local covers in this prototype are visual fixtures only, not a canonical catalogue, real library inventory, or evidence that a Provider or volume is available. Artwork should represent the canonical work where it exists.

Search expands in place, has distinct clear and exit actions, filters only the current library as text is entered, and preserves status and advanced filters. It adds no global or external results, history or suggestions. Filtros is a modal bottom sheet: ORIGEM is single-select and reflects runtime availability; LISTA appears only for a selected external origin with usable custom lists, never as a duplicate status rail; FORMATO is multi-select and omits `Unknown`; CATEGORIA is single-select with Todas, Sem categoria and real user categories. Demonstrative values such as Kitsu, Manga, an example list and Sem categoria may exercise the board but do not define a fixed Provider set, a permanent list or category catalogue, or real user data.

Filter changes apply immediately. Limpar filtros clears only advanced filters and preserves status and search; Concluído closes the sheet without applying a separate draft. Dismissing the sheet also preserves choices already made. A genuinely empty library has a calm “Sua biblioteca está vazia” state. A query or active filter with no matches has a separate “Nenhuma obra encontrada” state, keeps the applied choices, and may offer Limpar filtros. Loading is discreet, accessible and has no false covers.

The current board shows six compositions: Biblioteca padrão; filtros avançados ativos; busca ativa; Filtros open; nenhum resultado; and biblioteca vazia. The HTML's local interactions illustrate this surface and signal the existing canonical-detail destination; they do not implement Android operations. The Biblioteca architecture is preserved as approved. Its final polish is limited to readable empty states, a naturally scrolling status rail and the advanced-filter count. This does not certify Android implementation.

Open Android questions remain for adaptive grid behavior, system insets, font scaling, TalkBack, touch targets and animation/transitions. These need evaluation in the real Android implementation. Prototype CSS measurements and local fonts are not dp/sp, accessibility or font-distribution contracts.


## Busca principal

**Search architecture and visual direction approved.** This section follows the requested product architecture and current Search contracts. It does not authorize Compose changes before visual approval.

Busca is a root destination in **Início · Busca · Biblioteca · Perfil**. Its large title is immediately followed by a permanent **Buscar mangá** field with a search icon, clear only while text exists and Enter submission. There is no Back action at this level. Biblioteca keeps its separate contextual search.

With an empty query, show Discovery. Recent searches appear only when present, as a light horizontal rail with Limpar; tapping executes the query. The runtime retains at most 20, which is not a fixed visual quantity. Content sections appear only when populated, in this order: Em alta, Populares, Mais bem avaliados, Mais favoritados, Atualizados recentemente. Each is a heading and horizontal compact-card rail, without an enclosing surface or repeated empty notices.

Discovery and Search Results share one compact card: 148 px reference width, the same uniform cover area (full-frame image contained without cropping), typography and spacing. Titles occupy their natural one- or two-line height, clamp at two with an ellipsis, and remain readable in full through the card action. No smaller title type or marquee. Results contain only cover, title, compact ratings and year; omit absent years. No synopsis, library status, menu, action buttons, provenance or format inside the card. The former two-column results grid (and interim three-column adjustment) is superseded by horizontal format rails. Native branding and scales remain visible. Compact surfaces have at most two rating slots: Tsuzuki Rating first when present, then one native rating; otherwise at most two native ratings. Remaining native scores use +N. Do not average, convert or imply fixture ratings are live values.

Identity consolidation requires verified identity. Never merge by title. Distinct unresolved candidates may keep the same title and stay separate. Do not add permanent Provider/origin metadata to resolve ambiguity. If cover, year, rating or format section cannot communicate a genuine distinction, retain it as a future product tension. Opening a card represents the existing canonical-detail flow; the prototype does not invent a canonical ID or certify reconciliation.

Results render only populated groups, initially in stable order: Manga, Manhwa, Manhua, Webtoon, One-shot, Novel, Doujinshi. No counts or format repeated on the card. Unknown formats must not be dropped; Outros is a provisional fallback, pending product validation. Discovery stays editorial, not grouped by format. New groups append without moving existing groups, new cards append within their rail, and rating updates retain rail scroll position. Search and ratings arrive progressively. Initial waiting may show discreet loading, but once useful results exist they remain while subsequent results and rating enrichment arrive. A result frame demonstrates that intermediate state. Empty results preserve query and use **Nenhum resultado encontrado / Tente outra busca.** Error is separate: **Não foi possível concluir a busca**, neutral explanation and **Tentar novamente**. Partial useful results need not be erased to communicate failure.

When no catalog-search Provider is active, use **Nenhum Provider com busca de catálogo está ativo**, **Ative ou instale um Provider compatível para pesquisar obras.** and **Configurar Providers**. This represents the public Providers destination. Legacy hardcoded Kitsu/MAL or Integrações wording is superseded for this presentation, without modifying the Android code in this round.

The board includes Discovery, Discovery without recents, loading, results, no results, error and no compatible Provider. The board additionally presents a long-title result and the two Biblioteca empty states for polish review. Real-work covers and years, recent queries and illustrative ratings are documented fixtures, not a real catalogue, Provider availability or verified identity. Mockup interactions execute local state transitions and represent Android destinations. No runtime identity semantics were changed. See [Search rationale](tensions/search-tensions.md) and [mockup usage](mockups/README.md).

### Biblioteca in Settings — approved scope retained

Configurações → Biblioteca remains the operational hub with its existing three groups and eight rows. Configurações → Biblioteca and Personalização → Layout → Biblioteca keep their separate destinations. Its category-default choices remain Sempre perguntar and Padrão; internal identifiers and demonstration-only copy stay out of product UI. Atualização automática retains its approved detail screen and interval choices: Desligado, 12, 24, 48, 72 horas and Semanal (168 hours). Wi-Fi, rede não limitada and carregamento are independent restrictions; when the interval is off, dependent rows remain visible but disabled and keep their values. Included/excluded categories use separate three-state choices; they start unset, only Padrão do sistema is assumed in the documented fixture, and exclusion takes precedence. The metadata switch starts off. Four independent Smart update criteria start selected: skip works with unread chapters, skip works whose reading has not started, skip completed works, and estimate the next release date. Multi-selection keeps the approved confirmation/cancel semantics where that existing Settings flow stages changes; summaries reflect current values, and return preserves focus and scroll. These decisions apply only to Settings preferences; Biblioteca principal uses its own immediate filter semantics described above. Android persistence, scheduling and capability behavior remain implementation concerns.

## Downloads

Root is a hybrid hub. Rede e arquivos exposes Wi-Fi, CBZ and splitting tall images (existing explanation: “Melhora o desempenho do leitor”). Performance uses **Fontes simultâneas**, range 1–10, and Páginas simultâneas, range 1–15; higher page parallelism may increase resource use. Value 5 is a fixture.

Downloads automáticos and Limpeza automática have dedicated details. Baixar enquanto lê uses the existing disabled/next 2/3/5/10 unread-chapter choices. Auto-download dependencies preserve unread-only and category values when disabled. Categories use separate include/exclude states; cleanup exceptions use ordinary multi-selection.

Remover após leitura summarizes exactly its selected option (for example Último capítulo lido). Marked-chapter protection copy changes with its switch: “Capítulos marcados permanecem protegidos.” / “Capítulos marcados também podem ser removidos.” Storage, backup and Host P2P controls are outside Downloads. [Rationale](tensions/downloads-tensions.md).

## Dados e armazenamento

Root links Local de armazenamento, Uso do armazenamento, Backup e restauração and Exportar biblioteca. Storage selection is Android OpenDocumentTree with persisted URI/access grants, not an internal/SD chooser. Detail shows friendly current folder/path or a clear empty state and Alterar local/Escolher pasta. Use a hierarchical path; omit redundant text announcing the system picker.

Storage usage shows simple proportional usage, available/total space and readable location. Accept multiple detected storages. Cache cleanup is an operation; automatic chapter-cache cleanup is boolean. **Arquivos temporários** explains files managed by Tsuzuki during temporary acquisitions. No acquisition-route selection or P2P consent here.

Manual backup creation and restoration are distinct actions. Automatic frequency retains Desativado, 6, 12, 24, 48 hours and Semanal; last backup is information. Creation selects real existing components before CreateDocument; restoration selects a file first, validates it, then presents restorable components. Do not add a destination/name form or merge/replace mode.

Creation Biblioteca: Mangás, Capítulos, Tracking, Histórico, Categorias, Dados de leitura fora da biblioteca. Configurações: Configurações do app, Repositórios de extensões, Configurações de fontes, Configurações privadas. Capítulos/Tracking/Histórico/outside-library reading data depend on Mangás; private settings depend on app OR source settings. Preserve existing selections while dependent controls are unavailable. Restorable components are Biblioteca, Categorias, Configurações do app, Repositórios de extensões and Configurações de fontes. Valid selection gates the CTA.

Invalid backups block restoration. Missing components appear in naturally growing independent lists of sources and Tracking services; omit empty groups, support many/long names, and permit continuation only where validation allows. The legacy backup component taxonomy is preserved for functional fidelity, **not** a new public Provider taxonomy or guarantee that repositories/settings/secrets of the Provider Platform are backed up. That migration remains TBD.

CSV has only Título, Autor, Artista. Título gates the other fields according to the real implementation. **Exportar CSV** stays fully visible above the safe area, then invokes Android CreateDocument. Final Tsuzuki filename is implementation work. [Rationale](tensions/dados-armazenamento-tensions.md).

## Privacidade e segurança

Groups: Segurança, Privacidade de rede, Dados e telemetria. Device authentication controls appear only when a compatible authenticator exists. No own PIN/password. Bloquear após depends on Bloqueio do app, with real choices Sempre, 1/2/5/10 minutos, Nunca; Sempre requires unlocking on return and Nunca disables return-based locking. Secure screen is a selection row with **Sempre / Modo anônimo / Nunca**, never a switch or an added incognito feature. Notification-content privacy is boolean.

P2P direto is global privacy consent. Copy: “Permite conexões P2P diretas quando um Provider precisar. Seu endereço IP pode ficar visível para outros participantes da rede.” It starts off. Enabling opens **Permitir P2P direto?**, explaining direct connections and IP exposure, with Cancelar/Permitir. Only Permitir enables it; cancel, dismissal and Escape leave it off. Disabling is immediate. No routes, ports, tracker, Debrid or Provider-specific controls.

Telemetry is shown only if included in the build: Relatórios de falhas and Dados de uso do app. No disabled placeholders or Firebase section title. [Rationale](tensions/privacidade-seguranca-tensions.md).

## Diagnóstico

Hub contains only Logs and Informações técnicas, without a redundant DIAGNÓSTICO label. Logs follows start → reproduce → stop → share. A small status block communicates stopped/active; detailed capture automatically ends after 15 minutes. Start and Stop are mutually state-dependent; share remains available in either state. No verbose-logging switch, uploads, report history or crash controls.

Limpar logs requires confirmation and removes Tsuzuki diagnostic data and the persisted last crash, **not global Android Logcat**. Sharing generates `tsuzuki_logs.txt` and invokes Android sharing. Near share: “Revise o relatório antes de compartilhá-lo. Logs e informações de falha podem conter dados fornecidos por serviços ou componentes externos.” Do not promise complete anonymity or sanitization.

Technical information groups real app/build, Installation ID, Profile Verifier compilation status, WebView version, model, Android and detected One UI/MIUI. Installation ID has a small explicit copy affordance beside the value and a separate trailing regeneration action. Do not add unrelated hardware metrics. Tools drill down to read-only Workers and backup schema.

Workers keeps Em execução / Na fila / Concluídas, discrete empty states, variable records with ID/tags/state/available timing and Copy. No editing/cancel/run controls. Schema is generated, monospaced, scrollable and copyable, never editable; the prototype excerpt is not the complete generated schema. [Rationale](tensions/diagnostico-tensions.md).

## Avançado

Groups: Sistema, Rede, Manutenção, Compatibilidade. System rows invoke Android notification settings, battery-optimization exemption flow and external dontkillmyapp.com. They are not app-controlled switches or internal replacement screens.

Rede retains cookie/WebView clearing as immediate operations **without chevrons**, DNS-over-HTTPS selection and editable/resettable User Agent. DNS choices are Desativado, Cloudflare, Google, AdGuard, Quad9, AliDNS, DNSPod, 360, Quad 101, Mullvad, Control D, Njalla, Shecan. No custom DNS. User Agent has a compact text sheet, validation, Salvar and reset only when custom. Changes/reset communicate required restart.

Manutenção: Reindexar downloads (rebuild view, not deletion), Atualizar capas da biblioteca, Redefinir configurações do leitor em cada série (individual reading/orientation, not all global Reader settings), Limpar dados fora da biblioteca. Dedicated cleanup lists actual dynamic SourceWithCount records, count and checkbox; do not rename a legacy Source as Provider. Use coherent content-source fixtures such as MangaFire/MangaBall/MangaDex, not metadata-only Hikka. Preserve select-all/invert and disabled Remover when selection is empty.

Cleanup confirms removal; **Manter obras com capítulos lidos** starts on. Turning it off shows “Capítulos lidos e o progresso de entradas fora da biblioteca poderão ser perdidos.” Keep attention restrained. Compatibility contains the existing high-quality renderer boolean and ASCII-filename preference.

Do not port source-bound update-library-titles, extension installer modes/trust controls, old advanced hub or onboarding. Debug/logs, telemetry, P2P consent, Provider settings, storage/backup and Downloads retain their assigned homes. [Rationale](tensions/avancado-tensions.md).

## Sobre

Short identity screen: large title, centered official horizontal **ic_tsuzuki_lockup** outside a card, whitespace and discreet dynamic version. Stable/FOSS/Nightly/Debug may include build/commit/date only when provided. Version has no hidden debug-copy, developer mode or easter egg.

Projeto links to [Tsuzuki source](https://github.com/jssantogit/tsuzuki) and [Mihon upstream](https://github.com/mihonapp/mihon), with external indicators. Mihon is attribution, without competing branding. Código aberto opens internal Licenças: preserve AboutLibraries-generated dynamic list and detail sheet, not hardcoded dependency taxonomy or invented filters/search. Current sample is documented, not a complete legal catalog.

Legacy onboarding is intentionally absent until rewritten for Tsuzuki concepts. No new update/changelog/help/contact/social functions. [Rationale](tensions/sobre-tensions.md).

## Providers and earlier approved families

- Providers preserves Installed/Discover chips, shared cards and neutral capability chips. Disabled is normal user choice; Blocked/Invalid have explicit status and no usable enablement. Catalog update can offer Atualizar; installed-newer, revoked and origin-conflict do not offer downgrade/install/update/takeover. Detail may explain without exposing runtime IDs. Repositories requires explicit trust confirmation of the presented key/fingerprint; URL alone grants no trust and the contextual menu floats without changing card height. Synthetic Atlas Manga is only a design fixture. [Provider rationale](tensions/providers-tensions.md).
- Tracking manages external accounts independently of Provider enablement/configuration. Connected/disconnected Kitsu and transient authentication/disconnect confirmation share one family. [Tracking](tensions/tracking-tensions.md).
- Conta has related login/create/reset/connected states; reset has no password field, connected overview has identity/sync and separate confirmed sign-out. Account sync detail is not newly specified here. [Conta](tensions/conta-tensions.md).
- Perfil owns personal identity, metrics, current-reading carousel and rails; global navigation is Início / Busca / Biblioteca / Perfil, with Settings via Perfil gear. Sample metrics do not define calculations. [Perfil](tensions/perfil-tensions.md).
- Preset selection belongs to Personalização → Preset visual; current skin in Aparência is context. Theme is not a preset. Reading/Webtoon and Library/automatic updates keep their approved specialized drill-downs and visible preference dependencies. [Personalização](tensions/personalizacao-tensions.md), [Aparência](tensions/aparencia-tensions.md), [Preset](tensions/preset-visual-tensions.md), [Leitura](tensions/leitura-tensions.md), [Biblioteca](tensions/biblioteca-tensions.md).
- Collections administration follows Collection → Folder → List, preserving List Builder, include/exclude lookup, Advanced Filters and preserved query. Mangás/Seinen Folder Catalog remains non-approved. [Collections](tensions/collections-tensions.md), [Folder history](tensions/folder-catalog-tensions.md).
- Older Integrações/MyAnimeList public categorization is historical where replaced by Providers. Preserve account/configuration dependencies when applicable; do not revive a second public extensibility taxonomy. [Historical rationale](tensions/integracoes-tensions.md).


## Canonical Details — visual review

**Refined board visually approved and closed.** Design, mockup and documentation only; no Android, identity or Reader implementation changes.

### Approved architecture and runtime boundary

Canonical Details is a deep surface without bottom navigation. One stable Hero precedes contextual reading actions and the modes **Capítulos / Detalhes / Recomendações**. Switching a mode replaces only the lower content, preserving the mounted Hero. Search, Settings and public navigation stay approved and unchanged.

`CanonicalTitleScreen`, `CanonicalTitleDetailScreen`, `CanonicalTitleScreenModel` and `ChapterCoverage` remain functional references, not layout references. Local/cache content may appear first and receive metadata, ratings and chapters progressively. Preserve canonical identity, progress, Library membership and downloads. Reported chapter counts are not availability evidence. Matching diagnostics, IDs, confidence and source machinery do not belong in consumer metadata.

### Single-artwork Hero — refinement for review

Use **one work artwork representation**, never backdrop plus foreground vertical poster. Artwork begins behind the status bar; safe Back and overflow controls overlay it. The atmospheric transition ends in the exact Default Skin background `#F7F7F8`, with no hard edge, framed card or disconnected identity block. Use a long, softly graduated tonal veil: title identity, editorial metadata and ratings participate in the lower artwork transition. Keep meaningful text in normal flow; only artwork/fade are decorative layers.

Trustworthy official title-logo artwork may occupy the central title role, centered with natural proportions and a semantic accessible textual title. Otherwise a prominent centered textual title occupies the same role. Never synthesize official logos or require artwork to make the surface usable.

**Editorial Pair + Status** replaces the concatenated year · format · status line. Year and format form a centered pair; publication status sits on its own quieter line; genres form a separate typographic layer below. No Ano/Formato/Status labels or heavy chips. Omit missing fields and rebalance naturally, without dashes, Unknown or N/A. The title dominates these secondary facts.

If no dedicated backdrop is available, keep a single atmospheric artwork concept using credible cover-derived decorative treatment, or use a clean background with a prominent textual title. Never restore a separate foreground cover. The current fallback uses a clean neutral background rather than enlarging a low-resolution cover.

### Complete ratings and primary actions

Canonical Details exposes **all available ratings**, unlike compact Search/Discovery cards. Tsuzuki Rating leads when available, followed by every native rating with recognizable identity and original scale. Use a lightweight single-line horizontal rating rail, with scroll and a continuation cue when needed. Do not wrap into a large multi-line block, normalize, average, truncate the inventory or use +N. The many-ratings frame demonstrates overflow with seven illustrative values. Compact recommendation cards continue the approved compact-surface slot rule.

A short synopsis follows identity and ratings, with a discreet **Ler sinopse** expansion. **Começar a ler** / **Continuar lendo** remains dominant. No textual page/chapter progress beneath the CTA. Membership is an independent compact bookmark/check control next to reading, with selected state and accessible action semantics, no permanent Na biblioteca/+ Biblioteca label. If nothing is legible, omit the reading CTA while retaining bookmark and the modes. Refresh stays secondary in overflow.

### Compact chapters and full-list navigation

The initial Capítulos mode shows **Capítulos / Ver todos** and a small contextual rail: first relevant chapters when never started; chapters around progress while reading; latest relevant chapters when caught up. No thousand-item carousel or explanatory algorithm copy. Refined reference cards are 128px wide with at least 104px height, 8px internal rhythm and 16px internal padding.

State semantics are shared across rail and list: read visually recedes, unread has normal contrast, current is distinct and may say **Em leitura**, partial progress is a thin visual bar, and downloaded state is a compact functional icon. Do not repeat Lido/Não lido/ Baixado labels. Accessible names retain those states.

**Ver todos** opens a dedicated continuous row list, not a stack of cards. Rows share a 76px reference base geometry. Current uses a restrained positional accent, slight tint, stronger title, Em leitura and a thin progress bar, without breaking the list into a bordered card. Download actions remain trailing and compact. Preserve actual display identities, fractions, specials, gaps and download states; never infer a continuous integer chapter sequence.

Ascending/descending order and jump remain discreet header actions with sheets. Missing jump targets receive an inline explanation. No permanent Ir para capítulo atual footer. Scrubber targets **position in the actual ordered collection**, not arithmetic chapter number. During drag, its floating label updates with the destination identity; release recenters the list and hides the label. A reserved side lane prevents the thumb/label from covering row download actions. Keyboard range input and direct jump remain alternative access paths.

### Editorial Details and Recommendations

Details keeps clean semantic sections: Sinopse, Criação, Publicação and Gêneros, rendering reliable available data without individual value cards or implementation internals. Hero contains only the high-value summary. Metadata provenance remains available conceptually; it is not a permanent technical source list.

The mode heading is **Recomendações**. Related-work fixtures reuse the approved Search compact catalog family: 148px reference width, full-frame cover containment, natural one/two-line title height with two-line ellipsis, compact native ratings and minimal metadata. Tapping a recommendation opens its canonical-detail presentation with a return path. The suggesting Provider does not own the work identity. Final sourcing, ranking, deduplication, caching and APIs remain **runtime TBD**, not solved by this board.

### Loading, empty and degraded states

Keep useful Hero, metadata and chapters during background enrichment and secondary errors. Never replace useful content with a full-screen loading state. The degraded frame retains reading and chapters while showing **Não foi possível atualizar agora / Tentar novamente**. Total failure retains Back and **Não foi possível carregar esta obra / Tentar novamente** as an existing interactive state. No technical exception text.

**Nenhum capítulo disponível** is local to the chapter region. Hero, metadata, modes and bookmark survive; no disabled Leitura indisponível CTA or obsolete Add-ons wording.

### Review frames and evidence classification

Focused frames: A reading in progress with official title-logo; B never started; C all ratings/overflow; D editorial Details; E Recommendations; F continuous chapter list with read/current/unread and partial progress; G active scrubber; H no dedicated backdrop/textual title; I no chapters; J degraded refresh with useful content retained. Search remains an accessible approved reference; the previously approved Biblioteca polish remains preserved.

- **Approved visual contract:** deep navigation, stable Hero and three modes, reading priority, compact membership action, canonical identity invariants and Search's compact card family. This single-artwork/editorial-pair refinement is visually approved.
- **Fixture-only demonstration:** real-work artwork, title-logo, illustrative native ratings, Library membership, progress, synthetic irregular chapter collection and related-work choices. They do not establish catalogue availability, identity verification or Provider support.
- **Runtime capability TBD:** trustworthy Provider-neutral artwork/title-logo sourcing and recommendation sourcing/deduplication/ranking. Official adaptation art does not assert manga metadata sourcing.
- **Future implementation concern:** Android tokens, safe insets, contrast over asynchronously changing artwork, font scaling, focus/semantics, virtualized chapter rows, accessible scrubber and preserving useful content during progressive updates.

The earlier duplicated-cover Hero and concatenated metadata are superseded. A bounded [source snapshot](mockups/history/canonical-details-cover-hero/README.md) preserves the previous composition for rationale only. See [Canonical Details tensions](tensions/canonical-details-tensions.md) and [mockup usage](mockups/README.md).


## Remaining implementation work

Map CSS evidence to Android tokens/dp/sp and fonts; implement real preferences, conditional capabilities/build features, pickers, validation, jobs and dynamic catalogs. Measure contrast, font scaling, touch targets, insets/IME, focus, long lists and reduced motion on the actual implementation. Preserve functional repository contracts rather than assuming prototype adapters run Android operations. Backup migration to Provider Platform, final CSV filename and operational P2P activity location remain explicit open work.

No new Settings exploration is required by this milestone. The next meaningful UI work is the implementation handoff, not inventing another Settings page.



### Bounded Canonical Details refinement — visually approved

Architecture and identity hierarchy remain unchanged. The Hero fade retains more artwork behind the title before dissolving into the exact neutral background. The no-backdrop fixture uses its existing cover as a softly blurred atmospheric source, with no foreground cover or invented artwork. Compact chapter cards keep their 128px width with a shorter 104px minimum height. Frame D opens scrolled to the existing Details mode so synopsis, creation and publication information can actually be reviewed; the same Hero remains mounted above. The active scrubber shows the real destination label live beside the thumb, hides it on release and recenters by collection position, including fractional/special/gapped chapters. These refinements are visually approved; no Android implementation is implied.

## Reader — session surface / visual review

**Conceptual contract and visual board approved. Reader is visually closed. This round does not implement Android.**

Reader is a deep, reading-first surface without bottom navigation. Center tap reveals floating overlays without changing canvas geometry. Full-width top/footer bars are superseded: a circular back bubble, a flexible title/chapter pill filling the available middle space and a right-side eye bubble for reading protection form the top composition. Visual protection is a session page-tint/comfort tool, not a lock or visibility toggle. The top eye bubble and last tools-rail item open the same small tonal-choice card and reflect its selected state.

Bottom chrome comprises two separate floating pills: progress slider + current/total count once, then Capítulos, Anterior, Próximo, Brilho, Ajuste, Corte, Fonte, Tela ligada, Proteção visual. The top-right eye bubble also opens visual protection. The tools pill scrolls horizontally by touch, trackpad or mouse drag; labels use the shared compact chrome scale and the next action peeking at the edge makes continuation discoverable; the scrollbar is hidden without disabling scrolling. No Mais button, large panel or draggable sheet. Inset rounded surfaces, subtle elevation and light translucency leave the reading canvas dominant; chrome does not form a permanent navigation slab.

### Session versus persistent preferences
Configurações → Leitura owns durable defaults: reading mode/direction, double-page behavior, orientation, touch regions, volume keys and general Reader behavior. Reader owns immediate session tools. It does not duplicate the preferences hub, add Configurações do Reader, or send a generic Mais opções action to Settings. The root DESIGN.md binding/back/progress invariant remains in force.

### Contextual tools
Capítulos opens a small card with current/nearby chapters and discreet direct jump, staying in Reader. Brilho opens a small labeled slider card. Ajuste controls current-page presentation; Corte controls margins separately. Fonte opens only verified alternatives for the same canonical chapter, not Provider search. Tela ligada is a direct session toggle; Proteção visual opens Desativada / Quente / Fria / Suave tonal choices and shows a selected state whenever a treatment is active. Source identity is absent from permanent chrome.

Contextual cards retain white light-theme surfaces, primary dark text, a subtle neutral border and soft shadow so they remain identifiable on white reading content. This applies to Brilho, Capítulos, Fonte, Ajuste, Corte and recovery. Chapter and source choices use continuous navigable row lists, a primary label, concise secondary status and a check only on the selected row. The current chapter/source is clearly selected. Chapter jump is a compact inline control at the end of the list, not the organizing structure of the card. Fonte may show Provider name, language and availability, only within the contextual selector for verified alternatives to the same canonical chapter; it is not a Provider browser or diagnostics surface.

Cards have explicit close paths and return focus to their triggers. Controls have semantic buttons, accessible labels, visible keyboard focus and 44 px touch targets. Canvas and controls respect safe areas; the action rail scrolls rather than shrinking labels to fit all tools. The supplied iPhone presentation shell is not an Android platform decision.

### Failure and recovery
Individual-page failure uses a bounded floating recovery card on the canvas, never a full-width white band. It offers Tentar novamente and leaves other pages navigable. Source failure offers retry and Outra fonte without resetting chapter/page. P2P failure names the requirement and offers Revisar permissão or another source. Its permission remains the existing global opt-in privacy permission, off by default, never a session toggle. Mockup authorization is explicitly simulated and changes no real permission. No Provider debugging or internal identity data enters normal chrome.

### Board and fixtures
Thirteen frames: clean canvas; visible chrome; action rail scrolled; brightness; chapters; source choice; page failure; source failure with alternative; P2P requirement; fit; crop; screen-on/protection active. Fictional Travessia is authored illustrative content, not a real manga page, catalog promise or canonical identity. Chapter numbers, Provider Aurora / Provider Nami / Provider Sora, PT-BR / PT-PT labels and healthy source alternatives are fictional fixtures. They assert neither real Provider existence nor compatibility/verified chapter evidence. Brightness, crop, fit, jumps and retry are local simulations. Collection-based jump does not certify chapter availability; production must resolve real collection identities, fractional/special/gapped chapters.

### Status
Reader modules preserve previous boards and provide a presentation-only return to the prior board. Android, Search, Settings, Providers and Canonical Details contracts remain unchanged. Visual closure is approved; native protection, actual brightness, source recovery and permission behavior remain implementation work. No preview, rendering or post-write checks are claimed.

Light-theme refinement: contextual cards remain white with a subtle border and soft shadow. Opening a tool card adds a 32% dark neutral scrim over the rest of the Reader; closing it restores the undimmed canvas and chrome. The top back, title/chapter and eye-protection controls share a 48 px base height. The bottom tools pill uses 16 px horizontal and 4 px vertical inner padding, 20 px icons and 13 px labels with 16 px line height; each action retains a 48 px minimum touch height. Its scrollbar stays hidden and touch/trackpad/mouse scrolling remains available. A 16 px gap separates progress and tools pills. Architecture and Android remain unchanged; the visual board is approved.


### Reader — final visual polish
The top intentionally bridges its two 48 px circular controls with an elastic title/chapter pill: left-aligned title, smaller chapter, one-line ellipsis and equal 8 px gaps. The center pill fills available width; it never hugs short titles. Floating white surfaces have a neutral outline and controlled shadow so contrast does not depend on white, black or colorful page content. Contextual cards remain white above the canvas scrim.

Tools remain ordered Capítulos / Anterior / Próximo / Brilho / Ajuste / Corte / Fonte / Tela ligada / Proteção visual. The hidden-scrollbar rail has subtle edge fades, maintains mouse/touch scrolling and has no Mais action. Session toggles use a restrained tonal row treatment, stronger foreground and a small positional mark, not nested pills. The active frame starts at the rail end so screen-on and visual protection are both visible. Progress count remains exclusive to the separate progress pill.

Proteção visual means a session page-comfort tint, never a padlock, content lock or visibility toggle. Both eye controls share Desativada / Quente / Fria / Suave choices and the active state; the mockup tints only the illustrative reading page. A thirteenth frame shows the small protection card. No persistent preference changes.

Capítulos contains a vertically scrollable collection around the selected chapter, including a fractional entry, a special and a numbering gap. The secondary jump resolves an existing collection identity instead of synthesizing an integer chapter. Sources use distinct Provider / language / current-or-available rows, with concise selection marks. Aurora, Nami and Sora are fictional demonstration Providers, not compatibility evidence. Local page recovery overlays the retained illustrative page instead of replacing it; source recovery preserves loaded content and P2P remains explicit and actionable.

Reader is visually approved. Android, runtime, persistent Settings and previous boards are unchanged. Native comfort-filter implementation and runtime source/recovery availability remain implementation work; this pass establishes only the visual contract and local fixtures.


Reader — listas compactas: remover chevrons das opções de capítulos, fontes e ajuste. Proteção visual não repete olhos nas opções; somente a opção selecionada mostra check. O olho permanece como identificação da ferramenta no chrome.

## Home: refinamento da transição profunda

Esta seção consolida o contrato de Home desta rodada e substitui propostas anteriores incompatíveis. A arquitetura foi definida pelo brief; os onze frames refinados estão visualmente aprovados. Não há implementação Android ou mudança de runtime.

### Composição modular

A Home não é um dashboard fixo. A ordem estrutural é Hero opcional, Continue lendo opcional, Collections pertencentes ao usuário na ordem configurada e futuras Lists conforme o modelo de ordenação do produto. Cada módulo ausente recolhe seu espaço. Não há título de página Início, busca no topo, seções de descoberta obrigatórias ou placeholders de módulos vazios.

A navegação é Início / Busca / Biblioteca / Perfil, com Início selecionado. Settings continua acessível por Perfil. O board preserva as referências anteriores em vez de redesenhá-las.

### Hero aberto, Proposal 5

Um único artwork começa no topo, atrás da status bar, e se dissolve profundamente até #F7F7F8. O Hero não tem caixa, moldura, poster em foreground, divisão pesada ou grande intervalo até o conteúdo. Metadata curta, título, sinopse e ações vivem na zona inferior da transição. Artwork expressivo no topo; scrim e fade controlam o contraste local dos textos.

O título pode ser textual ou um title-logo oficial confiável. A ausência de logo nunca impede a composição. O exemplo de título longo permanece integral, sem elipse de slogan. O carousel admite até quatro destaques com indicador discreto e sem controles de gestão. Ausência de destaques não cria discovery fictício.

Ler é a ação primária compacta e abre a leitura diretamente; Detalhes abre o destino canônico da obra. O mockup representa destinos locais, sem executar fontes. Hero e progresso são dados demonstrativos.

### Continue lendo

Há somente uma rail horizontal de cards horizontais orientados a retomada: capa inteira, título, capítulo atual, página atual / total quando conhecido e progresso fino calculado a partir dessa posição. Nenhum rating, Provider, status editorial ou gênero. A identidade visual difere tanto dos cards de catálogo quanto das pastas de Collections. Tocar retoma diretamente a leitura, preservando obra e capítulo, sem passar por Details.

### Collections e futuras Lists

Cada Collection é uma seção da página. A rail contém pastas 16:9, sem capas, com o nome abaixo. Sem contagens de obras, Provider, status ou administração. O usuário navega em Folder tiles; a Collection não recebe card externo.

O destino preserva a linguagem de consumo: Collection View com galeria de duas colunas, e Folder Catalog com subpastas antes das Lists e do catálogo. O mockup usa dados locais para demonstrar esse caminho, sem promover o antigo Folder Catalog Mangás/Seinen excluído do milestone a referência aprovada.

As futuras Lists devem usar apresentação de catálogo própria. Continue lendo comunica posição de leitura, Collections comunica navegação por pastas e Lists comunica conteúdo. Esta rodada não define a exposição, ranking, ordenação ou sourcing de Lists.

### Escala, acessibilidade e estados

Tokens da Default Skin: fundo #F7F7F8, texto #17181A, secundário #545860, accent #275A80; corpo Segoe UI local e display Bahnschrift local, continuando os assets existentes. Insets de 16 px, intervalo entre seções de 32 px e ritmo de 8 px. Covers mantêm dimensões intrínsecas e frame completo. Folder tiles têm 16:9; somente o artwork atmosférico do Hero recebe crop. Controles têm foco visível e nomes acessíveis. Rails permitem toque, trackpad e navegação por teclado; o app rola dentro do shell iPhone. Navegação inferior reserva espaço no conteúdo.

Onze frames: Home completa; transição do Hero; Continue lendo focado; várias Collections; sem Hero; sem Continue lendo; Collections-first; muitas Collections com scroll longo; título longo sem logo; title-logo oficial; novo usuário sem Collections. O empty state é discreto, com Criar Collection em memória. Nenhuma persistência ou capacidade de Provider é comprovada pelo board.

### Assets e limites

Artworks oficiais já localizados: One Piece, Toei, https://www.toei-animation.com/wp-content/uploads/2019/02/one_piece_product.jpg ; Fullmetal Alchemist, https://www.fullmetalalchemistusa.com/img/top/bg/character.png ; logo, https://www.fullmetalalchemistusa.com/img/common/header/logo.png . Capas locais reaproveitadas de assets/perfil e assets/search, com suas fontes mantidas nas notas anteriores. No exemplo sem backdrop dedicado, a capa é usada somente como matéria-prima atmosférica do Hero, sem poster duplicado.

As obras, posições de leitura e Collections são fixtures de design. Não comprovam catálogo oficial, disponibilidade em Provider, identidade derivada do mockup ou origem futura de artwork. Home refinada está visualmente aprovada. Reader, Search, Canonical Details e Settings permanecem como referências sem alteração de seus módulos.

## Home — polish final do contrato visual

A arquitetura e os onze estados existentes permanecem: Hero aberto com transição profunda, navegação Início / Busca / Biblioteca / Perfil, Continue lendo único, Collections dinâmicas e módulos ausentes sem espaços reservados. Sem título Início ou Search no topo. Estados esparsos podem terminar cedo; não recebem seções artificiais.

Continue lendo mantém uma capa pequena vertical à esquerda de cada card horizontal e retomada direta. Cada card associa capítulo, página atual / total quando conhecidos e uma barra fina com marcador calculado pela posição real. A página é identificada como “pág.”; capítulo e página ocupam linhas curtas para não comprimir o card. Todas as barras compartilham largura, espessura e tamanho do marcador. Ratings, Provider e metadata de catálogo não entram nessa família. Valores de progresso do board são demonstrativos.

Títulos textuais de Hero usam escala responsiva: tamanho usual para títulos comuns, redução gradual para títulos longos e limite visual de três linhas, com ellipsis apenas quando excedido. O título integral permanece acessível semanticamente e no destino Detalhes. Sinopse e CTAs permanecem na composição. Title-logo oficial continua opcional, sem fabricação de lettering.

Os tiles de Folder continuam 16:9, sem capas, contagens ou metadata, com nome abaixo. A representação abstrata usa planos editoriais e pequenas linhas gráficas, substituindo o ícone genérico de pasta. Isso não altera comportamento, hierarquia de Collection ou destino de Folder.

O fade chega exatamente a #f7f7f8 e protege a zona textual com uma camada clara suficiente, preservando a região superior expressiva. Perfis locais de artwork claro, escuro e colorido ajustam o início e a intensidade do fade; o crop continua limitado ao artwork decorativo. O perfil conservador cobre artwork sem classificação. São regras visuais do mockup; classificação automática de imagens no runtime não foi implementada. Detalhes importantes da imagem podem exigir ajuste de posição focal por asset, sem deslocar a identidade ou inventar um poster separado.

Home está visualmente aprovado. Esta rodada altera apenas mockup e documentação; Android, runtime, Providers e Settings não foram modificados.


### Continue lendo — correção do componente horizontal

A apresentação anterior com mini posters verticais está superseded. Continue lendo usa uma rail horizontal de cards horizontais: capa pequena inteira à esquerda; título, capítulo atual, página atual / total quando conhecido e barra de progresso à direita. As barras compartilham geometria e marcador proporcional à posição de leitura. O card inteiro retoma diretamente a leitura, sem botões internos nem passagem por Details. Não inclui ratings, gêneros, status editorial ou Provider. Esta família de retomada permanece distinta do catálogo e das pastas 16:9. O componente compartilhado atualiza todos os frames em que aparece, sem alterar Hero, Collections, navegação ou estados vazios. Progresso do mockup é demonstrativo; Android permanece fora do escopo.


## Primeiro acessó / Entry Flow

### Contrato visual

Proposal 1: welcome minimalista, centrado no lockup oficial do Tsuzuki. Sem app bar, sem bottom navigation, sem artwork de fundo ou grande card envolvendo a tela. Marca em proporção natural, mensagem curta e espaçamento generoso. Copy: **Seu espaço para ler, organizar e acompanhar suas obras.**

Hierarquia obrigatória: **Criar conta** (primário), **Entrar** (secundário), **Continuar como visitante** (terciário, visível e claramente acionável). Visitante é um caminho válido, sem linguagem de pular, ignorar ou adiar.

Entrar, Criar conta e Esqueci minha senha pertencem a mesma família visual de Conta: fundo #F7F7F8, texto #17181A, secundário #50565C, campos brancos com borda #D6D9DC, foco #275A80, display Bahnschrift e corpo Segoe UI locais apenas para apresentação. Formulários usam rótulos persistentes, campos com erro contextual e CTA principal claro. Auth tem retorno discreto e nenhuma navegação inferior. Conteudo segue o fluxo normal, rola e respeita safe areas.

### Ciclo de primeiro acesso

**Instalação nova -> Welcome -> escolha de conta ou visitante -> funcionamento normal do app.** Welcome não é splash, nem login wall recorrente. Concluir a entrada deve ser registrado independentemente de ter uma conta autenticada: visitante não recebe o Welcome a cada abertura; falha de login ou retorno entre formularios não conclui automaticamente o fluxo. Sair da conta no futuro não constitui uma instalação nova.

O armazenamento real desse estado, reinstalação/restauração e integração com inicialização pertencem a implementação posterior. Nao foram implementados em Android nesta rodada.

### Telas e continuidade

- Welcome: Criar conta abre cadastro; Entrar abre login; Continuar como visitante leva diretamente a Home existente.
- Entrar: email e senha, mostrar/ocultar senha, Esqueci minha senha e Criar conta. Voltar retorna ao Welcome.
- Criar conta: email e senha, conforme os campos existentes do produto; não adicionar nome obrigatório nesta etapa. Oferecer caminho para Entrar e retorno ao Welcome. Confirmação de email pode exigir estado de continuidade, sem afirmar autenticação antes da confirmação.
- Recuperação: email e Enviar instruções. Mensagem breve; resposta discreta que não revela se o email pertence a uma conta. Voltar retorna a Entrar.

### Mockup e limites

Cinco frames conectados em `assets/entry-flow.js` e `assets/entry-flow.css`, integrados a `index.html` e ao espelho em `docs/design/default-skin/mockups/index.html`. O lockup `assets/sobre/tsuzuki-lockup.svg` tem viewBox 2940 x 640 e dimensões declaradas 294 x 64; manter o frame integral.

Validação local ocorre ao sair do campo ou enviar, com sumário acionável, foco e mensagem junto ao campo. Envio tem estado de processamento, seguido de Home no login ou de mensagem de confirmação/recuperação. Todas as respostas são fixtures: não criam conta, não enviam email, não validam credenciais com servidor. Nao persistir email ou senha. Apenas um marcador demonstrativo de entrada concluida pode existir em sessionStorage; o board continua apresentando Welcome para revisão e não constitui a inicialização real do app.

Boards anteriores permanecem acessíveis pela ação externa de referencia, que não faz parte da interface do produto. Nenhum Android, runtime de auth, Provider ou Settings foi alterado. **Entry Flow está visualmente aprovado.** Requisitos e comportamentos reais de backend permanecem autoridade da implementação existente.

### Redefinir senha — etapa após o link de recuperação

O board inclui um frame dedicado de Redefinir senha, na mesma família visual dos formulários existentes: Nova senha, Confirmar nova senha e Salvar nova senha. Rótulos persistentes, revelação da nova senha, erro contextual de confirmação e estado de salvamento. Após sucesso, apresentar Senha redefinida e Entrar. Retorno leva a Entrar; sem bottom navigation.

O frame representa a chegada por um link válido de recuperação. A confirmação de sucesso é somente demonstrativa: nenhuma senha é enviada ou persistida e nenhum token é validado. Política de senha, validade/expiração do link e submissão real seguem o serviço de autenticação na implementação posterior. Android não foi alterado.

## Consolidação para versionamento

Esta consolidação reúne os refinamentos de Biblioteca, Search, Canonical Details, Reader, Home e Entry Flow, incluindo Redefinir senha. O título redundante Obras foi removido do catálogo de pasta demonstrativo, conforme solicitado. Search, Canonical Details, Reader, Home e Entry Flow estão visualmente aprovados. A aprovação visual não representa implementação de Android, autenticação, recomendações ou ferramentas reais de leitura.
