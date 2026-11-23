#!/usr/bin/env python3
"""Offline, fail-closed guard for an INERT private-only MangaALL CI template.

No provider requests, runner registration or browser session access.
Does not parse untrusted upstream data or grant CI network access.
"""
from pathlib import Path

TEMPLATE = Path("docs/ci-private/mangaall-fire-private.workflow.yml")
DOCS = Path("docs/ci-private/mangaall-private-runner.md")
SRC_SHA = "14af4ef49615336c66a1912ec183f3cc235c02ca"
PROVIDER_SHA = "195502f981bd776a68feebde3efc6d20f55cca65"
PACKAGE_SHA = "bcdfd03ac18c3cfdd0517e08b58dde7efa1f5baa874e93002af28e518860f95f"


def audit(text: str) -> None:
    # Private-only: never attach this workflow to the public fork.
    assert "github.event.repository.private" in text
    assert "reason=PUBLIC_REPOSITORY" in text
    assert "workflow_dispatch:" in text
    assert "on:\n  workflow_dispatch:" in text
    assert "\n  push:" not in text and "\n  pull_request:" not in text
    assert "\n  pull_request_target:" not in text and "\n  schedule:" not in text
    assert "\n  workflow_run:" not in text
    assert "runs-on: [self-hosted, Linux, X64, mangaall-ephemeral-android]" in text
    assert "needs: private-repository-preflight" in text

    # The user explicitly approves exactly one known three-work signed test.
    assert "I_ACCEPT_MANGAFIRE_CI_WEBVIEW_LIVE" in text
    assert "MANGAALL_CI_GATE_MODE: fire" in text
    assert "MANGAALL_CI_SHAPE_ACK: NOT_AUTHORIZED" in text
    assert "bash .github/scripts/run_mangaall_ci_webview_android.sh" in text
    assert "MANGAALL_CI_FIRE_SOURCE_CONTRACT" not in text  # no fabricated result
    assert "api-level: 35" in text and "arch: x86_64" in text
    assert "KVM_NOT_AVAILABLE" in text

    # Immutable supply-chain inputs, not mutable PR refs or shell injection.
    assert text.count(SRC_SHA) == 2  # checkout + explicit git verify
    assert text.count(PROVIDER_SHA) == 2
    assert PACKAGE_SHA in text
    assert "verify_index.mjs" in text
    assert "source=EXPECTED" not in text
    assert "ref: main" not in text and "ref: HEAD" not in text
    assert "ref: ${{" not in text
    assert "persist-credentials: false" in text
    assert "permissions:\n  contents: read" in text

    # No extra privileges, cookie bridge, challenge automation or exports.
    assert "secrets." not in text
    assert "sudo " not in text
    assert "curl " not in text
    assert "actions/upload-artifact@" in text
    assert "tsuzuki/.github/results/mangaall-ci-webview/verdict.txt" in text
    assert "retention-days: 1" in text
    assert "shape" not in text.lower().split("MANGAALL_CI_SHAPE_ACK: NOT_AUTHORIZED")[-1].split(
        "- name: Retain ONLY"
    )[0]  # nothing in runner code attempts a solver
    assert "cancel-in-progress: false" in text


def rejected(source: str, original: str, replacement: str) -> None:
    assert original in source, original
    changed = source.replace(original, replacement, 1)
    try:
        audit(changed)
    except AssertionError:
        return
    raise AssertionError("unsafe private runner mutation was not rejected")


def main() -> None:
    text = TEMPLATE.read_text(encoding="utf-8")
    docs = DOCS.read_text(encoding="utf-8")
    audit(text)
    assert "não deve ser registrado no fork público" in docs
    assert "NÃO provisionada" in docs
    assert "Não habilita runner próprio" in docs
    assert SRC_SHA in docs and PROVIDER_SHA in docs
    mutations = [
        ("github.event.repository.private", "github.event.repository.owner"),
        ("reason=PUBLIC_REPOSITORY", "reason=NONE"),
        ("on:\n  workflow_dispatch:", "on:\n  push:"),
        ("runs-on: [self-hosted, Linux, X64, mangaall-ephemeral-android]",
         "runs-on: [self-hosted, Linux, X64]"),
        ("needs: private-repository-preflight", "needs: no-check"),
        ("MANGAALL_CI_GATE_MODE: fire", "MANGAALL_CI_GATE_MODE: shape"),
        ("MANGAALL_CI_SHAPE_ACK: NOT_AUTHORIZED", "MANGAALL_CI_SHAPE_ACK: LIVE"),
        (SRC_SHA, "main"),
        (PROVIDER_SHA, "main"),
        (PACKAGE_SHA, "0" * 64),
        ("persist-credentials: false", "persist-credentials: true"),
        ("permissions:\n  contents: read", "permissions:\n  contents: write"),
        ("retention-days: 1", "retention-days: 90"),
        ("KVM_NOT_AVAILABLE", "KVM_ALLOWED"),
        ("api-level: 35", "api-level: 23"),
    ]
    for original, replacement in mutations:
        rejected(text, original, replacement)
    print(
        "MANGAALL_PRIVATE_CI_TEMPLATE|outcome=PASS|offline=YES"
        f"|negativeContracts={len(mutations)}|runner=NOT_REGISTERED|network=NOT_STARTED"
    )


if __name__ == "__main__":
    main()
