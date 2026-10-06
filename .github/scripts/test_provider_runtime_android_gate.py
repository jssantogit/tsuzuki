import unittest
from pathlib import Path

SCRIPT_PATH = Path(__file__).with_name("provider-runtime-android-gate.sh")
WORKFLOW_PATH = Path(__file__).parents[1] / "workflows" / "provider-runtime-android.yml"


class ProviderRuntimeAndroidGateTest(unittest.TestCase):
    def test_gate_executes_runtime_and_reader_acceptance_packages(self):
        script = SCRIPT_PATH.read_text(encoding="utf-8")

        self.assertIn("eu.kanade.tachiyomi.provider.runtime", script)
        self.assertIn("eu.kanade.tachiyomi.ui.reader.loader", script)
        self.assertIn(":app:connectedDebugAndroidTest", script)

    def test_workflow_selects_acceptance_branch_and_android_test_sources(self):
        workflow = WORKFLOW_PATH.read_text(encoding="utf-8")

        self.assertIn("tsuzuki/provider-torrent-acceptance-harness", workflow)
        self.assertIn("app/src/androidTest/**", workflow)
        self.assertIn("provider-runtime-android-gate.sh", workflow)


if __name__ == "__main__":
    unittest.main()
