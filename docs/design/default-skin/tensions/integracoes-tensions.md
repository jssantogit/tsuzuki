# Integrações — tensões e limites

> Registro de elaboração preservado. Os estados Provisional/TBD abaixo descrevem a rodada original. O fechamento visual de Settings e as decisões que a substituem estão na [especificação vigente](../design.md). Medidas Android, execução real e limites expressamente pendentes continuam sem certificação. Aquisição global e Folder Catalog não são referências aprovadas.


Este registro acompanha a composição de Configurações → Integrações. As dez telas aprovadas e documentadas em `design.md` permanecem preservadas. A raiz de Integrações foi aprovada como índice de serviços com summary status. Esta rodada acrescenta a tela detalhada MyAnimeList; não define autenticação real, persistência ou a reforma de Providers.

## Inventário e agrupamento

O índice reúne dez entidades em três grupos:

- **Geral:** Tsuzuki.
- **Metadados e serviços:** Kitsu, MyAnimeList, MangaUpdates, Bangumi, Shikimori e Hikka.
- **Servidores pessoais:** Komga, Kavita e Suwayomi.

O rastreamento pertence a cada integração. Tsuzuki é a integração first-party. A reforma interna de providers não define a taxonomia dos grupos da interface; não separar entidades por capability, implementação, Manga ou Torrent.

## Tensões — regra, problema e solução

| Tensão | Regra / problema | Decisão registrada na composição | Alcance e status |
|---|---|---|---|
| Taxonomia de grupos | Os dez itens têm papéis diferentes, mas a interface precisa de um índice previsível | Agrupar Tsuzuki em Geral, seis serviços em Metadados e serviços e três servidores em Servidores pessoais | Composição desta tela **Provisional**; inventário e nomes vêm do pedido. Não inferir novos grupos a partir da arquitetura interna |
| Linha raiz | A pessoa precisa encontrar e abrir uma integração sem expor controles demais no índice | Cada linha mostra ícone genérico coerente, nome, estado e chevron | Hierarquia local **Provisional**; não exibir capabilities, credenciais, alternâncias ou detalhes dos serviços na raiz |
| Summary status | Ativação, conexão e configuração são eixos independentes; não existe um enum universal que os represente | A raiz mostra um único summary status curto, escolhido pelo estado mais útil; para o exemplo conectado de MyAnimeList, usa apenas “Conectada”, sem concatenar eixos | Composição da raiz aprovada; valor exibido depende de evidência real por integração. Dados da fixture nunca representam usuário |
| Capacidades não suportadas | Exibir capabilities indisponíveis faria parecer que há uma escolha ou configuração possível | Ocultar capabilities não suportadas | Regra para esta composição; inventário final por serviço e tratamento de capability **TBD** |
| Conexão consolidada | Não há decisão de produto que determine se várias conexões terão um estado agregado | Não sintetizar estado no grupo nem na raiz de Configurações | Consolidação de conexão **TBD** |
| Ícones e marcas | Marcas reais não foram aprovadas e ícones antigos podem sugerir uma identidade indevida | Reutilizar ícones genéricos compatíveis com a família visual; não fabricar logos. Não usar `ic_mihon` como marca de Tsuzuki | Ícones genéricos locais **Provisional**; identidade visual oficial por serviço **TBD** |
| Navegação e retorno | Integrações abre uma área especializada a partir da raiz de Configurações | Página inicial com Voltar acima do título grande e sem navegação inferior; voltar restaura foco e rolagem da linha Integrações | Relação local **Provisional**; destinos de detalhe fora do escopo usam o feedback demonstrativo existente. Destinos completos **TBD** |
| Tema e tokens | A composição deve pertencer à família das telas aprovadas sem transformar valores locais em sistema global | Reutilizar cores, superfícies, divisores, tipografia e medidas CSS já presentes, incluindo os papéis dark/light | Valores locais existentes continuam com o status de `design.md`; tokens globais, dp/sp, responsividade e acessibilidade **TBD** |

## Fixtures de estado e interação

Os rótulos de estado apresentados no protótipo são exemplos visuais para distinguir ativação, conexão e configuração. Não representam contas, credenciais, preferências, disponibilidade real de servidores ou estado persistido. A interação de cada linha fora da página inicial usa o feedback demonstrativo existente; esse feedback explica o limite do protótipo e não é copy final de produto.

Ativar uma integração, conectar uma conta/servidor e configurar uma integração são ações e estados independentes. A composição não implica que uma ação execute as outras. Também não estabelece uma regra de precedência entre esses estados nem como uma conexão consolidada deve ser calculada.

## Limites

Esta entrega compõe somente o índice inicial e o caminho de retorno para Configurações. Detalhes de Kitsu, MyAnimeList, MangaUpdates, Bangumi, Shikimori, Hikka, Komga, Kavita, Suwayomi e Tsuzuki permanecem fora do escopo. Credenciais, autorização, endpoints, capabilities, sincronização, erros, persistência, descoberta e manutenção de servidores permanecem **TBD**. Nenhum estado demonstrativo deve ser tratado como dado de usuário ou evidência de comportamento implementado.

## MyAnimeList — tensões da tela detalhada

| Tensão | Regra atual | Problema encontrado | Solução visual proposta | Alcance e status |
|---|---|---|---|---|
| Estados independentes | A raiz usa summary status; habilitação, conta e configuração não formam um enum único | A tela detalhada precisa expor eixos simultâneos sem confundir estado da integração com autenticação | Grupo Status separa switch de habilitação do Client ID e de seu estado de salvamento; Conta e tracking mostra a conexão em seção própria | MAL nesta composição **Provisional**; não generalizar a anatomia de status sem outras integrações |
| Client ID obrigatório | A integração exige Client ID antes de habilitar; alterar o ID encerra a sessão e removê-lo desliga a integração | Configuração obrigatória pode bloquear a ação de habilitar | Campo rotulado, ajuda concisa e ação secundária Salvar; habilitação disabled até haver ID salvo. Remoção do ID desliga a integração | Dependência observada no produto; aparência de campo, ajuda e botão **Provisional** |
| Recursos dependentes | Os sete recursos pertencem ao manifesto MAL: Search, Discovery, Metadata Basic, Artwork, Editorial, Staff e Ratings | Não ocultar ou perder escolhas quando a integração está globalmente desligada | Toggles permanecem visíveis, preservam os valores e ficam disabled quando a habilitação está desligada | Conteúdo MAL conforme fonte atual; opacidade/contraste e tratamento exato de disabled **Provisional** |
| Conta e tracking | User Lists e tracking são recursos da conta, não capability toggles comuns; comportamentos globais de tracking pertencem à integração Tsuzuki | Uma ação de conexão não deve parecer capability nem duplicar configuração global | Grupo próprio apresenta conectado/desconectado e uma ação contextual Conectar/Desconectar; conectar requer Client ID salvo, não a habilitação global | Organização visual MAL **Provisional**; fluxo e integração reais de conta permanecem **TBD** |
| Ação de conexão demonstrativa | O protótipo não executa OAuth, rede nem persistência | Feedback pode ser confundido com conexão real | A UI não apresenta copy de simulação nem declara validação remota. A documentação informa que as transições são locais e não executam OAuth; nenhum nome de usuário fictício | Apenas protótipo; autenticação real, erros, tokens e persistência **TBD** |
| Marcas de integração | Ícones genéricos atuais são placeholders do protótipo | Ícones genéricos reduzem reconhecimento de serviços identificáveis | Preservar placeholders agora; integração identificável pode usar seu mark/logo oficial na implementação final | Placeholder atual **Provisional**; assets e uso de marcas por serviço **TBD** |
| Resumo de retorno | A raiz preserva nome, summary status e chevron | Ao voltar do detalhe, o estado demonstrativo deve continuar coerente sem virar badge ou pill | A row MyAnimeList apresenta um summary status único e conciso (no exemplo conectado, “Conectada”); o detalhe expõe configuração, disponibilidade, habilitação e conta separadamente; retorno restaura foco e rolagem | Estado demonstrativo **Provisional**; regra de resumo baseada em dados reais **TBD** |

### Escopo funcional preservado

A tela demonstra somente as capabilities MAL confirmadas: Search, Discovery, Metadata Basic, Artwork, Editorial, Staff e Ratings. Tracking e User Lists não aparecem como toggles. As escolhas de capability iniciam ligadas, mas ficam disabled porque o estado inicial tem integração desligada e Client ID vazio. O estado da conta inicia desconectado. A conexão simulada exige Client ID salvo, mas não depende da habilitação global. Alterar Client ID salvo encerra a sessão simulada; remover o Client ID desliga a integração. Valores de capability são preservados ao desligar.

Conectar/Desconectar altera somente o estado local do protótipo; a interface não anuncia a simulação. Desconectar exige confirmação. Isto não define OAuth, mensagens reais de erro, persistência ou sincronização. A raiz não usa pills nem badges coloridos. Rótulos de estado são texto demonstrativo e não copy final de produto.
### Ajustes desta composição MyAnimeList

A composição congelada apresenta três eixos separadamente: configuração por Client ID, disponibilidade da integração e habilitação global. A configuração é aberta em uma folha inferior dedicada; o protótipo distingue um valor salvo localmente da validade remota, que não é verificada. Sem valor salvo, a habilitação fica indisponível. Remover o valor desliga a integração; mudar o valor encerra a conexão demonstrativa.

As sete capabilities são Search, Discovery, Metadata Basic, Artwork, Editorial, Staff e Ratings. Os toggles permanecem visíveis e preservam seus valores quando a habilitação global está desligada. Conta e tracking formam um eixo independente: conectar depende de Client ID salvo, não da habilitação de catálogo. Conectar/desconectar é uma simulação local explicitamente indicada, sem OAuth real, usuário fictício, toggle de Tracking ou toggle de User Lists.

| Tensão | Regra atual | Problema encontrado | Solução proposta | Alcance e status |
|---|---|---|---|---|
| Folha de configuração | Configurações obrigatórias não devem virar um formulário técnico exposto na seção principal | O Client ID precisa de uma edição clara, mas sua edição inline compete visualmente com os demais estados de Status | Uma row de configuração com resumo abre uma folha inferior com campo rotulado, ajuda curta e Salvar; disponibilidade e habilitação continuam rows/estados separados | Só MyAnimeList nesta composição; anatomia, foco e dimensões da folha **Provisional** |
| Habilitação e disponibilidade | Preferências dependentes não devem desaparecer; valores podem permanecer visíveis quando inativos | Client ID ausente bloqueia a habilitação, e integração desligada inativa as capabilities | Comunicar a sequência configuração → disponibilidade → habilitação; manter sete valores visíveis e controles desabilitados enquanto a integração estiver desligada | Relação de produto presente no escopo; aparência exata de disponibilidade/disabled **Provisional** |
| Conexão independente | Conta é distinta de capabilities de catálogo e pode existir sem a integração estar habilitada | Uma ação de autenticação poderia sugerir dependência incorreta da chave global | Conectar depende somente da configuração salva e é explicado como simulação local no protótipo; desconectar pede confirmação | Relação da composição; OAuth, validade remota, erros e persistência **TBD** |
| Metadata Basic | O manifesto identifica a capability configurável como Metadata Basic | O rótulo abreviado pode parecer paridade com uma capability genérica de metadata | Apresentar “Metadata Basic”, sem criar capability adicional | MyAnimeList apenas; nome conforme inventário atual |

O protótipo mantém estados locais e não executa OAuth nem valida remotamente o Client ID. A interface não expõe copy sobre simulação ou validade não verificada. A raiz apresenta um único summary status contextual; o detalhe mantém configuração, disponibilidade, habilitação e conta como eixos independentes, sem pills ou badges.
