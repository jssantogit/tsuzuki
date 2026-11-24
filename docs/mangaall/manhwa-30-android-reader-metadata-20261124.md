# MangaALL — 30 manhwas: metadados das paginas Reader no Android

**Evidencia [CI #45368754172](https://github.com/jssantogit/tsuzuki/actions/runs/45368754172), 24 nov 2026, conclusao SUCCESS em todos os quatro jobs.**

Escopo: pacote code11 experimental, **UNSIGNED**, MangaDex + MangaDot; conjunto exatamente igual de [30 manhwas congelados](../../.github/mangaall-dual-source-30-cohort-lock.json), SHA256 `19996790a934514c7877447610ee09a9e0dd2bd8fe8b2c42ff46a7a5cfe64bd1`. Nao houve troca de titulo, provider, edicao ou HID apos alguma falha.

## O que realmente passou

| Job Android | Obras | Resultado |
|---|---|---|
| `build-once-unsigned` | Um unico par de APKs nao assinados | SUCCESS |
| `frozen-manhwa-reader-metadata (0)` | Indices 1–10 | SUCCESS |
| `frozen-manhwa-reader-metadata (1)` | Indices 11–20 | SUCCESS |
| `frozen-manhwa-reader-metadata (2)` | Indices 21–30 | SUCCESS |

Para cada indice, a mesma instrumentacao Android/QuickJS/Binder:

1. Verifica o hash dos 30 titulos e descobre candidato real de fonte por titulo/alias especifico, sem equivalencia editorial presumida.
2. Percorre `reading.chapters` em paginas granulares ate cursor terminal, comprova capitulo 1 por HID da mesma fonte, sem repetir IDs.
3. **No mesmo HID comprovado**, invoca `reading.pages` para o ID do capitulo de numero 1. Requer resposta `type=page_list`, entre 1 e 5.000 metadados de imagem e URL com `https://`. A falha da obra resulta em RED; nao ocorre troca silenciosa de edicao.
4. Preserva somente vereditos de instrumentacao sanetizados. Nenhum payload, URL, pagina ou imagem e persistido nos artefatos.

## Interpretacao correta

- **SOURCE READER METADATA 30/30 = GREEN.** Isto completa mais um gate real depois da [CI de fontes #45365160634](https://github.com/jssantogit/tsuzuki/actions/runs/45365160634) e da [revalidacao coreana #45366893915](https://github.com/jssantogit/tsuzuki/actions/runs/45366893915).
- **PIXELS NAO TESTADOS nesta CI:** `reading.pages` entregou apenas metadados e URLs, sem transferir bytes, decodificar bitmaps ou desenhar a pagina na tela real do Reader. Um teste Android separado de transporte+BitmapFactory pode avancar esse criterio, mas ainda nao validara automaticamente a interface.
- **CANONICAL/SQLITE/PRODUCAO NAO TESTADOS:** mesma fonte/HID e capitulo 1 nao provam equivalencia de obra/edicao; respostas MangaDot podem ter rivais, Host assinado code10 permanece intacto, e a entrada HTTP do Host legado tem teto de 192 KiB.
- MangaFire permanece fora deste lote de CI. A [PR #275](https://github.com/jssantogit/tsuzuki/pull/275) permanece Draft, sem merge/assinatura/release. O agregado de fonte global mais exigente permanece RED/NEEDS_CORRECTION.

**Decisao:** nao substituir qualquer uma das 30 obras antes de todo o percurso canônico + Reader real + persistencia estar validado.
