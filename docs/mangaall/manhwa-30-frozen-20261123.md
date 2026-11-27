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
| [#45303820829](https://github.com/jssantogit/tsuzuki/actions/runs/45303820829) | Consulta específica Boxer/Horizon/Breaker, filtro de título ORIGINAL preservado | **27/30** | **46** | 0 | RED legítimo; 3 buscas parciais |
| [#45304306390](https://github.com/jssantogit/tsuzuki/actions/runs/45304306390) | Budget 80 apenas para as três buscas previamente parciais | **30/30** | **46** | 0 | RED legítimo; Raeliana ainda sem capítulo 1 |
| [#45304943319](https://github.com/jssantogit/tsuzuki/actions/runs/45304943319) | Correção de pontuação estrita de apóstrofo + auditoria da edição real Raeliana | **30/30** | **47** | 0 | RED legítimo; **30/30 com capítulo 1 em HID** |

**Atualização na terceira rodada:** *SSS-Class Revival Hunter* e *Return of the Blossoming Blade* ganharam comprovação de capítulo 1 em HID de fonte após incluir nomes alternativos verificados. O número de obras com capítulo 1 comprovado passou de 27/30 para **29/30**. *Sweet Home* e *Wind Breaker* não apresentam mais conflito de autor entre títulos principais depois de filtrar MangaDex para idioma original coreano; seguem com rivais de fonte/edições e sem edição canônica autorizada. *Raeliana* continua sem correspondência exata/primeiro capítulo comprovado.

**Busca parcial (6/30):** *The Boxer*, *The Horizon*, *Weak Hero*, *The World After the Fall*, *The Breaker*, *Cheese in the Trap*. **Sem comprovação de capítulo nº 1 em qualquer HID auditado (3/30):** *SSS-Class Revival Hunter*, *Return of the Blossoming Blade*, *The Reason Why Raeliana Ended Up at the Duke's Mansion*. **Colisões de autores em títulos principais:** *Sweet Home* e *Wind Breaker*; especificamente para *Wind Breaker*, não misturar a obra coreana com o mangá japonês homônimo. Outras obras apresentaram rivais no mesmo provedor e capítulos sem numeração inicial em uma edição específica, sem prova de ausência da obra.

Embora 27/30 obras tenham ao menos um HID com capítulo 1 e cursor terminal, isso continua sendo **observação por edição da fonte**, não seleção de edição canônica. A busca ampla MangaDot continua ficando parcial ao atingir o orçamento de 32 páginas em alguns termos; há respostas upstream que excedem o limite de entrada Host original de 192 KiB, apesar das saídas experimentais granuladas de até 48 KiB.

## Correções/hipóteses sob validação, sem trocar os 30 títulos

1. O runner e o agregador agora verificam `manhwa`, `cohort` e a lista exata do lock. Nem reformatar o manifesto nem atualizar o código autoriza substituição das obras.
2. Adaptação CI-only da busca MangaDex usa `attributes.originalLanguage === "ko"` antes de devolver candidatos ao roteador, para não classificar mangás japoneses homônimos como manhwas. O provider assinado não foi alterado.
3. Somente três obras recebem **termos adicionais corroborados** para busca, mantendo seus títulos canônicos e seus HIDs independentes: *SSS-Class Revival Hunter* → *SSS-Class Suicide Hunter*; *Return of the Blossoming Blade* → *Return of the Mount Hua Sect*; *The Reason Why Raeliana Ended Up at the Duke's Mansion* → *Why Raeliana Ended Up at the Duke's Mansion* e o título original `그녀가 공작저로 가야 했던 사정`. Essas variantes NÃO promovem identidade automaticamente.
4. Repetir o live com o mesmo manifesto até distinguir limites de paginação, ausência genuína de capítulos na fonte, erro do provider e ambiguidades legítimas. Nunca completar páginas omitidas por inferência ou atribuir autoria inexistente.


**4ª CI do corpus fixo:** [#45303354025](https://github.com/jssantogit/tsuzuki/actions/runs/45303354025) confirmou **24/30 buscas completas, 46 inventários completos por HID, 29/30 obras com capítulo 1 em HID**, zero saída >64KiB. O título coreano acrescentado ao teste encontrou candidato para *Raeliana* por alias, mas **não comprovou capítulo 1**. O MangaDot anunciou páginas de resultados muito além do orçamento: *The Boxer* **464**, *The Horizon* **464**, *The Breaker* **466**, *Weak Hero* **48**, *The World After the Fall* **43**, *Cheese in the Trap* **74**. Aumentar globalmente o orçamento de 32 para 64 ou 96 não resolveria as três buscas de mais de 460 páginas, e pode consumir requisições desnecessárias.

**5ª CI do corpus fixo:** [#45303820829](https://github.com/jssantogit/tsuzuki/actions/runs/45303820829) concluiu RED com **27/30 buscas completas** e 46 inventários completos por HID, zero saída >64KiB. Para a busca original *The Boxer*, a consulta de origem `Boxer` reduziu 464→1 página; *The Horizon* `Horizon` reduziu 464→1; *The Breaker* `Breaker` reduziu 466→6. O filtro do roteador sobre os títulos originais permaneceu estrito, sem canonizar candidatos. As três buscas restantes: *Weak Hero* (48 páginas), *The World After the Fall* (43) e *Cheese in the Trap* (74).

**6ª CI do corpus fixo:** [#45304306390](https://github.com/jssantogit/tsuzuki/actions/runs/45304306390) iniciada após [offline #45304234239](https://github.com/jssantogit/tsuzuki/actions/runs/45304234239) GREEN. Sem mudar nenhuma das 30 obras, aumento experimental condicionado a esses três títulos: no máximo 80 páginas apenas para eles, mantendo os demais em 32. Orçamento global não foi ampliado; se a fonte não couber, registrar falha explícita. A versão assinada e Host de produção permanecem intactos. Não assumir conclusão da 6ª antes dos resultados reais.

## 6ª rodada: busca 30/30, mas evidências ainda incompletas

[Live #45304306390](https://github.com/jssantogit/tsuzuki/actions/runs/45304306390): após medir os totais MangaDot `Weak Hero=48`, `The World After the Fall=43`, `Cheese in the Trap=74` páginas, o orçamento limitado a 80 e aplicado **somente** a essas três obras permitiu esgotar a busca das trinta. **30/30 lookups completos**, 46 inventários de capítulo por HID, zero saída >64KiB. O agregador permaneceu RED em razão de riscos na entrada HTTP Host192KiB e capítulos/edições individuais não corroborados; a seleção canônica/Reader no Android não foi validada. Nunca usar esse resultado para substituir o corpus por novas obras.

## Falso negativo encontrado: Raeliana

O artefato detalhado da 4ª live confirmou que a fonte MangaDot já devolvia o candidato `Why Raeliana Ended Up at the Duke’s Mansion`, com apóstrofo tipográfico `’`, mas o auditor CI realizava comparação estrita com o alias de consulta `Why Raeliana Ended Up at the Duke's Mansion`, com apóstrofo ASCII `'`. O roteador original aceitava ambos por tokenização de pontuação; **o verificador de CI excluía indevidamente o HID MangaDot da auditoria de capítulos**. O MangaDex confirmou o título original em coreano e uma edição de 91 capítulos em inglês, porém ainda sem número exato 1; isso não implica ausência de capítulo 1 na obra inteira.

A correção [commit ab327062](https://github.com/jssantogit/tsuzuki/commit/ab32706296cfc2eb2024fc2e891e9956192d2d16) altera **apenas a cópia da jornada no runner não assinado**, equivalendo exclusivamente variações de apóstrofo, sem comparação difusa, remoção de termos ou vinculação automática. O teste de regressão [commit 5218f0cc](https://github.com/jssantogit/tsuzuki/commit/5218f0cc3fb593d9451ca26963b4119679f7696e) exerce busca simulada com variantes `'` e `’` e recusa palavras adicionais (e.g., edição `Part 2`); [CI offline #45304876197](https://github.com/jssantogit/tsuzuki/actions/runs/45304876197) GREEN. A [7ª live do corpus fixo #45304943319](https://github.com/jssantogit/tsuzuki/actions/runs/45304943319) confirmou a regressão no mesmo conjunto de obras: **30/30 buscas completas, 47 inventários terminais por HID, 30/30 obras com capítulo 1 comprovado em AO MENOS UM HID real**, zero saída de capítulos >64KiB. *Raeliana* tem agora HID MangaDot com `chapterOne=true`, porque a correção da comparação de apóstrofos permite que o auditor trate a edição da fonte previamente rejeitada. A edição MangaDex observada ainda não comprova capítulo 1 e seu status é preservado. **Gate agregado continua RED/NEEDS_CORRECTION**: respostas de MangaDot de entrada >192KiB no Host legado, capítulos ausentes em edições específicas e alternativas de fonte/edição ainda não vinculadas com autoridade canônica; nenhuma afirmação de Reader Android/SQLite 30/30. **Não trocar as obras**.


## Inventários per-HID e transição para Android real

A [tabela detalhada de 30 obras e inventários por HID](manhwa-30-round7-per-hid-inventories.md) foi preservada em repositório, pois os artefatos GitHub Actions originais expiram. Na 7ª CI: 23 respostas MangaDot acima de 192KiB (máximo 1.307.642 bytes), nenhum HTTP403/429, nenhuma falha de transporte, 0 auditorias inválidas e 18 auditorias fora da categoria COMPLETE, que incluem edições vazias. **Não misturar capítulos de HIDs rivais nem concluir que a obra inteira está vazia.**

Foi ampliado um teste de Android code11 **não assinado** em [commit 33d62e19](https://github.com/jssantogit/tsuzuki/commit/33d62e1948494edb2d39e494d227ad6af08a3ebb) para preservar o teste real já verde de Bleach e exigir leitura do HID de fonte de *Raeliana* com apóstrofo tipográfico, capítulos únicos, saída <=48KiB e cursor terminal pelo Host/Binder/QuickJS real. [CI Android #45305619695](https://github.com/jssantogit/tsuzuki/actions/runs/45305619695) **PASSOU**: `BUILD SUCCESSFUL` e veredito `outcome=PASS|works=BLEACH_RAELIANA|scope=SOURCE_HID_ONLY|nativeSnapshot=TESTED|multipage=TESTED|terminal=TESTED|chapterOne=TESTED`. A instrumentação no emulador verificou os HIDs de fonte das duas obras com comunicação Android Host/QuickJS/Binder real, nenhuma duplicata, output de capítulos <=48KiB, capítulo 1 e cursor terminal, mantendo original Bleach já validado. **Isso NÃO valida os 30 manhwas no Android**, nem associa edição canônica ou Reader; *Bleach* é regressão técnica prévia e não altera o corpus fixo de 30.

## Imutabilidade do corpus mesmo contra troca dupla de arquivos

Após validar os 30 títulos, adicionamos digest SHA-256 da lista canônica `19996790a934514c7877447610ee09a9e0dd2bd8fe8b2c42ff46a7a5cfe64bd1`, verificado independentemente pelo runner Node, pelo agregador Python e pela autorização do live workflow. Nem editar a lista de obras no manifesto e no arquivo de lock simultaneamente permite substituí-la sem mudança explícita dos guardas do código e nova revisão. Ordem e títulos são imutáveis até autorização após validação real. O digest é de `JSON.stringify(works)` com exatamente os 30 títulos, não da serialização indentada do manifesto.

## Critério para encerrar o corpus

A rodada somente poderá ser classificada como 30/30 aprovada quando **cada** uma das 30 obras congeladas dispuser de evidência coerente de descoberta e identidade de fonte (inclusive quando há homônimos), auditoria correta de inventário e cursores de capítulos, ausência de erros relevantes do contrato/Host, e nenhum falso vazio, truncamento ou associação canônica indevida. Manter separada a validação do Reader Android, cujos pixels e persistência real exigem gates próprios. Qualquer fonte sem capítulo 1 ou sem a edição correspondente deve ficar claramente classificada como lacuna de cobertura, não aprovada por um fallback inventado.

**PR:** [#275 — Draft](https://github.com/jssantogit/tsuzuki/pull/275). Sem merge, assinatura ou publicação.


## Checkpoint de homologação — 26 nov 2026

**Estado vigente: gate canônico integral GREEN, aprovação de troca do corpus ainda pendente.** Os resultados históricos acima não devem ser interpretados como o estado atual.

| Evidência | Resultado |
|---|---|
| [Auditoria offline #45906633715](https://github.com/jssantogit/tsuzuki/actions/runs/45906633715) | 2/2 jobs; 126 testes core-common, incluindo recuperação de snapshots terminais sob pressão e CAS de geração |
| [Live integral #45909425855](https://github.com/jssantogit/tsuzuki/actions/runs/45909425855) | 6/6 jobs; exatamente 30/30 coletados, restaurados após force-stop e primeira página decodificada |
| [Reader Android controlado #45911409832](https://github.com/jssantogit/tsuzuki/actions/runs/45911409832) | 5/5 cenários: seleção MangaDot, retry e pixels, troca de fonte, recriação de Activity, histórico/preferência idempotentes |
| [MangaDex independente #45396938397](https://github.com/jssantogit/tsuzuki/actions/runs/45396938397) | 30/30 auditados; 4 obras legíveis, 15 EMPTY_EN, 9 NO_CHAPTER_ONE_EN, 1 EXTERNAL_ONLY, 1 NO_EXACT_HID |

Versão funcional: `e1fb22b3d182b9029b55f3902310fc5205cb08fa`; disparos de workflow `96d1140d` e `ef3b30c6` não modificaram a implementação. Pacote experimental code11 não assinado, upstream fixado em `89b5979fff297c2a099359258c9f9249e0089db4`.

### Cobertura canônica observada no live integral

Os 30 registros `MANGAALL_CANONICAL_DIAGNOSTIC` mostram exatamente um capítulo canônico número 1 por obra. As opções selecionáveis são contabilizadas por obra (não pelo número de releases):

| Cobertura do capítulo 1 | Obras |
|---|---:|
| Apenas MangaDot | 26 |
| Apenas MangaDex | 1 |
| Ambas | 3 |
| Nenhuma | 0 |

MangaDex possui opção no capítulo 1 de Eleceed, The World After the Fall, The Breaker e Cheese in the Trap. The World After the Fall é a única obra sem opção MangaDot naquele capítulo no resultado canônico. A cobertura do MangaDex coincide em quantidade com o audit independente anterior; não representa falha do adaptador por si só. Nenhum registro de tentativa de capítulos com `outcome=FAILED` apareceu nos três shards da rodada integral.

Os índices 7, 8 e 9 (Nano Machine, SSS-Class Revival Hunter e Return of the Blossoming Blade), que falharam com HOST_ERROR em #45895596624, agora registram respectivamente 370, 169 e 200 capítulos canônicos, um capítulo 1, restauração e primeiro bitmap PASS. A recuperação de snapshots terminais sob pressão corrobora a hipótese de saturação; não isola causalidade exclusiva.

### Limites preservados

- O live comprova seleção e decodificação pelo loader após restauração; não comprova pixels do CDN na Activity do Reader. O teste de interface separado usa respostas HTTP controladas.
- Um capítulo canônico único não significa uma única release: rivais exigem escolha explícita quando aplicável. A equivalência editorial não foi demonstrada.
- Ausência de cobertura de uma fonte não deve ser convertida em erro de identidade nem preenchida com capítulos inventados.
- MangaFire suspenso; assinatura, publicação e merge permanecem fora do escopo. Não repetir a regressão integral sem mudança funcional, novo risco mensurável ou pedido explícito de estabilidade.
- O lock original continua intacto até autorização explícita de rotação. O próximo corpus deve ter novos títulos, catálogo independente e controles equivalentes; conservar este baseline como regressão.
