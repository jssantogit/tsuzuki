# MangaALL — 30 manhwas: imagem real decodificada no Android

**24 nov 2026 · [CI #45371037327](https://github.com/jssantogit/tsuzuki/actions/runs/45371037327) · conclusao SUCCESS · pacote code11 unsigned · mesmo corpus congelado de 30 obras.**

## Resultados comprovados

| Job | Escopo | Veredito |
|---|---|---|
| build-once-unsigned | Par comum de APKs, compilado uma vez | SUCCESS |
| frozen-manhwa-image-pixels (0) | Obras 1–10 | SUCCESS |
| frozen-manhwa-image-pixels (1) | Obras 11–20 | SUCCESS |
| frozen-manhwa-image-pixels (2) | Obras 21–30 | SUCCESS |

A instrumentacao Android valida o hash SHA256 da [lista de 30 manhwas fixos](../../.github/mangaall-dual-source-30-cohort-lock.json) (`19996790a934514c7877447610ee09a9e0dd2bd8fe8b2c42ff46a7a5cfe64bd1`). Para cada obra, o Android usa lookup de fonte, lista granular de capitulos ate cursor terminal, o capitulo numero 1 de **um HID de fonte sem rebinding silencioso**, o `reading.pages` real e a URL HTTPS da primeira pagina.

Na fase de imagem, o teste:

- Valida os hosts explicitamente permitidos: `mangadot.net`, `uploads.mangadex.org` ou subdominios de `mangadex.network`; rejeita usuario embutido na URL, portas desconhecidas e redirecionamentos automaticos.
- Aplica apenas os cabecalhos de imagem previamente declarados pelo adaptador (Referer, Origin e User-Agent), validando seu formato.
- Obtem bytes reais em conexao HTTPS nativa do Android, com limites de tempo e no maximo 12 MiB de corpo por primeira pagina. A resposta HTTP deve ser 200.
- Decodifica o corpo com Android `BitmapFactory` (amostragem 1/8), exige bitmap com dimensoes positivas e le um pixel real, sempre descartando o bitmap no fim.
- Nunca armazena, envia nem publica bytes, URLs, credenciais, headers, nomes de servidores especificos ou pixels nos artefatos. Preserva apenas vereditos sanitizados de sucesso/erro por shard.

O ensaio anterior [#45370592259](https://github.com/jssantogit/tsuzuki/actions/runs/45370592259) havia falhado exclusivamente no verificador Spotless por ordem de import Kotlin, antes de qualquer teste em rede; a alteracao exata de imports foi aplicada, e o novo build e os tres emuladores passaram.

## Fronteiras da evidencia

**VALIDADO:** trabalho de fonte por HID, paginacao de capitulo 1, metadados de paginas de Reader e **transporte+decodificacao em Android da PRIMEIRA imagem** de cada uma das 30 obras, no pacote experimental.

**AINDA NAO VALIDADO:** renderizacao da imagem na tela real do Reader do app, troca de paginas/scroll, persistencia SQLite e retomada apos reinicio, vinculo canônico autor/obra/edicao (inclusive rivais do MangaDot), regras de remocao/atribuicao MangaDex, todos os limites e politicas do Host de producao, imagem de todos os capitulos, MangaFire e release assinada. A conexao HTTPS do teste e exercitada na instrumentacao Android, **nao equivale automaticamente ao caminho de imagem da UI do app**.

**Nao confundir:** mais um CI verde do trajeto Android nao implica fechamento do agregado geral RED/NEEDS_CORRECTION nem liberacao de obras do corpus. [PR #275](https://github.com/jssantogit/tsuzuki/pull/275) permanece Draft e unsigned, sem merge/publicacao. **Nenhum dos 30 titulos deve ser substituido antes da validacao completa.**
