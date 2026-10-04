#!/usr/bin/env python3
from __future__ import annotations

import argparse
import os
import subprocess
import tempfile
import zipfile
from pathlib import Path
from typing import Iterable

EXPECTED_ABIS = ("armeabi-v7a", "arm64-v8a", "x86")
FORBIDDEN_RELEASE_ABIS = ("x86_64",)
ELF_16K_ABIS = ("arm64-v8a",)
MIN_PAGE_ALIGNMENT = 16 * 1024
JLIBTORRENT_PREFIX = "libjlibtorrent"


def _apk_entries(path: Path) -> set[str]:
    with zipfile.ZipFile(path) as archive:
        return {name for name in archive.namelist() if not name.endswith("/")}


def discover_apks(root: Path) -> list[Path]:
    candidates = sorted(root.rglob("*.apk"))
    if not candidates:
        raise RuntimeError(f"No APK was produced under {root}")

    for apk in candidates:
        try:
            _apk_entries(apk)
        except zipfile.BadZipFile as error:
            raise RuntimeError(f"Invalid APK archive: {apk}") from error
    return candidates


def validate_jlibtorrent_abi_coverage(apks: Iterable[Path]) -> set[str]:
    observed: set[str] = set()
    forbidden: set[str] = set()
    for apk in apks:
        entries = _apk_entries(apk)
        for abi in EXPECTED_ABIS:
            prefix = f"lib/{abi}/{JLIBTORRENT_PREFIX}"
            if any(
                entry.startswith(prefix) and entry.endswith(".so")
                for entry in entries
            ):
                observed.add(abi)
        for abi in FORBIDDEN_RELEASE_ABIS:
            prefix = f"lib/{abi}/{JLIBTORRENT_PREFIX}"
            if any(
                entry.startswith(prefix) and entry.endswith(".so")
                for entry in entries
            ):
                forbidden.add(abi)

    if forbidden:
        forbidden_text = ", ".join(sorted(forbidden))
        raise RuntimeError(
            "Native package gate found unsupported release jlibtorrent ABI(s): "
            f"{forbidden_text}"
        )

    missing = set(EXPECTED_ABIS) - observed
    if missing:
        missing_text = ", ".join(sorted(missing))
        observed_text = ", ".join(sorted(observed)) or "none"
        raise RuntimeError(
            "Native package gate is missing jlibtorrent for release-supported P2P ABIs; "
            f"missing: {missing_text}; observed: {observed_text}"
        )
    return observed


def validate_program_headers(output: str, label: str) -> None:
    load_alignments: list[int] = []
    relro_ranges: list[tuple[int, int]] = []

    for raw_line in output.splitlines():
        parts = raw_line.split()
        if not parts:
            continue

        if parts[0] == "LOAD":
            try:
                load_alignments.append(int(parts[-1], 0))
            except (ValueError, IndexError) as error:
                raise RuntimeError(f"Unable to parse PT_LOAD alignment for {label}") from error
        elif parts[0] == "GNU_RELRO":
            try:
                virtual_address = int(parts[2], 0)
                memory_size = int(parts[5], 0)
            except (ValueError, IndexError) as error:
                raise RuntimeError(f"Unable to parse GNU_RELRO segment for {label}") from error
            relro_ranges.append((virtual_address, memory_size))

    if not load_alignments:
        raise RuntimeError(f"No PT_LOAD segments found in {label}")

    bad_loads = [alignment for alignment in load_alignments if alignment < MIN_PAGE_ALIGNMENT]
    if bad_loads:
        rendered = ", ".join(hex(value) for value in bad_loads)
        raise RuntimeError(
            f"{label} has PT_LOAD alignment below 16 KiB: {rendered}"
        )

    for virtual_address, memory_size in relro_ranges:
        if (virtual_address + memory_size) % MIN_PAGE_ALIGNMENT != 0:
            raise RuntimeError(
                f"{label} has GNU_RELRO end that is not 16 KiB aligned: "
                f"virt={hex(virtual_address)} memsz={hex(memory_size)}"
            )


def should_verify_wave6_elf(name: str) -> bool:
    if not name.endswith(".so"):
        return False
    if not any(name.startswith(f"lib/{abi}/") for abi in ELF_16K_ABIS):
        return False
    return Path(name).name.startswith(JLIBTORRENT_PREFIX)


def verify_zip_alignment(apks: Iterable[Path], zipalign: Path) -> int:
    checked = 0
    for apk in apks:
        subprocess.run(
            [str(zipalign), "-c", "-P", "16", "-v", "4", str(apk)],
            check=True,
        )
        checked += 1
    return checked


def verify_elf_alignment(apks: Iterable[Path], readelf: str) -> int:
    checked = 0
    with tempfile.TemporaryDirectory() as temp:
        temp_root = Path(temp)
        for apk_index, apk in enumerate(apks):
            with zipfile.ZipFile(apk) as archive:
                for name in archive.namelist():
                    if not should_verify_wave6_elf(name):
                        continue

                    destination = temp_root / str(apk_index) / name
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    destination.write_bytes(archive.read(name))

                    result = subprocess.run(
                        [readelf, "-lW", str(destination)],
                        check=True,
                        capture_output=True,
                        text=True,
                    )
                    validate_program_headers(
                        result.stdout,
                        f"{apk.name}:{name}",
                    )
                    checked += 1

    if checked == 0:
        raise RuntimeError("No 64-bit jlibtorrent libraries were found for ELF alignment validation")
    return checked


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk-root", type=Path, required=True)
    parser.add_argument("--zipalign", type=Path, required=True)
    parser.add_argument("--readelf", default="readelf")
    args = parser.parse_args()

    if not args.zipalign.is_file():
        raise RuntimeError(f"zipalign executable not found: {args.zipalign}")
    if not os.access(args.zipalign, os.X_OK):
        raise RuntimeError(f"zipalign is not executable: {args.zipalign}")

    apks = discover_apks(args.apk_root)
    observed_abis = validate_jlibtorrent_abi_coverage(apks)
    zip_count = verify_zip_alignment(apks, args.zipalign)
    elf_count = verify_elf_alignment(apks, args.readelf)

    print(f"Native package gate passed for {len(apks)} APK(s)")
    print(f"jlibtorrent ABIs: {', '.join(sorted(observed_abis))}")
    print(f"16 KiB APK alignments checked: {zip_count}")
    print(f"16 KiB jlibtorrent ELF libraries checked: {elf_count}")


if __name__ == "__main__":
    main()
