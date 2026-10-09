# Providers — tensões de design

> Registro de elaboração preservado. Os estados Provisional/TBD abaixo descrevem a rodada original. O fechamento visual de Settings e as decisões que a substituem estão na [especificação vigente](../design.md). Medidas Android, execução real e limites expressamente pendentes continuam sem certificação. Aquisição global e Folder Catalog não são referências aprovadas.


## Correção conceitual vigente — Aquisição fora da IA pública

- O overflow global de Providers contém somente Repositórios e Atualizar repositórios. Aquisição foi removida sem substituição por outra categoria.
- A antiga tela Aquisição, incluindo rotas Debrid/P2P e disponibilidade, é **Exploração não aprovada / superseded before consolidation**. Seu HTML é preservado como evidência histórica, fora da composição visível, do milestone e de qualquer futura consolidação como superfície Approved. Os padrões de rota documentados abaixo são históricos, não regras públicas vigentes.
- Torrent, Debrid, P2P, HTTPS, metadados e leitura são capabilities combináveis, não tipos exclusivos de Provider nem modos globais selecionáveis nesta família.
- Provider Detail permanece o ponto de configuração declarado por cada Provider. Futuros Providers Debrid podem apresentar credenciais, endpoint e preferências reais próprios; nenhum serviço é hardcodado nesta rodada. Configuração de Direct P2P, quando declarada, pertence ao próprio Provider; MangaFire não integra um painel global de torrent/Debrid/P2P.
- O coordenador e a política de aquisição podem continuar internos ao runtime, sem controles públicos de Debrid only, P2P only ou Debrid then P2P. Uma eventual preferência de Providers/capabilities exige validação própria, sem painel fixo por transporte.
- **TBD — localização na IA:** consentimento explícito para P2P direto e exposição do endereço de rede; atividade P2P do Host; arquivos temporários gerenciados pelo Tsuzuki. São requisitos mantidos, não configuração atribuída silenciosamente a um Provider. Privacidade e segurança, Dados e armazenamento e uma superfície operacional de transferências são possibilidades futuras, sem escolha nesta rodada.
- Instalados preserva integralmente Desativado, Bloqueado e Inválido. Descobrir preserva atualização disponível, versão instalada mais recente, Revogado e Conflito de origem, incluindo os controles fail-closed existentes. Esses estados não dependem da antiga Aquisição.
- Nenhuma nova tela ou realocação de consentimento, atividade ou armazenamento foi criada. A microcorreção anterior de disponibilidade não recebe novo refinamento; o código histórico é mantido.
- design.md e Notion permanecem intactos. Esta correção não consolida Providers; a validação final continua.

## Escopo desta rodada

Direção geral aprovada pelo usuário, com refinamento local ainda sujeito a validação: Configurações com a taxonomia Providers / Collections em CONTEÚDO e Providers / Instalados. A Default Skin e todas as superfícies Approved anteriores são preservadas. Não houve consolidação em design.md ou atualização do Notion.

## Contrato conceitual

Provider é a unidade pública de extensibilidade. Pode combinar capabilities; origem e mecanismos internos não definem categorias públicas. Integrações e Add-ons deixam de ser destinos paralelos na raiz de Configurações. Tracking continua gerenciando vínculos de contas externas.

## Provisional

- Cards informativos com identidade, versão discreta, enablement, overflow, divisor, poucas capabilities resumidas e descrição curta.
- Dois chips principais e +N para capacidades adicionais; descrição curta, sem uma terceira linha enumerando operações ou capabilities.
- Seleção principal Instalados / Descobrir por chips flutuantes, sem underline; Repositórios e Aquisição ficam no overflow global, junto de Atualizar repositórios. A tab bar anterior é histórico substituído nesta proposta.
- Densidade com três cards completos e início do quarto; medidas, espaçamentos e tratamentos locais não são tokens globais Approved.
- Switch usa o padrão existente e muda somente o estado demonstrativo local. Não substitui status explícitos futuros de Bloqueado/Inválido.
- Impeccable auxiliou hierarquia, grid e consistência; suas heurísticas não foram promovidas a regras Approved.

## Fixtures e limites

- Kitsu, MangaFire, Nyaa e Direct P2P são os quatro Providers solicitados. Versão 1.0.0 é demonstrativa, não informa versões reais instaladas ou disponíveis.
- Kitsu usa o mark real local, de 192 × 192. Os demais usam símbolos funcionais neutros, não logotipos inventados. Esses símbolos são placeholders visuais, não identidades de marca definitivas.
- Todos começam habilitados. Descrições/capabilities seguem o briefing; não indicam inventário verificado do runtime.
- Direct P2P participa de aquisição autorizada; transporte privilegiado continua responsabilidade do Host.
- A imagem Nuvio não foi localizada entre os inputs acessíveis. A inspiração segue somente a anatomia e densidade descritas no briefing, sem alegar comparação visual com a imagem.
- Ações fora do escopo indicam destino futuro, sem criar as superfícies ou simular operações de lifecycle.

## TBD

- Provider Detail, Repositórios, aquisição e seus fluxos completos; integração real de Descobrir com refresh, instalação e atualização.
- Ações contextuais condicionais a lifecycle, update, rollback e remoção; nenhuma ação indisponível deve ser exposta por preenchimento.
- Estados Desativado, Bloqueado e Inválido com suas diferenças de interação.
- Identidades finais dos Providers sem mark local, versões reais e persistência do enablement.
- Insets e responsividade finais Android e comportamento de navegação no produto.

## Proteções

Nenhuma mudança visual nas referências anteriores. A alteração da raiz Settings se limita à taxonomia necessária. A direção geral foi aprovada; medidas e tratamentos do refinamento permanecem Provisional até validação explícita.


## Refinamento local — contrato de mudança v1

- Baseline: proposta Providers v1 em index.html, direção geral aprovada; TaskProfileVersion 2.2.0.
- Escopo autorizado: header de Providers e espaçamento/conteúdo dos cards. Entrada canônica e fonte editável permanecem index.html; produção HTML local.
- Header em três níveis: retorno; título com overflow na mesma linha; tabs com maior intervalo. Tabs e indicador preservados.
- Nome/versão, identidade/chips e chips/descrição recebem respiro local. Cards mantêm superfícies, switches, overflow e descrições do briefing.
- Linha inferior de operações removida de todos os cards, sem substituição por outra lista. +3 do Kitsu permanece.
- Conteúdo protegido: Settings corrigida, tema claro, navegação, fixtures, ações e todas as referências Approved anteriores; design.md e Notion intactos.
- Requisitos estáveis: providers-header, providers-card-content, providers-card-spacing, providers-scope-protection. Conclusão corresponde à escrita desses ajustes delimitados no HTML e deste registro.
- Intake/Execution: fonte e anchors acessíveis, CSS e markup delimitados, fontes e assets locais reutilizados, sem dependência externa nova. Alteração executada em modo simples; nenhuma inspeção posterior do HTML.
- Riscos/limites: medidas locais continuam Provisional; versões e enablement continuam demonstrativos. Nenhum novo comportamento de produto é introduzido.

## Providers root + Descobrir — proposta Provisional

- Duas frames iniciais: Instalados e Descobrir, com o mesmo header. Retorno, título com overflow e chips constituem três níveis compactos; o chip ativo tem preenchimento contrastante. Trocar o chip mantém o aparelho e a posição de rolagem de cada superfície.
- Instalados preserva seus cards e controles. Settings permanece como destino de retorno, sem redesenho.
- Descobrir usa cards mais enxutos: identidade, versão, capabilities neutras, descrição curta, origem discreta e status/ação separados. Não contém switch, permissões, idiomas ou linha de operações.
- Disponível usa Instalar; atualização disponível tem status explícito e Atualizar; instalado não repete uma ação. Incompatível, revogado, conflito de origem e versão instalada mais recente não oferecem instalação/downgrade.
- O modelo de estados acompanha ProviderRepositoryEntryStatus do runtime. Loading, vazio, falha de refresh e operações em andamento têm regiões próprias, sem frames adicionais ou controles de demonstração expostos.
- Instalar/Atualizar alteram somente fixtures locais, com feedback temporário e ações indisponíveis durante a operação. Não acionam repositórios ou lifecycle reais.

### Fixtures desta composição

- MangaFire 0.1.3 disponível; Nyaa 0.1.12 → 0.1.13 com atualização; Direct P2P 0.1.0 instalado; MangaDex 0.2.0 incompatível. São exemplos visuais, não fatos de publicação, compatibilidade ou inventário instalado.
- providers-tsuzuki é origem humana demonstrativa. Os cards representam entradas publicadas em repositório na fixture, sem promover Providers incorporados ao catálogo.
- Símbolos funcionais neutros reutilizados não são marcas inventadas. O mark local de Kitsu permanece somente em Instalados.

### Limites Provisional / TBD

- Provisional: header por chips, anatomia de Descobrir, hierarquia de status/ação, densidade, medidas e espaçamentos.
- TBD: identidade definitiva de Providers sem mark local; estados finais de loading/empty/refresh error; persistência, operações reais, confirmações e detalhes de lifecycle.
- Impeccable auxilia refinamento, sem definir capabilities ou promover heurísticas a Approved.
- design.md, Notion e todas as superfícies Approved permanecem intactos. Esta proposta não é consolidação do milestone.

### Ajuste local de respiro — contrato mínimo v1

- Baseline: Providers root + Descobrir v1 em index.html; TaskProfileVersion 2.2.0; fonte editável HTML local.
- Escopo autorizado: aumentar de 16 para 24 px o padding superior da área rolável de Providers, separando o header/chips do primeiro card em Instalados e Descobrir.
- Requisito providers-first-card-spacing: preservar título, chips, cards, navegação, Settings e comportamentos; medidas continuam Provisional. Entrada canônica index.html; documento afetado providers-tensions.md.
- Inputs e anchors acessíveis; alteração CSS delimitada, sem assets ou dependências novos. design.md e Notion preservados.


## Refinamento de ações em Instalados — Provisional

A direção dos cards foi aprovada pelo usuário; esta rodada preserva header, seleção por chips, tema claro, capabilities resumidas, descrições e enablement. A faixa de gestão e suas medidas ainda são Provisional, sem consolidação de Providers em design.md.

### Anatomia e ações condicionais

- Três zonas: identidade/versão/switch; capabilities/descrição; faixa inferior discreta de ações. Não reservar slots vazios.
- Configurar é entrada explícita quando há configuração real, conta ou controles pertinentes. Atualizar tem maior destaque quando existe atualização conhecida. Desinstalar é secundária/destrutiva, sem lixeira dominante.
- Instalados permite manutenção sem depender de Descobrir. Descobrir continua catálogo/instalação/atualização, sem Configurar ou Desinstalar.
- Overflow fica restrito a ações raras realmente disponíveis. As fixtures não têm rollback ou operação rara confirmada; não renderizam overflow vazio.
- Kitsu: Configurar; sem remoção ou atualização. MangaFire: Desinstalar; configuração não confirmada, portanto omitida. Nyaa: Atualizar e Desinstalar. Direct P2P: Desinstalar; sem configuração inventada.
- Nyaa demonstra 0.1.12 → 0.1.13; atualização local compartilha estado entre as duas listas. Outras versões são fixtures, não inventário real.

### Limites Provisional / TBD

- Desinstalar é Provisional: a superfície Android atual não expõe uninstall por Provider. O botão indica a futura confirmação; não remove nada nem desenha a confirmação nesta rodada.
- Provider Detail/configuração e confirmação destrutiva permanecem fora do escopo. Acesso é indicado por feedback local, sem inventar telas ou operações de runtime.
- Medidas, densidade resultante e hierarquia fina da faixa de ações são Provisional. Integração real de update/uninstall, persistência e confirmações permanecem TBD.
- Impeccable auxilia hierarquia e consistência, sem definir capacidades. design.md, Notion e superfícies Approved são preservados.


## Provider Detail — Kitsu / Nyaa — Provisional

Duas frames da mesma família em tema claro. Raiz, Instalados, Descobrir e action model estão aprovados pelo usuário; o detalhe desta rodada ainda aguarda aprovação. Não há duas UIs públicas por origem.

### Evidências e fontes

- Kitsu: DefaultIntegrationManifests.kt declara Search, Discovery, Metadata Basic, Artwork, Editorial, Ratings, Tracking e User Lists. DefaultIntegrationRegistry.kt mapeia o descriptor com permissões vazias, settings vazios e contentLanguages vazios.
- Nyaa: https://raw.githubusercontent.com/jssantogit/providers-tsuzuki/main/providers/nyaa/manifest.json consultado em 2026-10-09. Versão 0.1.14; somente torrent.search@1; rede https://nyaa.si; localNetwork false; armazenamento desabilitado; secrets, contentLanguages e settings vazios. Os identificadores ficam apenas nesta documentação.
- Mark real de Kitsu reutilizado (192 × 192). Nyaa conserva símbolo funcional outline neutro, sem representar um logo oficial.

### Anatomia proposta

- Retorno ← Providers; identidade compacta com ícone, nome, versão, descrição e switch. Sem bottom navigation.
- Capacidades em grupo contínuo, títulos humanos e subtítulos curtos. Kitsu agrupa Catálogo, Metadados, Ratings, Tracking e Lists. Nyaa tem apenas Torrent.
- Origem e versão seguem a mesma anatomia: Incluído no Tsuzuki para Kitsu; providers-tsuzuki para Nyaa. Provenance não cria uma taxonomia paralela.
- Nyaa mostra Permissões → Rede → nyaa.si. Permissões sem declaração aplicável, configuração e idiomas são omitidos. Ausência não gera mensagens ou placeholders.
- Tracking/Lists são informativos neste detalhe; vínculo e identidade da conta continuam em Configurações → Tracking → Kitsu. Não há login, conexão, desconexão ou estado de autenticação.
- Retorno preserva posição da lista. Enablement é local e compartilhado entre lista e detalhe. Entradas existentes de Kitsu/Nyaa passam a abrir o detalhe dentro da mesma moldura.

### Fixtures e limites

- Kitsu 1.0.0 permanece demonstrativo. Nyaa Detail usa 0.1.14 como instalação demonstrativa sem update; a lista histórica preserva a fixture aprovada de atualização 0.1.12 → 0.1.13. São cenários de composição distintos, não inventário real.
- Não há controles declarativos ou idioma real nos dois descriptors. A ação Configurar legada de Kitsu conduz ao detalhe; esta rodada não inventa configuração para preencher esse destino.
- Gerenciamento permanece em Instalados nesta exploração; o detalhe não acrescenta botões destrutivos, update ou rollback.
- Provisional: anatomia do detalhe, densidade, agrupamento humano, medidas, spacing e linguagem fina de permissões. Impeccable auxiliou refinamento sem criar capabilities ou regras Approved.
- TBD: lifecycle Blocked/Invalid e respectivas restrições, configuração texto/boolean/select/secret com Provider real, idiomas declarados, gerenciamento no detalhe, aplicação/persistência Android e integração ao inventário real.
- design.md, Notion, assets e composições Approved preservados; nenhuma consolidação do milestone nesta rodada.


## Provider configurável — Atlas Manga — Provisional

Atlas Manga é exclusivamente fixture sintético de design. Não é Provider publicado, não integra o inventário real e não deve ser registrado em design.md ou Notion como entidade do produto. Nome, versão 1.4.0, origem providers-tsuzuki, endpoint example.org e valores são demonstrativos.

- Fonte contratual: ProviderModel.kt, ProviderSettingType (STRING, BOOLEAN, SELECT, SECRET), ProviderSettingDescriptor.required/options e ProviderDescriptor.contentLanguages. Idiomas são dimensão própria, não settings.
- Duas frames: Detail compacto e Configurar dedicado. Detail conserva capacidades humanas, permissão Rede e origem/versão; entradas Configuração e Idiomas são grupos separados.
- Geral: Servidor obrigatório; Qualidade preferida opcional; HTTPS estrito opcional; API key obrigatória. Os estados iniciais não são defaults do runtime.
- API key inicia apenas com indicador mascarado de credencial existente. Substituir habilita campo password; nenhum valor real é fornecido. Não há revelação, criptografia alegada ou persistência de segredo no navegador. Ao salvar, o texto de substituição é descartado; a demonstração conserva somente presença de credencial.
- Idiomas: pt-BR/en ativos, es inativo. Português, English +1 no Detail resume os três idiomas declarados, não três habilitados. A tela Configurar esclarece que estes controlam conteúdo do Provider.
- Salvar alterações inicialmente desativado; edições habilitam o CTA. Aplicação somente em memória desta demonstração, com retorno preservando rolagem. Nenhuma operação remota ou inventário real é alterado.
- Provisional: composição, medidas, espaçamento, required/optional, entrada de substituição de secret, idiomas e CTA. Impeccable auxilia hierarquia e consistência, sem inventar capacidades.
- TBD: persistência Android, tratamento de teclado, integração de settings/idiomas com runtime e eventual revelação temporária de secret. Não há autenticação de conta, Tracking, novas permissões ou dependências condicionais.
- design.md, Notion e superfícies anteriores preservados.


## Providers → Repositórios — Provisional

### Fontes e escopo
- Índice consultado: https://raw.githubusercontent.com/jssantogit/providers-tsuzuki/main/dist/index.json. Envelope apresenta keyId tsuzuki-provider-root-2026-02. Metadata fornecida pelo usuário: displayName Tsuzuki Providers, repositoryId app.tsuzuki.providers, sequence 19. repositoryId e sequence não aparecem nas telas.
- O índice consultado não fornece a chave pública necessária para calcular seu fingerprint. A composição utiliza SHA-256 de uma string de fixture sintética, rotulado Valor demonstrativo. Não é fingerprint da chave oficial, não autentica a fonte e não deve ser utilizado para decisões reais de confiança.
- Lista começa com uma fonte já confiável e summary Atualizado demonstrativos. Adicionar e Confirmar ilustram o onboarding dessa mesma fonte em outro cenário, não um inventário compartilhado contraditório. Não há refresh, validação de assinatura, confiança ou persistência reais.

### Padrões propostos
- Três frames em tema claro: lista de fontes confiáveis; campo único URL do índice; confirmação explícita da chave apresentada. Navegação profunda, sem bottom navigation.
- Lista mantém identidade, descrição curta e saúde de refresh; URL completa e informações da chave ficam fora da raiz. Overflow contextual mantém Atualizar, Ver detalhes e Remover sem desenhar seus destinos ou confirmações.
- URL não estabelece confiança. Continuar apenas leva à identidade/chave apresentada. Confiar e adicionar é a única ação que registra o aceite em memória; Cancelar/Voltar não adicionam a fonte.
- Confirmar distingue Identidade apresentada de fonte confiável, sem selo Verificado. Fingerprint completo em 32 bytes agrupados tem prioridade sobre ID da chave. Origem se mostra por domínio e caminho legíveis.
- Provisional: agrupamento visual, layout do fingerprint, medidas, espaçamento, ações persistentes, apresentação de origem e status de refresh.
- TBD: verificação e persistência Android, deduplicação, empty/loading/error finais, mudança de chave, replay/downgrade, revogação, conflito de origem e operações de gerenciamento. Nenhum desses estados é criado nesta rodada.
- Segurança preservada como requisito: mudança inesperada de chave exige decisão explícita; replay/downgrade, revogação e conflitos falham fechados. O protótipo não cria caminhos de bypass nem apresenta verificações criptográficas simuladas como reais.
- design.md, Notion e composições anteriores preservados.


## Repositórios: menu contextual e Aquisição do Host — histórico

### Escopo e aprovação
- Família de Repositórios aprovada pelo usuário; somente o overflow anterior é substituído. O menu flutuante ancorado não altera a altura do card. Adicionar, confirmação, fingerprint e fluxo de confiança permanecem intactos.
- A antiga superfície Aquisição é exploração não aprovada / superseded before consolidation. A política do Host continua interna; Nyaa continua responsável por descoberta torrent.
- Novos agrupamentos, menu, medidas e tratamentos visuais permanecem Provisional. Não há consolidação em design.md ou Notion.

### Referências e fixtures
- SettingsTsuzukiProvidersScreen.kt confirma as três rotas, restrição por suporte P2P nativo e consentimento independente. ProviderTorrentPreferences.kt mantém consentimento desativado por padrão no produto.
- Nesta exploração, Debrid e P2P aparecem disponíveis e consentimento já ativado explicitamente. Esses estados são fixtures, não detecção real do dispositivo nem novo default do produto.
- Atividade inicial vazia. Seleção, consentimento, parar e limpar alteram apenas estado local demonstrativo. Nenhuma aquisição, conexão, limpeza de arquivos ou persistência real ocorre.

### Padrões Provisional
- Duas frames em tema claro: Repositórios com menu aberto; Aquisição com grupos Rota de aquisição, Disponibilidade, P2P direto, Atividade e Armazenamento.
- Overflow ancorado: Atualizar, Ver detalhes, Remover; fechamento externo/Escape e retorno de foco. Remover discreto, sem confirmação ou remoção real nesta rodada.
- Política por radio rows; consentimento independente. Escolher uma rota nunca ativa P2P silenciosamente.
- Ausência de suporte nativo desabilita Somente P2P e consentimento, com explicação humana. Disponibilidade não é configuração de Providers.
- Parar só aparece quando há atividade. Limpar encerra trabalhos locais antes da limpeza demonstrativa. Feedback operacional não é sinal de operação Android real.
- TBD: disponibilidade e persistência Android, atividade real, falhas operacionais e confirmações de gerenciamento. Nenhuma superfície adicional criada.


## Validação final: lifecycle e catálogo excepcionais
- Repositórios, incluindo menu contextual flutuante, permanece aprovado. A aprovação anterior da superfície Aquisição foi retirada antes da consolidação; ela permanece apenas como exploração histórica. Nenhuma consolidação em design.md ou Notion nesta rodada.
- Microcorreção única de Aquisição: Disponibilidade → P2P direto informa Provider e dispositivo disponíveis. A fixture possui Provider P2P habilitado e suporte do Host; suporte nativo isolado não comprova disponibilidade da rota.
- Três frames: Aquisição; Instalados com MangaFire desativado, Nyaa bloqueado e Example Provider inválido; Descobrir com MangaFire atualizável, Nyaa com versão instalada mais recente, Direct P2P revogado e MangaDex em conflito de origem.
- Esses lifecycle/catalog statuses e versões são cenários demonstrativos, não constatações sobre os Providers ou repositórios reais. Example Provider é fixture sintético; sua capability Leitura é declarada somente para exercitar a anatomia e não deve se tornar entidade do produto.
- Desativado é escolha normal do usuário, sem alerta. Bloqueado e Inválido apresentam status separado das capabilities e controle de enablement indisponível. Os handlers e o detalhe alcançável também impedem ativação; Configurar não é oferecido como solução fictícia.
- Atualização normal tem ação. Versão instalada mais recente não oferece downgrade; revogação não oferece instalação/atualização; conflito não oferece instalação/takeover. Os motivos permanecem humanos, sem enums, IDs ou diagnóstico bruto.
- As capabilities permanecem neutras. Cor de atenção é restrita ao status; nenhum card vira uma superfície vermelha dominante.
- Provisional: hierarquia dos estados excepcionais, posição de copy e tratamento exato de controles indisponíveis. Preservado o padrão anterior de Incompatível, sem repetir a frame.
- TBD: diagnóstico detalhado, recuperação segura, persistência e operações Android reais. Atualização, switches e feedback deste protótipo são locais. Nenhum novo fluxo de recuperação ou confirmação é criado.
