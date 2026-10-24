# Design system tensions — Leitura

> Registro de elaboração preservado. Os estados Provisional/TBD abaixo descrevem a rodada original. O fechamento visual de Settings e as decisões que a substituem estão na [especificação vigente](../design.md). Medidas Android, execução real e limites expressamente pendentes continuam sem certificação. Aquisição global e Folder Catalog não são referências aprovadas.


Esta documentação registra Leitura e Webtoon como referências Default Skin aprovadas nos limites descritos abaixo e em `design.md`. O protótipo simula preferências localmente e não altera o Reader Android.

## Composição principal de Leitura

- **Regra atual:** hub híbrido combina preferências universais curtas com grupos especializados acessíveis por drilldown; uma subpágina especializada pode conter vários grupos densos do mesmo domínio. Grupos semânticos organizam telas longas; rows navegáveis mostram título, valor atual e chevron sem ação redundante.
- **Problema:** o Reader contém várias dezenas de opções e não deve virar uma lista longa nem espalhar controles em acordeões inline.
- **Solução aprovada para esta tela:** cinco grupos na ordem Conteúdo, Exibição, Comportamento, Modos de leitura e Navegação e ações. Conteúdo mostra idiomas preferidos e fallback; Exibição mostra orientação, fundo, fullscreen, cutout, tela ligada, número de página, transições e destino E-Ink; Comportamento mostra as quatro opções de capítulo; Modos de leitura aponta para Páginas e Webtoon; Navegação e ações aponta para Controles durante leitura. Voltar fica acima do título e não há bottom navigation. Destinos de drilldown usam título, valor atual e chevron.
- **Alcance:** composição principal de Configurações → Leitura e relações híbridas de hub/drilldown, grupos semânticos, navegação profunda e apresentação de rows explicitamente aprovadas.
- **Status:** Approved para essas relações e composição. Anatomia e medidas específicas de controles permanecem Provisional; comportamento funcional permanece TBD.

## Primeira proposta de Leitura

- **Regra atual:** uma proposta só vira referência aprovada após validação explícita do usuário.
- **Problema:** a versão inicial usava expansões inline para E-Ink, Webtoon e Navegação e ações, e uma tela secundária Páginas. Essa versão foi substituída antes da aprovação.
- **Solução:** tratá-la como composição não aprovada; não conservar suas expansões ou subpágina Páginas como decisão de design.
- **Alcance:** histórico da validação de Leitura.
- **Status:** registro histórico; a primeira proposta não foi aprovada e foi substituída. Não constitui decisão vigente.

## Webtoon como tela secundária

- **Regra atual:** navegação profunda Configurações → Leitura → Webtoon usa Voltar acima do título grande e não exibe navegação inferior. Relações compartilhadas aprovadas em `design.md` valem nos limites registrados; controles específicos do Reader não se tornam componentes globais por esta aprovação.
- **Problema:** Webtoon agrupa navegação por toque, opções de exibição, zoom e tratamento de imagens largas com dependências entre ações.
- **Solução aprovada:** uma tela com quatro grupos, nesta ordem: Navegação (toque e inversão), Exibição (padding lateral, threshold, crop), Zoom (zoom por toque duplo, impedir zoom out) e Imagens largas (dividir, inverter divisão, girar para caber, inverter rotação). O slider de padding ocupa a mesma área horizontal de conteúdo das rows. Dependências são comunicadas visualmente sem remover preferências silenciosamente.
- **Alcance:** somente a subpágina Webtoon; Leitura principal é preservada. A aprovação não generaliza anatomia, dimensões ou controles para outras telas.
- **Status:** Approved para estrutura, relações e composição descritas. Anatomia e medidas do slider, tratamento exato de disabled e tokens novos permanecem Provisional. Aplicação, persistência e comportamento funcional não definidos pela composição permanecem TBD.

## Destinos conceituais não demonstrados

- **Regra atual:** protótipos devem comunicar o recorte sem inventar indisponibilidade de produto.
- **Problema:** Páginas, E-Ink e Controles durante leitura são áreas reais no Reader, mas não são subpáginas demonstradas nesta validação.
- **Solução:** mantê-las como destinos na composição principal, sem criar configuração fictícia, tela vazia ou estado de produto indisponível. A tela Webtoon é a única subpágina implementada nesta rodada.
- **Alcance:** protótipo de Leitura; omissão visual não indica que uma área não exista no produto.
- **Status:** composição interna TBD; hierarquia da principal Approved.

## Idiomas e fallback

- **Regra atual:** a fonte Android armazena códigos de idioma como lista ordenada múltipla. A ordem classifica alternativas e não exclui idiomas. Lista vazia significa nenhuma preferência.
- **Problema:** confundir fallback de conteúdo com fallback de idioma muda o significado da configuração.
- **Solução:** manter campos separados. Fallback automático tenta outro add-on quando a fonte preferida não consegue fornecer o capítulo; default `false`.
- **Alcance:** Conteúdo na principal de Leitura.
- **Status:** semântica e defaults têm evidência de código; apresentação/edição continuam Provisional.

## Dependências e termos técnicos

- **Regra atual:** o código Android comprova estados e dependências atuais, não uma regra visual global.
- **Problema:** inversões dependem das respectivas opções de navegação, split ou rotação. Split e rotação são exclusivas. Labels longos e termos como threshold, crop e imagens largas exigem contexto sem ocultar ações.
- **Solução:** representar dependências visualmente e manter as preferências disponíveis na interface, sem remoção silenciosa. Textos podem quebrar naturalmente. Semântica e exclusividade de opções observadas no código são evidência da implementação existente, não aprovação de comportamento funcional nem definição do tratamento visual exato de disabled.
- **Alcance:** Webtoon desta composição.
- **Status:** evidência de código Android sobre semântica não constitui aprovação funcional. Apresentação das dependências é Approved em princípio; tratamento exato de disabled permanece Provisional.

## Inventário existente e detalhes adiados

O inventário completo observado em `SettingsReaderScreen.kt` e `ReaderPreferences.kt` permanece registrado como conteúdo do produto, embora só parte apareça na proposta principal/secundária:

- **Conteúdo:** idiomas preferidos ordenados e fallback automático para outro add-on quando a fonte preferida não tem o capítulo.
- **Exibição:** rotação, fundo do Reader, fullscreen, cutout, manter tela ligada, número de página, transições. **E-Ink:** flash, duração, intervalo e cor.
- **Comportamento:** pular lidos, filtrados e duplicados; transição entre capítulos.
- **Páginas, adiado:** modo padrão, navegação por toque/inversão, escala, início do zoom, crop, zoom em paisagem, navegação até o ponto ampliado, exibição de duas páginas (`Nunca`, `Sempre`, `Em imagens largas`), dividir imagens largas/inverter divisão, girar imagem para caber/inverter rotação. A política de duas páginas é distinta das operações de split/rotação sobre imagem; split e rotação são mutuamente exclusivos.
- **Webtoon:** navegação por toque/inversão, padding lateral, threshold, crop, zoom por toque duplo, impedir zoom out, dividir/girar imagens largas e inversões.
- **Controles durante leitura, adiado:** teclas de volume/inversão, navegador vertical por modo/lado/altura, indicadores de modo e navegação, ações no toque longo, pasta por mangá ao salvar e velocidade de animação do toque duplo. `doubleTapAnimSpeed` é global e continua no inventário de controles, não na tela Webtoon.

## Acessibilidade e simulação

- **Regra atual:** texto quebra naturalmente nas composições; isso não comprova suporte Android a escala de fonte, dp/sp, foco assistivo, contraste medido ou ausência de overflow.
- **Problema:** controles e textos técnicos podem colidir em larguras menores; estados locais podem parecer persistentes.
- **Solução:** manter alvos de toque amplos, foco visível, labels que crescem e ranges com unidade. Defaults vêm do código; mudanças são locais à sessão e não alteram o app.
- **Alcance:** protótipo Leitura/Webtoon.
- **Status:** Provisional para apresentação; persistência, acessibilidade real e responsividade Android permanecem TBD.
