#!/usr/bin/env python3
"""Regenerates tools/cleo_plus_commands.xml from a CLEO+ checkout.

The CLEO+ distribution ships a gta3sc command definition file at
"(for developers)/gta3script/cleo.xml".  We only need the opcodes used by
Snake_Procedural.sc, so this script extracts exactly those commands
(verbatim: IDs, names, argument order and types) into a small file that can be
reviewed by hand.

Usage:
    python3 tools/generate_cleo_plus_config.py /path/to/CLEOPlus
"""

import os
import re
import sys

# Opcodes used by Snake_Procedural.sc / the manager thread.
WANTED = [
    "0xD3A",  # GET_COLLISION_BETWEEN_POINTS
    "0xD3B",  # GET_COLPOINT_NORMAL_VECTOR
    "0xD3C",  # GET_COLPOINT_SURFACE
    "0xD3E",  # GET_COLPOINT_DEPTH
    "0xE6B",  # GET_COLPOINT_LIGHTING
    "0xE2F",  # DELETE_RENDER_OBJECT
    "0xE31",  # SET_RENDER_OBJECT_VISIBLE
    "0xE01",  # CREATE_OBJECT_NO_SAVE
    "0xE27",  # GET_ANGLE_FROM_TWO_COORDS
    "0xE2D",  # IS_GAME_FIRST_START
    "0xE4D",  # RANDOM_PERCENT
    "0xE6D",  # GET_UNDERWATERNESS
    "0xE6F",  # STREAM_CUSTOM_SCRIPT_FROM_LABEL
    "0xE70",  # GET_LAST_CREATED_CUSTOM_SCRIPT
    "0xEE6",  # LOCATE_CHAR_DISTANCE_TO_OBJECT
    "0xEEA",  # LOCATE_CHAR_DISTANCE_TO_COORDINATES
    "0xEF0",  # GET_COORD_FROM_ANGLED_DISTANCE
    "0xF00",  # LOAD_SPECIAL_MODEL
    "0xF01",  # REMOVE_SPECIAL_MODEL
    "0xF04",  # CREATE_RENDER_OBJECT_TO_OBJECT_FROM_SPECIAL
    "0xF05",  # GET_SPECIAL_MODEL_DATA
    "0xF11",  # GET_CLOSEST_WATER_DISTANCE
    "0xF10",  # GET_ACTIVE_CAMERA_ROTATION
]

HEADER = """<?xml version="1.0" encoding="UTF-8"?>
<!--
  Extra command definitions for the gta3sc compiler (GTA3Script).

  These are the CLEO+ opcodes used by cleo/Snake_Procedural.sc, copied
  verbatim from the definitions published by the CLEO+ author in
  "(for developers)/gta3script/cleo.xml" -- https://github.com/JuniorDjjr/CLEOPlus

  IDs, argument order and argument types must match the native layout, so do
  not reorder them.  Keep this file in sync by running
  tools/generate_cleo_plus_config.py against a CLEO+ checkout.

  Build usage:
    gta3sc compile cleo/Snake_Procedural.sc --config=gtasa --cs --guesser ^
        -fno-entity-tracking --add-config="tools\\cleo_plus_commands.xml"
-->
<GTA3Script>
  <Commands>
"""

# Hand-written helpers (not CLEO+ commands). 0x0485/0x059A are the standard
# San Andreas opcodes that force a condition result, so they can be used as an
# always-true loop condition ("WHILE TRUE") and as early exits from a
# subroutine.  gta3sc does not accept the BOOL constants as statements.
EXTRA_COMMANDS = """    <Command ID="0x485" Name="TRUE"/>
    <Command ID="0x485" Name="RETURN_TRUE"/>
    <Command ID="0x59A" Name="RETURN_FALSE"/>
"""

FOOTER = """  </Commands>
</GTA3Script>
"""


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 1

    source = os.path.join(sys.argv[1], "(for developers)", "gta3script", "cleo.xml")
    if not os.path.isfile(source):
        print("cleo.xml not found at %s" % source)
        return 1

    with open(source, encoding="utf-8", errors="replace") as handle:
        data = handle.read()

    blocks = []
    for command_id in WANTED:
        match = re.search(
            r'[ \t]*<Command ID="%s"[^>]*(?:/>|>.*?</Command>)' % command_id, data, re.S
        )
        if not match:
            print("missing opcode %s in %s" % (command_id, source))
            return 1
        blocks.append(match.group(0).rstrip())

    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cleo_plus_commands.xml")
    with open(out, "w", newline="\n") as handle:
        handle.write(HEADER + "\n".join(blocks) + "\n" + EXTRA_COMMANDS + FOOTER)

    print("wrote %s with %d commands" % (out, len(blocks)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
