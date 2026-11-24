# MangaALL — 30 manhwas congelados: inventários de fonte da 7ª CI

**Run [#45304943319](https://github.com/jssantogit/tsuzuki/actions/runs/45304943319)** · Corpus imutável [MANHWA_30_FIXED_20261123](../../.github/mangaall-dual-source-30-cohort-lock.json) · MangaDex + MangaDot reais · 30/30 buscas completas · 30/30 obras com capítulo número 1 em **ao menos um HID de fonte**, 47 inventários terminais não vazios, 0 saídas acima de 64KiB. Dados extraídos do relatório `mangaall-dual30-aggregate`; são evidências de fonte não canônica, **NÃO** confirmação de edição editorial equivalente, conteúdo completo no Reader nem aprovação do Host legado ou do Android.

A quantidade abaixo é **por HID auditado**, não a contagem editorial verificada de capítulos do mangá. Separadores `/` indicam HIDs distintos/edições rivais: não somar ou misturar seus capítulos. `0†` é inventário de fonte com terminal confirmado mas vazio na faceta auditada, não ausência do capítulo na obra. “Não auditado” significa que nenhum HID dessa fonte passou à etapa de auditoria neste lote, não que o provedor não contenha a obra. Um capítulo número 1 em qualquer HID não resolve identidade nem qualidade do Reader.

| # | Manhwa | MangaDex — HIDs | MangaDot — HIDs | Cap. 1 em HID | Observações adicionais |
|--:|---|---:|---:|:---:|---|
| 1 | Solo Leveling | 24 | 1109 | sim | — |
| 2 | Omniscient Reader | não auditado | 1246 | sim | — |
| 3 | Eleceed | 103 | 1743 | sim | — |
| 4 | Tower of God | 0† / 0† | 1793 | sim | — |
| 5 | The Greatest Estate Developer | 3 | 678 | sim | MangaDex auditado sem cap. 1 |
| 6 | The Legend of the Northern Blade | 0† | 808 | sim | — |
| 7 | Nano Machine | 0† | 1559 | sim | — |
| 8 | SSS-Class Revival Hunter | 10 | 972 | sim | MangaDex auditado sem cap. 1 |
| 9 | Return of the Blossoming Blade | 0† | 577 | sim | — |
| 10 | The Boxer | 12 | 151 | sim | MangaDex auditado sem cap. 1 |
| 11 | The Horizon | 0† | 61 | sim | — |
| 12 | Bastard | 0† | 264 | sim | — |
| 13 | Sweet Home | 0† | 140 / 1 | sim | MangaDot: segundo HID sem cap. 1 |
| 14 | Weak Hero | 0† | 295 | sim | — |
| 15 | Lookism | 26 | 2562 | sim | MangaDex auditado sem cap. 1 |
| 16 | Wind Breaker | 6 | 1142 / 1068 | sim | MangaDex sem cap. 1; homônimos/edições ainda exigem validação |
| 17 | The God of High School | 0† | 2111 | sim | — |
| 18 | Noblesse | 0† | 1728 | sim | — |
| 19 | A Returner's Magic Should Be Special | 0† / 0† | 817 | sim | — |
| 20 | The World After the Fall | 75 | não auditado | sim | Apenas match por alias em diagnóstico |
| 21 | Teenage Mercenary | 39 | 837 | sim | MangaDex auditado sem cap. 1 |
| 22 | The Breaker | 72 | 255 | sim | — |
| 23 | Gosu | 0† | 233 | sim | — |
| 24 | Seasons of Blossom | 0† | 130 | sim | — |
| 25 | Villains Are Destined to Die | 1 / 0† | 1492 | sim | MangaDex auditado sem cap. 1 |
| 26 | Who Made Me a Princess | 59 | 600 | sim | MangaDex auditado sem cap. 1 |
| 27 | The Remarried Empress | 0† | 1256 / 879 | sim | Edições MangaDot rivais |
| 28 | The Reason Why Raeliana Ended Up at the Duke's Mansion | 91 | 790 | sim | MangaDex sem cap. 1; MangaDot corrigido no auditor |
| 29 | Cheese in the Trap | 96 | 952 / 74 | sim | Edições MangaDot rivais |
| 30 | See You in My 19th Life | 0† | 115 | sim | — |

## Bloqueios ainda ativos

- **23 respostas reais do MangaDot acima de 192KiB**, máximo observado **1.307.642 bytes**. Esse é o transporte de **entrada** do Host HTTP V1; saída granular de até 48KiB não elimina o problema de entrada. Na CI Node o limite de entrada foi ampliado experimentalmente e não é prova do Host de produção.
- **18 auditorias não contadas como COMPLETE** (incluindo `COMPLETE_EMPTY`, e não necessariamente erros): não converter vazio da edição em ausência de capítulo de toda a obra. **0 auditorias de fonte com erro de contrato**, HTTP403/429 ou falha de transporte.
- **7 casos com rivais do mesmo provedor, 28 obras com candidatos de ambos os provedores, 20 matches por alias**. São candidatos independentes, não licença para vincular HIDs diferentes, misturar capítulos nem eleger um canônico automaticamente.
- Falta testar o **conjunto completo no Host Android/SQLite e Reader** com a paginação nativa code11 não assinada, edição correta, leitura/metadata/imagens, além de corrigir os limites de entrada para uma integração real. A [PR #275](https://github.com/jssantogit/tsuzuki/pull/275) permanece Draft, sem merge, assinatura ou publicação.

**Regra:** não mudar nenhum dos 30 títulos, mesmo que todos apareçam com busca completa e capítulo 1; a liberação do corpus exige aprovação integral real.
