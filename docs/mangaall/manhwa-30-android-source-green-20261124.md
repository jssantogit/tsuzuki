# MangaALL — 30 manhwas congelados no Host Android: primeira CI verde de fonte

**24 nov 2026 · GitHub Actions [#45365160634](https://github.com/jssantogit/tsuzuki/actions/runs/45365160634) · conclusao `success` · SEM merge, assinatura ou release.**

## Prova executada

- Corpus imutavel [MANHWA_30_FIXED_20261123](../../.github/mangaall-dual-source-30-cohort-lock.json), mesmo vetor de 30 obras e mesma ordem do [manifesto](../../.github/mangaall-dual-source-30-request.json), SHA256 `19996790a934514c7877447610ee09a9e0dd2bd8fe8b2c42ff46a7a5cfe64bd1`. O hash foi verificado **tambem dentro de cada emulador Android**.
- Um unico build gerou APK da aplicacao e APK de instrumentacao. Os tres emuladores GitHub-hosted Android 35 usaram este mesmo par de APKs.
- Cada shard executou um teste Kotlin instrumentado REAL com `ProviderRuntimeClient`, `ProviderHostInvocationFactory`, Binder, QuickJS e modulo MangaALL code11 **unsigned**. `reading.lookup` e `reading.chapters` invocaram MangaDex/MangaDot reais; o snapshot nativo granular do MangaDot preservou os HIDs de capitulo.
- Para cada obra, exige candidato por titulo/alias explicitamente informado, ao menos um HID de fonte com paginação terminal e capitulo `rawNumber=1`, IDs sem repeticao, cursor nao ciclico, paginas normalizadas de no maximo 48 KiB. HIDs rivais nao sao misturados nem promovidos a canônicos.

| Etapa do workflow | Resultado | Obras do corpus |
|---|---|---|
| `build-once-unsigned` | **SUCCESS** | Compilacao e verificador de formato |
| `frozen-manhwa-native-chapters (0)` | **SUCCESS** | Obras 1–10 |
| `frozen-manhwa-native-chapters (1)` | **SUCCESS** | Obras 11–20 |
| `frozen-manhwa-native-chapters (2)` | **SUCCESS** | Obras 21–30 |

Artefatos sanetizados e de retencao curta: `mangaall-frozen30-code11-android-shard-0`, `-1`, `-2`. Os resultados de instrumentacao verde sao distintos do agregado mais amplo de fontes, ainda **RED/NEEDS_CORRECTION** pelas regras conservadoras.

## Nao comprovado

1. Uma obra coreana selecionada por HID com mesmo titulo ainda **nao constitui** entidade editorial, edicao canônica ou consenso de autoria. A fonte MangaDot pode conter edicoes/obras homonimas. O teste pode usar um HID com capitulo 1 sem provar que **cada** HID ou provedor tem capitulo 1.
2. `reading.pages`, imagens reais do Reader, renderizacao em pixel na UI, estado persistido no SQLite, continuacao apos reinicio do app e creditos/licencas do MangaDex **nao foram validados** nesta CI.
3. O modulo code11 e **experimental e unsigned**. O limite original de entrada HTTP de 192 KiB do Host de producao e o code10 assinado nao foram alterados nem certificados. A abordagem experimental de snapshot nativo nao transforma o code10 publicado em pronto para producao.
4. MangaFire segue temporariamente excluido desta CI por bloqueios reais de acesso. A aprovacao integral das 30 obras segue pendente e **nenhum titulo sera trocado**.

## Retomada

- [PR #275 Draft](https://github.com/jssantogit/tsuzuki/pull/275): unica linha de implementacao experimental.
- Refinamento de fonte `--manhwa-only` segue separadamente para testar filtro MangaDex `originalLanguage=ko` e busca MangaDot de termos especificos com filtro exato original preservado. Nao usar como nova prova sem CI verde.
- Adicionar instrumentacao de `reading.pages` por HID do capitulo 1 e, posteriormente, entrega/decodificacao real de imagens, identificacao canônica e persistencia. Cada novo gate deve preservar um veredito proprio para nao apagar a prova Android de fontes.

**Regra:** fonte Android 30/30 = **VERDE nesta camada**, nao = catalogo/Reader/MangaALL 30/30 pronto. O corpus somente sera liberado apos a validacao integral.
