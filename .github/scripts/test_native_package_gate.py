import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("native_package_gate.py")
SPEC = importlib.util.spec_from_file_location("native_package_gate", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)

EXPECTED_ABIS = MODULE.EXPECTED_ABIS
discover_apks = MODULE.discover_apks
validate_jlibtorrent_abi_coverage = MODULE.validate_jlibtorrent_abi_coverage
validate_program_headers = MODULE.validate_program_headers
should_verify_wave6_elf = MODULE.should_verify_wave6_elf


class NativePackageGateTest(unittest.TestCase):
    def test_split_apks_collectively_cover_all_supported_jlibtorrent_abis(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for abi in EXPECTED_ABIS:
                self._write_apk(
                    root / f"app-{abi}-debug.apk",
                    {f"lib/{abi}/libjlibtorrent-2.0.12.9.so": abi.encode()},
                )

            apks = discover_apks(root)
            self.assertEqual(len(apks), len(EXPECTED_ABIS))
            self.assertEqual(
                validate_jlibtorrent_abi_coverage(apks),
                set(EXPECTED_ABIS),
            )

    def test_universal_apk_is_valid_but_not_required(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            universal = root / "app-universal-debug.apk"
            self._write_apk(
                universal,
                {
                    f"lib/{abi}/libjlibtorrent.so": abi.encode()
                    for abi in EXPECTED_ABIS
                },
            )

            apks = discover_apks(root)
            self.assertEqual(apks, [universal])
            self.assertEqual(
                validate_jlibtorrent_abi_coverage(apks),
                set(EXPECTED_ABIS),
            )

    def test_release_jlibtorrent_does_not_require_upstream_x86_64_until_relro_is_fixed(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            release_abis = ("armeabi-v7a", "arm64-v8a", "x86")
            for abi in release_abis:
                self._write_apk(
                    root / f"app-{abi}-release.apk",
                    {f"lib/{abi}/libjlibtorrent-2.0.12.9.so": abi.encode()},
                )

            apks = discover_apks(root)
            self.assertEqual(
                validate_jlibtorrent_abi_coverage(apks),
                set(release_abis),
            )

    def test_missing_jlibtorrent_abi_fails_with_observed_coverage(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for abi in EXPECTED_ABIS:
                if abi == "arm64-v8a":
                    continue
                self._write_apk(
                    root / f"app-{abi}-release.apk",
                    {f"lib/{abi}/libjlibtorrent-2.0.12.9.so": abi.encode()},
                )

            apks = discover_apks(root)
            with self.assertRaisesRegex(
                RuntimeError,
                r"missing: arm64-v8a.*observed: armeabi-v7a, x86",
            ):
                validate_jlibtorrent_abi_coverage(apks)

    def test_discover_apks_rejects_empty_output(self):
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(RuntimeError, "No APK was produced"):
                discover_apks(Path(temp))

    def test_elf_scope_is_limited_to_wave6_jlibtorrent_64_bit_binaries(self):
        self.assertTrue(
            should_verify_wave6_elf(
                "lib/arm64-v8a/libjlibtorrent-2.0.12.9.so",
            ),
        )
        self.assertFalse(
            should_verify_wave6_elf(
                "lib/x86_64/libjlibtorrent-2.0.12.9.so",
            ),
        )
        self.assertFalse(
            should_verify_wave6_elf(
                "lib/arm64-v8a/libandroidx.graphics.path.so",
            ),
        )
        self.assertFalse(
            should_verify_wave6_elf(
                "lib/armeabi-v7a/libjlibtorrent-2.0.12.9.so",
            ),
        )

    def test_program_headers_accept_16k_or_larger_load_alignment_and_relro_boundary(self):
        output = """
  Type           Offset             VirtAddr           PhysAddr
  LOAD           0x0000000000000000 0x0000000000000000 0x0000000000000000 0x1000 0x1000 R E 0x4000
  LOAD           0x0000000000010000 0x0000000000010000 0x0000000000010000 0x2000 0x2000 RW  0x10000
  GNU_RELRO      0x000000000001f000 0x000000000001f000 0x000000000001f000 0x1000 0x1000 R 0x1
"""
        validate_program_headers(output, "libok.so")

    def test_program_headers_reject_4k_load_alignment(self):
        output = """
  LOAD           0x0000000000000000 0x0000000000000000 0x0000000000000000 0x1000 0x1000 R E 0x1000
"""
        with self.assertRaisesRegex(RuntimeError, "PT_LOAD"):
            validate_program_headers(output, "libbad.so")

    def test_program_headers_reject_misaligned_relro_end(self):
        output = """
  LOAD           0x0000000000000000 0x0000000000000000 0x0000000000000000 0x1000 0x1000 R E 0x4000
  GNU_RELRO      0x000000000001e000 0x000000000001e000 0x000000000001e000 0x1000 0x1000 R 0x1
"""
        with self.assertRaisesRegex(RuntimeError, "GNU_RELRO"):
            validate_program_headers(output, "libbad-relro.so")

    @staticmethod
    def _write_apk(path: Path, entries: dict[str, bytes]) -> None:
        with zipfile.ZipFile(path, "w") as archive:
            for name, payload in entries.items():
                archive.writestr(name, payload)


if __name__ == "__main__":
    unittest.main()
