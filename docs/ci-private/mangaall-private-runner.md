# MangaALL — laboratório de CI Android em repositório privado (não integrado)

Estado: **proposta executável, ainda NÃO provisionada**. Não habilita runner próprio no fork público Tsuzuki, não dispara consultas e não substitui o gate oficial. Ver [workflow de exemplo](mangaall-fire-private.workflow.yml).

## Por que não executar em `jssantogit/tsuzuki`?

O repositório Tsuzuki é um fork **público**. Um runner auto-hospedado associado a ele pode ser exposto a código não confiável em workflows; a documentação de segurança do GitHub desaconselha essa configuração. O runner privado aqui proposto **não deve ser registrado no fork público nem em uma organização que o disponibilize para ele**.

Referências oficiais:

- [GitHub: segurança de Actions e runners próprios](https://docs.github.com/en/actions/reference/security/secure-use)
- [GitHub: runner labels](https://docs.github.com/en/actions/how-tos/manage-runners/self-hosted-runners/use-in-a-workflow)
- [GitHub: dispatch requer workflow na branch padrão](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow)
- [GitHub: faixas de IP dos runners hospedados](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)

## Montagem isolada e execução sob demanda

1. Crie **outro repositório privado, independente (não fork)** para os workflows. Nada foi criado ou vinculado automaticamente por esta PR. Não conceda acesso a colaboradores ou workflows de PR de terceiros.
2. Copie o conteúdo de `docs/ci-private/mangaall-fire-private.workflow.yml` para `.github/workflows/mangaall-fire-private.yml` na **branch padrão do repositório privado**. O workflow possui apenas `workflow_dispatch` e falha fechado se o repositório não for privado ou não houver confirmação explícita.
3. Prepare uma máquina Linux x86_64 **descartável**, exclusiva para a execução, com virtualização KVM acessível ao usuário do runner (`/dev/kvm` leitura e escrita), ferramentas Android SDK/emulator, Git, Python 3, Bash e rede cuja utilização seja legítima. Não use o Galaxy A54, a máquina pessoal principal, nem um executor com credenciais ou dados sensíveis. Configure um runner **efêmero** (`--ephemeral`) registrado apenas no repositório privado, com a label adicional `mangaall-ephemeral-android`. Use o fluxo oficial de registro do GitHub; **não copie tokens de registro para commits, logs ou esta documentação**. Destrua a VM e seu armazenamento ao finalizar.
4. Na aba Actions do repositório **privado**, execute manualmente e informe `I_ACCEPT_MANGAFIRE_CI_WEBVIEW_LIVE` no campo de confirmação. É necessário que o runner efêmero esteja conectado; caso contrário o job ficará na fila, sem executar.
5. Compare o artefato **somente de veredito tipado** com o gate live GitHub-hospedado [#45275225376](https://github.com/jssantogit/tsuzuki/actions/runs/45275225376). Esse teste anterior registrou `HTTP403 / CF_SERVER_HTML` em Punpun, Koe no Katachi e Akame ga Kill. Se a rede privada também for rejeitada, encerre a medição; não faça rotações de IP, reuso de clearance, evasão de desafios ou tentativas repetidas em massa.

### Garantias do template

- Apenas dois repositórios públicos **fixados por SHA** são clonados, sem código de PR ou ref mutável: o Tsuzuki experimental em `14af4ef49615336c66a1912ec183f3cc235c02ca` e `providers-tsuzuki` em `195502f981bd776a68feebde3efc6d20f55cca65`.
- A assinatura P-256 e o hash SHA-256 do code10 publicado são verificados. A fonte assinada `app.tsuzuki.mangaall` permanece intocada.
- O Host e os APKs são compilados no próprio runner; depois rodam o **mesmo** `.github/scripts/run_mangaall_ci_webview_android.sh` no emulador Android API 35 e com `MANGAALL_CI_GATE_MODE=fire`.
- Escopo fechado às três obras originais, às três fontes simultâneas e à validação MangaFire de API real, capítulo 1 e cursor de capítulos. Não assina ou publica code11; `unified=NOT_PROVEN` e `canonical=NOT_TESTED` continuam fora do PASS.
- Não compartilha cookies entre Chromium e OkHttp, não exporta tokens/HTML/URLs assinadas, não injeta solver, não utiliza proxy rotativo e não trata HTML de challenge como API JSON.
- `permissions: contents: read`, `persist-credentials: false`, uma execução por vez, `retention-days: 1` para o veredito sanitizado; nenhum gatilho por push/PR no repositório privado.

### Limites

A existência de um runner próprio **não prova** que MangaFire aceitará a rede. Somente a origem pode autorizar tráfego bloqueado por suas políticas. Se houver permissão do operador do serviço para um IP estável, isso deve ser tratado com o próprio operador. Não apresente o resultado como `READY` sem JSON assinado consumido por code10 e capítulos completos. Não configure esse runner em `jssantogit/tsuzuki`, que permanece público.

**Nenhuma etapa acima foi executada em infraestrutura do usuário.** Este documento e seu template são artefatos inertes, preparados para uma implementação em repositório privado mediante provisionamento separado.
