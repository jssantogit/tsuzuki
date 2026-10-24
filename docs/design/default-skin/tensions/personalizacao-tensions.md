# Design system tensions

> Registro de elaboração preservado. Os estados Provisional/TBD abaixo descrevem a rodada original. O fechamento visual de Settings e as decisões que a substituem estão na [especificação vigente](../design.md). Medidas Android, execução real e limites expressamente pendentes continuam sem certificação. Aquisição global e Folder Catalog não são referências aprovadas.


Este registro preserva as propostas originais para o hub **Personalização** e descreve o resultado à luz das aprovações posteriores de Personalização e Aparência. A aprovação confirma as composições e relações explicitadas; medidas e componentes introduzidos continuam locais ou provisórios.

## Cabeçalho profundo e navegação

- **Regra de referência:** Configurações tem título grande, busca e navegação inferior na raiz; a referência inicial não definia subtelas.
- **Problema considerado:** Personalização é profunda em relação a Configurações; navegação inferior repetida poderia indicar uma raiz diferente e ação junto ao título reduziria largura.
- **Proposta original:** Voltar acima do título, sem busca nem navegação inferior, retornando a Configurações com posição de rolagem preservada.
- **Resultado aprovado:** a composição Personalização mantém Voltar acima do título e não mostra navegação inferior. A composição posterior Aparência repete a relação. Só Configurações exibe a navegação inferior nas três composições aprovadas.
- **Alcance:** relação recorrente entre estas subtelas e a raiz desenhada, não uma especificação de navegação global.
- **Status:** Approved para a relação visual recorrente.

## Título e intervalo de entrada

- **Regra de referência:** título `36px/700` e ritmo de grupos observados em CSS são medidas provisórias, sem escala Android aprovada.
- **Problema considerado:** título Personalização é mais longo e pode disputar espaço com Voltar; a referência não determina a distância entre cabeçalho profundo e conteúdo.
- **Proposta e resultado:** Voltar ocupa sua linha; o texto pode quebrar naturalmente. O intervalo de `32px` aparece nas composições profundas e foi aprovado como parte delas.
- **Alcance:** composições profundas atuais, não uma escala global de títulos ou espaçamento.
- **Status:** relação visual (ação acima do título e possibilidade de quebra) Approved; métrica CSS e generalização de `32px` Provisional.

## Texto de estado e escopo do preset

- **Regra de referência:** subtítulos são seletivos; valores CSS de texto são provisórios.
- **Problema considerado:** o usuário precisava distinguir identidade visual atual de áreas configuráveis e esclarecer o escopo global de Collections.
- **Proposta original:** mostrar “Tsuzuki Default Skin” junto ao item Preset visual; explicar que Collections tem apresentação global e personalização própria.
- **Resultado aprovado:** na Personalização, Preset visual identifica o preset atual e é o único lugar para configurá-lo. Em Aparência, Tsuzuki Default Skin é apenas um estado contextual; não é controle de configuração ali.
- **Alcance:** distinção entre identificação e localização da configuração do preset, e texto de escopo de Collections.
- **Status:** Approved para essa regra de produto e informação. Tamanho, largura, quebra e valores CSS continuam Provisional.

## Hierarquia, grupos e ícones

- **Regra de referência:** Configurações sustenta grupos em superfície única, divisores discretos, ícone à esquerda, conteúdo no centro e ação à direita. A mistura de ícones preenchidos/contornados não aprova um estilo único.
- **Problema considerado:** áreas do hub cruzam apresentação, estrutura e comportamento; o ícone pode sugerir preferência independente onde há herança.
- **Proposta original e resultado:** reutilizar anatomia de grupo/linha e glifos disponíveis, usando rótulos para explicar escopo. Personalização e Aparência aprovadas mantêm relações de grupo e hierarquia.
- **Alcance:** estas composições e os ícones nelas mostrados; modelo de herança e sistema de ícones permanecem indefinidos.
- **Status:** relações recorrentes de hierarquia e agrupamento Approved. Regras de ícone, herança e dimensões globais Provisional/TBD.

## Limites do hub e destinos

- **Regra de referência:** a primeira referência de Configurações não especificava estados de subtelas.
- **Proposta original:** linhas do hub poderiam abrir mensagem local de escopo sem inventar controles ou presets.
- **Resultado aprovado:** Personalização contém Aparência como destino configurável; Preset visual é o ponto de configuração exclusivo do preset. Outros itens e mensagens demonstrativas não definem telas ou comportamento de produto.
- **Alcance:** organização visual aprovada do hub e limite de configuração do preset.
- **Status:** Aparência e Preset visual como destinos descritos são parte da composição aprovada. Destinos funcionais restantes e estados reais continuam TBD.

## Quebra de texto, densidade e acessibilidade

- **Regra de referência:** subtítulos seletivos e valores de tipografia da referência são observados, não validados em Android.
- **Problema considerado:** descrições extensas como a de Collections podem mudar alinhamento e altura das linhas; um protótipo web não prova leitura com escala de fonte, foco assistivo ou métricas Android.
- **Proposta original e resultado:** usar quebra normal de texto, agrupamento, rolagem, foco visível, alvos interativos e áreas seguras como pontos a avaliar. Textos com comprimentos diversos aparecem nas composições aprovadas.
- **Alcance:** tratamento visual dos textos e critérios para validação futura; não certifica acessibilidade, ausência de overflow, nem equivalência CSS px↔dp/sp.
- **Status:** quebra de texto observada/Approved como conteúdo da composição; comportamento em escala de fonte, densidade, acessibilidade e dimensões Provisional/TBD.
