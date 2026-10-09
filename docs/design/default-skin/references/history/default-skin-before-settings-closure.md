> Histórico anterior ao fechamento de Settings. Não usar como contrato vigente onde conflitar com a [especificação consolidada](../../design.md). Integrações como categoria pública e antigas decisões de navegação são substituídas pela IA final.

# Tsuzuki Default Skin — design reference

## Escopo e fontes

Este documento consolida composições aprovadas como referências visuais oficiais da **Tsuzuki Default Skin**: **Configurações**, **Personalização**, **Aparência**, **Preset visual**, **Leitura**, **Webtoon**, **Biblioteca**, **Atualização automática**, **Integrações**, **MyAnimeList** a família **Conta**, **Perfil** e a família **Tracking**, além de **Collections administration + List Builder**. Conta contém quatro estados relacionais — Entrar, Criar conta, Redefinir senha e Conta conectada — e conta como uma superfície/arquitetura, não quatro arquiteturas. A aprovação vale para as relações e decisões explicitadas abaixo; não certifica implementação, acessibilidade, cada medida CSS ou um sistema global de tokens.

Fontes e papéis:

- Aprovações do usuário das composições Configurações, Personalização, Aparência e Preset visual. São evidência de design e decisões de produto somente nos pontos aprovados explicitamente.
- Aprovação explícita das composições Leitura e Webtoon como referências visuais Default Skin. São referências independentes; Leitura → Webtoon é um fluxo aprovado, detalhado em `../../tensions/leitura-tensions.md`.
- Aprovação explícita das composições Biblioteca e Atualização automática como referências visuais Default Skin. São referências independentes; Biblioteca → Atualização automática é um fluxo aprovado, detalhado em `../../tensions/biblioteca-tensions.md`.
- Aprovação explícita da raiz Integrações e da tela MyAnimeList como referências visuais Default Skin. As relações e os limites desta superfície estão em `../../tensions/integracoes-tensions.md`.
- Aprovação explícita da família Conta (Entrar, Criar conta, Redefinir senha e Conta conectada) como referência Default Skin. Os quatro estados pertencem a uma única superfície relacional de formulário/conta; relações e limites estão em `../../tensions/conta-tensions.md`.
- Aprovação explícita de Perfil como referência oficial: identidade, métricas, carrossel de leitura atual, trilhos e navegação raiz. Estados inicial e após scroll são a mesma superfície; evidência e limites em `../../tensions/perfil-tensions.md`.
- Aprovação explícita da família Tracking: raiz, Kitsu desconectado/conectado, conexão transitória e confirmação de desconexão. Relações e limites em `../../tensions/tracking-tensions.md`; os estados de Kitsu são uma mesma arquitetura.
- Aprovação explícita da família administrativa de Collections e List Builder: raiz, editores de Collection/Folder/Subfolder/List, Advanced Filters, Remote Lookup, troca de fonte e query preservada. Relações e limites em `../../tensions/collections-tensions.md`. Mangás/Seinen de Folder Catalog são explorações não aprovadas e ficam fora deste milestone.
- `index.html` no OpenDesign é o artefato de composição e evidência dos valores e estados ali demonstrados. CSS, lógica local e exemplos não provam comportamento Android nem preferências do usuário.
- Android/GitHub é a fonte da implementação. Enumerações e preferências existentes podem comprovar nomes e valores do código, não decisões visuais ou disponibilidade de produto em cada aparelho.
- Notion registra intenção e memória; OpenDesign contém a verdade visual e formalização de design; GitHub contém a verdade de implementação.
- Brand v3 define três formas de T negativo monocromáticas, wordmark A2 Organic e paleta de marca Ink `#0B0C0D`, Paper `#F5F3EC`, Graphite `#242628`, Ash `#7D8185`, Mist `#C9CCCE`. A paleta de marca não substitui automaticamente cores da interface. Roboto pertence à referência do wordmark, não é fonte de interface aprovada.

Segoe UI Local regular/bold aparece nas três composições como fonte local de apresentação. A família Android de produto, os equivalentes tipográficos e o mapeamento CSS px para dp/sp continuam em aberto.

## Approved

### Relações visuais recorrentes

- **Hierarquia e agrupamento:** título de tela dominante; rótulos de grupo menores; opção mais enfatizada que descrição seletiva. Opções relacionadas compartilham uma superfície arredondada e divisores internos discretos.
- **Linha de preferência:** ícone em recipiente à esquerda, conteúdo no centro e ação/estado à direita, com quebra normal dos textos. A forma exata depende da ação: linha navegável, seletor ou controle direto.
- **Subtelas:** nas composições aprovadas de Personalização e Aparência, Voltar fica acima do título e a navegação inferior não aparece. A antiga Configurações raiz com navegação inferior é evidência histórica dessas composições. A decisão posterior de Perfil substitui essa navegação global: Configurações é acessada pela engrenagem de Perfil; o padrão de cabeçalho profundo continua válido.
- **Skin e paleta:** Tsuzuki Default Skin é identificada como estado contextual atual. O preset configurável pertence exclusivamente a Personalização → Preset visual; a identificação contextual em Aparência não torna a skin configurável ali.
- **Hub e detalhe de preferências:** a tela principal funciona como hub híbrido, com preferências universais curtas e grupos especializados acessados por drilldown. Uma subpágina especializada pode conter vários grupos densos do mesmo domínio.
- **Navegação profunda:** Configurações → Leitura → Webtoon mantém Voltar acima do título grande e não exibe navegação inferior. Configurações → Biblioteca → Atualização automática também é um fluxo aprovado, com detalhe em `../../tensions/biblioteca-tensions.md`. Personalização, Aparência e Preset visual são referências independentes, não etapas do fluxo Leitura/Webtoon.
- **Grupos e linhas:** grupos semânticos organizam telas longas. Rows navegáveis mostram título, valor atual e chevron; não repetem ações textuais redundantes como “Editar” ou “Configurar”. Dependências entre preferências existentes são comunicadas visualmente sem remover preferências silenciosamente. Em Biblioteca, o estado-pai desabilitado mantém os valores dependentes visíveis; linhas que resumem múltiplas escolhas usam estado ou contagem. Esta relação não define o tratamento de capabilities indisponíveis.

### Evidência delimitada de Configurações

- Interface escura, legível, de densidade intermediária generosa; grupos, divisores e anatomia descritos acima.
- Evidência histórica: busca em botão circular e Configurações selecionada na navegação inferior daquela composição. A navegação atual aprovada é Início · Busca · Biblioteca · Perfil, com Configurações pela engrenagem de Perfil.
- A referência combina ícones preenchidos e contornados; não há estilo único aprovado para ícones novos.

### Relações aprovadas de Preset visual

- **Localização e escopo:** Personalização → Preset visual é o único ponto de seleção do preset. Em Aparência, “Skin atual” é informação contextual, não um seletor. O preset não altera a arquitetura funcional do produto.
- **Cartão e estado:** um cartão visual selecionável apresenta o preset atual. O estado selecionado é comunicado redundantemente por tratamento visual e texto.
- **Prévia:** a prévia esquemática representa a linguagem visual da skin, não uma captura literal de tela. Ela se adapta ao tema atual; tema não é preset. Tsuzuki Default Skin existe nos temas claro e escuro, e selecionar um tema não troca a skin.
- **Limite da aprovação:** essas relações pertencem à composição aprovada. Anatomia reutilizável do cartão, dimensões, proporção, tokens, interação detalhada e responsividade global continuam Provisional. Um único preset não valida troca real, aplicação, persistência ou estados desabilitados.

### Relações aprovadas de Biblioteca

- **Hub híbrido:** o hub de Configurações também atende áreas operacionais especializadas. Configurações → Biblioteca abre a referência Biblioteca; Personalização → Layout → Biblioteca mantém seu destino atual.
- **Dependências visíveis:** para dependências entre preferências existentes, o estado-pai desabilita opções dependentes sem escondê-las. Seus valores permanecem visíveis para comunicar a configuração preservada. Esta regra não define o tratamento de capabilities indisponíveis.
- **Resumo de escolhas:** rows que representam múltiplas escolhas resumem o estado por indicação ou contagem.
- **Seleção:** folhas single-select e multi-select têm padrões distintos. Uma folha multi-select pode exigir Cancelar/Confirmar quando a aplicação das alterações não for imediata.
- **Escopo aprovado:** as composições Biblioteca e Atualização automática, e o fluxo Biblioteca → Atualização automática, são referências aprovadas. Inventário funcional e histórico das tensões ficam em `../../tensions/biblioteca-tensions.md`.

### Relações aprovadas de Integrações e MyAnimeList

- **Resumo na raiz:** cada integração usa um summary status curto, escolhido pelo estado mais útil para a próxima ação. Não concatenar configuração, habilitação e conta; para o exemplo conectado de MyAnimeList, mostrar apenas “Conectada”.
- **Eixos independentes:** configuração por Client ID, disponibilidade, habilitação global e conta são estados distintos. A raiz resume; o detalhe pode mostrar esses eixos separadamente.
- **Recursos preservados:** as sete capabilities atuais de MyAnimeList permanecem visíveis quando inativas, com valores preservados. Tracking e User Lists pertencem à área de conta, não são capability toggles.
- **Dependências de conta:** sem Client ID, Conectar permanece visível, desabilitado e acompanhado da dependência. Conectar depende da configuração, não da habilitação global. Desconectar é uma ação separada da habilitação global.
- **Marcas:** ícones genéricos podem servir como placeholders no protótipo; integrações identificáveis poderão usar seus próprios marks/logos na implementação final.
- **Escopo:** os dez itens e grupos aprovados são os definidos na raiz Integrações. Não inferir uma nova taxonomia visual a partir da arquitetura de Providers.
- **Limite:** estas relações documentam a composição e dependências aprovadas, não comprovam validade remota do Client ID, OAuth, persistência ou funcionamento Android.
### Relações aprovadas de Conta

- **Uma família relacional:** Entrar, Criar conta e Redefinir senha são estados do mesmo formulário; Conta conectada é o overview da mesma superfície. Não contar os quatro estados como quatro arquiteturas.
- **Campos e ações por estado:** labels persistentes acompanham E-mail e Senha nos estados pertinentes. Entrar oferece Entrar, Criar conta e Redefinir senha; Criar conta oferece Criar conta e voltar a Entrar; Redefinir senha contém somente E-mail, Enviar instruções e Voltar ao login, sem campo de senha.
- **Conta conectada:** o resumo mostra e-mail, sem avatar ou username, status operacional curto e última sincronização quando disponível. Sincronizar agora é ação manual e seu feedback de conclusão é transitório. A linha de status indica o destino futuro **Conta → Sincronização**; esta rodada não define nem desenha essa subpágina.
- **Sair:** ação separada, com confirmação própria e retorno de foco ao acionador ao cancelar/fechar; ao confirmar, a conta é desconectada e o foco retorna ao cabeçalho.
- **Limite:** as relações aprovadas não definem autenticação, persistência, envio real de e-mail, rede, comportamento de sync nem detalhes operacionais. Valores mostrados no protótipo são amostras locais documentadas em `../../tensions/conta-tensions.md`.
### Relações aprovadas de Perfil

Perfil é a nova referência oficial de composição da Default Skin. A aprovação é explícita e seu alcance não promove automaticamente medidas ou tokens para o sistema global.

- **Navegação raiz / root-screen utility actions:** Início · Busca · Biblioteca · Perfil. Perfil substitui Configurações como quarto destino; mantém bottom navigation. Engrenagem no canto superior esquerdo abre Configurações, e ⋯ no canto superior direito reserva ações contextuais de Perfil. Não exibir título textual Perfil no topo. A presença do menu não define arquitetura de múltiplos perfis.
- **Profile identity header:** avatar centralizado e nome grande são a identidade principal; o nome é o título visual. Preservar respiro generoso entre avatar, nome e métricas, sem converter os espaçamentos locais em tokens globais.
- **Statistic trio:** Concluídas / Capítulos / Páginas em faixa horizontal, numerais com maior peso que labels, sem três cards independentes pesados. Concluídas abrange obras concluídas de qualquer formato suportado. O cálculo histórico/retroativo de Páginas permanece TBD.
- **Current-reading carousel:** Lendo agora vem imediatamente após as métricas e representa atividade/progresso atual. Um card largo completo por vez, de uma a cinco obras, troca por swipe horizontal, sem pedaço do próximo card. Anatomia relacional: cover à esquerda, título, capítulo atual, barra de progresso e percentual. Sem Ver todos, +N ou fileira de covers; não fabricar obras adicionais quando houver apenas uma.
- **Pagination indicator:** pontos abaixo do card, atual preenchido e demais neutros. Dimensões, espaçamento, contraste exato e implementação assistiva permanecem Provisional ou exigem validação adicional.
- **Profile content rails:** Favoritos representa identidade/gosto, com covers verticais em trilho horizontal e Ver todos. Concluídas recentemente é trilho separado de histórico, com Ver todas, e não equivale à métrica total Concluídas. Não reinterpretar esses trilhos como cards de progresso. A composição aprovada separa as seções por espaço, sem a divisória horizontal rejeitada abaixo de Favoritos.
- **Rolagem:** estado inicial e após scroll pertencem à mesma tela, sem segunda arquitetura ou aparelho. Avatar, nome e métricas saem naturalmente da viewport; o topo compacto preserva engrenagem e ⋯ sem reintroduzir Perfil. Não é obrigatório que o label Lendo agora esteja visível naquela viewport específica.
- **Conteúdo e limites:** One Piece, Fullmetal Alchemist, Death Note, demais obras, nome e números são fixtures visuais, não regra de produto ou dados reais. Perfil não amplia as funcionalidades de Conta nem introduz feed, followers, badges, ranking ou tracking.

### Relações aprovadas da família Tracking

Tracking é referência oficial da Tsuzuki Default Skin: raiz Tracking, detalhe Kitsu desconectado/conectado, fluxo transitório de conexão e confirmação de desconexão.

- **Raiz:** retorno ← Configurações, título dominante Tracking, grupo CONTAS DE TRACKING; branding à esquerda, nome, summary status à direita e chevron. Conectada, Desconectada e Configuração necessária são summaries úteis, não um enum universal nem concatenação de todos os eixos.
- **Separação de responsabilidades:** Integrações/Providers configura serviço, configuração técnica e capabilities; Tracking gerencia vínculo externo para acompanhamento; Conta pertence ao Tsuzuki; Perfil é identidade/dashboard pessoal. Tracking não duplica capability toggles nem configuração técnica.
- **Detalhe Kitsu:** retorno ← Tracking, branding e título Kitsu. Desconectado mostra status e CTA Conectar conta; conectado mostra identidade disponível e status, sem duplicação desnecessária. Os dois estados pertencem à mesma composição, sem bottom navigation.
- **Encerramento do vínculo:** Desconectar conta fica separado das preferências comuns e não desabilita a integração. Confirmação explícita informa que a configuração da integração permanece intacta.
- **Superfícies transitórias:** conexão pode usar bottom sheet com as credenciais exigidas pelo serviço; não exige campos permanentemente expostos no detalhe. A confirmação de desconexão é uma superfície distinta da autenticação.
- **Copy e evidência:** Nome não disponível é fixture/estado possível, não copy obrigatória. Impeccable auxiliou no refinamento; suas heurísticas não são regras Approved por si. Aprovação visual não comprova autenticação, persistência ou implementação Android.

### Relações aprovadas de Collections administration + List Builder

Esta família é referência oficial da Tsuzuki Default Skin por aprovação explícita do usuário. A aprovação cobre as composições finais e as relações abaixo, não medidas globais nem aceitação da implementação Android.

- **Modelo e responsabilidades:** Collection → Folder → List. Collection e Folder são containers organizacionais; List é consulta dinâmica. Filtros/source pertencem à List, não à Collection/Folder. Administração em Configurações permanece distinta do consumo na Home/Folder Catalog. As explorações Mangás/Seinen não são Approved e não integram esta referência.
- **Raiz administrativa:** retorno ← Configurações, título Collections dominante, overview de Collections/Folders e Nova coleção como CTA principal. Cards de Collection combinam nome, contagem, preview textual leve das primeiras pastas e +N quando necessário. Drag handle é a única affordance visível de reorder; edição explícita e overflow têm papéis distintos. Importar/Exportar são ações globais secundárias. Sem covers, ratings, provider logos ou tiles de Home nesta raiz.
- **New/Edit Collection:** mesma anatomia BÁSICO → PASTAS para criar e editar; muda estado, conteúdo e CTA. Folders podem ser adicionadas antes da persistência, sem copy de implementação. Cards filhos são administráveis: reorder pelo handle, editar/remover e ações secundárias. CTA inferior persistente e amplo: Criar coleção / Salvar alterações.
- **New/New Subfolder/Edit Folder:** mesma linguagem com nome e LISTAS staged/existentes, cards administráveis, reorder pelo handle e editar/remover. Contexto simples de parent distingue subpasta sem breadcrumb complexo; não exibe árvore completa, catálogo ou uma seção adicional de subfolders. CTA persistente Criar pasta / Salvar alterações.
- **Quick Builder:** BÁSICO → Fonte do catálogo → filtros disponíveis → ORDENAÇÃO → filtros ativos → Filtros avançados → Preview → CTA persistente. Exibição compacta suporta Padrão/Grade/Lista. Quick Filters só aparecem quando oferecidos pelo descriptor; provider sem Quick não gera seção vazia. Filtros ativos usam copy humana, remoção individual e Limpar tudo secundário quando houver seleção. Advanced usa uma row com resumo específico do provider. Preview compacto com covers permite reconhecer resultados sem virar catálogo completo. Tema claro é a referência desta rodada; large title dá lugar a compact header opaco durante scroll, sem overlap e com área reservada para o CTA.
- **Capabilities:** List Builder é descriptor/capability-driven; providers podem ter densidades e controles diferentes. Ausência de capability implica ausência da UI correspondente. Ordenação ajustável permite direção; direção fixa/nativa produz menos controles, sem seletor inútil ou explicação técnica. A UI comum não expõe REMOTE_EXACT, RESIDUAL_EXACT, AST, QueryOperator, MultiValueMode ou IDs internos.
- **Advanced Filters:** conteúdo definido pelo descriptor; grupos contínuos podem variar por provider, evitando um card por filtro. Static select, remote lookup, include/exclude, boolean, free text, integer/decimal/date range têm controles humanos correspondentes às capacidades reais. Operadores disponíveis determinam modos e campos: mínimo, máximo, valor exato/ano único ou intervalo quando suportados; não assumir sempre Min/Max. Filtros Quick não reaparecem automaticamente em Advanced.
- **Remote Lookup + Include/Exclude:** sheet alta sobre Advanced reconhecível, busca, uma lista contínua com estados Neutro/Incluir/Excluir legíveis, resumo compacto, Limpar secundário e Aplicar dominante. Escolhas em edição ficam separadas das aplicadas; fechar/cancelar preserva o estado anterior. IDs e semântica lógica permanecem no modelo.
- **Troca de fonte:** filtros/sort compatíveis podem ser preservados; perda semântica exige confirmação explícita que lista somente as perdas em linguagem humana. Nome/exibição e filtros compatíveis permanecem. Queries complexas não traduzíveis bloqueiam a troca antes de alteração: comportamento fail-closed, sem converter mesmo assim.
- **Imported/preserved query:** nunca simplificar nem reescrever silenciosamente a definição original. Nome/exibição e ordenação compatível permanecem editáveis quando seguros; fonte aparece preservada e filtros read-only. Quick apresenta resumo humano; Advanced oferece inspeção canonical-like read-only, sem AST bruto por padrão. Preview permanece disponível quando executável; Salvar alterações não reconstrói a query preservada. Reset explícito não foi definido.
- **Alcance da evidência:** exemplos de nomes, providers, filtros e capas são fixtures, não defaults obrigatórios ou resultados remotos comprovados. Impeccable auxiliou refinamento; suas heurísticas não se tornam regras Approved sem evidência. Gestos finos, tokens e implementação conservam os limites abaixo.

## Provisional

Todas as decisões introduzidas pelos seletores, alternâncias, folhas de seleção, listas de opções e tema claro nas composições de Aparência permanecem provisórias. Anatomia reutilizável, dimensões, proporções, tokens, interação detalhada e responsividade global do cartão e da prévia de Preset visual também permanecem provisórias. O contraste/opacidade exatos de controles desabilitados e o styling exato das ações de conta em Integrações/MyAnimeList também são Provisional. A forma aprovada das telas documenta relações e composição, não promove controles a componentes reutilizáveis.

### Collections administration + List Builder — limites da aprovação

Medidas exatas, spacing/insets finais Android, comportamento fino de collapsing header, dimensões de sheets, forma exata de expansão/overflow, dropdowns, calendário/date picker, foco/teclado, lookup loading/error, copy final provider-specific e tratamento de toggles unset/true/false permanecem Provisional. Nenhum valor CSS, proporção ou token observado nesta família é promovido a regra global. Pequenos detalhes de scroll/insets serão resolvidos na implementação Android.

### Tracking — limites da aprovação

Medidas exatas dos sheets, normalização óptica e medidas locais dos marks/rows, tratamento final de campos/foco/teclado, styling exato de Desconectar conta, apresentação de identidade sem nome/display name e apresentação dos estados de erro/loading/session expired permanecem Provisional. As relações aprovadas não promovem proporções ou tokens específicos a regras globais.

### Perfil — limites dos novos padrões

As relações dos seis padrões acima estão Approved para Perfil por decisão explícita. Anatomia reutilizável, dimensões, proporções exatas, espaçamentos, fonte, tokens, tratamento de ícones, detalhes de foco/gesto e responsividade global continuam Provisional. O shell de apresentação não é um componente da skin. Valores locais observados em `../../tensions/perfil-tensions.md` não constituem uma escala global.

### Papéis de cor compartilhados, valores por tema

Os papéis semânticos abaixo podem ser comparados entre tema escuro e claro. Valores são locais às composições e continuam provisórios; o tema claro não está aprovado como paleta global.

| Papel | Escuro observado/proposto | Claro proposto | Status e alcance |
|---|---|---|---|
| Fundo | `#0B0C0D` | `#F7F7F8` | Provisional; valores de composição |
| Grupo | `#1C1E1F` | `#FFFFFF` | Provisional; superfícies de grupos |
| Container de ícone | `#2B2D2E` | `#E8EAED` | Provisional; ícones de linha |
| Texto principal | `#F5F5F5` | `#17181A` | Provisional; texto principal |
| Texto secundário | `#ADB2B6` | `#50565C` | Provisional; descrições e ações |
| Rótulo de seção | `#BCC5CD` | `#50565C` | Provisional; títulos de grupos |
| Divisor | `#303234` | `#D6D9DC` | Provisional; divisores internos |

### Medidas e tipografia observadas

Os números seguintes descrevem a referência CSS, não escalas Android. Cada valor continua Provisional fora da composição em que foi observado.

| Token | Valor observado/proposto | Alcance |
|---|---:|---|
| Recuo horizontal | `16px` | Configurações |
| Intervalo entre grupos | `24px` | Configurações |
| Padding de linha | `12px` | Configurações |
| Raio de grupo / container de ícone | `16px` / `8px` | Configurações |
| Container de ícone | `36px` | Configurações |
| Título / opção / descrição / seção | `36px/700`, `16px/400`, `14px/400`, `12px/700` | CSS da composição |
| Fonte local | Segoe UI Local regular/bold | Configurações, Personalização e Aparência |
| Cabeçalho→conteúdo | `32px` | Composições profundas |
| Alvo interativo mínimo proposto | `44px` | Controles e folhas de Aparência; não equivale a dp |

### Relações aprovadas de Biblioteca

- **Hub híbrido:** o hub de Configurações também atende áreas operacionais especializadas. Configurações → Biblioteca abre a referência Biblioteca; Personalização → Layout → Biblioteca mantém seu destino atual.
- **Dependências visíveis:** para dependências entre preferências existentes, o estado-pai desabilita opções dependentes sem escondê-las. Seus valores permanecem visíveis para comunicar a configuração preservada. Esta regra não define o tratamento de capabilities indisponíveis.
- **Resumo de escolhas:** rows que representam múltiplas escolhas resumem o estado por indicação ou contagem.
- **Seleção:** folhas single-select e multi-select têm padrões distintos. Uma folha multi-select pode exigir Cancelar/Confirmar quando a aplicação das alterações não for imediata.
- **Escopo aprovado:** as composições Biblioteca e Atualização automática, e o fluxo Biblioteca → Atualização automática, são referências aprovadas. Inventário funcional e histórico das tensões ficam em `../../tensions/biblioteca-tensions.md`.

### Relações aprovadas de Integrações e MyAnimeList

- **Resumo na raiz:** cada integração usa um summary status curto, escolhido pelo estado mais útil para a próxima ação. Não concatenar configuração, habilitação e conta; para o exemplo conectado de MyAnimeList, mostrar apenas “Conectada”.
- **Eixos independentes:** configuração por Client ID, disponibilidade, habilitação global e conta são estados distintos. A raiz resume; o detalhe pode mostrar esses eixos separadamente.
- **Recursos preservados:** as sete capabilities atuais de MyAnimeList permanecem visíveis quando inativas, com valores preservados. Tracking e User Lists pertencem à área de conta, não são capability toggles.
- **Dependências de conta:** sem Client ID, Conectar permanece visível, desabilitado e acompanhado da dependência. Conectar depende da configuração, não da habilitação global. Desconectar é uma ação separada da habilitação global.
- **Marcas:** ícones genéricos podem servir como placeholders no protótipo; integrações identificáveis poderão usar seus próprios marks/logos na implementação final.
- **Escopo:** os dez itens e grupos aprovados são os definidos na raiz Integrações. Não inferir uma nova taxonomia visual a partir da arquitetura de Providers.
- **Limite:** estas relações documentam a composição e dependências aprovadas, não comprovam validade remota do Client ID, OAuth, persistência ou funcionamento Android.
### Relações aprovadas de Conta

- **Uma família relacional:** Entrar, Criar conta e Redefinir senha são estados do mesmo formulário; Conta conectada é o overview da mesma superfície. Não contar os quatro estados como quatro arquiteturas.
- **Campos e ações por estado:** labels persistentes acompanham E-mail e Senha nos estados pertinentes. Entrar oferece Entrar, Criar conta e Redefinir senha; Criar conta oferece Criar conta e voltar a Entrar; Redefinir senha contém somente E-mail, Enviar instruções e Voltar ao login, sem campo de senha.
- **Conta conectada:** o resumo mostra e-mail, sem avatar ou username, status operacional curto e última sincronização quando disponível. Sincronizar agora é ação manual e seu feedback de conclusão é transitório. A linha de status indica o destino futuro **Conta → Sincronização**; esta rodada não define nem desenha essa subpágina.
- **Sair:** ação separada, com confirmação própria e retorno de foco ao acionador ao cancelar/fechar; ao confirmar, a conta é desconectada e o foco retorna ao cabeçalho.
- **Limite:** as relações aprovadas não definem autenticação, persistência, envio real de e-mail, rede, comportamento de sync nem detalhes operacionais. Valores mostrados no protótipo são amostras locais documentadas em `../../tensions/conta-tensions.md`.
## Provisional (Leitura/Webtoon)

- Anatomia específica de controles e medidas observadas na composição Webtoon, inclusive slider de padding e seu alinhamento horizontal com o conteúdo das rows, permanece provisional e local à tela.
- Para Leitura/Webtoon, o tratamento exato de controles desabilitados e os tokens visuais novos não estão aprovados como sistema global. Em Biblioteca, está aprovado que o estado-pai desabilite dependentes entre preferências existentes sem escondê-los e preserve seus valores visíveis; esta regra não define o tratamento de capabilities indisponíveis; contraste e opacidade exatos continuam Provisional.
- Não generalizar automaticamente dimensões nem anatomia de toggle, seletor ou sheet para outras telas.

## TBD

- Collections administration + List Builder: visual final de loading/error/empty do Preview; import/export detalhado; confirmações destrutivas de remoção; reset explícito de preserved query; futuros descriptors/capabilities e comportamentos não sustentados pelo runtime. Persistência, tradução/preservação fiel de consultas, execução remota, acessibilidade e restauração Android precisam de evidência de implementação; a aprovação visual não os certifica.

- Tracking: múltiplas contas por serviço; expiração/renovação de sessão; conflitos de tracking; comportamento de servidores pessoais; preferências adicionais de sincronização não demonstradas. Autenticação/persistência reais e encaminhamento entre Tracking e configuração técnica não são comprovados pela composição.

- Perfil: cálculo histórico/retroativo de Páginas; persistência e origem de avatar/nome, favoritos, atividade e histórico; edição/troca e múltiplos perfis; ordenação e critérios das métricas/carrossel; destinos Ver todos/Ver todas e suas pesquisas/filtros. Não inferir essas regras pela composição ou pelas fixtures.

- Significado e contrato de produto de Preto/AMOLED em relação a Sistema, Claro e Escuro; não inferir semântica pela demonstração local.
- Comportamento do sistema de paletas, disponibilidade de Monet, persistência e relação entre skin e cores além do limite aprovado para onde se configura o preset visual.
- Catálogo de presets, disponibilidade de alternativas, instalação/aquisição, mecanismo de aplicação, persistência, troca real e estado desabilitado. Nada disso está definido pela composição de um único item.
- Anatomia final multi-select, dimensões e tratamento das ações de confirmação; detalhes permanecem Provisional. Se agendamento/persistência Android e outras funções não foram decididos, continuam TBD conforme `../../tensions/biblioteca-tensions.md`.
- Escalas globais e mapeamento CSS px→dp/sp; fonte Android, métricas e escala para acessibilidade.
- Valores finais de cor, contraste, bordas, raios, espaçamentos e dimensões reutilizáveis. Em particular, nenhum token claro foi aprovado globalmente.
- Sistema de ícones, estados de foco/erro/sucesso/toque, transições e comportamento de navegação fora das relações aprovadas.
- Validação remota de Client ID, OAuth real, respostas de erro e persistência da conta MyAnimeList; as interações locais do protótipo não comprovam esses comportamentos.
- Acessibilidade real, suporte a escala de fonte e comportamento em aparelho; nenhuma aprovação de composição equivale a auditoria.
- Moldura, barra de status, indicador de gesto, zoom e fundo de apresentação ficam fora dos tokens do produto.
- Para Leitura/Webtoon, comportamento funcional não determinado pela composição continua TBD: aplicação e persistência de alterações, acessibilidade Android, mapeamento CSS px para dp/sp e outras regras de implementação. Evidência de código Android informa semântica existente, mas não representa aprovação de função ou de apresentação.

## Validation targets

Alvos para avaliação futura, não resultados obtidos nem escolha antecipada de uma próxima tela:

- Avaliar textos curtos e longos, quebra natural, listas extensas e rolagem de folhas de seleção em larguras e escalas de fonte variadas; a composição atual não prova ausência de overflow nem suporte a escala.
- Observar hierarquia, agrupamento e divisores com combinações variadas de linhas e conteúdo.
- Verificar a correspondência entre affordance e ação para linhas navegáveis, seletores e alternâncias.
- Avaliar foco, ordem e semântica assistiva, alvos de toque e movimento na plataforma Android; verificar contraste por medição antes de propor padrões.
- Conferir tema escuro e claro, áreas seguras e navegação em tamanhos de tela distintos sem promover os valores CSS a dp/sp.
- Avaliar se a prévia esquemática de Preset visual se adapta aos temas claro e escuro preservando a distinção entre tema e skin; medir dimensões, proporção e responsividade antes de propor padrões reutilizáveis.
- Confirmar, com telas adicionais e evidência de produto, se componentes e relações provisórios se repetem o bastante para promover regras.
- Avaliar anatomia, medidas, controles desabilitados e tokens de Leitura/Webtoon em telas e aparelhos Android; validar aplicação, persistência, acessibilidade e mapeamento para dp/sp antes de promover qualquer detalhe local.
