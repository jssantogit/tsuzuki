# Collections administration + List Builder — tensões e limites

> Registro de elaboração preservado. Os estados Provisional/TBD abaixo descrevem a rodada original. O fechamento visual de Settings e as decisões que a substituem estão na [especificação vigente](../design.md). Medidas Android, execução real e limites expressamente pendentes continuam sem certificação. Aquisição global e Folder Catalog não são referências aprovadas.


## Status e escopo

**Referência oficial Approved da Tsuzuki Default Skin**, consolidada por aprovação explícita do usuário em 2026-10-07. A família inclui Collections root, New/Edit Collection, New Folder/New Subfolder/Edit Folder, New/Edit List — Quick Builder, Advanced Filters, Remote Lookup + Include/Exclude, troca de fonte e imported/preserved query.

A consolidação substitui os status de proposta das rodadas anteriores. Não altera HTML, assets ou composições. Approved identifica relações e decisões explicitamente aprovadas; medidas locais, microinterações pendentes e implementação não recebem aprovação automática.

**Fora do milestone:** Mangás/Seinen de Folder Catalog permanecem exploração não aprovada. Não sustentam regras Provisional/TBD desta família. O registro histórico de exclusão está em `folder-catalog-tensions.md`. Administração de Collections e consumo na Home/Folder Catalog continuam distintos.

## Referências e autoridade

- `design.md`: referência consolidada, com regras Approved anteriores preservadas.
- `index.html`: evidência visual das composições finais aprovadas. A aprovação não valida todas as versões históricas existentes no arquivo.
- Aprovações do usuário: autoridade para o status de cada superfície e os limites desta consolidação.
- `CollectionsScreen.kt`, `ListBuilderScreen.kt`, `CollectionProviderSwitchPlanner` e descriptors reais: evidência de semântica funcional, não prova de execução pelo mock nem aceitação Android.
- Projects → Tsuzuki: UI & Design, Decisions, Current State e Session History registram resumo durável; não substituem os documentos detalhados.
- Impeccable auxiliou composição, hierarquia, spacing e refinamento. Suas heurísticas não são regras Approved por si.

## Approved — relações por superfície

| Superfície | Relações aprovadas | Limite |
|---|---|---|
| Collections root | Deep navigation desde Configurações; título e overview Collections/Folders; Nova coleção dominante; cards administrativos com preview textual; handle, editar e overflow; Importar/Exportar globais secundários | Não é catálogo nem preview de Home |
| New/Edit Collection | Mesma família BÁSICO/PASTAS; staged children antes da persistência; reorder, editar/remover; CTA persistente criar/salvar | A forma exata do overflow/expansão continua Provisional |
| New/New Subfolder/Edit Folder | Nome e LISTAS staged/existentes; cards administráveis; handle, editar/remover; contexto simples de parent; CTA persistente | Não desenha árvore interna ou consumo de obras |
| Quick Builder | Básico, fonte, filtros disponíveis, ordenação, filtros ativos, Advanced, Preview com covers, CTA persistente; light theme; large title → compact header | Pequenos detalhes Android de scroll/insets não foram fechados |
| Advanced Filters | Conteúdo determinado pelo descriptor, organização variável em grupos contínuos e controles humanos derivados dos operadores reais | Não impõe formulário universal ou Min/Max obrigatório |
| Remote Lookup | Sheet alta, busca, neutro/incluir/excluir, resumo compacto, Limpar secundário, Aplicar dominante | Fechar preserva seleção aplicada; medidas/estados excepcionais pendentes |
| Source switching | Preservação compatível; confirmação de perdas; perda descrita humanamente; bloqueio fail-closed de queries complexas | Não autoriza tradução com semântica diferente |
| Preserved query | Original intacto; safe fields editáveis; fonte preservada, filtros read-only; resumo humano e inspeção canonical-like; Preview executável; salvar sem reescrever | Reset explícito não definido |

### Modelo e limites de produto

Collection → Folder → List. Collection e Folder são containers organizacionais; List é consulta dinâmica. Filtros pertencem à List. Não introduzir capas, provider, descrição, cores, configuração de Home ou filtros de Folder/Collection nos editores administrativos.

Handle é a única affordance visível de reordenação; não usar setas ↑/↓. Cards administrativos não são Folder tiles de Home. Criar/editar compartilham anatomia; estado, conteúdo e CTA fazem a distinção. Children podem existir antes da persistência sem rótulo de draft na UI.

Quick/Advanced são descriptor/capability-driven. Ausência de capability significa ausência de UI; capability fixa reduz controles. Não expor REMOTE_EXACT, RESIDUAL_EXACT, AST, QueryOperator, MultiValueMode ou IDs internos ao usuário comum. Inspeção canonical-like é read-only, uma projeção da consulta, não um editor ou serializador.

### Quick Builder e Advanced

Ordem aprovada: Básico → Fonte do catálogo → filtros disponíveis → Ordenação → filtros ativos → Filtros avançados → Preview → CTA persistente. Exibição compacta: Padrão, Grade, Lista. Fonte sem explicação técnica permanente. Chips humanos removíveis; Limpar tudo somente com filtros. Preview compacto com 4–6 covers é direção visual, não catálogo de metadata nem Preview textual.

Header compacto opaco e conteúdo rolável devem impedir overlap. Bottom CTA reserva espaço real e safe area; último conteúdo deve poder ficar totalmente visível. A relação está Approved; dimensões e comportamento fino Android permanecem Provisional.

Advanced suporta static select, remote lookup, include/exclude, boolean, free text, integer range, decimal range e date range conforme descriptor. Modos mínimo/máximo/intervalo/exato não são presumidos universalmente. Seleções detalhadas, calendário e dropdowns não foram aprovados como famílias completas.

### Remote Lookup e preservação

Uma lista contínua substitui catálogos separados de incluir/excluir. Estados são legíveis por texto e ícone; busca mantém escolhas fora dos resultados. Aplicar confirma edição; Limpar afeta apenas edição; cancelar/fechar conserva seleção anterior. O gesto fino do controle, ordenação da lista, lotes e teclado Android não se tornam padrões globais.

Source switching conserva nome/exibição e semânticas compatíveis; confirmação lista só as perdas. Query complexa bloqueia a troca antes de mutação. Na fixture Kitsu → MyAnimeList, Gênero: Drama e Popularidade representam perdas semânticas específicas, não ausência universal dessas capacidades no destino.

Preserved query permanece separada de nome/exibição/sort seguros. Fonte sem affordance de seleção normal; filtros sem remoção ou controles editáveis. Quick mostra resumo humano e Advanced inspeção sem AST bruto por padrão. Preview continua disponível quando executável. Salvar não reconstrói o original a partir da copy. Redefinir filtros foi omitido e segue TBD.

## Tensões resolvidas pela aprovação

| Tensão | Decisão consolidada | O que não foi promovido |
|---|---|---|
| Administração versus catálogo | Cards textuais administrativos; consumo separado | Mangás/Seinen e seus padrões exploratórios |
| Criar versus editar | Uma família com conteúdo/estado/CTA diferentes | Nova arquitetura para cada estado |
| Provider sem Quick | Omitir seção vazia | UI desabilitada para capability inexistente |
| Range e sort heterogêneos | Controles derivados de operadores/direções reais | Min/Max universal ou direção fixa explicada tecnicamente |
| Fragmentação e tema | Grupos contínuos, alinhamento comum e light theme na referência final | Medidas locais e paleta global |
| Scroll e CTA | Large title → compact header; bottom action com reserva real | Insets e transição fina Android |
| Preview textual | Direção final com covers compactos | Resultados remotos atribuídos às fixtures |
| Perda semântica | Consentimento explícito ou bloqueio seguro | Conversão forçada |
| Query não representável | Preservação exata com edição apenas segura | Simplificação silenciosa ou reset automático |

## Provisional

- Medidas exatas, grid, espaçamentos, raios, tokens e proporções locais; nenhum valor CSS vira escala global ou dp/sp.
- Spacing/insets finais Android, comportamento fino do collapsing header, safe areas, foco, teclado e acessibilidade da interação.
- Dimensões das bottom sheets e forma exata do overflow/expansão e da ação destrutiva nos cards.
- Microinterações de dropdowns, calendário/date picker e controles de range.
- Estados loading/error de Remote Lookup, copy final de alguns filtros/labels de providers e tratamento de toggles ternários unset/true/false quando necessário.
- Gesture fino de reorder/seleção, feedback, responsividade e normalização óptica. A aprovação visual não é auditoria de contraste ou acessibilidade.

## TBD

- Visual final de loading/error/empty do Preview e detalhes de continuidade/paginação, execução remota e scan-budget quando sustentados pelo runtime.
- Import/export detalhado: formatos, merge, validação, persistência e erros.
- Confirmações destrutivas de remoção e comportamento de built-ins, que podem oferecer Hide em lugar de Delete.
- Reset explícito de preserved query; não inferir conversão automática ou exclusão de filtros.
- Futuros descriptors/provider capabilities e qualquer comportamento não sustentado pelo runtime atual.
- Persistência Android, restauração após reinício, preservação fiel da definição importada, tradução entre providers, cache/cancelamento de lookup e execução de consultas precisam de evidência de implementação.

## Evidência específica dos descriptors

Estes exemplos descrevem os descriptors consultados, não uma lista eterna de capacidades nem copy obrigatória:

- Kitsu Quick: status, tipo, gênero RemoteLookup/ALL e avaliação decimal com mínimo/máximo/intervalo. Popularidade e avaliação aceitam asc/desc; atualizados recentemente somente desc. Ano, capítulos, volumes e popularidade podem integrar Advanced.
- Bangumi: zero Quick; ordenações nativas Match/Heat/Rank/Score. Advanced pode fornecer tags, data, avaliação, rank e demais opções reais. Não inventar Quick para preencher espaço.
- MangaUpdates Advanced: gêneros RemoteLookup include/exclude, categoria única, licenciado, lançamento estático e ano inicial. Avaliação permanece Quick.
- Shikimori Advanced: ano único/intervalo, gêneros/editoras RemoteLookup include/exclude, franquia textual include/exclude, censurado e termo de busca. Avaliação mínima somente >= pertence ao Quick.
- MyAnimeList Advanced: gênero/autor/artista são free text, não remote lookup; ano/data/capítulos/volumes conforme operadores disponíveis. Tipo/status/avaliação permanecem Quick.
- Os seis descriptors consultados não exigem autenticação nesta superfície; não inventar fluxo de credenciais no Builder.

## Fixtures, fontes e demonstração local

- Raiz: Favoritos (3 pastas: Mangás, Manhwas, Para ler), Descobertas (4: Ação, Romance, Novidades, +1) e Temáticas (1: Cyberpunk). Total 3 Collections/8 pastas é fixture.
- Collection create/edit: Minha coleção com Mangás/Manhwas; Favoritos com três pastas e contagens demonstrativas de listas. Folder/Subfolder: Mangás, Seinen; Em alta, Romance, Novidades e Clássicos, com resumos de provider/query apenas demonstrativos. Normalização futura de Lists → listas é copy, não alteração do modelo.
- Quick: Kitsu com Concluído, Mangá, Drama e avaliação mínima 8; Bangumi Mais populares/Grade/Heat com filtros Advanced. Valores não são defaults obrigatórios.
- Preview: capas locais de Fullmetal Alchemist, Death Note, Attack on Titan e Witch Hat Atelier em `assets/perfil`, dimensões 333×500, 329×500, 322×500 e 348×500. Preservam o frame completo. São fixtures, não resultados executados das queries; não inventar total/+N.
- Lookup: snapshots `../mockups/assets/lookups/mangaupdates-genres.json` e `../mockups/assets/lookups/shikimori-publishers.json`; fontes `https://api.mangaupdates.com/v1/genres` e `https://shikimori.io/api/publishers`. Romance/Drama incluídos, Ecchi excluído; Afternoon incluído e Young Animal excluído são valores disponíveis nos snapshots. Shueisha/Kodansha não foram inventados como entradas desses dados.
- Preserved fixture Kitsu: `STATUS = COMPLETED AND (GENRE = Drama OR GENRE = Romance) AND NOT GENRE = Ecchi`. Resumo humano conserva OR/negação; não converte para ALL. Canonical-like é exemplo de inspeção, não sintaxe universal de produto.
- Protótipo faz mudanças em memória, usa snapshots e feedback local. Não comprova autenticação, persistência ou execução remota. Shell/system bars são apresentação, não tokens globais.

## Histórico de aprovação e exclusões

Raiz e editores Collection/Folder/Subfolder foram aprovados antes da validação do Builder. Quick Builder teve propostas rejeitadas por chrome, copy técnica, Preview textual, tema e scroll/insets; somente a composição final em light theme com covers e collapsing header é referência. Advanced, Lookup, source switching e preserved query foram aprovados sequencialmente e integram a consolidação final.

O Folder Catalog Mangás/Seinen foi excluído explicitamente do milestone. Sua existência no HTML não implica aprovação. As notas antigas de proposta foram supersedidas por este documento; não promovem composições rejeitadas ou medidas locais.

## Continuidade

Collections administration + List Builder é referência oficial. Não há nova composição nesta consolidação. Próximo trabalho deve partir das relações Approved, preservar separação administração/consumo e manter Provisional/TBD até evidência ou decisão explícita. Os documentos detalham a referência; Notion recebe somente resumo durável.
