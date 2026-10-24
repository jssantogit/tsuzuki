# Design system tensions

> Registro de elaboração preservado. Os estados Provisional/TBD abaixo descrevem a rodada original. O fechamento visual de Settings e as decisões que a substituem estão na [especificação vigente](../design.md). Medidas Android, execução real e limites expressamente pendentes continuam sem certificação. Aquisição global e Folder Catalog não são referências aprovadas.


Este registro preserva as propostas feitas para Aparência e separa o que as aprovações das composições decidiram do que continua em aberto. A aprovação confirma a composição e relações explicitadas; não transforma componentes demonstrativos nem valores CSS em padrões globais.

## Cabeçalho profundo e navegação

- **Regra de referência:** Configurações tem busca no cabeçalho e navegação inferior na raiz.
- **Problema considerado:** repetir a navegação inferior numa tela profunda poderia sugerir outra raiz; colocar Voltar ao lado de um título longo reduziria o espaço disponível.
- **Resultado aprovado:** nas composições de Personalização e Aparência, Voltar aparece acima do título e não há navegação inferior. Configurações continua sendo a raiz representada com navegação inferior. Esta relação é recorrente nas duas composições aprovadas.
- **Alcance:** relação visual dessas composições; navegação global de produto permanece em aberto.
- **Status:** Approved para essa relação recorrente, sem definir outros destinos ou estados de navegação.

## Seletor visual e alternâncias

- **Regra de referência:** a anatomia aprovada de Configurações mostra grupos e linhas, mas não define seletor exclusivo, prévias, alternâncias ou estados indisponíveis.
- **Proposta preservada:** três prévias abstratas e opções Sistema, Claro e Escuro com marca textual/ícone; alternâncias usam forma e estado anunciado, com desabilitação visual.
- **Resultado aprovado:** a composição de Aparência foi aprovada, mas a densidade, anatomia e comportamento desses componentes não foram promovidos a padrão reutilizável.
- **Pendência:** a semântica de Preto/AMOLED perante Sistema, Claro e Escuro é TBD de produto. A demonstração local que desabilitava AMOLED quando Claro estava efetivo e o ligava a fundo preto é apenas histórica, não constitui contrato.
- **Alcance:** componentes desta composição.
- **Status:** Provisional para seletores, alternâncias e seus estados; semântica AMOLED TBD.

## Tema claro e papéis de cor

- **Regra de referência:** as configurações aprovadas mostram uma composição escura. Valores escuros são observações CSS, não tokens finais.
- **Proposta preservada:** Claro demonstrou fundo `#F7F7F8`, grupo `#FFFFFF`, ícone `#E8EAED`, texto principal `#17181A`, secundário/rótulo `#50565C` e divisor `#D6D9DC`; a composição escura usa os valores já observados na referência.
- **Resultado aprovado:** papéis como fundo, grupo, container de ícone, texto principal/secundário, seção e divisor podem ser comparados entre temas, mantendo valores separados por tema.
- **Pendência:** valores claros continuam provisórios e não aprovam uma paleta clara global. Valores escuros também continuam provisórios.
- **Alcance:** tokens semânticos locais às composições.
- **Status:** papéis compartilhados aprovados como organização documental; valores de ambos os temas Provisional.

## Skin atual e paleta configurável

- **Regra de referência:** a Personalização identifica Tsuzuki Default Skin como preset visual atual; a implementação tem enum de paletas com entradas tituladas e entradas deprecated sem título.
- **Proposta preservada:** identificar a skin, separar a escolha de paleta e listar somente opções com título comprovado: Default, Monet, Catppuccin, Green Apple, Lavender, Midnight Dusk, Nord, Strawberry Daiquiri, Tako, Teal Turquoise, Tidal Wave, Yin Yang, Yotsuba, Tokyo Night e Monochrome.
- **Resultado aprovado:** Tsuzuki Default Skin em Aparência é somente estado contextual. Configurar o preset visual pertence exclusivamente a Personalização → Preset visual.
- **Pendência:** Monet não implica compatibilidade universal. A disponibilidade e o efeito das paletas, persistência e nomenclatura de produto precisam de confirmação no produto.
- **Alcance:** localização do controle de preset e nomes existentes; não especifica comportamento de implementação.
- **Status:** Approved para o limite contextual/configurável; opções e comportamento de paleta Provisional/TBD conforme produto.

## Opções de exibição, textos e listas longas

- **Regra de referência:** Configurações permite descrições seletivas; enums consultados comprovam formatos de data e modos de tablet existentes. O código não comprova um catálogo completo de idiomas.
- **Proposta preservada:** exemplos locais para Sistema, Português (Brasil), English; modos Automatic, Always, Landscape, Never; formatos `''`, `MM/dd/yy`, `dd/MM/yy`, `yyyy-MM-dd`, `dd MMM yyyy`, `MMM dd, yyyy`; interruptores iniciam nos valores de demonstração informados pelas preferências padrão examinadas.
- **Resultado aprovado:** textos e valores fazem parte da composição aprovada; quebra natural ajuda a preservar descrições e nomes longos.
- **Pendência:** idiomas mostrados não são catálogo completo. Exemplos e defaults do protótipo não descrevem preferências do usuário. Densidade das folhas, seleção e apresentação de listas extensas seguem Provisional; rolagem é a evidência local, virtualização e paginação não foram decididas.
- **Alcance:** nomes/valores de referência e tratamento textual da composição.
- **Status:** valores-fonte existentes têm evidência de implementação; layout, exemplos, listas e estados demonstrativos Provisional.

## Folhas de seleção e retorno

- **Regra de referência:** as composições profundas mostram Voltar acima do título; a hierarquia de controles mais profundos não tinha regra aprovada.
- **Proposta preservada:** folha/modal contida na tela do telefone, com fechamento, foco inicial e retorno de foco ao acionador; a composição local ilustra retorno Aparência → Personalização → Configurações.
- **Resultado aprovado:** o fluxo visual e a existência das opções aparecem na composição aprovada.
- **Pendência:** anatomia e densidade de bottom sheets continuam Provisional até outras telas fornecerem evidência. Retenção de rolagem, foco, fechamento por Escape e implementação real não foram certificados pela aprovação.
- **Alcance:** fluxo desenhado, sem estabelecer implementação Android.
- **Status:** Provisional para componente e interações.

## Responsividade e acessibilidade

- **Regra de referência:** texto pode ter descrição seletiva; Segoe UI Local é uma fonte local às composições. Nenhuma das três referências audita foco, contraste, escala de fonte, semântica ou alvos em Android.
- **Proposta preservada:** quebra normal de texto, foco visível, nomes/estados acessíveis, áreas seguras, preferência por movimento reduzido e áreas interativas propostas de ao menos 44 CSS px.
- **Resultado aprovado:** textos com diferentes comprimentos e quebras fazem parte das composições.
- **Pendência:** quebra visível não prova suporte a escala de fonte nem ausência de overflow em outras larguras; 44px não equivale a 44dp. Segoe UI Local e métricas Android permanecem provisórios.
- **Alcance:** critérios para validação futura, não resultado nem certificação.
- **Status:** Provisional.
