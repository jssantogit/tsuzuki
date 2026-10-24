# Tracking — tensões de design

> Registro de elaboração preservado. Os estados Provisional/TBD abaixo descrevem a rodada original. O fechamento visual de Settings e as decisões que a substituem estão na [especificação vigente](../design.md). Medidas Android, execução real e limites expressamente pendentes continuam sem certificação. Aquisição global e Folder Catalog não são referências aprovadas.


## Estado e escopo

A família Tracking é referência oficial da Tsuzuki Default Skin por aprovação explícita: raiz, Kitsu desconectado, Kitsu conectado, fluxo transitório de conectar conta e confirmação de desconectar conta. Não houve nova rodada visual nem alteração de index.html ou assets nesta consolidação. Os dois estados do detalhe são a mesma arquitetura.

Fontes: aprovação atual do usuário, design.md, artefato index.html e Projects → Tsuzuki → Current State, UI & Design e Decisions. Android comprova a implementação existente, não medidas visuais ou aprovação de funções novas.

## Approved

### Raiz Tracking

Retorno ← Configurações, título dominante Tracking, único grupo CONTAS DE TRACKING. Branding à esquerda; nome, summary status à direita e chevron. Conectada, Desconectada e Configuração necessária resumem o estado útil, não um enum universal. Não concatenar configuração, habilitação e conta.

### Responsabilidades

Integrações/Providers mantém serviço, configuração técnica e capabilities. Tracking gerencia vínculo externo para acompanhamento. Conta é conta/sincronização do Tsuzuki; Perfil é identidade/dashboard pessoal. Tracking não replica capability toggles, Client ID nem enablement global. Configuração necessária pode indicar dependência técnica sem duplicar seu formulário; o encaminhamento final permanece TBD.

### Detalhe Kitsu

Retorno ← Tracking; branding e título Kitsu; sem bottom navigation. Desconectado apresenta status e Conectar conta. Conectado apresenta identidade disponível e status. Desconectar conta é separado das preferências comuns e não equivale a desabilitar a integração.

### Superfícies transitórias

Conectar pode iniciar bottom sheet com credenciais exigidas pelo serviço, sem campos permanentes no detalhe. Desconectar exige confirmação explícita que comunica preservação da configuração da integração. A autenticação e o encerramento do vínculo têm propósitos distintos.

## Tensions e limites

### Identidade e summary nas rows

- Regra atual: branding à esquerda e summary curto à direita estão Approved para Tracking.
- Problema: marks diferentes e summaries longos competem com nomes em larguras estreitas.
- Solução: preservar identidade real e summary em texto; a disposição observada usa nome e summary em blocos próprios, com summary à direita.
- Alcance: família Tracking, sem regra global de duas linhas.
- Classificação: relação Approved; normalização óptica, medidas, densidade e responsividade global Provisional.

### Credenciais em superfície transitória

- Regra atual: autenticação transitória com credenciais do serviço está Approved; campos e ações herdam a família Conta.
- Problema: teclado, foco e fechamento precisam respeitar a área do aparelho e restaurar contexto.
- Solução: folha dedicada, labels persistentes, fechamento e retorno ao acionador; credenciais não ocupam o overview.
- Alcance: fluxo de conexão Kitsu.
- Classificação: relação Approved; medidas exatas dos sheets e tratamento final de campos/focus/keyboard Provisional.

### Identidade ausente

- Regra atual: mostrar somente identidade disponível, sem inventar dados.
- Problema: um serviço pode não retornar nome/display name.
- Solução: admitir uma apresentação de identidade indisponível; Nome não disponível é estado possível/fixture desta composição, não texto obrigatório do produto.
- Alcance: detalhe de contas externas.
- Classificação: apresentação e copy finais Provisional.

### Ação de desconectar

- Regra atual: ação separada e confirmação explícita preservam distinção entre vínculo de conta e habilitação técnica.
- Problema: styling deve comunicar encerramento do vínculo sem equivaler ao CTA comum ou desabilitar o Provider.
- Solução: reutilizar linguagem da família Conta/MyAnimeList; confirmação informa que a configuração da integração permanece intacta.
- Alcance: Tracking → Kitsu.
- Classificação: relação Approved; tratamento exato da ação e suas medidas Provisional.

## Provisional

Medidas exatas dos sheets; campos/foco/teclado; styling de Desconectar conta; apresentação de identidade sem nome/display name; apresentação de erro/loading/session expired. Esses estados excepcionais não foram desenhados nesta rodada. Medidas, proporções e tokens locais não são regras globais; aprovação não é auditoria de contraste, acessibilidade ou suporte Android.

## TBD

- Múltiplas contas por serviço.
- Expiração/renovação de sessão e regras funcionais de estados excepcionais.
- Conflitos de tracking.
- Comportamento de servidores pessoais dentro de Tracking.
- Qualquer preferência adicional de sincronização não demonstrada.
- Autenticação/persistência reais, seleção prioritária de summaries e encaminhamento final à configuração em Integrações.
- Detalhes das outras contas externas não demonstrados.

## Implementação observada e fixtures

Kitsu.kt usa KitsuApi.login(username, password), consulta getCurrentUser(), salva currentUser.profile.name e credenciais do tracker. dto/KitsuUser.kt expõe profile.name. Logout limpa autenticação do tracker. Decisions preserva o par de credenciais de aplicação fornecido pelo Kitsu; esta superfície não inventa um formulário de configuração técnica.

Na raiz, Kitsu/Bangumi conectados, MyAnimeList com configuração necessária e os demais desconectados são fixtures. A frame Kitsu inicialmente conectada não recebe identidade real; Nome não disponível não define copy obrigatória. Credenciais digitadas e mudanças de vínculo no HTML são demonstração local, não prova de autenticação remota. Nenhuma opção de tracking habilitado, sync automática/bidirecional, importação de listas ou atualização de progresso foi adicionada.

As duas molduras são comparação solicitada de estados, não um componente global de produto. Impeccable auxiliou refinamento de hierarquia, spacing e consistência; suas heurísticas não foram promovidas a Approved sem evidência.

## Histórico

A raiz e o detalhe começaram como propostas. Esta consolidação promove somente relações aprovadas explicitamente; preserva lacunas e não faz ajustes visuais.

## Assets

SVGs locais em assets/tracking foram transcritos de brand_kitsu.xml, brand_myanimelist.xml, brand_mangaupdates.xml, brand_bangumi.xml, brand_shikimori.xml e brand_hikka.xml em app/src/main/res/drawable do checkout Tsuzuki. Paths, fills, gradientes e viewport 192 × 192 foram preservados; não há marks inventados nem hotlinks. Assets de implementação não equivalem a certificação de diretriz oficial de marca.
