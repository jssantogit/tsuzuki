# Design system tensions

> Registro de elaboração preservado. Os estados Provisional/TBD abaixo descrevem a rodada original. O fechamento visual de Settings e as decisões que a substituem estão na [especificação vigente](../design.md). Medidas Android, execução real e limites expressamente pendentes continuam sem certificação. Aquisição global e Folder Catalog não são referências aprovadas.


Este registro documenta a composição aprovada de **Preset visual** e delimita o que ela comprova. As relações explicitamente validadas são Approved; a composição não promove anatomia, medidas ou comportamento sem evidência a regras gerais.

## Entrada e navegação

- **Regra:** Configurações é a raiz representada com busca e navegação inferior; Personalização e Aparência usam Voltar acima do título e não exibem navegação inferior. Personalização → Preset visual é o único ponto de seleção de preset.
- **Problema:** a tela precisa manter uma relação compreensível com o hub Personalização sem confundir seleção de preset com as preferências de Aparência.
- **Solução:** Preset visual aparece como destino de Personalização. A composição usa Voltar acima do título e não mostra navegação inferior, seguindo a relação aprovada para subtelas.
- **Alcance:** relação de entrada e composição destas telas; não define navegação global, restauração de foco ou preservação de rolagem.
- **Status:** Approved para Personalização como ponto único de seleção e para a relação de subtela representada. Destino inicial direto, retorno, foco e preservação de rolagem permanecem Provisional/TBD conforme a implementação.

## Preset e preferências de Aparência

- **Regra:** Tsuzuki Default Skin é o preset atual. A seleção de preset acontece somente em Personalização → Preset visual. “Skin atual” em Aparência é informativa. Tema é uma preferência distinta: a skin existe nos temas claro e escuro, e selecionar um tema não troca a skin. O preset não altera a arquitetura funcional do produto.
- **Problema:** sem escopos distintos, a identificação de skin em Aparência pode parecer outro seletor ou o tema pode ser confundido com um preset.
- **Solução:** Preset visual apresenta e seleciona a skin; Aparência apenas informa a skin atual e mantém seu papel de preferências de aparência. A prévia de skin se adapta ao tema atual sem converter tema em preset.
- **Alcance:** distinção conceitual e localização dos controles; não define persistência, aplicação ou semântica adicional de controles existentes.
- **Status:** Approved para localização única, Skin atual informativa, distinção Skin/Preset e Aparência, independência entre tema e skin e ausência de alteração da arquitetura funcional.

## Cartão selecionável, estado e prévia

- **Regra:** a composição aprovada apresenta um cartão visual selecionável para Tsuzuki Default Skin. O estado selecionado tem sinais redundantes: tratamento visual e texto. A prévia esquemática representa a linguagem visual da skin, não uma captura literal de tela, e adapta sua apresentação ao tema atual.
- **Problema:** um item único precisa comunicar a skin e seu estado atual sem alegar que a prévia é uma tela real nem sugerir um catálogo não definido.
- **Solução:** mostrar cartão, prévia esquemática e estado selecionado por tratamento visual mais texto, respeitando o tema atual. Não apresentar conteúdo de tela como screenshot literal.
- **Alcance:** relações visuais aprovadas nesta composição. A aprovação não fixa anatomia reutilizável, medidas, proporção, tokens, detalhes de interação nem responsividade global.
- **Status:** Approved para existência do cartão selecionável e da prévia esquemática, representação não literal, adaptação conceitual aos temas claro/escuro e redundância visual+texto do estado selecionado. Provisional para generalização do componente, anatomia, dimensões, proporção, tokens, interação detalhada e responsividade global.

## Tokens, proporção e acessibilidade

- **Regra:** as composições existentes observam fundo `#0B0C0D`, superfície `#1C1E1F`, container de ícone `#2B2D2E`, texto `#F5F5F5`, secundário `#ADB2B6`, rótulo `#BCC5CD` e divisor `#303234`. Segoe UI Local, título `36px/700`, opção `16px`, helper `14px`, label `12px uppercase`, margem `16px`, intervalo `24px` e raio `16px` são valores locais provisórios. `OD-LAYOUT-PRIMITIVES` descreve estrutura e fluxo.
- **Problema:** valores de composição não determinam medidas reutilizáveis, equivalência Android, legibilidade da prévia ou comportamento em telas estreitas e com texto ampliado.
- **Solução:** manter nome com quebra normal e conteúdo em fluxo. Usar a adaptação ao tema aprovada como relação visual, sem inferir valores de cor, dimensão ou proporção.
- **Alcance:** valores CSS e exemplos desta composição; não estabelece tokens globais, mapeamento CSS px→dp/sp, foco, alvo de toque ou conformidade de acessibilidade.
- **Status:** Provisional para valores, anatomia, proporção, dimensões, foco, alvos e responsividade. Valores de tema claro, acessibilidade Android e tokens finais permanecem TBD.

## Limites de seleção e comportamento

- **Regra:** somente Tsuzuki Default Skin está representada como preset real selecionado. A composição com um item não demonstra troca real.
- **Problema:** uma opção não estabelece o comportamento perante alternativas nem como o sistema aplica e guarda uma seleção.
- **Solução:** documentar apenas o estado visual atual. Não inferir catálogo, download, instalação, aquisição, aplicação ou persistência.
- **Alcance:** relações de apresentação aprovadas nesta composição; comportamentos e estados não demonstrados não são especificados.
- **Status:** Approved para representar o preset atual na composição. Provisional para o tratamento da seleção no protótipo. TBD para alternativas, troca real, aplicação, persistência, estado desabilitado e catálogo/download/instalação/aquisição.

## Limitações de validação

A aprovação comprova relações visuais explícitas, não valida uma troca real entre presets. Adaptação conceitual da prévia a temas claro e escuro está aprovada; valores de cor, responsividade, quebra do nome, escala de fonte, teclado e acessibilidade na implementação ainda precisam de evidência. Uma única opção não demonstra aplicação, persistência ou estado desabilitado. AMOLED permanece TBD.
