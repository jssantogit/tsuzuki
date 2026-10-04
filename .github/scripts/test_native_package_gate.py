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
find_universal_apk = MODULE.find_universal_apk
validate_program_headers = MODULE.validate_program_headers


class NativePackageGateTest(unittest.TestCase):
    def test_find_universal_apk_requires_all_supported_jlibtorrent_abis(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            split = root / "app-arm64-v8a-debug.apk"
            universal = root / "app-universal-debug.apk"

            self._write_apk(
                split,
                {"lib/arm64-v8a/libjlibtorrent.so": b"arm64"},
            )
            self._write_apk(
                universal,
                {
                    f"lib/{abi}/libjlibtorrent.so": abi.encode()
                    for abi in EXPECTED_ABIS
                },
            )

            self.assertEqual(find_universal_apk(root), universal)

    def test_find_universal_apk_rejects_missing_jlibtorrent_abi(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            apk = root / "app-universal-debug.apk"
            entries = {
                f"lib/{abi}/libjlibtorrent.so": abi.encode()
                for abi in EXPECTED_ABIS
                if abi != "x86_64"
            }
            self._write_apk(apk, entries)

            with self.assertRaisesRegex(RuntimeError, "four supported ABIs"):
                find_universal_apk(root)

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
