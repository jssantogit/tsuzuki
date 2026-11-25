# MangaBall — investigação da paridade de transporte (2026-11-25)

Pesquisa isolada sobre a diferença entre a extensão do Keiyoushi/Mihon e o provider SCRIPT. Base fixa: PR #279, commit 356c7a69900ee6e64fda56bce6b65e6ac832ae55; pacote development 89b5979fff297c2a099359258c9f9249e0089db4, SHA-256 524849196f9cbe1e3f5bf4ce5dc943e58c6dfdc70d52d2f63ea7992019642260. Não modifica #275, fontes ativas, segurança do Host, assinatura ou publicação.

## Evidência anterior

- A extensão nativa foi carregada em Android CI anteriormente (42 fontes internas; 41 elegíveis após desabilitar uma). O teste live de pesquisa não obteve status HTTP conclusivo (CI #36167100902, conforme openspec/changes/mangaball-real-validation/tasks.md). Logo, não atribuir a falha exclusivamente ao QuickJS.
- #278 demonstrou o pacote experimental offline; #279, CI #45431894436, falhou na navegação comum da homepage antes da consulta real.
- NetworkHelper (core/common) cria cliente OkHttp com AndroidCookieJar/CookieManager, cache, interceptadores UserAgentInterceptor e CloudflareInterceptor, DNS conforme preferência e prazos de chamada próprios. ProviderHostInvocationFactory (app) recebe OkHttpClient padrão em produção e constrói DefaultProviderHttpHostService com cookie jar separado por provider, DNS/política próprios, redirecionamentos validados manualmente e prazo de invocação.
- ProviderHttpProtocol.MAX_RESPONSE_BODY_CHARS = 192 Ki caracteres; JSON IPC limitado separadamente. O limite é uma diferença real do contrato, não uma explicação comprovada do 403 anterior. Browser Host é outro perfil WebView e tem limite próprio. Não compartilhar cookies/credenciais nem copiar interceptador de desafio.

## Novo gate offline

O teste mangaBallDevelopmentArchive_nativeHttpOfflineParity captura a requisição do pacote exato por QuickJS/Binder usando resposta sintética e interceptador terminal. Compara mesma requisição e corpo com clone do cliente herdado (cache e cookie jar desabilitados para preservar dados reais), HTTP Host direto e pacote QuickJS. Verifica sucesso ordinário nos três, rejeição do corpo sintético válido acima do limite no Host/QuickJS e uma negativa 403 sem fallback. O clone nativo conserva seus interceptadores normais mas recebe apenas 200 sintéticos; não é uma execução da extensão original nem valida comportamento de sessão/challenge. Nenhum tráfego upstream: interceptador terminal e DNS bloqueado no clone de teste; política do Host preservada. Não inferir disponibilidade real, cobertura, autoria, capítulo, Reader ou equivalência de sessão.

## Protocolo opcional de dispositivo físico (somente após CI verde)

1. Registrar versão da extensão original do Keiyoushi e tentar uma pesquisa normal por Oyasumi Punpun no Mihon.
2. Registrar a mesma consulta no Tsuzuki com a extensão nativa, se instalada e habilitada. Mesmo aparelho, rede e idioma; registrar apenas resultado e classe de erro, não credenciais, cookies, payloads ou URLs de conteúdo. Se houver uma negativa explícita de acesso, interromper; não repetir por transporte alternativo para tentar contorná-la.
3. Para comparação autorizada, o APK debug e o APK androidTest da CI permitem executar a consulta do pacote QuickJS isolado. Usar instalação de teste separada, não substituir instalação com dados pessoais nem apagar dados. O teste manual não roda na CI, só com opt-in explícito. Faz no máximo uma consulta ordinária ao endpoint de pesquisa, sem retry automático, solver, warmup ou transporte alternativo; redirecionamentos/consultas adicionais não são seguidos.

```sh
adb shell am instrument -w -r \
  -e class eu.kanade.tachiyomi.provider.reading.ScriptProviderReadingGatewayIsolationTest#mangaBallDevelopmentArchive_manualDeviceLookup \
  -e mangaBallDeviceLookupOptIn I_ACCEPT_ONE_MANGABALL_LOOKUP \
  app.tsuzuki.dev.test/androidx.test.runner.AndroidJUnitRunner
```

Compartilhar somente a linha MANGABALL_DEVICE_LOOKUP (status, requests, outcome, candidates). O resultado de uma pesquisa não confirma a identidade canônica nem inventário ou Reader. Se as duas extensões funcionarem e o pacote SCRIPT falhar, investigar o limite/política/contrato real conforme a evidência; não deduzir automaticamente problema de cookies ou challenge. Se a extensão nativa falhar no Tsuzuki, investigar também o gateway de extensões herdado. O celular não está acessível nesta sessão; nenhuma execução física foi realizada.

Nenhuma mudança de produção aprovada. Critérios independentes: contrato offline, acesso live, reconciliação canônica e Reader.
