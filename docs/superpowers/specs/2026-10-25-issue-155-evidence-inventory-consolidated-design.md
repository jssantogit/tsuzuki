# Issue #155 — Inventário Canônico de Evidências (ICE)
## Solução consolidada para mangas, manhwas, manhuas e webcomics

**Data:** 2026-10-25  
**Estado:** **arquitetura ICE aprovada pelo usuário em 2026-10-25 para execução incremental** sobre a [issue #155](https://github.com/jssantogit/tsuzuki/issues/155). A aprovação não autoriza scraping de terceiros, aquisição sem permissão, promoção de índices comunitários como editoriais, alteração de sync/backup nem rollout sem gates.  
**Base:** arquitetura A da #155, [Relatórios A/C da #154](https://github.com/jssantogit/tsuzuki/issues/154), [pesquisa de fontes editoriais](../../research/issue-155/primary-editorial-source-feasibility.md), [pesquisa R2 para mangas/manhwas/manhuas](../../research/issue-155/commercial-manga-manhwa-inventory-feasibility-round2.md), decisões vigentes em Notion / Projects / Tsuzuki.  
**Branch:** `feat/issue-155-independent-chapter-inventory`, PR #156 Draft. Nenhuma mudança funcional é implicada pela publicação desta proposta.

### 1. Resultado de produto

**Um único pipeline de inventário local baseado em evidências; muitas fontes opt-in intercambiáveis; um único motor de identidade, reconciliação e persistência canônicas.**

O Tsuzuki instala **sem catálogo ativo, sem títulos/índices pré-carregados e sem consultas de inventário passivas**. Integrações que o usuário habilita encontram trabalhos e metadados; a capacidade opcional **Chapter Inventory** pode enriquecer uma obra canônica previamente identificada. Independência dos *providers de leitura* significa que uma obra pode ter capítulos conhecidos sem nenhuma fonte capaz de fornecer páginas. Isso não significa que uma integração de inventário possa inventar/descobrir títulos por padrão.

Não existe catálogo central obrigatório, API universal ou dependência de Shueisha/Kakao/Wikimedia no Core. Nenhuma integração ganha status de verdade por ser mais popular. A estrutura principal deve aproveitar `IntegrationRegistry`, o contrato tipado `IndependentInventoryProducer`, o ledger SQLDelight já iniciado na migração 46, o modelo `ChapterEvidence` e o reconciliador de capítulos; **não criar um segundo Chapter Engine, identidade ou reader**.

### 2. Fronteiras de responsabilidade

```text
Integrações habilitadas + importação local explícita
  ├── catálogo/metadados de obras (MAL/Kitsu/MU etc.)
  ├── inventário editorial autorizado (quando existir feed permitido)
  ├── índices comunitários reutilizáveis (Wikimedia, por exemplo)
  ├── metadados bibliográficos/opcionais (Open Library, KMAS)
  ├── importação privada de índice de edição (EPUB/ToC sem contornar DRM)
  └── releases e totais (MU/MD/AniList/Kitsu, valor auxiliar)
        ↓   observações tipadas, não comandos de escrita
Registry + Host policy: opt-in, permissões, identidade/edição, rede, licença, orçamento
        ↓
Ledger local (existente, aditivo): batch, claims, revisão, origem, cobertura, conflitos
        ↓
Avaliação canônica de admissibilidade e revisão explícita quando necessária
        ↓
Um reconciliador existente → CanonicalChapter (se elegível) / candidato (senão)
        ↓
Home/Details/Reader → leitura apenas por provider elegível, quando disponível
```

**Integração** responde "o que se sabe e de onde veio?"; **Tsuzuki Core** responde "a que obra/edição/unidade isso corresponde?"; **provider de leitura** responde "como ler?". Não inverter essa dependência.

### 3. Mudança mínima do modelo extensível

1. Introduzir **capacidade** `CHAPTER_INVENTORY` no sistema existente `IntegrationCapability` + `IntegrationRegistry` (ou unificar funcionalmente com `ChapterEvidenceProvider` sem perder tipos). O atual `ChapterEvidenceProvider.evidenceFor` é plano e não representa cursor/edição/proveniência de releases; compatibilidade deve usar um adapter de contrato, nunca coerção de releases em evidência `EDITORIAL`. A capacidade deve apontar para o `IndependentInventoryProducer` ou sua pequena evolução, NÃO criar um registro paralelo global.
2. **Opt-in deve ser duplo e explícito**: integração habilitada E capacidade de inventário consentida. Atualmente `IntegrationSettingsConfig.capabilityEnabled(key)` retorna `true` para chave ausente; portanto, adicionar a capacidade a integrações já habilitadas **não pode** ativá-la por fallback legado. Default explícito para essa nova capability: **false**, mesmo quando configuração antiga não possui a chave. Nenhum refresh automático apenas por abrir Details/ativar metadados. Ações por obra e refresh incrementais continuam sob permissão.
3. O Host fornece **guard de permissão/conta/configuração/edição** antes da requisição, após resposta e de modo atômico no commit quando necessário. Revalidar a propriedade da obra ao alternar título/conta, desabilitar integração ou trocar edição. Conservar respostas antigas permitidas sem permitir escrita tardia sob identidade revogada.
4. Evitar um HTTP client irrestrito: as origens e métodos autorizados, quotas, cache, `429`/`403`, redirecionamentos, cookies, política de sessão e tamanho ficam isolados nos adapters/Host Services apropriados. APIs não documentadas, crawlers proibidos, download de páginas e extensões com privilégios implícitos **não** entram no caminho de inventário.
5. Importação de arquivo local é um **modo de aquisição do mesmo contrato**, iniciado por seleção explícita do usuário. Não precisa ser uma integração online ou publisher incorporado. Não varrer automaticamente o armazenamento e não processar DRM/acesso protegido.

**Nota de implementação verificável:** em 2026-10-25, `RefreshIndependentInventory.execute` aceita producer + callback de habilitação avulsos e constrói `InventoryScope(editionKey=null, editionIdentityVerified=false)` para os adapters MU/MD; esse caminho permanece correto para releases em shadow mode. O novo fluxo editorial precisa de identidade de edição reconhecida e avaliada fora dos produtores, sem fazer `editionIdentityVerified=true` apenas porque a fonte enviou uma string.

### 4. Um único contrato: evidência + autoridade + escopo

Um lote oferece, no mínimo, `canonicalTitleId`, identidade externa de obra verificada, **identidade externa de edição**, variante/idioma pertinente, `producerId`, origem jurídica/atribuição, modo de aquisição, identificador de cada unidade upstream, título original, numeração **estrutural** (volume, arco/temporada quando definem identidade, parte, ordinal exato e classe principal/extra/prólogo/especial), referência de revisão, timestamp de observação, cursor, resultado técnico e informação sobre abrangência **somente quando a fonte realmente a declara**.

Separar três coisas que normalmente são confundidas:

- **Classe da observação:** episódio/capítulo individual, release, intervalo, total declarado, placeholder derivado, correção, disponibilidade. `INDIVIDUAL` não implica editoria confiável.
- **Autoridade de origem:** `PRIMARY_EDITORIAL` comprovada **pela política do Host a partir de feed autorizado e identidade exata**, `COMMUNITY_CURATED` com revisão/atribuição, `USER_SUPPLIED_EDITION_TOC` (sumário privado), `RELEASE_RECORD`, `COUNT_DERIVED`, `UNKNOWN`. A etiqueta declarada pelo próprio adapter **não é prova**; adapters não podem atribuir-se autoridade editorial por JSON arbitrário.
- **Estado canônico:** apenas observado/candidato; adotado explicitamente pelo usuário (possível caminho novo, distinto de editoria verificada); editorialmente confirmado; conflitado/corrigido. Nunca mostrar "oficial" para adoção de Wikipédia ou "completo" para paginação encerrada.

A identidade canônica pertence ao Tsuzuki e permanece estável. `work`, `series`, `edition`, `publication/volume`, `episode/chapter`, tradução/revisão e `reading.release` não podem se tornar a mesma chave. Idioma desconhecido não equivale a todos os idiomas. Um ID externo `work` de MangaDex, UUID de release ou ISBN de volume **não** comprova identidade editorial de capítulo sozinho.

**Número de exibição ≠ identificador:** "Tower of God Season 3 Ep. 235" e índice global #652 são duas noções diferentes; "Omniscient Reader Episode 308" ≠ item de página #309; `Tokyo Ghoul` e `Tokyo Ghoul:re` são obras distintas; `Solo Leveling` capítulo principal 179 e extras 1–21 não são 200 capítulos lineares; `0.01` ≠ `0.1`, `12.05` ≠ `12.5`. A estrutura exata deve ser representável sem coercão para `Double` onde a identidade decimale precisa de precisão textual. Nunca preencher lacunas a partir de totais.

### 5. Política única de materialização

| Entrada | Armazenamento | Criar capítulo automaticamente? | Ação opcional |
| --- | --- | --- | --- |
| Capítulo individual de fonte editorial **autorizada**, identidade de obra+edição+unidade verificada pelo Host | Evidência primária | **Elegível** para reconciliação conservadora, não obrigatória | Revisar conflitos e atualização |
| Capítulo de índice comunitário obtido via API permitida | Evidência comunitária, revisão e atribuição | **Não** | Prévia; **adoção explícita por edição/lote** somente se a política de usuário for aprovada |
| Item de sumário de arquivo de edição fornecido pelo usuário | Evidência de arquivo/edição local, sem upload | **Não** por importar o arquivo | Confirmação explícita de edição e unidades |
| MangaUpdates/MangaDex release, mesmo rotulado "7" | Release com provenance | **Nunca** apenas por release | Corroboração operacional, conflito e disponibilidades |
| MAL/Kitsu/AniList total de capítulos | Total declarado | **Nunca** | Exibir total separado |
| Intervalo, range, conteúdo ambíguo, parser parcial | Claim não admissível | **Nunca** | Inspeção / não inferir unidades |

**Separação fundamental:** `InventoryClaimPolicy.mayPromote` permanece *necessário*, não suficiente, para `PRIMARY_EDITORIAL`. A via futura `USER_ADOPTED`, se aprovada, deve ser uma política explícita **separada**, transacional e auditável; jamais converter `COMMUNITY_CURATED` em `PRIMARY_EDITORIAL` para reutilizar um `true` na função. A adoção em lote só poderá aceitar entradas individualmente identificadas na edição escolhida, com revisão/versão do parser fixadas, recusando silenciosamente nenhuma entrada ambígua. Prévia deve mostrar contagem aceita, excluída e conflictuosa.

**Durabilidade e privacidade:** antes de materializar `USER_ADOPTED` em `CanonicalChapter`, decidir/validar v1 sync e portable backup para suas identidades e recibos sem exigir v2 ou ativar backup canônico. Até que a trilha segura seja provada, armazenar apenas candidatos shadow (mesmo que exista UI de prévia). Uma adoção posterior não rekeya `tsuzuki:count:*`; qualquer match exato de legado preserva ID/progresso/histórico/variantes/downloads e não gera tombstones. Correção de índice não equivale a deletar estado do usuário.

### 6. Fontes concretas e seus papéis (sem fornecedor obrigatório)

| Fonte | Papel aceito no ICE | Contrato de implantação |
| --- | --- | --- |
| **Wikimedia** | Índices comunitários **selecionados** para Black Clover, Tokyo Ghoul e casos com capítulos explícitos | API documentada, id/revisão fixa, atribuição/licença CC BY-SA conforme página, orçamento e user-agent; parser por estrutura suportada; somente opt-in; candidatos não editoriais |
| **Shueisha / VIZ / MANGA Plus** | Evidência primária **por edição individual observada**, quando houver meio autorizado | Shueisha publica ToCs reais; página pública **não autoriza scraping**; VIZ proíbe robôs sem permissão; MANGA Plus listas parciais, APIs extraoficiais não autorizadas |
| **KakaoPage / Kakao Webtoon / WEBTOON / Kuaikan** | Evidência editorial por episódio, edição/idioma/temporada, com capítulos principais e extras | Exemplos públicos existem; não há contrato universal de coleta permitido nesta pesquisa; RSS WEBTOON precisa validar XML/termos antes de uso; não usar APIs privadas por padrão |
| **Open Library** | Sumário opcional de edição `table_of_contents[]` e ISBN | API documentada; campo opcional e cobertura de mangas-alvo não validada; não presumir ToC por existir ISBN |
| **KMAS (Coreia)** | Crosswalk e metadados bibliográficos de obra/edição | API oficial com chave e aprovação; campos públicos consultados não comprovam episódios individuais |
| **EPUB local** | Sumário de edição sob controle do usuário | Navegação `toc` é estrutura de publicação, não prova direta de capítulo; validar unit/edição, sem contornar DRM ou distribuir conteúdo |
| **MU/MD, MAL/Kitsu** | Releases, cruzamento de IDs, contagem e contexto | Reutilizar adapters atuais e ledger; não promover releases nem inventar posições |

Não reproduzir bancos de dados inteiros de terceiros, não permitir API host arbitrário, não fazer scans em massa e não pressupor que várias bases derivadas da mesma fonte sejam evidência independente.

### 7. Um fluxo no produto, não quatro UIs

**Instalação limpa:** integração nenhuma ativa → catálogo remoto não é preenchido, capítulo nenhum é sintetizado, nada consulta rede em background.

**Obra conhecida:** ao habilitar uma integração de catálogo e abrir uma obra com ID externo verificado, o usuário pode configurar uma **fonte de inventário** específica. Apenas fontes/edições que tenham vínculo verificado são elegíveis para atualização. A capacidade está desligada por padrão inclusive para integrações antigas já habilitadas.

**Ação única "Atualizar inventário":** só consulta fontes explicitamente habilitadas/permitidas daquela obra; cada uma retorna ao mesmo ledger; UI mostra um resumo enxuto de capítulos confirmados, candidatos, desconhecido/parcial e disponibilidade de leitura separada. Identidade não confirmada impede a consulta ou passa por associação explícita; não usar similaridade de título como prova.

**Quando só há Wikipédia/EPUB:** mostrar prévia por edição/volume, origem e conflitos. A política de adoção em lote (se aprovada) é uma confirmação informada, sem marcar a lista como oficial. Pode recusar entradas conflituosas e deixar as demais como candidatas. Fonte desativada não apaga dados do usuário; retenção de payload de terceiros respeita licença.

**Quando existe fonte editorial primária admissível:** materialização conservadora através do reconciliador existente e consulta offline, sem provider de leitura; ação "ler" permanece condicionada a um `ContentBinding` realmente resolúvel. Sem evidência: "inventário desconhecido/parcial", nunca fabricar `1..N` a partir de `chapterCount`.

### 8. Acceptance corpus e resultados esperados

1. **Black Clover**: unir unidades individualizadas de volumes japoneses sem criar capítulos faltantes por total; extra pages preservadas como especiais; community Wikipedia é candidato, não promoção editorial automática.
2. **Tokyo Ghoul / :re**: works separados; capítulos locais #001 e :re 1 não colidem; identidade de edição inglesa/japonesa e reedições não se misturam.
3. **Solo Leveling**: fim principal 179 e 21 extras permanecem separados; o total de 203 produtos KakaoPage inclui outras categorias e nunca cria 203 capítulos principais.
4. **Tower of God**: estação/temporada + ordinal local e ID de site global têm namespaces distintos; temporada reiniciando em 0/1 não colide.
5. **Omniscient Reader**: número do episódio e contador de item da página não geram off-by-one automático.
6. **Manhua/Kuaikan**: número total de conteúdos e total de capítulos de história são métricas diferentes.
7. **Apenas 120 declarados, 80 individuais**: 80 observações no máximo; 40 capítulos adicionais **não** podem ser sintetizados.
8. **Edições, fracionários, partes, traduções, specials**: `0`, `0.01`, `0.1`, `12.05`, `12.5`, reupload `1v2` e ranges `246-251` não colapsam.
9. **Revogação/restart/offline/429/erro/página vazia**: sem deletar capítulo/claim prévio ou aceitar resposta tardia de vínculo/configuração alterados; sem assumir completude por cursor exaurido.
10. **Legado**: SQLite real, Reader, download, histórico, overrides, v1 sync/export/tombstones e codec backup provam que `tsuzuki:count:*` mantém sua identidade mesmo após convergência. Teste físico Android manual só no checkpoint integrado e APK identificado, não por fase documental.

### 9. Execução incremental sem reescrita de arquitetura

**Fase A — consolidar capacidade:** testes RED para novo gate de `CHAPTER_INVENTORY` **desativado por padrão**, inclusive integrações existentes, e para nenhuma consulta implícita. Integrar descoberta/seleção de `IndependentInventoryProducer` ao `IntegrationRegistry` sem quebrar `ChapterEvidenceProvider` legado nem mudar Reader. Revalidar os MU/MD existentes; HTTP continua host-specific. Esta fase pode ser implementada **sem** fonte editorial real.

**Fase B — provar fonte reutilizável:** spike/testes de API Wikimedia com **uma obra já vinculada**, uma revisão conhecida e um parser estrito, shadow mode, sem cópia global nem promoção. Em paralelo, fixture de EPUB/ToC local sintético e fonte editorial fictícia **autorizada simulada** para exercitar elegibilidade positiva e repositório. Medir cobertura efetivamente enumerada, falsos matches, exclusões, falhas parciais e licença/atribuição. Não ativar importação de conteúdo.

**Fase C — decidir materialização de dados comunitários:** exigir decisão explícita sobre `USER_ADOPTED` com UX de confirmação por edição, recibo de revisão e compatibilidade v1 sync/backup. Sem essa aprovação, candidatos apenas no ledger shadow. Não confundir o teste positivo de produtor editorial fictício com permissão para coletar Shueisha, Kakao ou WEBTOON.

**Fase D — produtor editorial real e rollout:** habilitar **somente** fonte primária autorizada ou arquivo de edição local sob política aprovada; reconciliar pela máquina atual com transação, identidade exata e cobertura testada; smoke de dispositivo real após CI exata. Não depender da negociação de editoras para iniciar/validar Fases A/B.

**Gates antes de aprovar a PR #156:** produção sem catálogo por padrão; evidência independente admissível realmente projeta capítulos sem providers de leitura; coexistência de integradores; perda nula no legado; CI relevante no SHA exato; aceitação manual de UI/Reader. Manter Draft se não satisfeitos; não mesclar nem gerar APK automático.

### 10. Alternativas rejeitadas / razões

- **Catálogo pré-semeado, banco global ou scrapers de sites no Core:** dependência externa/termos/licença + desrespeito ao opt-in.
- **Contagem como capítulos `1..N`:** gera identidades fictícias e riscos reais de cascatas/sync.
- **Wikipédia como publisher / acordo de duas fontes derivadas como verificação:** falsa autoridade.
- **Liberar todos os capítulos de MU/MD com label numérico:** release/scanlation não é capítulo editorial único.
- **Novo "WikipediaChapterEngine" e outro banco de capítulos:** duplicação de Core e migrações de estado pessoal.
- **Requisição manual como licença de scraping:** consentimento do usuário não sobrepõe termos de uso de terceiros.
- **Importar toda a `toc` do EPUB sem inspeção:** prefácio, pósfácio e seções seriam capítulos falsos.

### 11. Estado efetivo vs proposta

**Fase A implementada na PR #156 após aprovação (checkpoint `b42359da`):** nova capacidade `CHAPTER_INVENTORY` no `IntegrationCapability`, explícita e off-by-default inclusive no JSON legado; registro `IntegrationRegistry.inventoryProducers()` filtrado por manifesto, habilitação e capacidade; implementação MU/MD contribuída por Metro; entrada de integração MangaDex sem publicar um Provider de leitura vazio; configuração PT-BR/EN e seleção do produtor no ScreenModel através do registro, com rejeição quando desativado e revalidação por fingerprint durante requisições. CI v2.1 afetada [#40204129797](https://github.com/jssantogit/tsuzuki/actions/runs/40204129797) **SUCCESS** no SHA `b42359da7580d88d2d2579e3d6880f39f22a0246`, App + Format + Gate. Nenhuma fonte editorial nova, importador de arquivos ou escrita canônica ativada.

**Já implementado na PR #156 antes desta especificação:** produtor tipado com `InventoryScope`, classificador conservador, lote paginado, ledger SQLDelight local shadow/offline, produtores MU/MD de releases, vinculação work-level MangaDex, UI de provenance, preservação de `tsuzuki:count:*` e testes de v1 sync/backup/SQLite/HTTP. **Ainda não implementado:** adaptador Wikimedia, importador EPUB, classificação/recibo de `USER_ADOPTED`, associação de edição verificada por produtor editorial autorizado e materialização real sem provider de leitura. Material complementar não altera os resultados de CI do commit de código anterior. Relatórios R1/R2 permanecem como evidência histórica; este documento converge os caminhos em **um design proposto**, não reescreve a issue aprovada até sua revisão.
