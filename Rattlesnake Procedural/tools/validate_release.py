#!/usr/bin/env python3
"""Dependency-free static validation for the Rattlesnake Procedural package.

Run from anywhere:

    python3 "Rattlesnake Procedural/tools/validate_release.py"

Optional:

    ... --compiled <path>   # freshly built .cs, compared with the shipped one

The checks are intentionally conservative: they only inspect text, XML, the
compiled bytecode and the assets.
"""

from __future__ import annotations

import argparse
import re
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "cleo" / "Snake_Procedural.sc"
COMPILED = ROOT / "cleo" / "Snake_Procedural.cs"
INI = ROOT / "cleo" / "SnakeProcedural.ini"
CONFIG = ROOT / "tools" / "cleo_plus_commands.xml"

MODELS = tuple("ModelsQa/Snake%d.dff" % index for index in range(1, 11))
TEXTURES = ("ModelsQa/Snake.txd",)
SOUNDS = (
    "SoundsQa/SnakeAttack.mp3",
    "SoundsQa/SnakeIdle.mp3",
    "SoundsQa/SNAKEDEATH.mp3",
)

# Coordinates used by the old, fixed-location scripts. The procedural version
# must not contain any of them.
OLD_SPAWN_COORDINATES = (
    "1194.4731", "-2370.7844", "12.4779",
    "1454.3586", "-1944.9504", "24.9489",
    "110.1197", "-1719.4899", "9.2032",
    "766.2333", "376.8286", "23.2122",
    "633.1775", "-629.5248", "16.7997",
    "1563.9517", "34.0211", "24.1641",
)

INI_KEYS = (
    "Enabled", "Chance", "CheckInterval", "MaxSnakes", "InCities", "Surfaces",
    "AvoidCameraView", "SpawnRadius", "MinDistance", "DespawnDistance",
    "Volume", "Debug",
)


def fail(message: str) -> None:
    raise AssertionError(message)


def read_text(path: Path) -> str:
    raw = path.read_bytes()
    if b"\r" in raw:
        fail(f"{path.relative_to(ROOT)} must use LF line endings")
    try:
        return raw.decode("ascii")
    except UnicodeDecodeError as error:
        fail(f"{path.relative_to(ROOT)} must be ASCII: {error}")


def validate_source() -> str:
    if not SOURCE.is_file():
        fail(f"missing source: {SOURCE.relative_to(ROOT)}")
    source = read_text(SOURCE)
    relative = SOURCE.relative_to(ROOT)

    required = (
        "SCRIPT_START",
        "SCRIPT_END",
        "STREAM_CUSTOM_SCRIPT_FROM_LABEL SnakeWorker",
        "GET_LAST_CREATED_CUSTOM_SCRIPT",
        "LOAD_DYNAMIC_LIBRARY \"CLEO+.cleo\"",
        "0x01020000",
        "GET_COLLISION_BETWEEN_POINTS",
        "GET_COLPOINT_SURFACE",
        "GET_COLPOINT_NORMAL_VECTOR",
        "GET_CITY_FROM_COORDS",
        "GET_ACTIVE_CAMERA_ROTATION",
        "LOAD_SPECIAL_MODEL",
        "CREATE_RENDER_OBJECT_TO_OBJECT_FROM_SPECIAL",
        "SET_RENDER_OBJECT_VISIBLE",
        "LOCATE_CHAR_DISTANCE_TO_OBJECT wk_i wk_obj wk_despawn",
        "SET_CLEO_SHARED_VAR",
        "GET_CLEO_SHARED_VAR",
        "GENERATE_RANDOM_FLOAT_IN_RANGE",
        "READ_INT_FROM_INI_FILE \"cleo\\SnakeProcedural.ini\"",
    )
    for token in required:
        if token not in source:
            fail(f"{relative}: missing required token: {token}")

    # Procedural only: the old fixed spawn points must be gone.
    for coordinate in OLD_SPAWN_COORDINATES:
        if coordinate in source:
            fail(f"{relative}: fixed spawn coordinate {coordinate} is back")

    # Exactly one load for each animation frame, in order.
    loads = re.findall(r'LOAD_SPECIAL_MODEL "ModelsQa\\Snake(\d+)" "ModelsQa\\Snake"', source)
    if loads != [str(index) for index in range(1, 11)]:
        fail(f"{relative}: expected 10 sequential frame loads, found {loads}")

    # The worker threads must never terminate on their own: CLEO 4 keeps a
    # dangling pointer in the parent's child list when a label-created script
    # removes itself, which crashes when the parent goes away too.
    worker = source.split("SnakeWorker:", 1)
    if len(worker) != 2:
        fail(f"{relative}: SnakeWorker entry point not found")
    if "TERMINATE_THIS_CUSTOM_SCRIPT" in worker[1]:
        fail(f"{relative}: worker threads must not terminate themselves")

    # Sanity: the manager only terminates before any worker exists.
    manager = worker[0]
    if manager.count("TERMINATE_THIS_CUSTOM_SCRIPT") < 1 or manager.count("TERMINATE_THIS_CUSTOM_SCRIPT") > 4:
        fail(f"{relative}: unexpected amount of manager terminations")

    return source


def validate_ini(source: str) -> None:
    text = read_text(INI)
    for key in INI_KEYS:
        if not re.search(r"^%s\s*=" % re.escape(key), text, re.MULTILINE):
            fail(f"{INI.relative_to(ROOT)}: missing key {key}")

    radius = re.search(r"^SpawnRadius\s*=\s*([0-9.]+)", text, re.MULTILINE)
    despawn = re.search(r"^DespawnDistance\s*=\s*([0-9.]+)", text, re.MULTILINE)
    if not radius or float(radius.group(1)) > 40.0:
        fail(f"{INI.relative_to(ROOT)}: SpawnRadius must stay within 40 m")
    if not despawn or float(despawn.group(1)) > 55.0:
        fail(f"{INI.relative_to(ROOT)}: DespawnDistance must stay within 55 m")

    # Every INI read in the script must exist in the file.
    keys = set(re.findall(r'READ_(?:INT|FLOAT)_FROM_INI_FILE "[^"]*" "[^"]*" "([^"]+)"', source))
    keys.update(re.findall(r'"[A-Za-z_]+"\s+"([A-Za-z_]+)"\s+\(', source))
    for key in sorted(keys):
        if not re.search(r"^%s\s*=" % re.escape(key), text, re.MULTILINE):
            fail(f"{INI.relative_to(ROOT)}: {key} is read by the script but not configured")


def validate_config() -> None:
    text = read_text(CONFIG)
    if "<GTA3Script>" not in text or "</GTA3Script>" not in text:
        fail(f"{CONFIG.relative_to(ROOT)}: not a gta3sc config file")
    needed = (
        "GET_COLLISION_BETWEEN_POINTS", "GET_COLPOINT_SURFACE",
        "GET_COLPOINT_NORMAL_VECTOR", "DELETE_RENDER_OBJECT", "SET_RENDER_OBJECT_VISIBLE",
        "CREATE_OBJECT_NO_SAVE", "GET_ANGLE_FROM_TWO_COORDS", "RANDOM_PERCENT",
        "STREAM_CUSTOM_SCRIPT_FROM_LABEL", "GET_LAST_CREATED_CUSTOM_SCRIPT",
        "LOCATE_CHAR_DISTANCE_TO_OBJECT", "LOCATE_CHAR_DISTANCE_TO_COORDINATES",
        "GET_COORD_FROM_ANGLED_DISTANCE", "LOAD_SPECIAL_MODEL", "REMOVE_SPECIAL_MODEL",
        "CREATE_RENDER_OBJECT_TO_OBJECT_FROM_SPECIAL", "GET_ACTIVE_CAMERA_ROTATION",
    )
    for name in needed:
        if 'Name="%s"' % name not in text:
            fail(f"{CONFIG.relative_to(ROOT)}: missing definition for {name}")


def validate_assets() -> None:
    for relative in MODELS:
        validate_renderware(resolve_case_exact(relative), 0x10)   # Clump (DFF)
    for relative in TEXTURES:
        validate_renderware(resolve_case_exact(relative), 0x16)   # Texture dictionary (TXD)
    for relative in SOUNDS:
        path = resolve_case_exact(relative)
        data = path.read_bytes()
        if len(data) < 1024:
            fail(f"{relative} is too small")
        if first_mp3_frame(data) < 0:
            fail(f"{relative} has no valid MPEG audio frame")


def resolve_case_exact(relative: str) -> Path:
    current = ROOT
    for component in Path(relative).parts:
        names = {entry.name for entry in current.iterdir()}
        if component not in names:
            fail(f"asset path does not match filesystem casing: {relative}")
        current /= component
    if not current.is_file():
        fail(f"referenced asset is not a file: {relative}")
    return current


def validate_renderware(path: Path, expected_chunk: int) -> None:
    data = path.read_bytes()
    if len(data) < 12:
        fail(f"{path.relative_to(ROOT)} is too small")
    chunk, payload_size, _version = struct.unpack_from("<III", data)
    if chunk != expected_chunk:
        fail(f"{path.relative_to(ROOT)} has chunk 0x{chunk:X}, expected 0x{expected_chunk:X}")
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


def validate_compiled(fresh: Path | None) -> None:
    if not COMPILED.is_file():
        fail(f"missing compiled script: {COMPILED.relative_to(ROOT)}")
    data = COMPILED.read_bytes()
    if len(data) > 64 * 1024:
        fail("compiled script is larger than 64 KiB")
    if data[:2] == b"\x03\x00":
        fail("compiled script must not start with a SCRIPT_NAME opcode")
    if data.count(b"\x6f\x0e") < 1:
        fail("compiled script has no STREAM_CUSTOM_SCRIPT_FROM_LABEL call")
    if fresh is not None:
        if not fresh.is_file():
            fail(f"missing freshly compiled script: {fresh}")
        if fresh.read_bytes() != data:
            fail("cleo/Snake_Procedural.cs is out of date: rebuild it with tools/build.sh")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compiled", type=Path, default=None,
                        help="freshly compiled .cs to compare against the shipped one")
    arguments = parser.parse_args()

    try:
        source = validate_source()
        validate_ini(source)
        validate_config()
        validate_assets()
        validate_compiled(arguments.compiled)
    except AssertionError as error:
        print("validation failed: %s" % error, file=sys.stderr)
        return 1

    print("Rattlesnake Procedural: all checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
