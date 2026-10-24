"""Regression guard for removing Mihon's inherited application updater."""
from __future__ import annotations

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]

REMOVED_FILES = (
    "app/src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateChecker.kt",
    "app/src/main/java/eu/kanade/tachiyomi/ui/more/NewUpdateScreen.kt",
    "app/src/main/java/eu/kanade/tachiyomi/ui/more/NewUpdateScreenModel.kt",
    "app/src/main/java/eu/kanade/presentation/more/NewUpdateScreen.kt",
    "domain/src/main/java/tachiyomi/domain/release/interactor/GetApplicationRelease.kt",
    "domain/src/main/java/tachiyomi/domain/release/model/Release.kt",
    "domain/src/main/java/tachiyomi/domain/release/service/ReleaseService.kt",
    "data/src/main/java/tachiyomi/data/release/GithubRelease.kt",
    "data/src/main/java/tachiyomi/data/release/ReleaseServiceImpl.kt",
    "domain/src/test/java/tachiyomi/domain/release/interactor/GetApplicationReleaseTest.kt",
)


def source(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


class InheritedApplicationUpdaterRemovalTest(unittest.TestCase):
    def test_owned_app_updater_implementation_and_release_chain_are_absent(self):
        remaining = [path for path in REMOVED_FILES if (ROOT / path).exists()]

        self.assertEqual([], remaining, "inherited app updater ownership is still present")

    def test_main_activity_keeps_only_the_extension_update_check(self):
        main_activity = source("app/src/main/java/eu/kanade/tachiyomi/ui/main/MainActivity.kt")
        app_graph = source("app/src/main/java/mihon/app/di/AppGraph.kt")

        self.assertIn("extensionApi.checkForUpdates(context)", main_activity)
        for app_update_reference in (
            "AppUpdateChecker",
            "updateChecker",
            "GetApplicationRelease",
            "NewUpdateScreen",
            "updaterEnabled",
        ):
            with self.subTest(reference=app_update_reference):
                self.assertFalse(
                    app_update_reference in main_activity,
                    f"MainActivity still references {app_update_reference}",
                )
                self.assertFalse(
                    app_update_reference in app_graph,
                    f"AppGraph still references {app_update_reference}",
                )

    def test_app_updater_build_switch_is_removed_without_touching_extension_installation(self):
        app_build = source("app/build.gradle.kts")
        build_config = source("gradle/build-logic/src/main/kotlin/mihon/gradle/BuildConfig.kt")
        runtime_build_config = source("app/src/main/java/eu/kanade/tachiyomi/util/system/BuildConfig.kt")

        self.assertFalse("UPDATER_ENABLED" in app_build, "app BuildConfig still declares the updater switch")
        self.assertFalse(
            "enableUpdater" in build_config,
            "Gradle convention plugin still exposes the updater switch",
        )
        self.assertFalse(
            "updaterEnabled" in runtime_build_config,
            "runtime BuildConfig still exposes updaterEnabled",
        )

        extension_api = source("app/src/main/java/eu/kanade/tachiyomi/extension/api/ExtensionApi.kt")
        extension_installer = source("app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionInstaller.kt")
        notifications = source("app/src/main/java/eu/kanade/tachiyomi/data/notification/Notifications.kt")
        self.assertIn("suspend fun checkForUpdates(context: Context)", extension_api)
        self.assertIn("APK_MIME", extension_installer)
        self.assertIn("ext_updates", notifications)


if __name__ == "__main__":
    unittest.main()
