#!/usr/bin/env python3
import gzip
import math
import os
import struct
from collections import OrderedDict

WIDTH, HEIGHT, DEPTH = 27, 10, 27
CENTER = WIDTH // 2
DATA_VERSION = 4790
OUTPUT = "src/main/resources/data/njw_after_the_end/structure/basecamp/basecamp_01.nbt"
blocks = {}

def put(x, y, z, name, properties=None, nbt=None):
    if not (0 <= x < WIDTH and 0 <= y < HEIGHT and 0 <= z < DEPTH): raise ValueError(f"Block outside structure: {(x, y, z)}")
    blocks[(x, y, z)] = (name, properties or {}, nbt)

def dist(x, z): return math.hypot(x - CENTER, z - CENTER)
def stair(facing): return {"facing": facing, "half": "bottom", "shape": "straight", "waterlogged": "false"}
def slab(): return {"type": "bottom", "waterlogged": "false"}

def radial_facing(dx, dz):
    if abs(dx) >= abs(dz): return "east" if dx >= 0 else "west"
    return "south" if dz >= 0 else "north"

def weathered(x, z):
    v = (x * 31 + z * 17 + x * z * 5) % 67
    if v == 0: return "minecraft:mossy_stone_bricks"
    if v in (1, 2): return "minecraft:cracked_stone_bricks"
    return "minecraft:stone_bricks"

def base_disc():
    for x in range(WIDTH):
        for z in range(DEPTH):
            dx, dz, r = x - CENTER, z - CENTER, dist(x, z)
            if r <= 10.9: put(x, 0, z, weathered(x, z))
            if r <= 9.25:
                put(x, 1, z, "minecraft:smooth_stone" if (x + z) % 8 else "minecraft:polished_andesite")
            elif r <= 10.15:
                put(x, 1, z, "minecraft:stone_brick_stairs", stair(radial_facing(dx, dz)))
    for dx, dz in ((0, -9), (6, -6), (9, 0), (6, 6), (0, 9), (-6, 6), (-9, 0), (-6, -6)):
        put(CENTER + dx, 1, CENTER + dz, "minecraft:chiseled_stone_bricks")

def approaches():
    for offset in (-1, 0, 1):
        for d in (10, 11, 12, 13):
            for x, z in ((CENTER + offset, CENTER - d), (CENTER + offset, CENTER + d), (CENTER - d, CENTER + offset), (CENTER + d, CENTER + offset)):
                put(x, 0, z, "minecraft:stone_bricks")
        put(CENTER + offset, 1, 1, "minecraft:smooth_stone_slab", slab())
        put(CENTER + offset, 1, DEPTH - 2, "minecraft:smooth_stone_slab", slab())
        put(1, 1, CENTER + offset, "minecraft:smooth_stone_slab", slab())
        put(WIDTH - 2, 1, CENTER + offset, "minecraft:smooth_stone_slab", slab())

def segment_distance(px, pz, ax, az, bx, bz):
    vx, vz, wx, wz = bx - ax, bz - az, px - ax, pz - az
    d = vx * vx + vz * vz
    if d == 0: return math.hypot(px - ax, pz - az)
    t = max(0.0, min(1.0, (vx * wx + vz * wz) / d))
    return math.hypot(px - (ax + t * vx), pz - (az + t * vz))

def on_triangle(dx, dz):
    a, b, c = (0, -4.3), (-3.8, 3.0), (3.8, 3.0)
    return min(segment_distance(dx, dz, *a, *b), segment_distance(dx, dz, *b, *c), segment_distance(dx, dz, *c, *a)) <= 0.38

def magic_altar():
    for x in range(CENTER - 7, CENTER + 8):
        for z in range(CENTER - 7, CENTER + 8):
            dx, dz, r = x - CENTER, z - CENTER, dist(x, z)
            if 6.4 < r <= 7.15: put(x, 1, z, "minecraft:stone_brick_stairs", stair(radial_facing(dx, dz)))
            if r <= 6.45: put(x, 2, z, "minecraft:smooth_stone")
    outer_runes = ((0, -6), (3, -5), (5, -3), (6, 0), (5, 3), (3, 5), (0, 6), (-3, 5), (-5, 3), (-6, 0), (-5, -3), (-3, -5))
    for dx, dz in outer_runes:
        put(CENTER + dx, 2, CENTER + dz, "minecraft:light_gray_glazed_terracotta", {"facing": radial_facing(dx, dz)})
    for x in range(CENTER - 5, CENTER + 6):
        for z in range(CENTER - 5, CENTER + 6):
            dx, dz, r = x - CENTER, z - CENTER, dist(x, z)
            facing = radial_facing(dx, dz)
            if on_triangle(dx, dz): put(x, 2, z, "minecraft:yellow_glazed_terracotta", {"facing": facing})
            elif 2.1 <= r <= 2.6 and (abs(dx) <= 1 or abs(dz) <= 1 or abs(abs(dx) - abs(dz)) <= 1): put(x, 2, z, "minecraft:blue_glazed_terracotta", {"facing": facing})
    for dx, dz in ((0, -4), (4, 0), (0, 4), (-4, 0)):
        put(CENTER + dx, 2, CENTER + dz, "minecraft:chiseled_stone_bricks")
    put(CENTER, 2, CENTER, "minecraft:yellow_glazed_terracotta", {"facing": "north"})
    for dx, dz in ((0, -1), (1, 0), (0, 1), (-1, 0)):
        put(CENTER + dx, 2, CENTER + dz, "minecraft:white_glazed_terracotta", {"facing": radial_facing(dx, dz)})

def pillar(px, pz, inward_x, inward_z):
    for x in range(px - 2, px + 3):
        for z in range(pz - 2, pz + 3):
            if abs(x - px) == 2 and abs(z - pz) == 2: continue
            put(x, 0, z, "minecraft:stone_bricks")
    for x in range(px - 1, px + 2):
        for z in range(pz - 1, pz + 2):
            put(x, 1, z, "minecraft:polished_andesite" if x == px and z == pz else "minecraft:stone_bricks")
            put(x, 2, z, "minecraft:chiseled_stone_bricks" if x == px and z == pz else "minecraft:stone_bricks")
    for x in range(px - 1, px + 2):
        for z in range(pz - 1, pz + 2):
            if abs(x - px) == 1 and abs(z - pz) == 1: continue
            put(x, 3, z, "minecraft:polished_andesite")
    for (x, z), facing in {(px, pz - 1): "north", (px + 1, pz): "east", (px, pz + 1): "south", (px - 1, pz): "west"}.items():
        put(x, 4, z, "minecraft:stone_brick_stairs", stair(facing))
    put(px, 4, pz, "minecraft:chiseled_stone_bricks")
    put(px, 5, pz, "minecraft:polished_andesite")
    put(px, 6, pz, "minecraft:chiseled_stone_bricks")
    put(px, 7, pz, "minecraft:stone_brick_wall")
    rune_x, rune_z = px + inward_x, pz + inward_z
    put(rune_x, 1, rune_z, "minecraft:yellow_glazed_terracotta", {"facing": radial_facing(-inward_x, -inward_z)})

def pillars():
    pillar(CENTER - 9, CENTER - 9, 1, 1)
    pillar(CENTER + 9, CENTER - 9, -1, 1)
    pillar(CENTER - 9, CENTER + 9, 1, -1)
    pillar(CENTER + 9, CENTER + 9, -1, -1)

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

def named(tag_type, name, value): return bytes([tag_type]) + utf8(name) + payload(tag_type, value)

def compound_payload(values):
    out = bytearray()
    for name, (tag_type, value) in values.items(): out.extend(named(tag_type, name, value))
    out.append(TAG_END)
    return bytes(out)

def palette_and_states():
    palette, index, state_for_pos = [], {}, {}
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
        entry = OrderedDict([("pos", (TAG_LIST, (TAG_INT, [x, y, z]))), ("state", (TAG_INT, state))])
        if block_nbt: entry["nbt"] = (TAG_COMPOUND, block_nbt)
        block_entries.append(entry)
    root = OrderedDict([("DataVersion", (TAG_INT, DATA_VERSION)), ("size", (TAG_LIST, (TAG_INT, [WIDTH, HEIGHT, DEPTH]))), ("palette", (TAG_LIST, (TAG_COMPOUND, palette))), ("blocks", (TAG_LIST, (TAG_COMPOUND, block_entries))), ("entities", (TAG_LIST, (TAG_COMPOUND, [])))])
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
    base_disc()
    approaches()
    magic_altar()
    pillars()
    write_structure()

if __name__ == "__main__": main()
