# Tsuzuki Default Skin — approved specification

## Status and scope

**Settings milestone: visually complete, approved and closed.** This document consolidates the approved direction, including the final Settings work and the earlier Default Skin families. It supersedes contradictory iteration notes. Approval concerns architecture, presentation and relationships; it does not certify Android implementation, exact reusable tokens, accessibility or physical-device behavior.

The [editable mockup](mockups/index.html) and [usage notes](mockups/README.md) preserve the existing source. The initial board now presents the main Biblioteca surface; earlier boards remain in the source and local navigation. [Earlier detailed reference](references/history/default-skin-before-settings-closure.md) preserves pre-closure material; it is historical where newer decisions supersede it.

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

The header presents the large “Biblioteca” title, search and advanced-filter actions. A single light, horizontally scrollable status rail follows: **Todos · Lendo · Planejado · Concluído · Em pausa · Abandonado**. Selection is clear through type weight and a restrained indicator. With no advanced filters, the cover grid follows directly. When filters are active, a quiet contextual line shows at most two removable filter chips and a `+N` control that reopens Filtros; status never appears in this line.

The responsive grid uses naturally proportioned, full-frame covers with subtle corners and consistent gaps. Each cover is the complete tap target and signals navigation to the existing canonical detail. It carries no permanent title, status, source, format, progress, rating, menu, button or decorative badge. The seven local covers in this prototype are visual fixtures only, not a canonical catalogue, real library inventory, or evidence that a Provider or volume is available. Artwork should represent the canonical work where it exists.

Search expands in place, has distinct clear and exit actions, filters only the current library as text is entered, and preserves status and advanced filters. It adds no global or external results, history or suggestions. Filtros is a modal bottom sheet: ORIGEM is single-select and reflects runtime availability; LISTA appears only for a selected external origin with usable custom lists, never as a duplicate status rail; FORMATO is multi-select and omits `Unknown`; CATEGORIA is single-select with Todas, Sem categoria and real user categories. Demonstrative values such as Kitsu, Manga, an example list and Sem categoria may exercise the board but do not define a fixed Provider set, a permanent list or category catalogue, or real user data.

Filter changes apply immediately. Limpar filtros clears only advanced filters and preserves status and search; Concluído closes the sheet without applying a separate draft. Dismissing the sheet also preserves choices already made. A genuinely empty library has a calm “Sua biblioteca está vazia” state. A query or active filter with no matches has a separate “Nenhuma obra encontrada” state, keeps the applied choices, and may offer Limpar filtros. Loading is discreet, accessible and has no false covers.

The current board shows six compositions: Biblioteca padrão; filtros avançados ativos; busca ativa; Filtros open; nenhum resultado; and biblioteca vazia. The HTML's local interactions illustrate this surface and signal the existing canonical-detail destination; they do not implement Android operations. The generated proposal awaits visual review and is not recorded as approved or closed.

Open Android questions remain for adaptive grid behavior, system insets, font scaling, TalkBack, touch targets and animation/transitions. These need evaluation in the real Android implementation. Prototype CSS measurements and local fonts are not dp/sp, accessibility or font-distribution contracts.

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

## Remaining implementation work

Map CSS evidence to Android tokens/dp/sp and fonts; implement real preferences, conditional capabilities/build features, pickers, validation, jobs and dynamic catalogs. Measure contrast, font scaling, touch targets, insets/IME, focus, long lists and reduced motion on the actual implementation. Preserve functional repository contracts rather than assuming prototype adapters run Android operations. Backup migration to Provider Platform, final CSV filename and operational P2P activity location remain explicit open work.

No new Settings exploration is required by this milestone. The next meaningful UI work is the implementation handoff, not inventing another Settings page.
