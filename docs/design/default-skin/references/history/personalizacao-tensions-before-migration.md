# Design system tensions

Esta proposta aplica a referência visual atual de Configurações ao hub **Personalização**. A tela foi montada como composição para avaliação; suas extensões locais são provisórias e não promovem valores CSS a padrões globais nem alteram a Tsuzuki Default Skin aprovada.

## Cabeçalho profundo e navegação

- **Regra atual:** a referência de Configurações tem título grande e busca no cabeçalho; a navegação inferior marca Configurações na raiz. O comportamento de subpáginas permanece TBD.
- **Problema:** Personalização é uma tela profunda de Configurações. Repetir a navegação inferior sugeriria outra raiz, e um título com ação lateral disputaria largura com o nome da tela.
- **Extensão proposta:** mostrar Voltar acima do título, sem busca. Voltar retorna à reprodução fiel de Configurações, preserva a posição de rolagem e deixa Personalização disponível para reabertura. O hub não mostra navegação inferior; a raiz Settings continua sendo o único destino selecionado na navegação inferior reproduzida.
- **Escopo:** fluxo local do protótipo Personalização e retorno à raiz Configurações; não estabelece navegação global nem comportamento Android de produção.

## Título longo e intervalo de entrada

- **Regra atual:** título de tela a 36px/700 e grupos separados por 24px são valores CSS observados na referência, ainda provisórios.
- **Problema:** o título Personalização ocupa mais largura que o título da referência. Uma ação de retorno na mesma linha reduziria o espaço e poderia quebrar a hierarquia em larguras menores. O espaço entre título e primeiro grupo também não está definido pela referência.
- **Extensão proposta:** colocar Voltar em sua própria linha, permitir quebra natural do título e propor 32px entre cabeçalho e primeiro grupo. Os 32px são uma decisão local para esta composição, a revisar após avaliação visual.
- **Escopo:** título e início do conteúdo deste hub; não atualiza a escala de espaçamento global.

## Subtítulos mais informativos

- **Regra atual:** subtítulos são seletivos e usam 14px/400 na referência; diversas linhas intencionalmente não têm descrição.
- **Problema:** algumas áreas precisam de contexto suficiente para distinguir seu alcance. Em especial, Preset visual identifica o estado atual e Collections poderia ser confundida com uma configuração restrita a Personalização.
- **Extensão proposta:** manter subtítulo somente onde ele explica escopo ou estado. Preset visual mostra exatamente “Tsuzuki Default Skin”. Collections esclarece que a apresentação é global e que a personalização específica pertence à própria Collection. Evitar descrições repetitivas nas demais linhas.
- **Escopo:** texto do hub. O tamanho observado do subtítulo continua provisório e pode precisar de reavaliação com essas frases mais extensas.

## Herança visual e limites dos ícones

- **Regra atual:** a anatomia aprovada reúne ícone em caixa à esquerda, texto central, chevron à direita, superfície agrupada e divisores discretos. A referência mistura ícones preenchidos e contornados; ela não fixa uma linguagem única para novos ícones.
- **Problema:** as áreas listadas cruzam apresentação, estrutura e comportamento. Um ícone pode sugerir que há uma preferência independente quando a configuração é herdada de outro contexto.
- **Extensão proposta:** reutilizar apenas glifos da linguagem SVG já presente no artefato aprovado e manter o mesmo tratamento de caixa. Os rótulos distinguem escopo local de alcance global; nenhuma nova família de ícones ou convenção de herança é declarada.
- **Escopo:** associação entre as linhas deste hub e os glifos existentes. O sistema geral de ícones e o modelo de herança continuam em aberto.

## Limites do hub como validação

- **Regra atual:** Configurações sustenta a estrutura de grupos e linhas, mas não especifica controles nem estados de subpáginas.
- **Problema:** este hub pode demonstrar agrupamento e textos de áreas, mas não demonstra composição de seletores, alternâncias, prévias, redefinição, conflitos ou herança efetiva.
- **Extensão proposta:** abrir cada linha apenas para uma mensagem demonstrativa de escopo, com retorno ao hub. Não apresentar controles, subpáginas de produto ou nomes inventados de presets além do estado já definido.
- **Escopo:** limites intencionais desta proposta navegável; telas de configuração rica exigem desenhos e decisões próprios.

## Acessibilidade e correspondência Android

- **Regra atual:** foco, contraste medido, semântica acessível, alvos de toque e equivalência de CSS px com dp/sp não foram auditados nem aprovados pela referência.
- **Problema:** um protótipo web pode indicar hierarquia sem provar legibilidade sob escala de fonte, navegação assistiva ou métricas do Android real.
- **Extensão proposta:** tratar quebra de texto, rolagem, foco visível, alvos interativos e movimento reduzido como requisitos a verificar nesta proposta. Avaliar contrastes, ordem/semântica de foco, áreas de toque, áreas seguras, escala de fonte e mapeamento para dp/sp em Android antes de consolidar regras reutilizáveis.
- **Escopo:** critérios para avaliação futura da composição e implementação Android; nenhuma falha de renderização foi observada ou inferida, e esta proposta não representa certificação de acessibilidade.
