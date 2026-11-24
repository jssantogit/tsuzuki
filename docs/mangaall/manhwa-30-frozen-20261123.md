# MangaALL — corpus congelado de 30 manhwas (23 nov 2026)

**Decisão explícita:** NÃO substituir qualquer obra, nem avançar para um novo corpus, antes de as 30 obras desta lista serem validadas corretamente. Usar sempre a mesma ordem e os mesmos 30 títulos, inclusive nas rodadas de regressão. Corrigir o provider/Host ou registrar o bloqueio real; nunca criar um GREEN artificial reduzindo critérios, inventando capítulos ou reinterpretando aliases como prova de identidade.

**Fonte de verdade do corpus:** [cohort-lock](../../.github/mangaall-dual-source-30-cohort-lock.json) e [manifesto de execução](../../.github/mangaall-dual-source-30-request.json). O runner, o agregador e a autorização do live comparam os 30 títulos ao lock `MANHWA_30_FIXED_20261123`; formato normalizado de teste é `manhwa`.

**Escopo executado:** GitHub Actions do próprio `jssantogit/tsuzuki`, dois provedores reais MangaDex e MangaDot, roteador V1 original com restrição em memória, `providers-tsuzuki@195502f981bd776a68feebde3efc6d20f55cca65`. MangaFire temporariamente fora por decisão vigente. Não foi alterado o code10 assinado. Esta CI é um teste unsigned de proveniência da fonte, NÃO um PASS do catálogo canônico Android, de todas as páginas do Reader nem de uma release.

## Evidência inicial com os MESMOS 30 manhwas

| Execução | Tipo | Busca completa | Inventários completos por HID | Saídas >64 KiB | Gate |
|---|---|---:|---:|---:|---|
| [#45302021667](https://github.com/jssantogit/tsuzuki/actions/runs/45302021667) | Primeira execução, etiquetas históricas ainda `manga` no auditor | 24/30 | 44 | 0 | RED legítimo |
| [#45302391930](https://github.com/jssantogit/tsuzuki/actions/runs/45302391930) | Exatamente os mesmos títulos, etiqueta de fonte `manhwa` e lock aplicado | 24/30 | 44 | 0 | RED legítimo |
| [#45302867949](https://github.com/jssantogit/tsuzuki/actions/runs/45302867949) | Mesma lista, filtro MangaDex coreano e três obras com aliases comprovados | 24/30 | **45** | 0 | RED legítimo; 29/30 com capítulo 1 em HID |
| [#45303354025](https://github.com/jssantogit/tsuzuki/actions/runs/45303354025) | Título original coreano de Raeliana + medição de páginas anunciadas pelo MangaDot | 24/30 | **46** | 0 | RED legítimo; Raeliana ainda sem capítulo 1 |

**Atualização na terceira rodada:** *SSS-Class Revival Hunter* e *Return of the Blossoming Blade* ganharam comprovação de capítulo 1 em HID de fonte após incluir nomes alternativos verificados. O número de obras com capítulo 1 comprovado passou de 27/30 para **29/30**. *Sweet Home* e *Wind Breaker* não apresentam mais conflito de autor entre títulos principais depois de filtrar MangaDex para idioma original coreano; seguem com rivais de fonte/edições e sem edição canônica autorizada. *Raeliana* continua sem correspondência exata/primeiro capítulo comprovado.

**Busca parcial (6/30):** *The Boxer*, *The Horizon*, *Weak Hero*, *The World After the Fall*, *The Breaker*, *Cheese in the Trap*. **Sem comprovação de capítulo nº 1 em qualquer HID auditado (3/30):** *SSS-Class Revival Hunter*, *Return of the Blossoming Blade*, *The Reason Why Raeliana Ended Up at the Duke's Mansion*. **Colisões de autores em títulos principais:** *Sweet Home* e *Wind Breaker*; especificamente para *Wind Breaker*, não misturar a obra coreana com o mangá japonês homônimo. Outras obras apresentaram rivais no mesmo provedor e capítulos sem numeração inicial em uma edição específica, sem prova de ausência da obra.

Embora 27/30 obras tenham ao menos um HID com capítulo 1 e cursor terminal, isso continua sendo **observação por edição da fonte**, não seleção de edição canônica. A busca ampla MangaDot continua ficando parcial ao atingir o orçamento de 32 páginas em alguns termos; há respostas upstream que excedem o limite de entrada Host original de 192 KiB, apesar das saídas experimentais granuladas de até 48 KiB.

## Correções/hipóteses sob validação, sem trocar os 30 títulos

1. O runner e o agregador agora verificam `manhwa`, `cohort` e a lista exata do lock. Nem reformatar o manifesto nem atualizar o código autoriza substituição das obras.
2. Adaptação CI-only da busca MangaDex usa `attributes.originalLanguage === "ko"` antes de devolver candidatos ao roteador, para não classificar mangás japoneses homônimos como manhwas. O provider assinado não foi alterado.
3. Somente três obras recebem **termos adicionais corroborados** para busca, mantendo seus títulos canônicos e seus HIDs independentes: *SSS-Class Revival Hunter* → *SSS-Class Suicide Hunter*; *Return of the Blossoming Blade* → *Return of the Mount Hua Sect*; *The Reason Why Raeliana Ended Up at the Duke's Mansion* → *Why Raeliana Ended Up at the Duke's Mansion* e o título original `그녀가 공작저로 가야 했던 사정`. Essas variantes NÃO promovem identidade automaticamente.
4. Repetir o live com o mesmo manifesto até distinguir limites de paginação, ausência genuína de capítulos na fonte, erro do provider e ambiguidades legítimas. Nunca completar páginas omitidas por inferência ou atribuir autoria inexistente.


**4ª CI do corpus fixo:** [#45303354025](https://github.com/jssantogit/tsuzuki/actions/runs/45303354025) confirmou **24/30 buscas completas, 46 inventários completos por HID, 29/30 obras com capítulo 1 em HID**, zero saída >64KiB. O título coreano acrescentado ao teste encontrou candidato para *Raeliana* por alias, mas **não comprovou capítulo 1**. O MangaDot anunciou páginas de resultados muito além do orçamento: *The Boxer* **464**, *The Horizon* **464**, *The Breaker* **466**, *Weak Hero* **48**, *The World After the Fall* **43**, *Cheese in the Trap* **74**. Aumentar globalmente o orçamento de 32 para 64 ou 96 não resolveria as três buscas de mais de 460 páginas, e pode consumir requisições desnecessárias.

**5ª CI do corpus fixo:** [#45303820829](https://github.com/jssantogit/tsuzuki/actions/runs/45303820829) foi iniciada depois de [offline #45303741114](https://github.com/jssantogit/tsuzuki/actions/runs/45303741114) GREEN; o experimento unsigned do MangaDot consulta por termos nucleares *Boxer*, *Horizon* e *Breaker* para os três títulos amplos, mas continua exigindo que o roteador confirme o título ORIGINAL/alias exato de cada candidato. Não mudar a lista nem promover edição canônica. O resultado real do 5º live ainda precisa ser observado.

## Critério para encerrar o corpus

A rodada somente poderá ser classificada como 30/30 aprovada quando **cada** uma das 30 obras congeladas dispuser de evidência coerente de descoberta e identidade de fonte (inclusive quando há homônimos), auditoria correta de inventário e cursores de capítulos, ausência de erros relevantes do contrato/Host, e nenhum falso vazio, truncamento ou associação canônica indevida. Manter separada a validação do Reader Android, cujos pixels e persistência real exigem gates próprios. Qualquer fonte sem capítulo 1 ou sem a edição correspondente deve ficar claramente classificada como lacuna de cobertura, não aprovada por um fallback inventado.

**PR:** [#275 — Draft](https://github.com/jssantogit/tsuzuki/pull/275). Sem merge, assinatura ou publicação.
