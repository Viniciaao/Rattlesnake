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

# The CLEO INI opcode clobbers INT variables when a key is missing; these are
# the INT keys of this package and each one needs an explicit default.
INT_INI_KEYS = (
    "Enabled", "Chance", "CheckInterval", "MaxSnakes", "InCities", "Surfaces",
    "AvoidCameraView", "Debug",
)

INI_KEYS = (
    "Enabled", "Chance", "CheckInterval", "MaxSnakes", "InCities", "Surfaces",
    "AvoidCameraView", "SpawnRadius", "MinDistance", "DespawnDistance",
    "Volume", "Debug",
)

# Commands that gta3sc accepts inside IF/AND/WHILE because they are marked as
# conditions in the Sanny Builder Library. Anything else (por exemplo
# GET_COLPOINT_SURFACE, 0xD3C) tem de ser chamado solto: dentro de um IF o
# desvio passa a depender do resultado da condicao anterior.
CONDITION_COMMANDS = frozenset((
    "DOES_OBJECT_EXIST",
    "GET_COLLISION_BETWEEN_POINTS",
    "GET_DYNAMIC_LIBRARY_PROCEDURE",
    "GET_LAST_CREATED_CUSTOM_SCRIPT",
    "GET_RANDOM_CAR_IN_SPHERE_NO_SAVE_RECURSIVE",
    "GET_RANDOM_CHAR_IN_SPHERE_NO_SAVE_RECURSIVE",
    "IS_CHAR_IN_ANY_CAR",
    "IS_EXPLOSION_IN_AREA",
    "IS_PLAYER_PLAYING",
    "IS_VEHICLE_TOUCHING_OBJECT",
    "LOAD_3D_AUDIO_STREAM",
    "LOAD_DYNAMIC_LIBRARY",
    "LOAD_SPECIAL_MODEL",
    "LOCATE_CHAR_DISTANCE_TO_COORDINATES",
    "LOCATE_CHAR_DISTANCE_TO_OBJECT",
    "RANDOM_PERCENT",
    "READ_FLOAT_FROM_INI_FILE",
    "READ_INT_FROM_INI_FILE",
    "READ_STRING_FROM_INI_FILE",
    "TIMERA", "TIMERB",  # pseudo-variaveis de comparacao, nao comandos
    "TRUE", "FALSE",
))

_CONDITION_USE = re.compile(r"\b(IF|AND|OR|WHILE)\s+(NOT\s+)?([A-Z][A-Z0-9_]*)")
_CONST_NAME = re.compile(r"[A-Z][A-Z0-9_]*")


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
        "GET_THIRD_PERSON_CAMERA_TARGET",
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
    if manager.count("TERMINATE_THIS_CUSTOM_SCRIPT") < 1 or manager.count("TERMINATE_THIS_CUSTOM_SCRIPT") > 6:
        fail(f"{relative}: unexpected amount of manager terminations")

    # "WHILE TRUE" e uma armadilha: o gta3script nao tem constante booleana, o
    # TRUE vira o opcode 0x485 (IS_PC_VERSION) e o laco passa a depender do
    # resultado de um comando que nao tem nada a ver com o teste. O laco do
    # gerente tem de usar rotulo + GOTO, como o do trabalhador.
    # (comentarios fora: o proprio arquivo explica o motivo e nao pode se acusar)
    for raw in source.splitlines():
        code = raw.split("//", 1)[0]
        if re.search(r"\bWHILE\s+TRUE\b", code, re.IGNORECASE):
            fail(f"{relative}: 'WHILE TRUE' compila para o opcode 0x485; use rotulo + GOTO")

    # Log em arquivo: tem de ser criado do zero no inicio (senao o arquivo da
    # partida anterior faria o diagnostico mentir) e so depois acrescentado.
    if source.count('OPEN_FILE "cleo\\Rattlesnake_procedural.log" "w"') != 1:
        fail(f"{relative}: o log precisa de um unico OPEN_FILE ... \"w\" no inicio")
    if source.count('OPEN_FILE "cleo\\Rattlesnake_procedural.log" "a"') < 2:
        fail(f"{relative}: o log precisa de OPEN_FILE ... \"a\" para cada evento")

    # O gta3script nao interpreta escapes dentro de string: uma barra dupla no
    # caminho vai literal para o jogo (\"cleo\\Snake.ini\" vira cleo com duas
    # barras). O compilador nao reclama, entao a checagem fica aqui.
    for raw in source.splitlines():
        code = raw.split("//", 1)[0]
        if re.search(r'"[^"]*\\\\[^"]*"', code):
            fail(f"{relative}: string com barra invertida dupla (o gta3sc nao "
                 "interpreta escapes); use uma barra so")

    for label, target in (("ManagerLoop:", "GOTO ManagerLoop"),):
        if label not in source:
            fail(f"{relative}: missing loop label {label}")
        if target not in source:
            fail(f"{relative}: {label} has no {target}")

    # O 9o argumento de GET_COLLISION_BETWEEN_POINTS e a entidade a ignorar e o
    # CLEO+ repassa o valor direto para CWorld::pIgnoreEntity, ou seja, e um
    # ponteiro. -1 (0xFFFFFFFF) e um ponteiro invalido: use 0.
    for line in source.splitlines():
        if "GET_COLLISION_BETWEEN_POINTS" in line and re.search(r"\s-1\s", line):
            fail(f"{relative}: GET_COLLISION_BETWEEN_POINTS com EntityToIgnore = -1 "
                 "(ponteiro invalido; use 0)")

    # Um comando que NAO e condicao nao pode ser usado dentro de IF/AND (nem
    # de WHILE): o compilador aceita, mas quem decide o desvio e o resultado da
    # ultima condicao de verdade. Isso fazia "IF GET_COLPOINT_SURFACE ..."
    # reprovar todas as tentativas com motivo 3 e nenhuma cobra nascia.
    # A lista abaixo sao os comandos realmente marcados como is_condition no
    # Sanny Builder Library; TIMERA/TIMERB sao pseudo-variaveis de comparacao.
    for raw in source.splitlines():
        code = raw.split("//", 1)[0]
        for match in _CONDITION_USE.finditer(code):
            name = match.group(3)
            if name == "NOT":
                # "AND NOT mg_x > 1": o NOT pertence a comparacao seguinte.
                continue
            if name in CONDITION_COMMANDS:
                continue
            fail(f"{relative}: '{name}' nao e um comando de condicao mas foi "
                 f"usado dentro de {match.group(1)} (chame solto e teste o valor)")

    # Argumentos de comando formatado vao direto para o sprintf do CLEO. Com
    # CONST_INT o gta3sc emite o NOME da constante como STRING (bug do
    # compilador), o que desalinha todos os %i da linha: so variaveis e
    # numeros podem entrar como vararg.
    for raw in source.splitlines():
        code = raw.split("//", 1)[0]
        for command in ("WRITE_FORMATTED_STRING_TO_FILE", "PRINT_FORMATTED_NOW"):
            if command not in code:
                continue
            rest = code.split(command, 1)[1]
            quoted = re.search(r'f?"[^"]*"', rest)
            if not quoted:
                continue
            for token in rest[quoted.end():].split():
                token = token.strip('()')
                if _CONST_NAME.fullmatch(token):
                    fail(f"{relative}: constante {token} como vararg de "
                         f"{command} (o gta3sc escreve o nome dela como texto); "
                         "passe por uma variavel")

    # Every INT read from the INI must have a default, because the CLEO INI
    # opcode writes the 0x80000000 sentinel into the variable when the file or
    # the key is missing (FLOAT reads leave the variable untouched instead).
    for key in INT_INI_KEYS:
        guarded = 'IF NOT READ_INT_FROM_INI_FILE "cleo\\SnakeProcedural.ini" "Settings" "%s"' % key
        if guarded not in source:
            fail(f"{relative}: INT read of \"{key}\" has no default guard (use IF NOT ...)")

    validate_local_var_budget(source, relative)
    validate_gosub_depth(source, relative)

    return source


# The script VM keeps a fixed subroutine stack: more than 8 nested GOSUBs
# without a RETURN crashes the game.
GOSUB_LIMIT = 8

_LABEL = re.compile(r"^(\w+):\s*$")
_GOSUB = re.compile(r"^GOSUB\s+(\w+)\s*$")


def validate_gosub_depth(source: str, relative: Path) -> None:
    """Walk the GOSUB call graph and fail if any path nests too deeply."""

    bodies: dict[str, list[str]] = {"<start>": []}
    current = "<start>"
    for raw in source.splitlines():
        line = raw.split("//", 1)[0].strip()
        label = _LABEL.match(line)
        if label:
            current = label.group(1)
            bodies.setdefault(current, [])
            continue
        bodies.setdefault(current, []).append(line)

    calls: dict[str, list[str]] = {}
    for name, lines in bodies.items():
        calls[name] = [_GOSUB.match(line).group(1) for line in lines if _GOSUB.match(line)]

    unknown = sorted({target for targets in calls.values() for target in targets if target not in bodies})
    if unknown:
        fail(f"{relative}: GOSUB to unknown label(s): {', '.join(unknown)}")

    worst = 0
    worst_chain: list[str] = []

    def walk(name: str, chain: list[str]) -> None:
        nonlocal worst, worst_chain
        if len(chain) > worst:
            worst, worst_chain = len(chain), list(chain)
        for target in calls.get(name, []):
            if target in chain:  # recursive GOSUB would never return
                fail(f"{relative}: recursive GOSUB {' -> '.join(chain + [target])}")
            walk(target, chain + [target])

    # Every label is walked, so a too-deep chain is caught even when it is not
    # reachable from the entry point right now.
    for name in sorted(bodies):
        walk(name, [name])

    if worst > GOSUB_LIMIT:
        fail(
            f"{relative}: GOSUB nesting of {worst} exceeds the safe limit of {GOSUB_LIMIT} "
            f"({' -> '.join(worst_chain)}), which crashes the game"
        )


# CLEO 4 gives every custom script 32 local variables (0@..31@); 32@/33@ are the
# two timers, which live in the fields right after them. A scope that declares
# more than that would spill into the timers and into the rest of the thread
# object at runtime, so the compiler output alone is not enough of a guarantee.
LOCAL_VAR_LIMIT = 32

_DECLARATION = re.compile(r"^(LVAR_INT|LVAR_FLOAT|LVAR_TEXT_LABEL16|LVAR_TEXT_LABEL|VAR_INT|VAR_FLOAT)\s+(.*)$")
_ARRAY_ITEM = re.compile(r"^(\w+)\[(\d+)\]$")


def validate_local_var_budget(source: str, relative: Path) -> None:
    """Count the local variables of every scope and enforce the CLEO limit.

    Text labels take 2 variables, LABEL16 takes 4, arrays take one per item.
    Global (`VAR_*`) declarations do not use the local space and are ignored.
    """

    scopes: list[tuple[int, int, list[str]]] = []
    current: dict[int, list] = {}
    depth = 0
    for number, raw in enumerate(source.splitlines(), 1):
        line = raw.split("//", 1)[0].strip()
        for _ in range(line.count("{")):
            depth += 1
            current.setdefault(depth, [number, 0, []])
        declaration = _DECLARATION.match(line)
        if declaration and not declaration.group(1).startswith("VAR_"):
            kind, names = declaration.group(1), declaration.group(2).split()
            total = 0
            for name in names:
                name = name.rstrip(",")
                item = _ARRAY_ITEM.match(name)
                if item:
                    name, size = item.group(1), int(item.group(2))
                elif kind == "LVAR_TEXT_LABEL":
                    size = 2
                elif kind == "LVAR_TEXT_LABEL16":
                    size = 4
                else:
                    size = 1
                total += size
                scope = current.setdefault(depth, [number, 0, []])
                scope[0] = min(scope[0], number)
                scope[2].append(name if size == 1 else f"{name}x{size}")
            scope = current.setdefault(depth, [number, 0, []])
            scope[1] += total
        closes = line.count("}")
        for _ in range(closes):
            scope = current.pop(depth, None)
            if scope is not None:
                scopes.append((scope[0], scope[1], scope[2]))
            depth -= 1

    if not scopes:
        fail(f"{relative}: no variable scope found")

    for start, total, names in sorted(scopes):
        if total > LOCAL_VAR_LIMIT:
            fail(
                f"{relative}: scope starting at line {start} declares {total} local "
                f"variables, above the CLEO limit of {LOCAL_VAR_LIMIT} ({', '.join(names)})"
            )


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
        "CREATE_RENDER_OBJECT_TO_OBJECT_FROM_SPECIAL", "GET_THIRD_PERSON_CAMERA_TARGET",
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
