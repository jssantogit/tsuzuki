# Perfil — tensões de design

> Registro de elaboração preservado. Os estados Provisional/TBD abaixo descrevem a rodada original. O fechamento visual de Settings e as decisões que a substituem estão na [especificação vigente](../design.md). Medidas Android, execução real e limites expressamente pendentes continuam sem certificação. Aquisição global e Folder Catalog não são referências aprovadas.


## Marco aprovado e alcance

Perfil está aprovado como referência oficial da Tsuzuki Default Skin, como apresentado. Esta consolidação documental não altera o HTML. `design.md` é a fonte detalhada das relações aprovadas; o Notion resume decisões/estado. A aprovação não certifica implementação Android, acessibilidade, cálculo de métricas ou tokens globais.

### Classificação dos padrões

| Padrão | Approved por decisão explícita | Provisional / TBD |
|---|---|---|
| Profile identity header | Avatar e nome como identidade principal; respiro generoso | Medidas, proporções exatas, tipografia e tokens Provisional; persistência TBD |
| Statistic trio | Concluídas / Capítulos / Páginas, numerais dominantes, faixa horizontal sem cards pesados | Geometria/tokens Provisional; cálculo histórico de Páginas TBD |
| Current-reading carousel | Um card largo completo por vez, 1–5 obras, cover/título/capítulo/progresso/percentual e swipe sem próximo card visível | Anatomia reutilizável e medidas Provisional; origem, ordenação e persistência TBD |
| Pagination indicator | Pontos, atual preenchido e demais neutros | Dimensões, contraste exato, foco e tratamento assistivo Provisional |
| Root-screen utility actions | Engrenagem à esquerda, ⋯ à direita, sem título Perfil; bottom nav Início · Busca · Biblioteca · Perfil | Styling e medidas Provisional; editar/trocar e múltiplos perfis TBD |
| Profile content rails | Favoritos = identidade/gosto; Concluídas recentemente = histórico; trilhos horizontais distintos | Medidas/densidade reutilizáveis Provisional; destinos completos e ordenação TBD |

Estado após scroll é a mesma tela: identidade e métricas saem naturalmente da viewport. Não requer segunda instância nem label Lendo agora visível naquela posição. Lendo agora representa atividade/progresso, não catálogo; Concluídas abrange os formatos suportados e o histórico recente não equivale ao total. Fixtures não determinam catálogo ou comportamento do produto.

## Refinamento local com impeccable

- **Regra atual:** a composição segue Identidade → métricas → Lendo agora → Favoritos → Concluídas recentemente, na linguagem da Default Skin e em uma única tela rolável.
- **Problema:** a divisória horizontal abaixo de Favoritos foi rejeitada; o contorno da engrenagem estava deformado. A captura também mostrou tipografia divergente, sem uma sobrescrita serif comprovada na fonte atual.
- **Solução proposta:** retirar a divisória e separar os trilhos pelo espaço existente entre grupos; substituir a engrenagem por um contorno SVG simétrico com centro circular, `currentColor`, tamanho de 24px e botão de 44px preservados. Explicitar a família local Segoe UI no wrapper e nos controles de Perfil como proteção de herança, com fallback sans-serif. Manter os tamanhos legíveis atuais e ajustar somente o ritmo local: avatar → nome 16px, nome → métricas 32px, identidade → conteúdo 40px e heading → conteúdo 12px.
- **Alcance:** somente Perfil. Avatar de 128px, nome de 32px, largura dos trilhos, geometria integral das capas, card único, navegação, temas e moldura compartilhada permanecem na mesma composição. A referência orienta proporções; a remoção explícita da linha prevalece sobre qualquer detalhe da imagem.
- **Status sugerido:** Provisional para medidas, proteção tipográfica e tratamento do ícone. Estes refinamentos não promovem tokens globais. Foram realizados antes da aprovação; esta rodada apenas consolida a documentação.

A leitura da fonte confirmou declarações de Segoe UI Local e os dois arquivos de fonte locais existentes; isso não comprova a causa da divergência na captura. Não foi feita renderização, inspeção ou teste após a gravação final desta rodada.

## Escopo desta composição

Perfil é uma superfície raiz na Tsuzuki Default Skin. A composição aprovada aplica a navegação Início · Busca · Biblioteca · Perfil; Configurações sai da navegação inferior e fica no botão de engrenagem do Perfil. A referência anexada define estrutura e proporções aproximadas, não uma especificação de aparência iOS. A composição é referência oficial; sua evidência é consolidada em `design.md` e resumida na memória do projeto, sem alterar o artefato visual.

### Correção de apresentação

A apresentação atual usa **uma instância do Perfil no estado inicial**, sem legenda/board comparativo. A versão com duas instâncias lado a lado e a rolagem programática do segundo frame foi supersedida. O shell do telefone fica em escala natural (`zoom: 1`); o zoom do visualizador externo da Open Design está fora do controle desta composição. O app não aplica escala ou transformação própria.

Para melhorar leitura na escala natural, os títulos dos trilhos e a navegação inferior usam 14px; os rótulos das métricas usam 16px quando cabem na faixa de três colunas. Esses tamanhos permanecem Provisional e específicos desta composição, mesmo após a aprovação visual, sem promover tokens globais.

## Tensions — histórico e resolução

As regras atuais abaixo descrevem o ponto de partida da rodada. As soluções estruturais foram aprovadas explicitamente; seus detalhes locais permanecem classificados conforme cada item.

### Cabeçalho de identidade do Perfil

- **Regra atual:** telas raiz aprovadas usam título no cabeçalho; cabeçalho profundo mantém Voltar acima do título.
- **Problema:** Perfil usa nome centralizado como título visual e mantém apenas ações utilitárias no topo, sem título textual da tela.
- **Solução proposta:** avatar centralizado, nome como maior bloco de texto da identidade e ações de engrenagem/menu no cabeçalho compacto. A relação foi aprovada explicitamente; preservar as medidas locais como Provisional.
- **Alcance:** somente Perfil nesta rodada.
- **Status:** Approved para a relação do identity header e ações utilitárias; anatomia/medidas/tokens Provisional.

### Trio de métricas

- **Regra atual:** estatísticas usam numeral e rótulo como blocos distintos; a aprovação não define uma faixa de métricas de identidade.
- **Problema:** três métricas precisam manter leitura horizontal sem se tornarem três cartões pesados.
- **Solução proposta:** trio em uma única faixa, números dominantes, rótulos em segunda linha e divisores sutis entre colunas. “Concluídas” agrega formatos suportados; “Páginas” mantém valor demonstrativo sem inferir histórico ou cálculo.
- **Alcance:** composição Perfil.
- **Status:** Approved para o trio e hierarquia numeral/label; medidas, divisores e tokens específicos Provisional. Definição do cálculo histórico de Páginas permanece TBD.

### Carrossel Lendo agora

- **Regra atual:** cards e trilhos existentes não validam um card de progresso em carrossel com paginação.
- **Problema:** atividade atual precisa apresentar contexto de leitura sem se tornar uma fileira de capas nem antecipar parcialmente o próximo item.
- **Solução proposta:** card largo único com capa completa à esquerda, título, capítulo, progresso e percentual; até cinco páginas, swipe horizontal e pontos de paginação com estado atual redundante por preenchimento.
- **Alcance:** somente Lendo agora em Perfil.
- **Status:** Approved para card único, conteúdo relacional, swipe e paginação por pontos; medidas/anatomia reutilizável Provisional. Origem, ordenação e persistência do progresso não são definidas pela composição.

### Trilhos de Perfil

- **Regra atual:** trilhos aprovados de biblioteca não determinam trilhos com semânticas diferentes na identidade do usuário.
- **Problema:** Favoritos, identidade/gosto, e Concluídas recentemente, histórico, precisam compartilhar a linguagem de capas sem se confundir com atividade de leitura ou com a métrica total.
- **Solução proposta:** capas verticais em dois trilhos separados, títulos e ações “Ver todos”/“Ver todas”; sem progresso nas capas. Os destinos completos permanecem fora da composição.
- **Alcance:** somente Perfil.
- **Status:** Approved para trilhos separados e seus papéis semânticos; medidas/densidade reutilizáveis Provisional. Ordenação, catálogo, pesquisa e filtros dos destinos são TBD.

### Navegação raiz e quadros comparativos

- **Regra atual:** a referência de Configurações usa essa área na navegação inferior; composições aprovadas não validam Perfil como destino raiz com duas ações contextuais no topo.
- **Problema:** a mudança global autorizada remove Configurações da barra inferior e a imagem pede duas posições de rolagem da mesma tela.
- **Solução proposta:** Perfil como quarto destino, engrenagem para Configurações, menu contextual para ações futuras de Perfil, sem texto “Perfil” no topo. Exibir uma única instância rolável do Perfil; a posição após scroll é um estado natural da mesma tela, não um segundo aparelho.
- **Alcance:** navegação principal conforme instrução explícita; styling e anatomia do cabeçalho permanecem locais a Perfil.
- **Status:** Approved para mudança de navegação, ações de topo e uma única tela com estados de scroll; styling e medidas Provisional. Semântica e arquitetura de “Editar perfil” e “Trocar perfil” permanecem TBD; não implementar múltiplos perfis nesta rodada.

## Dados demonstrativos

João e os números 28 obras concluídas, 1.240 capítulos e 18.560 páginas são exemplos transcritos da imagem conceitual, não dados confirmados de uma conta. As obras, capítulos e percentuais mostrados na tela são exemplos locais consistentes e servem somente para avaliar a composição. Nenhum deles comprova regras de sincronização, cálculo histórico ou conteúdo real da biblioteca.

## Fora de escopo e TBD

- Cálculo ou reconstrução retroativa da métrica Páginas.
- Persistência de avatar, nome, favoritos, leituras ativas e histórico.
- Como perfis são editados ou trocados e se há suporte a múltiplos perfis.
- Conteúdo, pesquisa, filtros, ordenação e capacidade dos destinos “Ver todos” e “Ver todas”.
- Regras de ordenação e limite real do carrossel além do intervalo visual de uma a cinco obras estabelecido para esta composição.
- Regras de foco, rolagem restaurada e carregamento de dados no aplicativo nativo.

## Limite de assets

Capas de obras reais devem ser localizadas no projeto, renderizadas no frame completo e dimensionadas pela razão intrínseca lida de cada arquivo. A consulta à skill dinâmica de media inputs não retornou corpo de orientação utilizável nesta execução; foram aplicadas as regras de aquisição e geometria presentes no contrato de produção.

As capas abaixo foram consultadas e localizadas nesta proposta pela Open Library Covers API. Dimensões intrínsecas foram lidas do arquivo antes de definir seus containers. Os livros continuam pertencendo aos seus respectivos titulares; os arquivos servem como imagens de referência para esta validação visual.

| Arquivo local | Obra / edição | ISBN consultado | Dimensão intrínseca |
|---|---|---:|---:|
| `assets/perfil/one-piece-v01.jpg` | One Piece, vol. 1 | `9781569319017` | 324 × 475 |
| `assets/perfil/one-piece-v02.jpg` | One Piece, vol. 2 | `9781569319024` | 320 × 500 |
| `assets/perfil/one-piece-v03.jpg` | One Piece, vol. 3 | `9781569319031` | 300 × 450 |
| `assets/perfil/fullmetal-alchemist-v01.jpg` | Fullmetal Alchemist, vol. 1 | `9781591169208` | 333 × 500 |
| `assets/perfil/death-note-v01.jpg` | Death Note, vol. 1 | `9781421501680` | 329 × 500 |
| `assets/perfil/attack-on-titan-v01.jpg` | Attack on Titan, vol. 1 | `9781612620244` | 322 × 500 |
| `assets/perfil/witch-hat-atelier-v01.jpg` | Witch Hat Atelier, vol. 1 | `9781632367709` | 348 × 500 |

Consulta: `https://covers.openlibrary.org/b/isbn/<ISBN>-L.jpg?default=false`. Nenhuma capa foi obtida para os ISBNs tentados de Dungeon Meshi e Fruits Basket; esses títulos não são usados como se a identidade da imagem tivesse sido confirmada.


### Moldura persistente entre Perfil e Configurações

- **Regra atual:** o protótipo apresenta as telas no shell Android, com chrome de sistema e áreas seguras; a versão anterior mantinha duas molduras independentes e aplicava escalas diferentes a Perfil e Configurações.
- **Problema:** navegar pela engrenagem substituía o aparelho inteiro e reduzia Configurações, criando discrepância de tamanho e legibilidade.
- **Solução proposta:** uma única moldura Android em escala natural, com um único conjunto de hardware, barra de status e indicador de gesto. Perfil e Configurações alternam somente seu conteúdo dentro de `.phone-content`; o retorno preserva a instância, o carrossel, a rolagem e o foco de Perfil. O tema efetivo é compartilhado com o chrome do aparelho.
- **Alcance:** apresentação e navegação do protótipo inteiro; nenhuma mudança na arquitetura funcional, nos tokens globais aprovados ou na composição das telas. Em janelas baixas, o documento pode rolar em vez de reduzir o aparelho; o fallback estreito existente permanece compartilhado.
- **Status sugerido:** Provisional para a apresentação do protótipo. A forma final do shell não é um componente da Tsuzuki Default Skin nem uma regra de navegação nativa.
