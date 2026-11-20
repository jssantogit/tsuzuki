# MangaAll code9: smokes sob demanda pelo ChatGPT via GitHub Actions

**Estado:** ponte de comando somente quando solicitada; nenhuma execução live
é disparada por commit, PR ou agendamento.

## Por que existe uma ponte de comentários?

A integração GitHub usada pelo ChatGPT pode criar comentários em issues e
analisar os workflows, mas não fornece uma chamada direta para o endpoint
`workflow_dispatch`. A issue estável
[#230](https://github.com/jssantogit/tsuzuki/issues/230) atua como um canal
de comandos **autenticado pela própria identidade GitHub**.

O workflow
[`mangaall-code9-on-demand-command.yml`](../.github/workflows/mangaall-code9-on-demand-command.yml)
escuta apenas eventos `issue_comment:created`; o único job autorizado
aceita **exclusivamente** a issue #230 aberta, título pinado, repositório
`jssantogit/tsuzuki`, comentário cujo usuário e remetente sejam
`jssantogit` e `author_association=OWNER`, e a primeira tentativa do
evento. Não executa comandos arbitrários ou scripts fornecidos no comentário.

Formato **para o planejamento sem rede**:

```text
/mangaall-plan isolated
manga :: Naruto
manga :: Death Note
manga :: Bleach
```

Formato **para iniciar a execução real**, apenas após pedido explícito:

```text
/mangaall-smoke isolated
manga :: Naruto
manga :: Death Note
manga :: Bleach
```

Para escalar, trocar `isolated` por `scale` e informar de **10 a 30 obras**,
uma por linha, depois de analisar o smoke isolado. Comandos de título são
públicos na issue, sem autenticação, cookies, URL privado ou segredos.
A ordem do vetor é preservada.

O validador
`.github/scripts/mangaall_code9_smoke_comment_dispatch.py` reaproveita
**exatamente** o parser offline original
`mangaall_code9_canonical_smoke_plan.parse_plan`: limite UTF-8, formatos
permitidos, duplicatas, normalização NFKC, número de obras e comprimento
bounded. Rejeita revisão/edição de comentários, comentários de PR,
issues não autorizadas, remetente estrangeiro, rerun e comandos soltos.

Um terceiro comando estritamente técnico verifica a **permissão real de
workflow_dispatch**, sem consultar fontes ou emulador:

```text
/mangaall-selftest-dispatch
```

Esse comando só existe para comprovar que o token efêmero do job de
`issue_comment` consegue despachar o workflow allowlistado
[`mangaall-code9-on-demand-selftest.yml`](../.github/workflows/mangaall-code9-on-demand-selftest.yml),
cuja única ação é registrar
`MANGAALL_ON_DEMAND_SELFTEST|dispatch=ACCEPTED|upstream=NONE|android=NOT_RUN`.
O selftest não aceita títulos nem executa o workflow de smoke.

Quando `/mangaall-plan` passa, o Actions exibe
`MANGAALL_ON_DEMAND_COMMAND|...|network=NONE` e **não** chama o workflow
de smoke. Quando `/mangaall-smoke` passa, o gateway usa o token
**efêmero** `GITHUB_TOKEN` com `actions:write`, `contents:read`,
`issues:read` para executar uma única requisição
`POST /repos/jssantogit/tsuzuki/actions/workflows/mangaall-code9-canonical-smoke.yml/dispatches`
com `ref=main`, vetor original, tier e o literal
`I_ACCEPT_CODE9_CANONICAL_SMOKE`; o selftest despacha apenas o arquivo
allowlistado de prova de permissão e usa `ref=main` sem outros inputs.
O destino do POST é um de **exatamente dois workflows** fixados no código,
jamais um URL fornecido pelo comentário. Exige HTTP 204 do GitHub; não faz
tentativas de POST repetidas, para evitar executar múltiplos smokes num
erro de rede incerto.

A **execução real** permanece responsabilidade do workflow
[`mangaall-code9-canonical-smoke.yml`](../.github/workflows/mangaall-code9-canonical-smoke.yml),
que verifica código9 assinado P-256 seq35, captura V1 por MangaAll unificado,
MangaDex/MangaDot/MangaFire na mesma consulta por obra, catálogo independente,
coletor canônico, reconciliação de capítulos e SQLite. A etapa Reader é
regressão com imagens sintéticas e **não** prova CDN real. Fonte 403 ou
continuação parcial deve ser inconclusiva.

## Uso pelo assistente

Quando o usuário pedir, por exemplo, **"Rode o smoke com Naruto, Death Note e
Bleach"**, o assistente poderá publicar na issue #230 um comentário
`/mangaall-smoke isolated` com esses títulos, consultar a execução do
gateway, localizar o workflow iniciado, inspecionar logs e artefatos, e
resumir erros por obra. Quando o usuário pedir um teste maior, escolher
`scale` apenas após confirmar o teste isolado e a autorização do usuário
para essas obras. Caso não seja possível ao GitHub Actions criar o dispatch
(por configuração de permissões do repositório), a execução falhará
claramente e o assistente deverá relatar esse bloqueio — jamais afirmar
que o smoke foi iniciado.

Os commits de integração fazem apenas **testes offline** desta ponte;
**não** enviam comandos de execução real, criam novas publicações ou
alteram a instalação física.

Tracking: [Host #207](https://github.com/jssantogit/tsuzuki/issues/207),
[Provider #72](https://github.com/jssantogit/providers-tsuzuki/issues/72).
