#!/usr/bin/env python3
"""Fail closed if signed code9 Host installation can run automatically again.

This is a static CI guard, not a signing verification or signed installation.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/mangaall-code9-official-signed-install-android.yml"
text = WORKFLOW.read_text(encoding="utf-8")
assert "  workflow_dispatch:" in text
assert "  pull_request:" in text, "PRs must retain compilation-only coverage"
for forbidden in (
    "  push:",
    "  schedule:",
    "  workflow_run:",
    "github.head_ref",
    "refs/heads/test/mangaall-code9",
):
    assert forbidden not in text, forbidden
assert "if: github.event_name == 'pull_request'" in text
assert "if: github.event_name == 'workflow_dispatch'" in text
assert "node tooling/verify_index.mjs" in text
assert "python3 tooling/mangaall_code9_signed_release_audit.py" in text
assert "bash .github/scripts/run_mangaall_official_code9_install_android.sh" in text
assert "mangaAllSignedCode9OptIn" not in text, "Opt-in arg belongs only to the emulator script"
assert "test_mangaall_signed_install_manual_only.py" in text
print("MANGAALL_CODE9_SIGNED_HOST_TRIGGER_GUARD|install=MANUAL_ONLY|pr=COMPILE_ONLY|upstream=NONE|outcome=PASS")
