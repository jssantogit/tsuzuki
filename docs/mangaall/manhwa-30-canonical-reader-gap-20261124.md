# MangaALL — barreira canônica antes do Reader visual / SQLite

**Auditoria de código em 24 nov 2026; não é resultado live adicional.** Branch de pesquisa [PR #275](https://github.com/jssantogit/tsuzuki/pull/275).

## Ponto exato onde os cinco verdes Android de fonte terminam

As CIs [#45365160634](https://github.com/jssantogit/tsuzuki/actions/runs/45365160634), [#45366893915](https://github.com/jssantogit/tsuzuki/actions/runs/45366893915), [#45368754172](https://github.com/jssantogit/tsuzuki/actions/runs/45368754172), [#45371037327](https://github.com/jssantogit/tsuzuki/actions/runs/45371037327) e [#45373652573](https://github.com/jssantogit/tsuzuki/actions/runs/45373652573) testaram os MESMOS 30 manhwas, com 10 obras por shard, sobre HIDs de fonte. Na quinta, o `ProviderHttpPageLoader` do app realmente chegou a `Page.State.Ready` e expôs bytes decodificáveis da primeira página de capítulo 1 de um HID para cada obra.

Isso **não constrói automaticamente um ID editorial canônico**. O Runner de prova criou um `ProviderBindingRef` apenas dentro da invocação de fonte. Não validou uma entidade editorial armazenada, capítulos mapeados por evidência nem uma escolha persistida de edição.

## Contratos exatos do Reader em produção (auditoria de código, não teste executado)

1. `PrepareCanonicalChapterForReader.execute(canonicalChapterId)`, em `domain/.../reader/interactor/PrepareCanonicalChapterForReader.kt`, começa por **`CanonicalChapterRepository.getById(canonicalChapterId)`**. Se o capítulo não existe no catálogo canônico, o Reader não cria um automaticamente só por encontrar HID.
2. Ao solicitar uma fonte/idioma explicitamente, `ResolveMangaAllSelectedChapter` chama `ResolveProviderChapterReading.options(canonicalChapterId)` e aplica o filtro do `ResolveMangaAllSelectedReadingOption` à fonte/idioma armazenados. Não é permitido inventar capítulo, escolher outra fonte ou outro idioma quando a escolha não está verificada.
3. `ResolveProviderChapterReading.options` exige elegibilidade real do provider, vínculo disponível ou associação de trabalho com `AUTHOR_CORROBORATED`, edição `UNRESOLVED` mas sem evidência de variante negativa, capítulo de fonte efetivamente **persistido** e **mapeado ao ID canônico exato** (`mappedCanonicalChapterId`), identidade/número/volume não conflitantes, e `MangaAllReadingIdentity.accepts()` sobre o HID de capítulo. `SOURCE_ATTESTED_VARIANT` bloqueia associação automática de edição base; `USER_CONFIRMED` deve continuar distinguível.
4. `ResolveMangaAllSelectedReadingOption.select` só aceita uma opção **única** que casa o capítulo, o `ProviderBindingRef` escolhido, faceta/idioma e prefixo do capítulo. `singleOrNull()` evita escolha ambígua.
5. O `ReaderViewModel` consulta `CanonicalChapterRepository` e `CanonicalReadingRepository` para montar a sessão e o progresso, e só então utiliza `planCanonicalReaderTarget(...)` para construir `CanonicalReaderTargetPlan.HttpPages` e `ProviderHttpChapterLoader`. O teste de fonte atual chamou diretamente `ProviderHttpPageLoader` sem passar pela cadeia de identidade e persistência acima.

## Consequência para o corpus congelado

- O MangaDot original publica resultados de busca contendo `externalWorkId`, `title`, `aliases` e URL, mas não fornece um autor corroborado no DTO de lookup. Um match exato de título **não basta** para promovê-lo a `AUTHOR_CORROBORATED` ou para selecionar uma edição rival.
- A rodada de fontes já detectou rivais do mesmo provider, aliases e associações paralelas nos 30 manhwas. É incorreto forçar a preferência da primeira fonte que tiver capítulo 1 ou fabricar evidência de capítulo canônico.
- A tarefa real agora é testar, **sobre os mesmos 30 títulos**, a identidade da obra/autor/edição, o binding persistido e o mapeamento de capítulos em armazenamento persistente com reabertura, e só depois executar `ReaderActivity` e viewer com a edição selecionada. Uma obra ambígua deve ficar bloqueada e explicitamente diagnosticada, nunca ser marcada verde por só ter imagens acessíveis.

## Regras de segurança da próxima etapa

**Não** usar um nome exato, um capítulo primeiro ou uma imagem decodificada como substituto da identidade editorial. Não colapsar edições, não converter falta de corroborador em verificação, não atribuir dados da fonte a obra canônica sem vínculo, não alterar o code10 assinado. Enquanto essa cadeia não passar corretamente para todas as 30 obras, a coorte segue CONGELADA, a PR #275 Draft e o agregado de fontes geral RED/NEEDS_CORRECTION.
