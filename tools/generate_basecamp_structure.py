#!/usr/bin/env python3
import gzip
import math
import os
import struct
from collections import OrderedDict

WIDTH, HEIGHT, DEPTH = 29, 11, 29
CENTER = WIDTH // 2
DATA_VERSION = 4790
OUTPUT = "src/main/resources/data/njw_after_the_end/structure/basecamp/basecamp_01.nbt"
blocks = {}

def put(x, y, z, name, properties=None, nbt=None):
    if not (0 <= x < WIDTH and 0 <= y < HEIGHT and 0 <= z < DEPTH): raise ValueError(f"Block outside structure: {(x, y, z)}")
    blocks[(x, y, z)] = (name, properties or {}, nbt)

def distance(x, z): return math.hypot(x - CENTER, z - CENTER)

def radial_facing(dx, dz):
    if abs(dx) >= abs(dz): return "east" if dx >= 0 else "west"
    return "south" if dz >= 0 else "north"

def weathered_stone(x, z):
    value = (x * 37 + z * 19 + x * z * 3) % 53
    if value == 0: return "minecraft:mossy_stone_bricks"
    if value in (1, 2): return "minecraft:cracked_stone_bricks"
    return "minecraft:stone_bricks"

def stair_props(facing, half="bottom"): return {"facing": facing, "half": half, "shape": "straight", "waterlogged": "false"}

def slab_props(): return {"type": "bottom", "waterlogged": "false"}

def foundation():
    for x in range(WIDTH):
        for z in range(DEPTH):
            dx, dz, r = x - CENTER, z - CENTER, distance(x, z)
            facing = radial_facing(dx, dz)
            if r <= 12.35: put(x, 0, z, weathered_stone(x, z))
            if r <= 11.05: put(x, 1, z, "minecraft:polished_andesite" if (x * 3 + z) % 11 else "minecraft:stone_bricks")
            elif r <= 12.0: put(x, 1, z, "minecraft:stone_brick_stairs", stair_props(facing))
            if r <= 8.95: put(x, 2, z, "minecraft:smooth_stone" if (x + z) % 9 else "minecraft:polished_andesite")
            elif r <= 10.0: put(x, 2, z, "minecraft:stone_brick_stairs", stair_props(facing))
    for dx, dz in ((0, -8), (6, -6), (8, 0), (6, 6), (0, 8), (-6, 6), (-8, 0), (-6, -6)):
        put(CENTER + dx, 2, CENTER + dz, "minecraft:chiseled_stone_bricks")

def approaches():
    slab = slab_props()
    for offset in (-1, 0, 1):
        for d in (10, 11, 12, 13, 14):
            for x, z in ((CENTER + offset, CENTER - d), (CENTER + offset, CENTER + d), (CENTER - d, CENTER + offset), (CENTER + d, CENTER + offset)):
                put(x, 1, z, "minecraft:stone_bricks")
        put(CENTER + offset, 1, 0, "minecraft:smooth_stone_slab", slab)
        put(CENTER + offset, 1, DEPTH - 1, "minecraft:smooth_stone_slab", slab)
        put(0, 1, CENTER + offset, "minecraft:smooth_stone_slab", slab)
        put(WIDTH - 1, 1, CENTER + offset, "minecraft:smooth_stone_slab", slab)
    for offset in (-1, 0, 1):
        put(CENTER + offset, 2, CENTER - 9, "minecraft:stone_brick_stairs", stair_props("south"))
        put(CENTER + offset, 2, CENTER + 9, "minecraft:stone_brick_stairs", stair_props("north"))
        put(CENTER - 9, 2, CENTER + offset, "minecraft:stone_brick_stairs", stair_props("east"))
        put(CENTER + 9, 2, CENTER + offset, "minecraft:stone_brick_stairs", stair_props("west"))

def point_segment_distance(px, pz, ax, az, bx, bz):
    vx, vz, wx, wz = bx - ax, bz - az, px - ax, pz - az
    c1, c2 = vx * wx + vz * wz, vx * vx + vz * vz
    if c2 == 0: return math.hypot(px - ax, pz - az)
    t = max(0.0, min(1.0, c1 / c2))
    return math.hypot(px - (ax + t * vx), pz - (az + t * vz))

def on_triangle(dx, dz):
    vertices = ((0, -4.7), (-4.1, 3.1), (4.1, 3.1))
    return min(point_segment_distance(dx, dz, *vertices[0], *vertices[1]), point_segment_distance(dx, dz, *vertices[1], *vertices[2]), point_segment_distance(dx, dz, *vertices[2], *vertices[0])) <= 0.42

def central_altar():
    for x in range(CENTER - 8, CENTER + 9):
        for z in range(CENTER - 8, CENTER + 9):
            dx, dz, r = x - CENTER, z - CENTER, distance(x, z)
            if 6.25 < r <= 7.25: put(x, 2, z, "minecraft:stone_brick_stairs", stair_props(radial_facing(dx, dz)))
            if r <= 6.3: put(x, 3, z, "minecraft:smooth_stone")
    for x in range(CENTER - 7, CENTER + 8):
        for z in range(CENTER - 7, CENTER + 8):
            dx, dz, r = x - CENTER, z - CENTER, distance(x, z)
            if r > 6.15: continue
            facing = radial_facing(dx, dz)
            name = None
            if 5.45 <= r <= 5.95 and (abs(dx) <= 1 or abs(dz) <= 1 or abs(abs(dx) - abs(dz)) <= 1): name = "minecraft:light_gray_glazed_terracotta"
            if on_triangle(dx, dz): name = "minecraft:yellow_glazed_terracotta"
            if 1.75 <= r <= 2.35 and (abs(dx) <= 1 or abs(dz) <= 1 or abs(abs(dx) - abs(dz)) <= 1): name = "minecraft:blue_glazed_terracotta"
            if r <= 0.8: name = "minecraft:white_glazed_terracotta"
            if dx == 0 and dz == 0: name = "minecraft:yellow_glazed_terracotta"
            if name: put(x, 3, z, name, {"facing": facing})
    for dx, dz in ((0, -6), (4, -4), (6, 0), (4, 4), (0, 6), (-4, 4), (-6, 0), (-4, -4)):
        put(CENTER + dx, 3, CENTER + dz, "minecraft:chiseled_stone_bricks")

def pillar(px, pz, inward_x, inward_z):
    for x in range(px - 2, px + 3):
        for z in range(pz - 2, pz + 3):
            if abs(x - px) == 2 and abs(z - pz) == 2: continue
            put(x, 0, z, "minecraft:stone_bricks")
            put(x, 1, z, "minecraft:polished_andesite" if abs(x - px) <= 1 and abs(z - pz) <= 1 else "minecraft:stone_bricks")
    for x in range(px - 1, px + 2):
        for z in range(pz - 1, pz + 2): put(x, 2, z, "minecraft:chiseled_stone_bricks" if x == px and z == pz else "minecraft:stone_bricks")
    for y in (3, 4, 5):
        for x in range(px - 1, px + 2):
            for z in range(pz - 1, pz + 2):
                if y >= 4 and abs(x - px) == 1 and abs(z - pz) == 1: continue
                put(x, y, z, "minecraft:polished_andesite" if y == 4 else "minecraft:stone_bricks")
    for (x, z), facing in {(px, pz - 1): "north", (px + 1, pz): "east", (px, pz + 1): "south", (px - 1, pz): "west"}.items(): put(x, 6, z, "minecraft:stone_brick_stairs", stair_props(facing))
    put(px, 6, pz, "minecraft:chiseled_stone_bricks")
    put(px, 7, pz, "minecraft:polished_andesite")
    put(px, 8, pz, "minecraft:chiseled_stone_bricks")
    put(px, 9, pz, "minecraft:stone_brick_wall")
    rune_x, rune_z = px + inward_x, pz + inward_z
    put(rune_x, 2, rune_z, "minecraft:yellow_glazed_terracotta", {"facing": radial_facing(-inward_x, -inward_z)})

def pillars():
    pillar(CENTER - 8, CENTER - 8, 1, 1)
    pillar(CENTER + 8, CENTER - 8, -1, 1)
    pillar(CENTER - 8, CENTER + 8, 1, -1)
    pillar(CENTER + 8, CENTER + 8, -1, -1)

def rim_details():
    for dx, dz in ((0, -10), (10, 0), (0, 10), (-10, 0)):
        put(CENTER + dx, 1, CENTER + dz, "minecraft:chiseled_stone_bricks")

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
    foundation()
    approaches()
    central_altar()
    pillars()
    rim_details()
    write_structure()

if __name__ == "__main__": main()
