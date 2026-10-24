# Privacidade e segurança — validação visual

> Registro de elaboração preservado. Os estados Provisional/TBD abaixo descrevem a rodada original. O fechamento visual de Settings e as decisões que a substituem estão na [especificação vigente](../design.md). Medidas Android, execução real e limites expressamente pendentes continuam sem certificação. Aquisição global e Folder Catalog não são referências aprovadas.


## Escopo

Continuação da Default Skin clara. Esta rodada não consolida design.md nem Notion. Sete estados da mesma superfície: padrão, bloqueio habilitado, Bloquear após, Tela segura, confirmação P2P, P2P habilitado e telemetria incluída.

## Contrato e fontes

- SettingsSecurityScreen.kt: autenticação do dispositivo, dependência de Bloquear após e telemetria condicional.
- SecurityPreferences.kt: bloqueio e notificações desligados; Tela segura em Modo anônimo; intervalo inicial 0.
- SecureActivityDelegate.kt: 0 exige desbloqueio ao retornar; -1 não exige por retorno; valores positivos são minutos. Labels pt-rBR: Sempre, 1 minuto, 2 minutos, 5 minutos, 10 minutos, Nunca. Não criar opções adicionais.
- SecureScreenMode: Sempre, Modo anônimo, Nunca. O modo anônimo aqui é uma opção existente de Tela segura, não uma nova função ou switch de privacidade.
- PrivacyPreferences.kt: relatórios de falhas e dados de uso ligados por padrão; a seção só existe quando telemetryIncluded.
- Decisão explícita do usuário: consentimento global de P2P em Configurações → Privacidade e segurança → Privacidade de rede. Opt-in, confirmação antes de habilitar, desligamento imediato. Não expor o coordenador de aquisição.

## Fixtures e limites

As seis primeiras frames representam build sem telemetria; a sétima inclui o recurso. Autenticador compatível nas frames; componente aceita ausência de suporte e omite bloqueio e intervalo. Bloquear após permanece visível e desabilitado com bloqueio desligado, preservando sua seleção. Frame de bloqueio habilitado usa 5 minutos como exemplo, não default do produto.

Autenticação do dispositivo e alterações em preferências são demonstrativas. O HTML não executa biometria, proteção contra captura, P2P ou envio de telemetria. A confirmação de P2P mantém o switch desligado até Permitir; fechamento, Cancelar e Escape não consentem.

## Design system tensions

| Regra existente | Necessidade | Proposta | Alcance / status |
|---|---|---|---|
| Grupos compartilhados e navegação profunda | Segurança, rede e telemetria têm condições distintas | Omitir grupos indisponíveis; preservar dependências de preferências existentes | Esta família, Provisional para composição |
| Preferências dependentes ficam visíveis | Intervalo não funciona sem bloqueio | Row disabled com valor preservado e explicação curta | Provisional para styling |
| Confirmação explícita de ações | Consentimento de exposição de IP | Sheet com Cancelar / Permitir; sem consentimento por simples toque | Decisão de produto explícita; visual Provisional |
| Selection sheets | Modos e intervalos reais | Seleção única com valor selecionado, fechamento e foco restaurado | Medidas e anatomia Provisional |
| Layout de Settings claro | Texto de consentimento maior | Texto em bloco com crescimento natural, sem cortes | Espaçamento local Provisional |

## TBD

Insets Android finais, teclado/foco, integração com autenticador, falha/cancelamento da autenticação, persistência, lifecycle e eventuais condições adicionais de suporte P2P. Não inventar interfaces para esses estados nesta rodada. Atividade P2P continua em família operacional futura; arquivos temporários permanecem em Dados e armazenamento. Diagnóstico, Avançado e Sobre são próximos trabalhos separados.

## Critérios de escrita

Referência ps-01 a ps-10 do plano. Fonte e cores existentes; título dominante; rolagem entre barras opacas; sheets respeitam safe area e suportam teclado, Escape, toque externo e reduced motion. Nenhum teste, render, preview ou aceitação após a escrita do HTML.
