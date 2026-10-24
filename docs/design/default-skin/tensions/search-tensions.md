# Busca — revisão de cards e resultados por formato

A [especificação vigente](../design.md) mantém a arquitetura de Busca. Search está aprovado como referência visual preservada no [board](../mockups/index.html). As tensões abaixo dizem respeito à implementação futura; esta consolidação não altera Android.

## Direção vigente e histórico substituído

O grid anterior de duas colunas, o ajuste intermediário para três colunas e as linhas de provenance foram substituídos por rails de formato. Discovery e resultados compartilham o mesmo card compacto. Abaixo da capa, o resultado tem somente título, ratings e ano. Título ocupa uma ou duas linhas conforme o conteúdo, com ellipsis após o limite de duas e leitura integral ao tocar. Discovery conserva suas seções editoriais.

## Tensões que permanecem

- **Formato desconhecido ou sobreposto:** não descartar resultados. Outros é fallback demonstrativo, não contrato público definitivo. A classificação entre Manhwa e Webtoon deve vir do domínio, sem inferir pela capa, idioma ou Provider e sem duplicar a obra entre grupos. Solo Leveling está em Manhwa somente como fixture desta revisão.
- **Identidade ambígua:** títulos iguais não autorizam merge. Se capa, ano, ratings e seção forem insuficientes para distinguir candidatos, a solução de desambiguação exige decisão futura; provenance permanente não foi reintroduzida.
- **Progressão versus ordem:** a ordem inicial é estável; grupos que chegam depois são acrescentados sem mover os já exibidos. Android ainda deve confirmar estabilidade, enriquecimento de ratings, rolagem e foco com callbacks reais. A demonstração local não comprova comportamento do runtime.
- **Implementação:** confirmar insets, escala de fonte, TalkBack, clamping e navegação canônica no Android após revisão. Provider é o destino público do estado sem capacidade de busca. Sem mudanças de identidade/runtime nesta rodada.

## Fixtures e fontes

As cinco capas anteriores continuam locais. Foram adicionadas capas reais de Solo Leveling, Look Back e The 100 Girlfriends Who Really, Really, Really, Really, Really Love You. Este último força o truncamento de duas linhas também no Discovery. IDs de fixture não são identidades canônicas. Scores são demonstrativos, com no máximo dois slots e branding nativo; MAL não inclui /10.

As fontes e dimensões estão no [registro dos assets](../mockups/assets/search/SOURCES.md). Os anos referem-se à publicação original da obra, não à edição da capa. Estas obras não estabelecem disponibilidade em Provider nem catálogo oficial. Tocar no card mostra o nome completo e sinaliza o destino canônico; não executa navegação Android.
