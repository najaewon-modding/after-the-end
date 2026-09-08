#!/usr/bin/env python3
import gzip
import os
import struct
from collections import OrderedDict

WIDTH, HEIGHT, DEPTH = 21, 10, 19
DATA_VERSION = 4790
OUTPUT = "src/main/resources/data/njw_after_the_end/structure/basecamp/basecamp_01.nbt"

blocks = {}

def put(x, y, z, name, properties=None, nbt=None):
    if not (0 <= x < WIDTH and 0 <= y < HEIGHT and 0 <= z < DEPTH):
        raise ValueError(f"Block outside structure: {(x, y, z)}")
    blocks[(x, y, z)] = (name, properties or {}, nbt)

def ground():
    coords = set()
    for x in range(3, 18):
        for z in range(3, 16):
            dx, dz = (x - 10) / 8.0, (z - 9) / 7.0
            if dx * dx + dz * dz < 1.0 and ((x * 17 + z * 31) % 11) not in (0, 1):
                coords.add((x, z))
    coords.update({
        (1, 8), (2, 6), (2, 12), (3, 2), (4, 16), (6, 1), (8, 17), (11, 1),
        (13, 17), (16, 2), (18, 5), (19, 9), (18, 14), (15, 16), (5, 17),
        (1, 10), (19, 11), (3, 15)
    })
    for x, z in sorted(coords):
        name = "minecraft:mossy_cobblestone" if (x * 13 + z * 7) % 9 in (0, 1) else "minecraft:cobblestone"
        put(x, 0, z, name)

def floor_and_frame():
    holes = {(6, 6), (7, 6), (13, 6), (14, 10), (6, 12), (11, 13)}
    for x in range(5, 16):
        for z in range(5, 14):
            if (x, z) not in holes and (x in (5, 15) or z in (5, 13) or (x + z) % 7 != 0):
                put(x, 1, z, "minecraft:spruce_planks")
    for x, z, height in ((5, 5, 5), (15, 5, 6), (5, 13, 4), (15, 13, 5), (10, 5, 6), (10, 13, 3)):
        for y in range(1, height + 1):
            put(x, y, z, "minecraft:stripped_spruce_log", {"axis": "y"})

def walls():
    for x in (6, 7, 8, 11, 12, 13, 14):
        for y in (2, 3):
            if (x, y) != (8, 3):
                put(x, y, 5, "minecraft:spruce_planks")
    for x in (6, 7, 8, 9, 12, 13, 14):
        for y in (2, 3):
            if (x, y) not in {(7, 3), (13, 3)}:
                put(x, y, 13, "minecraft:spruce_planks")
    for z in (6, 7, 10, 11, 12):
        for y in (2, 3):
            if (z, y) != (10, 3):
                put(5, y, z, "minecraft:spruce_planks")
    for z in (6, 7, 8, 11, 12):
        for y in (2, 3):
            if (z, y) != (8, 3):
                put(15, y, z, "minecraft:spruce_planks")
    for x in range(8, 13):
        put(x, 4, 5, "minecraft:spruce_log", {"axis": "x"})
    for x in range(6, 15):
        if x not in (9, 13):
            put(x, 4, 9, "minecraft:spruce_log", {"axis": "x"})
    for z in range(6, 13):
        if z not in (8, 11):
            put(10, 4, z, "minecraft:spruce_log", {"axis": "z"})

def roof():
    roof_z = list(range(5, 10)) + list(range(11, 14))
    west = {"facing": "west", "half": "bottom", "shape": "straight", "waterlogged": "false"}
    east = {"facing": "east", "half": "bottom", "shape": "straight", "waterlogged": "false"}
    for z in roof_z:
        for x in (5, 6, 7):
            if (x, z) not in {(6, 7), (7, 9)}:
                put(x, 5, z, "minecraft:spruce_stairs", west)
        for x in (13, 14, 15):
            if (x, z) not in {(14, 6), (13, 12)}:
                put(x, 5, z, "minecraft:spruce_stairs", east)
        for x in (8, 9):
            if (x, z) != (9, 8):
                put(x, 6, z, "minecraft:spruce_stairs", west)
        for x in (11, 12):
            if (x, z) != (11, 11):
                put(x, 6, z, "minecraft:spruce_stairs", east)
        if z not in (8, 12):
            put(10, 7, z, "minecraft:spruce_slab", {"type": "bottom", "waterlogged": "false"})
    for x, z, facing in ((3, 7, "south"), (4, 8, "east"), (17, 10, "north"), (16, 14, "west"), (8, 15, "south")):
        put(x, 1, z, "minecraft:spruce_stairs", {"facing": facing, "half": "bottom", "shape": "straight", "waterlogged": "false"})

def side_platform():
    for x in range(16, 19):
        for z in range(7, 12):
            if not (x == 18 and z in (7, 11)):
                put(x, 1, z, "minecraft:spruce_slab", {"type": "bottom", "waterlogged": "false"})
    fence = {"north": "false", "east": "false", "south": "false", "west": "false", "waterlogged": "false"}
    for x, z, height in ((18, 8, 3), (18, 11, 2), (16, 7, 3)):
        for y in range(1, height + 1):
            put(x, y, z, "minecraft:spruce_fence", fence)

def details():
    put(8, 2, 9, "minecraft:barrel", {"facing": "up", "open": "false"})
    put(8, 2, 10, "minecraft:barrel", {"facing": "up", "open": "false"})
    put(12, 2, 11, "minecraft:crafting_table")
    put(11, 2, 8, "minecraft:campfire", {"facing": "north", "lit": "false", "signal_fire": "false", "waterlogged": "false"})
    put(7, 2, 11, "minecraft:lantern", {"hanging": "false", "waterlogged": "false"})
    for x, z in ((6, 6), (13, 6), (14, 10), (6, 12)):
        put(x, 1, z, "minecraft:mossy_cobblestone")

TAG_END, TAG_BYTE, TAG_SHORT, TAG_INT, TAG_LONG, TAG_FLOAT, TAG_DOUBLE, TAG_BYTE_ARRAY, TAG_STRING, TAG_LIST, TAG_COMPOUND, TAG_INT_ARRAY, TAG_LONG_ARRAY = range(13)

def utf8(value):
    raw = value.encode("utf-8")
    return struct.pack(">H", len(raw)) + raw

def payload(tag_type, value):
    if tag_type == TAG_INT:
        return struct.pack(">i", value)
    if tag_type == TAG_STRING:
        return utf8(value)
    if tag_type == TAG_LIST:
        child_type, values = value
        return bytes([child_type]) + struct.pack(">i", len(values)) + b"".join(payload(child_type, item) for item in values)
    if tag_type == TAG_COMPOUND:
        return compound_payload(value)
    raise ValueError(f"Unsupported tag type: {tag_type}")

def named(tag_type, name, value):
    return bytes([tag_type]) + utf8(name) + payload(tag_type, value)

def compound_payload(values):
    out = bytearray()
    for name, (tag_type, value) in values.items():
        out.extend(named(tag_type, name, value))
    out.append(TAG_END)
    return bytes(out)

def palette_and_states():
    palette = []
    index = {}
    state_for_pos = {}
    for pos, (name, properties, nbt) in sorted(blocks.items(), key=lambda item: (item[0][1], item[0][2], item[0][0])):
        key = (name, tuple(sorted(properties.items())))
        if key not in index:
            index[key] = len(palette)
            entry = OrderedDict()
            entry["Name"] = (TAG_STRING, name)
            if properties:
                entry["Properties"] = (TAG_COMPOUND, OrderedDict((k, (TAG_STRING, v)) for k, v in sorted(properties.items())))
            palette.append(entry)
        state_for_pos[pos] = (index[key], nbt)
    return palette, state_for_pos

def write_structure():
    palette, states = palette_and_states()
    block_entries = []
    for (x, y, z), (state, block_nbt) in sorted(states.items(), key=lambda item: (item[0][1], item[0][2], item[0][0])):
        entry = OrderedDict()
        entry["pos"] = (TAG_LIST, (TAG_INT, [x, y, z]))
        entry["state"] = (TAG_INT, state)
        if block_nbt:
            entry["nbt"] = (TAG_COMPOUND, block_nbt)
        block_entries.append(entry)
    root = OrderedDict([
        ("DataVersion", (TAG_INT, DATA_VERSION)),
        ("size", (TAG_LIST, (TAG_INT, [WIDTH, HEIGHT, DEPTH]))),
        ("palette", (TAG_LIST, (TAG_COMPOUND, palette))),
        ("blocks", (TAG_LIST, (TAG_COMPOUND, block_entries))),
        ("entities", (TAG_LIST, (TAG_COMPOUND, []))),
    ])
    raw = named(TAG_COMPOUND, "", root)
    os.makedirs(os.path.dirname(OUTPUT), exist_ok=True)
    with open(OUTPUT, "wb") as file:
        with gzip.GzipFile(fileobj=file, mode="wb", mtime=0) as zipped:
            zipped.write(raw)
    print(f"Generated {OUTPUT}")
    print(f"Size: {WIDTH}x{HEIGHT}x{DEPTH}")
    print(f"Blocks: {len(block_entries)}")
    print(f"Palette states: {len(palette)}")
    print(f"Compressed bytes: {os.path.getsize(OUTPUT)}")

def main():
    ground()
    floor_and_frame()
    walls()
    roof()
    side_platform()
    details()
    write_structure()

if __name__ == "__main__":
    main()
