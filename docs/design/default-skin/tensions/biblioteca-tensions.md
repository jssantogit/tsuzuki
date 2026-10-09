# Biblioteca — tensões e limites

> Registro de elaboração preservado. Os estados Provisional/TBD abaixo descrevem a rodada original. O fechamento visual de Settings e as decisões que a substituem estão na [especificação vigente](../design.md). Medidas Android, execução real e limites expressamente pendentes continuam sem certificação. Aquisição global e Folder Catalog não são referências aprovadas.


Este registro acompanha as composições Biblioteca no OpenDesign. A tela principal Biblioteca já aprovada permanece preservada, com apenas as correções locais de texto e categoria registradas abaixo. A subpágina Atualização automática é a única composição nova deste incremento. As seis telas documentadas em `design.md` continuam preservadas.

## Inventário funcional preservado

A implementação Android de referência é `SettingsLibraryScreen.kt` e `LibraryPreferences.kt` no projeto Tsuzuki. O inventário abaixo preserva nomes, opções e valores identificados no código; não comprova apresentação aprovada, preferência pessoal ou persistência desta demonstração. Identificadores e nomes técnicos são apenas rastreabilidade interna, não copy de produto.

- Organização: gestão de categorias; categoria padrão; ordenação por categoria.
- Automação: intervalos 0, 12, 24, 48, 72 e 168 horas; restrições Wi-Fi, rede não limitada e carregamento, aplicáveis quando intervalo > 0; incluir/excluir categorias; metadados inicia falso.
- Smart update: quatro critérios independentes, selecionados por padrão — pular itens com capítulos não lidos, pular itens cuja leitura não foi iniciada, pular itens completos e prever a data do próximo lançamento.
- Contagem em Atualizações inicia verdadeira.
- Swipe: ambas as direções oferecem Disabled, Bookmark, Read e Download; início usa Bookmark e fim usa Read.
- Duplicados: regras existente/nova iniciam vazias; indicadores de capítulos ausentes inicia falso.

## Referência aprovada e decisões registradas

- **Approved:** o hub híbrido também atende áreas operacionais. Configurações → Biblioteca abre a tela Biblioteca; Personalização → Layout → Biblioteca mantém seu destino atual.
- Biblioteca mantém os três grupos e as oito linhas existentes. O badge da Biblioteca na raiz permanece ligado.
- **Approved:** Atualização automática abre sua subpágina. Categoria padrão apresenta Sempre perguntar e Padrão. Os identificadores `-1` e `0` ficam apenas na rastreabilidade interna; não aparecem como copy de produto. A frase “A seleção altera apenas este estado local.” não integra a interface.
- A subpágina apresenta Voltar acima do título grande e não mostra navegação inferior. Voltar restaura foco e rolagem da Biblioteca.
- Três grupos: Atualização, Restrições e Conteúdo. Intervalo oferece Desligado, 12, 24, 48, 72 horas e Semanal (168 horas), iniciando em Desligado.
- **Approved:** restrições Wi-Fi, rede não limitada e carregamento são escolhas independentes e simultâneas. Para dependências entre preferências existentes, o estado-pai desabilita dependentes sem escondê-los; seus valores permanecem visíveis para comunicar a configuração preservada. Esta regra não define o tratamento de capabilities indisponíveis. Os valores iniciais constam do inventário. Contraste e opacidade exatos ficam Provisional.
- Categorias incluídas e excluídas usam seleção de três estados e iniciam sem seleção. A opção disponível é somente Padrão do sistema; não inventar categorias. Exclusão prevalece sobre inclusão, relação comprovada no código.
- Metadados inicia desligado. Os quatro critérios Smart update iniciam selecionados e são seleções múltiplas. Não resumir Smart update em uma única alternância.
- Seletores múltiplos oferecem confirmar e cancelar. Cancelar descarta o rascunho e mantém o estado anterior.
- **Approved:** rows que resumem múltiplas escolhas usam estado ou contagem; o resumo de Atualização automática reflete intervalo e restrições atuais. A tela principal preserva os demais estados aprovados e foco e rolagem no retorno.

## Tensões — histórico, status e limites

| Tensão | Regra / problema | Decisão registrada na composição | Alcance e status |
|---|---|---|---|
| Intervalo e restrições | Restrições só se aplicam com intervalo maior que zero; preferências seguem independentes | Desabilitar apenas as três linhas de restrição em Desligado e manter valores legíveis | **Approved:** para estas dependências entre preferências existentes, o estado-pai desabilita dependentes sem ocultá-los e preserva valores visíveis; a regra não define o tratamento de capabilities indisponíveis. Contraste/opacidade exatos Provisional; aplicação/agendamento TBD |
| Categorias sem dados pessoais | Nenhum catálogo pessoal foi fornecido | Expor apenas Padrão do sistema e seleção de três estados; nenhuma seleção inicial | **Provisional:** exposição visual; integração com dados TBD |
| Inclusão e exclusão | Exclusão prevalece sobre inclusão no comportamento de referência | Representar estados separadamente e documentar precedência | Regra de código preservada; UI aprovada nesta composição, aplicação final TBD |
| Critérios Smart update | Quatro critérios independentes não equivalem a uma opção única | Seleção múltipla, quatro itens marcados por padrão | **Approved:** resumo por estado ou contagem. Apresentação multi-select detalhada Provisional; execução e persistência TBD |
| Seletores múltiplos | Alterações não confirmadas não devem substituir preferências existentes | Rascunho com Confirmar/Cancelar; Cancelar preserva estado | **Approved:** single-select e multi-select usam folhas distintas; multi-select pode exigir Cancelar/Confirmar se aplicação não for imediata. Anatomia, dimensões e tratamento das ações Provisional; persistência TBD |
| Cabeçalho e retorno | A nova página é um detalhe de Biblioteca | Voltar acima do título, sem navegação inferior; restaurar foco e rolagem | Relação desta composição **Approved**; navegação Android TBD |
| Medidas e controles | As referências não fixam tokens globais nem escala CSS→dp/sp | Reutilizar hierarquia, superfícies, divisores, fonte local e moldura existentes | Decisões Approved acima são locais à composição; medidas, acessibilidade Android e escala TBD |

A tela principal Biblioteca e suas relações aprovadas são contexto preservado, com as correções locais do seletor Categoria padrão e remoção da frase local de demonstração. Regras documentais não são texto de produto; nomes de preferências e identificadores servem apenas à rastreabilidade interna.

Continuam TBD o agendamento real, persistência Android, política de atualização, relação funcional entre conteúdo e intervalo, catálogo dinâmico de categorias, execução dos critérios Smart update e integração de navegação. Não inferir dependência de Conteúdo quando o intervalo está Desligado.
