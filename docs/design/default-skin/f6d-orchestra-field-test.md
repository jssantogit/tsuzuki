# Orchestra candidate 1.5.0 — F6D field test

Result: PARTIAL. Tsuzuki continues through the explicitly authorized native fallback. This is not release approval.

## Pinned candidate and environment

- Official source: https://github.com/jssantogit/Orchestra/pull/78 (Draft, left Draft).
- Pinned SHA: `4090da254168eb6df9ec7d1cc767cf48fb74292b`, detached source checkout `Orchestra-1.5-f6d-source`, separate from the Tsuzuki installation.
- Candidate exact-SHA CI: https://github.com/jssantogit/Orchestra/actions/runs/39982519267 — SUCCESS.
- Actual local native CLI: `codex-cli 0.159.2`, Windows / PowerShell. No CLI upgrade performed.
- Worktree: sibling `Tsuzuki-f6d`; branch `design/default-skin-f6d-refinement`; source baseline `cde46e656875d834628ca9898141600a2b0fd9a0`.

## Observations

The official `orchestra-worktree.mjs create` created the independent worktree and installed the candidate. `list`, worktree `doctor`, project `version --json` and project `doctor --json` were executed. Doctors reported `HEALTHY`, managed content clean, source/installed hashes matching.

**Version discrepancy:** candidate package and managed runtime manifests still declare `1.4.3`; `version --json` also returns `1.4.3`, despite the source being the pinned candidate. The installed Codex manifest hash was `8bc2590262fb89753913f78d5d787225458722e1534bc567c135f108407807ac`, runtime hash `7d9bf6d41ee0ffae0a9cbe38a3d0c0eb6af1687d51213fc0db7dbd5dad57ca11`. No version files or runtime code were patched to manufacture a 1.5 identity.

A fresh `codex exec -C <worktree>` session performed real read-only Git/directory/config inspection and stopped before product edits: no factual SessionStart acknowledgement or CONTROL authority was available. Official recovery `inspect` returned `NO_WORK_LEASE`. A native interactive CLI inspection through `/hooks` also did not present the worktree's local Orchestra hooks. Existing plugin hooks were listed. This establishes an **operational startup/trust discovery gap**, not its root cause: no protocol rejection, writer denial, or stale-generation test was fabricated. Codex's hook trust rules require explicit review of new definitions; see https://learn.chatgpt.com/docs/hooks . Further isolated investigation must establish why the local layer was absent before calling this an Orchestra protocol bug.

The CLI also reported an expired OAuth grant for its separate Notion MCP configuration. The controller's connected Notion tool was usable for initial project consultation. This authentication failure is not attributed to Orchestra. Interactive startup offered a shared-server restart; the isolated “run without daemon this time” option was selected, leaving other clients' server configuration untouched. The offered CLI upgrade was skipped.

No F6D code was written by those sessions. Both sessions ended normally before any runtime removal. Inspection again returned `NO_WORK_LEASE`. No leases, session identifiers or environment variables were invented, edited, copied or forcibly abandoned.

## Safe fallback and removal

From the external control session, official `uninstall --dry-run` succeeded. Its plan covered only manifest-owned Codex/delegation runtime paths and explicitly preserved state. The official `uninstall` then succeeded with `statePreserved: true` for both runtimes. Rollback backups, runtime-management history, worktree, branch and product files remained. No `git worktree remove`, downgrade, reset, clean, active runtime teardown or manual recovery occurred.

The actual F6D implementation, commits, review and CI proceeded using native Codex after removal, so O2/O3 cannot be marked Orchestra PASS. The requested fresh native CLI session and one substantive independent code reviewer were the only additional Codex sessions used. Neither was an artificial Orchestra routing demonstration. Orchestra required no worker/provider call, AGY, RSI, V2 service, resident daemon, heavy telemetry or benchmark.

Isolation: the primary Tsuzuki checkout remained at its original SHA/tree and original untracked directories; stable Orchestra-v1.4.3 and Orchestra V2 checkouts remained untouched. The old Tsuzuki-v2-f6c worktree has an unrelated existing test-formatting diff dated 13:47 UTC, before this run's ~17:14 UTC start; it was inspected and preserved. The manager retains its documented shared Git info/exclude patterns; that is shared Git metadata, not a product-tree edit.

## Recommendations for PR #78

1. Make version output clearly identify the candidate SHA/version; HEALTHY integrity and “runtime 1.5 active” are different claims.
2. Document and diagnose project-layer/hook discovery and trust for a newly created linked worktree, including noninteractive startup. A healthy byte installation must not be mistaken for accepted native hooks.
3. Retest real SessionStart/CONTROL, protected mutation and PostToolUse release after the startup prerequisite is resolved. Then test a normal commit checkpoint/new-chat continuation; do not manufacture stale ownership or crashes.
4. Retain the successful reversible-uninstall behavior; release approval stays separate and pending.

```text
ORCHESTRA VERSION PINNED: YES
WORKTREE CREATION: PASS
TEMPORARY INSTALL: PASS
INITIAL DOCTOR: PASS
NATIVE CODEX SESSION: PASS
SESSION AUTHORITY: FAIL
READ OPERATIONS: FAIL
WRITE OPERATIONS: FAIL
POST-TOOL LEASE RELEASE: NOT_TESTED
CHAT CONTINUATION: NOT_TESTED
STALE SESSION FENCING: NOT_TESTED
READ-ONLY DIAGNOSTIC: PASS
RECOVERY: NOT_TESTED
WORKTREE ISOLATION: PASS
STATE PRESERVATION: PASS
UNINSTALL DRY-RUN: PASS
UNINSTALL: PASS
CODEX-FIRST POLICY: PASS
ORCHESTRA 1.5 FIELD TEST: PARTIAL
ORCHESTRA 1.5 RELEASE APPROVAL: PENDING
```

The native session itself started, but the SESSION AUTHORITY / READ / WRITE integration gates failed at the missing-hook/lease prerequisite. Native unprotected reads did execute; protected read authorization and protected mutations were not exercised. INSTALL/DOCTOR PASS describe the pinned candidate's installation/integrity, not a truthful reported 1.5 version or native-hook acceptance. Recovery and continuity remain untested, not failed for lack of opportunity.
