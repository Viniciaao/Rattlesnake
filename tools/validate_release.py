#!/usr/bin/env python3
"""Dependency-free static validation for the Rattlesnake CLEO release."""

from __future__ import annotations

import argparse
import re
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCES = (ROOT / "cleo" / "SnakeQa.txt", ROOT / "cleo" / "SnakeQb.txt")


def fail(message: str) -> None:
    raise AssertionError(message)


def validate_renderware(path: Path, expected_chunk: int) -> None:
    data = path.read_bytes()
    if len(data) < 12:
        fail(f"{path.relative_to(ROOT)} is too small")
    chunk, payload_size, _version = struct.unpack_from("<III", data)
    if chunk != expected_chunk:
        fail(
            f"{path.relative_to(ROOT)} has chunk 0x{chunk:X}; "
            f"expected 0x{expected_chunk:X}"
        )
    if payload_size + 12 != len(data):
        fail(
            f"{path.relative_to(ROOT)} declares {payload_size + 12} bytes, "
            f"but contains {len(data)}"
        )


def first_mp3_frame(data: bytes) -> int:
    offset = 0
    if data[:3] == b"ID3" and len(data) >= 10:
        tag_size = sum((data[6 + index] & 0x7F) << (7 * (3 - index)) for index in range(4))
        offset = 10 + tag_size
    for index in range(offset, len(data) - 4):
        header = int.from_bytes(data[index : index + 4], "big")
        sync = (header >> 21) & 0x7FF
        version = (header >> 19) & 0x3
        layer = (header >> 17) & 0x3
        bitrate = (header >> 12) & 0xF
        sample_rate = (header >> 10) & 0x3
        if (
            sync == 0x7FF
            and version != 0x1
            and layer != 0
            and bitrate not in (0, 0xF)
            and sample_rate != 0x3
        ):
            return index
    return -1


def resolve_case_exact(relative: str) -> Path:
    current = ROOT
    for component in Path(relative.replace("\\", "/")).parts:
        names = {entry.name for entry in current.iterdir()}
        if component not in names:
            fail(f"asset path does not match filesystem casing: {relative}")
        current /= component
    if not current.is_file():
        fail(f"referenced asset is not a file: {relative}")
    return current


def validate_source(path: Path) -> None:
    raw = path.read_bytes()
    relative = path.relative_to(ROOT)
    if b"\r" in raw or b"\n" not in raw:
        fail(f"{relative} must use consistent LF line endings")
    try:
        source = raw.decode("ascii")
    except UnicodeDecodeError as error:
        fail(f"{relative} is not ASCII: {error}")

    if source.count("0F00: load_special_model_dff") != 10:
        fail(f"{relative} must load exactly 10 special-model frames")
    if source.count("0F04: create_render_object_to_object_from_special") != 10:
        fail(f"{relative} must create exactly 10 render frames")
    if source.count("0E31: set_render_object_visible") > 60:
        fail(f"{relative} regressed to excessive render visibility calls")
    for forbidden in (
        "get_any_char_no_save_recursive",
        "get_any_car_no_save_recursive",
        "remove_references_to_object",
        "not 03CA:",
    ):
        if forbidden in source:
            fail(f"{relative} contains forbidden pattern: {forbidden}")
    for required in (
        "0AE1: find_kill = find_actor_near_point",
        "0AE2: find_kill = find_vehicle_near_point",
        "gosub @Cleanup",
        ":RemoveAudio",
        ":Cleanup",
        "wait 100",
    ):
        if required not in source:
            fail(f"{relative} is missing required safety pattern: {required}")

    # Validate all quoted runtime assets with exact casing. Special-model
    # commands omit extensions, so append the format expected by CLEO+.
    for dff, txd in re.findall(
        r'load_special_model_dff "([^"]+)" txd "([^"]+)"', source
    ):
        resolve_case_exact(dff + ".dff")
        resolve_case_exact(txd + ".txd")
    for audio in re.findall(r'load_audio_stream_with_3d_support "([^"]+)"', source):
        resolve_case_exact(audio)


def validate_compiled(source_path: Path) -> None:
    compiled_path = source_path.with_suffix(".cs")
    data = compiled_path.read_bytes()
    marker = data.find(b"SRC\0")
    if marker < 0 or marker + 8 > len(data):
        fail(f"{compiled_path.relative_to(ROOT)} has no Sanny source metadata")
    source_size = int.from_bytes(data[marker + 4 : marker + 8], "little")
    embedded = data[marker + 8 : marker + 8 + source_size]
    expected = source_path.read_bytes()
    if embedded != expected:
        fail(
            f"{compiled_path.relative_to(ROOT)} was not built from "
            f"{source_path.relative_to(ROOT)}"
        )
    if marker < 100:
        fail(f"{compiled_path.relative_to(ROOT)} has implausibly small bytecode")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--sources-only",
        action="store_true",
        help="skip checking whether the compiled .cs files match their sources",
    )
    args = parser.parse_args()

    for model in sorted((ROOT / "ModelsQa").glob("*.dff")):
        validate_renderware(model, 0x10)
    validate_renderware(ROOT / "ModelsQa" / "Snake.txd", 0x16)

    for sound in sorted((ROOT / "SoundsQa").glob("*.mp3")):
        if first_mp3_frame(sound.read_bytes()) < 0:
            fail(f"{sound.relative_to(ROOT)} has no valid MPEG audio frame")

    for source in SOURCES:
        validate_source(source)
        if not args.sources_only:
            validate_compiled(source)

    mode = "sources/assets" if args.sources_only else "release"
    print(f"Rattlesnake {mode} validation passed")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (AssertionError, FileNotFoundError) as error:
        print(f"validation failed: {error}", file=sys.stderr)
        sys.exit(1)
