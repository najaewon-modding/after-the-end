#!/usr/bin/env python3
import gzip
import math
import os
import struct
from collections import OrderedDict

WIDTH, HEIGHT, DEPTH = 27, 9, 27
CENTER = WIDTH // 2
DATA_VERSION = 4790
OUTPUT = "src/main/resources/data/njw_after_the_end/structure/basecamp/basecamp_01.nbt"

blocks = {}

def put(x, y, z, name, properties=None, nbt=None):
    if not (0 <= x < WIDTH and 0 <= y < HEIGHT and 0 <= z < DEPTH):
        raise ValueError(f"Block outside structure: {(x, y, z)}")
    blocks[(x, y, z)] = (name, properties or {}, nbt)

def distance(x, z):
    return math.hypot(x - CENTER, z - CENTER)

def radial_facing(dx, dz):
    if abs(dx) >= abs(dz): return "east" if dx >= 0 else "west"
    return "south" if dz >= 0 else "north"

def weathered_stone(x, z):
    value = (x * 37 + z * 19 + x * z * 3) % 29
    if value in (0, 1): return "minecraft:mossy_stone_bricks"
    if value in (2, 3, 4): return "minecraft:cracked_stone_bricks"
    return "minecraft:stone_bricks"

def foundation():
    for x in range(WIDTH):
        for z in range(DEPTH):
            r = distance(x, z)
            if r <= 11.6:
                put(x, 0, z, weathered_stone(x, z))
            if r <= 10.0:
                put(x, 1, z, "minecraft:polished_andesite" if (x + z) % 7 else "minecraft:stone_bricks")
            if 6.6 < r <= 8.7:
                name = "minecraft:chiseled_stone_bricks" if ((x - CENTER) == 0 or (z - CENTER) == 0 or abs(x - CENTER) == abs(z - CENTER)) and r > 7.5 else weathered_stone(x, z)
                put(x, 2, z, name)

def approaches():
    slab = {"type": "bottom", "waterlogged": "false"}
    for offset in (-1, 0, 1):
        for d in (11, 12):
            put(CENTER + offset, 1, CENTER - d, "minecraft:stone_bricks")
            put(CENTER + offset, 1, CENTER + d, "minecraft:stone_bricks")
            put(CENTER - d, 1, CENTER + offset, "minecraft:stone_bricks")
            put(CENTER + d, 1, CENTER + offset, "minecraft:stone_bricks")
        put(CENTER + offset, 1, 0, "minecraft:smooth_stone_slab", slab)
        put(CENTER + offset, 1, DEPTH - 1, "minecraft:smooth_stone_slab", slab)
        put(0, 1, CENTER + offset, "minecraft:smooth_stone_slab", slab)
        put(WIDTH - 1, 1, CENTER + offset, "minecraft:smooth_stone_slab", slab)

def magic_floor():
    for x in range(CENTER - 6, CENTER + 7):
        for z in range(CENTER - 6, CENTER + 7):
            dx, dz = x - CENTER, z - CENTER
            r = math.hypot(dx, dz)
            if r > 6.35: continue
            facing = radial_facing(dx, dz)
            props = {"facing": facing}
            if dx == 0 and dz == 0:
                put(x, 2, z, "minecraft:yellow_glazed_terracotta", props)
            elif r <= 1.6:
                put(x, 2, z, "minecraft:blue_glazed_terracotta", props)
            elif 2.35 <= r <= 3.25:
                put(x, 2, z, "minecraft:yellow_glazed_terracotta", props)
            elif 5.0 <= r <= 6.2:
                put(x, 2, z, "minecraft:cyan_glazed_terracotta", props)
            elif (dx == 0 or dz == 0) and r <= 5.0:
                put(x, 2, z, "minecraft:white_glazed_terracotta", props)
            elif abs(abs(dx) - abs(dz)) == 0 and 1 <= abs(dx) <= 4:
                put(x, 2, z, "minecraft:light_blue_glazed_terracotta", props)
            else:
                put(x, 2, z, "minecraft:smooth_stone")
    for dx, dz in ((0, -7), (5, -5), (7, 0), (5, 5), (0, 7), (-5, 5), (-7, 0), (-5, -5)):
        put(CENTER + dx, 2, CENTER + dz, "minecraft:chiseled_stone_bricks")

def pillar(px, pz):
    for x in range(px - 1, px + 2):
        for z in range(pz - 1, pz + 2):
            put(x, 0, z, "minecraft:stone_bricks")
            put(x, 1, z, "minecraft:polished_andesite")
            put(x, 2, z, "minecraft:chiseled_stone_bricks" if x == px and z == pz else "minecraft:stone_bricks")
    stairs = {
        (px, pz - 1): "north",
        (px + 1, pz): "east",
        (px, pz + 1): "south",
        (px - 1, pz): "west",
    }
    put(px, 3, pz, "minecraft:chiseled_stone_bricks")
    for (x, z), facing in stairs.items():
        put(x, 3, z, "minecraft:stone_brick_stairs", {"facing": facing, "half": "bottom", "shape": "straight", "waterlogged": "false"})
    put(px, 4, pz, "minecraft:polished_andesite")
    put(px, 5, pz, "minecraft:chiseled_stone_bricks")
    put(px, 6, pz, "minecraft:stone_bricks")
    put(px, 7, pz, "minecraft:smooth_stone_slab", {"type": "bottom", "waterlogged": "false"})

def pillars():
    for px, pz in ((CENTER - 9, CENTER - 9), (CENTER + 9, CENTER - 9), (CENTER - 9, CENTER + 9), (CENTER + 9, CENTER + 9)):
        pillar(px, pz)

def rim_details():
    for dx, dz in ((0, -10), (10, 0), (0, 10), (-10, 0)):
        put(CENTER + dx, 2, CENTER + dz, "minecraft:chiseled_stone_bricks")
    for dx, dz in ((3, -9), (9, 3), (-3, 9), (-9, -3)):
        put(CENTER + dx, 2, CENTER + dz, "minecraft:polished_andesite")

TAG_END, TAG_BYTE, TAG_SHORT, TAG_INT, TAG_LONG, TAG_FLOAT, TAG_DOUBLE, TAG_BYTE_ARRAY, TAG_STRING, TAG_LIST, TAG_COMPOUND, TAG_INT_ARRAY, TAG_LONG_ARRAY = range(13)

def utf8(value):
    raw = value.encode("utf-8")
    return struct.pack(">H", len(raw)) + raw

def payload(tag_type, value):
    if tag_type == TAG_INT: return struct.pack(">i", value)
    if tag_type == TAG_STRING: return utf8(value)
    if tag_type == TAG_LIST:
        child_type, values = value
        return bytes([child_type]) + struct.pack(">i", len(values)) + b"".join(payload(child_type, item) for item in values)
    if tag_type == TAG_COMPOUND: return compound_payload(value)
    raise ValueError(f"Unsupported tag type: {tag_type}")

def named(tag_type, name, value):
    return bytes([tag_type]) + utf8(name) + payload(tag_type, value)

def compound_payload(values):
    out = bytearray()
    for name, (tag_type, value) in values.items(): out.extend(named(tag_type, name, value))
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
            if properties: entry["Properties"] = (TAG_COMPOUND, OrderedDict((k, (TAG_STRING, v)) for k, v in sorted(properties.items())))
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
        if block_nbt: entry["nbt"] = (TAG_COMPOUND, block_nbt)
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
        with gzip.GzipFile(fileobj=file, mode="wb", mtime=0) as zipped: zipped.write(raw)
    print(f"Generated {OUTPUT}")
    print(f"Size: {WIDTH}x{HEIGHT}x{DEPTH}")
    print(f"Blocks: {len(block_entries)}")
    print(f"Palette states: {len(palette)}")
    print(f"Compressed bytes: {os.path.getsize(OUTPUT)}")

def main():
    foundation()
    approaches()
    magic_floor()
    pillars()
    rim_details()
    write_structure()

if __name__ == "__main__": main()
