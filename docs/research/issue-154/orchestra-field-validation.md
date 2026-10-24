# Relatório B — Orchestra 1.5.0 Field Validation

> Registro da investigação de 24/10/2026, publicado posteriormente a pedido do usuário. O conteúdo abaixo preserva o relatório entregue na conversa. Estados de Git, CI, commits, PRs e ausência de alterações referem-se ao encerramento da investigação, antes desta publicação documental. A publicação não aprova arquitetura, implementação ou release.

[Índice da pesquisa](README.md)

## 1. Candidata e ambiente

| Item | Evidência observada |
|---|---|
| PR | [Orchestra #78](https://github.com/jssantogit/Orchestra/pull/78), aberta e Draft |
| SHA fixado | `3666478f7495abce547013507a3366c1fa003212` |
| Branch remota consultada | `feat/orchestra-1.5.0`, apontando para o mesmo SHA |
| Correções posteriores na branch | Nenhuma HEAD posterior encontrada na consulta |
| Versão reportada | `1.5.0-rc.1` |
| CI exata | [#40003028555](https://github.com/jssantogit/Orchestra/actions/runs/40003028555), success |
| Jobs da CI | Orchestra 1.x Verification, Portable Ubuntu e Portable Windows: success |
| Codex CLI usado | `0.159.2` |
| Servidor compartilhado preexistente | Doctor identificou `0.164.0` |
| Plataforma | Windows, PowerShell, Git e Node disponíveis |
| Source checkout isolado | `C:\Users\joaop\AppData\Roaming\Projects\Orchestra-issue154-rc` |
| Runtime instalado somente em | `Tsuzuki-issue154-research` |

A versão RC não foi interpretada como release estável publicada. A instalação oficial 1.4.3 não foi substituída.

## 2. Instalação e Doctor

Foram executados:

- criação de worktree Tsuzuki detached no `main` confirmado;
- criação de checkout Orchestra detached no SHA autorizado;
- instalação pelo `orchestra-codex-project.mjs`;
- `version --json`;
- `doctor --json`;
- `orchestra-worktree.mjs doctor`.

Resultados:

```text
installed: true
managed: true
runtime: CODEX
version: 1.5.0-rc.1
metadataCurrent: true
drifted: false
status: HEALTHY
hookActivation.status: UNVERIFIED
hookActivation.requiresUserReview: true
```

Hashes reportados:

| Elemento | SHA-256 |
|---|---|
| Manifest CODEX | `04589d0b3e6f13591d84ad0bf99ed13c05dd3c7b68f4193d719b1c6e8a64e799` |
| Runtime CODEX instalado/atual | `e6806943cc8f0671eee55ccab5320d277dae729f3ecad04e4c22206ffd4cb710` |
| Manifest delegation | `5daa98e8d8a567493958dd834eb6b1094d6d7a0f3be1bce0ecb72b20e632def8` |
| Runtime delegation | `0f2f04468127aa1047d7be90073315a8344cfc4fe0ce64a3d4df45feaa77c461` |

As correções de identidade e de linguagem do Doctor posteriores à F6D foram, portanto, observadas.

## 3. Sessões nativas, configuração e confiança

A primeira abertura interativa encontrou incompatibilidade de feature settings com o servidor compartilhado. Nenhum reinício desse servidor foi autorizado ou executado.

Uma segunda sessão foi aberta com:

```text
codex --no-daemon --no-alt-screen
      -s read-only -a on-request
      -C <worktree>
```

`--no-daemon` foi usado para isolar a execução do servidor incompatível, não para contornar confiança.

O primeiro cliente exibiu permissões herdadas como “YOLO mode”; nenhum prompt de trabalho ou operação de modelo foi enviado por ele. O segundo foi iniciado explicitamente em modo somente leitura.

A configuração efetiva reportou o modelo `gpt-6-sol`, coerente com o arquivo instalado. Isso é evidência de configuração efetiva, mas **não prova isoladamente a decisão de confiança específica dos hooks**.

Foram observadas entradas preexistentes de confiança no perfil, incluindo o diretório do usuário e o checkout original. Não foi criada nem alterada entrada global para a worktree temporária.

A documentação oficial diferencia confiança no projeto e revisão das definições de hooks. Hooks alterados podem exigir nova revisão. Nenhuma aprovação foi automatizada. [Hooks do Codex](https://learn.chatgpt.com/docs/hooks), [configuração avançada](https://learn.chatgpt.com/docs/config-file/config-advanced).

## 4. Descoberta dos hooks e Work Lease

O `/hooks` interativo mostrou:

| Evento | Installed | Active |
|---|---:|---:|
| PreToolUse | 2 | 2 |
| PermissionRequest | 1 | 1 |
| PostToolUse | 4 | 4 |
| PreCompact | 1 | 1 |
| PostCompact | 1 | 1 |
| SessionStart | 2 | 2 |
| SessionEnd | 1 | 1 |
| UserPromptSubmit | 1 | 1 |

Os dois `SessionStart` foram inspecionados individualmente:

- ambos vinham de **Plugin — get-fable@openai-curated-remote**;
- um era síncrono e outro assíncrono;
- ambos já apareciam Trusted;
- nenhum apontava para o `session-hook.mjs` local da Orchestra.

O runtime instalado contém definições de SessionStart, PreToolUse, PostToolUse e UserPromptSubmit. Sua presença no disco foi confirmada; sua descoberta pelo Codex não foi.

O comando oficial de inspeção retornou:

```text
status: NO_WORK_LEASE
generation: null
taskId: null
canAutoRecover: false
```

Não houve autoridade CONTROL nem geração factual a registrar.

**Classificação da causa: UNRESOLVED.** A evidência delimita ausência de descoberta/ativação local observada. Não estabelece defeito de protocolo, falha do comando do hook ou rejeição de sua resposta.

O Doctor do Codex também apontou uma configuração MCP opcional com variável ausente. Não há evidência suficiente para ligá-la à ausência dos hooks locais.

## 5. Trabalho real e limites do teste

A pesquisa prosseguiu com leitura nativa de código, Git, testes, documentação, GitHub, Notion e documentação externa.

Essas operações **não foram contabilizadas como trabalho protegido pela Orchestra**: não existia vínculo factual de lease.

Não foram:

- executados hooks manualmente para simular SessionStart;
- fabricados leases, IDs ou eventos;
- enviados trabalhos a workers adicionais;
- usados AGY, RSI ou Orchestra V2;
- realizados testes de escrita;
- provocados crashes;
- executadas recuperação ou terminalização forçada.

Consequentemente, PostToolUse de liberação de escrita, continuidade de autoridade, stale-session fencing e recovery não foram validados.

Os clientes interativos foram encerrados pelo fluxo normal de saída. O cliente ligado ao servidor compartilhado informou desconexão; nenhum trabalho de modelo havia sido despachado por esta validação. O servidor compartilhado preexistente não foi parado.

## 6. Preservação e encerramento

Verificações finais:

- Tsuzuki permaneceu no SHA/tree registrados.
- `git diff`, diff staged e verificação de arquivos modificados não apontaram alterações versionadas.
- Orchestra source permaneceu sem alterações versionadas.
- Os diretórios não rastreados preexistentes do checkout original foram preservados.
- A worktree de pesquisa contém o runtime temporário; `git status` mostra `.orchestra/` não rastreado.
- O diagnóstico classifica esse workspace como `DIRTY`; isso não foi descrito como alteração de código.
- Nenhum Work Lease foi observado.

O uninstall dry-run retornou:

```text
ok: true
dryRun: true
statePreserved: true
```

A desinstalação real não foi executada. Worktrees e runtime foram preservados para revisão. Não houve `git worktree remove`, `git clean`, `git reset` ou exclusão de diretórios.

### Metadados administrativos

A preparação criou os registros administrativos das duas worktrees e atualizou referências remotas pelos fetches.

O `info/exclude` do Tsuzuki é compartilhado e contém o bloco gerenciado da Orchestra. Seu timestamp observado é anterior à investigação, `2026-10-23 12:15:51 UTC`; não houve regravação observada nesta instalação.

Hash final:

```text
655ceea17ba111d665e0d0828a9346d1cd49c4d08cb067ff7bfffc1991067498
```

Isso preserva a distinção entre isolamento do checkout e metadados administrativos compartilhados.

## 7. Recomendações para a PR #78

1. **Reconhecer como validadas as correções de identidade e diagnóstico estático.**
2. **Manter a validação nativa como pendência de release.**
3. Acrescentar diagnóstico que identifique a origem efetivamente carregada das definições locais, quando a API do Codex permitir.
4. Distinguir explicitamente: arquivo instalado, configuração carregada, hook descoberto, hook aprovado, execução e protocolo aceito.
5. Documentar o caso observado de CLI e servidor compartilhado com versões/features diferentes, sem recomendar reinício automático ou bypass.
6. Repetir a etapa nativa depois de descoberta e confiança explicitamente verificadas pelo usuário.

Não há base para propor uma correção de protocolo específica nesta sessão. Não foi modificado o código da Orchestra.

```text
ORCHESTRA SHA PINNED: YES
VERSION 1.5.0-RC IDENTIFIED: PASS
TEMPORARY WORKTREE: PASS
INSTALLATION: PASS
STATIC DOCTOR: PASS
PROJECT TRUST: NOT_TESTED
HOOK DISCOVERY: FAIL
HOOK USER APPROVAL: NOT_TESTED
NATIVE SESSIONSTART: NOT_TESTED
WORK LEASE CONTROL: NOT_TESTED
REAL READ-ONLY WORK: NOT_TESTED
PROTECTED WRITES: NOT_TESTED
POST-TOOL WRITE RELEASE: NOT_TESTED
CHAT CONTINUATION: NOT_TESTED
STALE SESSION FENCING: NOT_TESTED
RECOVERY: NOT_TESTED
READ-ONLY DIAGNOSTIC: PASS
STATE PRESERVATION: PASS
UNINSTALL DRY-RUN: PASS
UNINSTALL: NOT_TESTED
NO UNNECESSARY DELEGATION: PASS
ORCHESTRA FIELD VALIDATION: PARTIAL
ORCHESTRA 1.5 RELEASE APPROVAL: PENDING
```
