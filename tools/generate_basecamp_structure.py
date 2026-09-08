#!/usr/bin/env python3
import gzip
import math
import os
import struct
from collections import OrderedDict

WIDTH, HEIGHT, DEPTH = 27, 10, 27
CENTER = WIDTH // 2
DATA_VERSION = 4790
OUTPUT_DIR = "src/main/resources/data/njw_after_the_end/structure/basecamp"
blocks = {}

VARIANTS = {
    "basecamp_01": {
        "base": "minecraft:stone_bricks", "aged": "minecraft:cracked_stone_bricks", "moss": "minecraft:mossy_stone_bricks", "floor": "minecraft:smooth_stone", "accent": "minecraft:polished_andesite", "stairs": "minecraft:stone_brick_stairs", "marker": "minecraft:chiseled_stone_bricks", "wall": "minecraft:stone_brick_wall", "outer": "minecraft:light_gray_glazed_terracotta", "triangle": "minecraft:yellow_glazed_terracotta", "inner": "minecraft:blue_glazed_terracotta", "core": "minecraft:white_glazed_terracotta", "rune": "minecraft:yellow_glazed_terracotta", "moss_rate": 67,
    },
    "basecamp_mossy": {
        "base": "minecraft:stone_bricks", "aged": "minecraft:cracked_stone_bricks", "moss": "minecraft:mossy_stone_bricks", "floor": "minecraft:mossy_stone_bricks", "accent": "minecraft:polished_andesite", "stairs": "minecraft:mossy_stone_brick_stairs", "marker": "minecraft:chiseled_stone_bricks", "wall": "minecraft:mossy_stone_brick_wall", "outer": "minecraft:green_glazed_terracotta", "triangle": "minecraft:lime_glazed_terracotta", "inner": "minecraft:cyan_glazed_terracotta", "core": "minecraft:white_glazed_terracotta", "rune": "minecraft:lime_glazed_terracotta", "moss_rate": 9,
    },
    "basecamp_deepslate": {
        "base": "minecraft:cobbled_deepslate", "aged": "minecraft:deepslate_bricks", "moss": "minecraft:cracked_deepslate_bricks", "floor": "minecraft:polished_deepslate", "accent": "minecraft:deepslate_tiles", "stairs": "minecraft:deepslate_brick_stairs", "marker": "minecraft:chiseled_deepslate", "wall": "minecraft:deepslate_brick_wall", "outer": "minecraft:black_glazed_terracotta", "triangle": "minecraft:purple_glazed_terracotta", "inner": "minecraft:blue_glazed_terracotta", "core": "minecraft:light_gray_glazed_terracotta", "rune": "minecraft:purple_glazed_terracotta", "moss_rate": 41,
    },
    "basecamp_red": {
        "base": "minecraft:stone_bricks", "aged": "minecraft:cracked_stone_bricks", "moss": "minecraft:mossy_stone_bricks", "floor": "minecraft:smooth_stone", "accent": "minecraft:polished_andesite", "stairs": "minecraft:stone_brick_stairs", "marker": "minecraft:chiseled_stone_bricks", "wall": "minecraft:stone_brick_wall", "outer": "minecraft:orange_glazed_terracotta", "triangle": "minecraft:red_glazed_terracotta", "inner": "minecraft:yellow_glazed_terracotta", "core": "minecraft:white_glazed_terracotta", "rune": "minecraft:orange_glazed_terracotta", "moss_rate": 67,
    },
    "basecamp_green": {
        "base": "minecraft:stone_bricks", "aged": "minecraft:cracked_stone_bricks", "moss": "minecraft:mossy_stone_bricks", "floor": "minecraft:smooth_stone", "accent": "minecraft:polished_andesite", "stairs": "minecraft:stone_brick_stairs", "marker": "minecraft:chiseled_stone_bricks", "wall": "minecraft:stone_brick_wall", "outer": "minecraft:lime_glazed_terracotta", "triangle": "minecraft:green_glazed_terracotta", "inner": "minecraft:cyan_glazed_terracotta", "core": "minecraft:white_glazed_terracotta", "rune": "minecraft:lime_glazed_terracotta", "moss_rate": 67,
    },
    "basecamp_purple": {
        "base": "minecraft:stone_bricks", "aged": "minecraft:cracked_stone_bricks", "moss": "minecraft:mossy_stone_bricks", "floor": "minecraft:smooth_stone", "accent": "minecraft:polished_andesite", "stairs": "minecraft:stone_brick_stairs", "marker": "minecraft:chiseled_stone_bricks", "wall": "minecraft:stone_brick_wall", "outer": "minecraft:magenta_glazed_terracotta", "triangle": "minecraft:purple_glazed_terracotta", "inner": "minecraft:blue_glazed_terracotta", "core": "minecraft:white_glazed_terracotta", "rune": "minecraft:magenta_glazed_terracotta", "moss_rate": 67,
    },
}

def put(x, y, z, name, properties=None, nbt=None):
    if not (0 <= x < WIDTH and 0 <= y < HEIGHT and 0 <= z < DEPTH): raise ValueError(f"Block outside structure: {(x, y, z)}")
    blocks[(x, y, z)] = (name, properties or {}, nbt)

def dist(x, z): return math.hypot(x - CENTER, z - CENTER)
def stair(facing): return {"facing": facing, "half": "bottom", "shape": "straight", "waterlogged": "false"}
def slab(): return {"type": "bottom", "waterlogged": "false"}
def radial_facing(dx, dz):
    if abs(dx) >= abs(dz): return "east" if dx >= 0 else "west"
    return "south" if dz >= 0 else "north"

def weathered(x, z, cfg):
    v = (x * 31 + z * 17 + x * z * 5) % cfg["moss_rate"]
    if v == 0: return cfg["moss"]
    if v in (1, 2): return cfg["aged"]
    return cfg["base"]

def base_disc(cfg):
    for x in range(WIDTH):
        for z in range(DEPTH):
            dx, dz, r = x - CENTER, z - CENTER, dist(x, z)
            if r <= 10.9: put(x, 0, z, weathered(x, z, cfg))
            if r <= 9.25: put(x, 1, z, cfg["floor"] if (x + z) % 8 else cfg["accent"])
            elif r <= 10.15: put(x, 1, z, cfg["stairs"], stair(radial_facing(dx, dz)))
    for dx, dz in ((0, -9), (6, -6), (9, 0), (6, 6), (0, 9), (-6, 6), (-9, 0), (-6, -6)): put(CENTER + dx, 1, CENTER + dz, cfg["marker"])

def approaches(cfg):
    for offset in (-1, 0, 1):
        for d in (10, 11, 12, 13):
            for x, z in ((CENTER + offset, CENTER - d), (CENTER + offset, CENTER + d), (CENTER - d, CENTER + offset), (CENTER + d, CENTER + offset)): put(x, 0, z, cfg["base"])
        put(CENTER + offset, 1, 1, cfg["floor"] if "slab" in cfg["floor"] else "minecraft:smooth_stone_slab", slab())
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

def magic_altar(cfg):
    for x in range(CENTER - 7, CENTER + 8):
        for z in range(CENTER - 7, CENTER + 8):
            dx, dz, r = x - CENTER, z - CENTER, dist(x, z)
            if 6.4 < r <= 7.15: put(x, 1, z, cfg["stairs"], stair(radial_facing(dx, dz)))
            if r <= 6.45: put(x, 2, z, cfg["floor"])
    for offset in (-1, 0, 1):
        put(CENTER + offset, 2, CENTER - 7, cfg["stairs"], stair("south")); put(CENTER + offset, 2, CENTER + 7, cfg["stairs"], stair("north")); put(CENTER - 7, 2, CENTER + offset, cfg["stairs"], stair("east")); put(CENTER + 7, 2, CENTER + offset, cfg["stairs"], stair("west"))
    for x in range(CENTER - 7, CENTER + 8):
        for z in range(CENTER - 7, CENTER + 8):
            dx, dz, r = x - CENTER, z - CENTER, dist(x, z)
            if r > 6.75: continue
            name = None
            if 5.65 <= r <= 6.45: name = cfg["outer"]
            if on_triangle(dx, dz): name = cfg["triangle"]
            if 2.15 <= r <= 2.75: name = cfg["inner"]
            if r <= 1.15: name = cfg["core"]
            if dx == 0 and dz == 0: name = cfg["rune"]
            if name: put(x, 2, z, name, {"facing": radial_facing(dx, dz)})
    for dx, dz in ((0, -7), (5, -5), (7, 0), (5, 5), (0, 7), (-5, 5), (-7, 0), (-5, -5)):
        put(CENTER + dx, 2, CENTER + dz, cfg["marker"])
        ix, iz = int(round(dx * 0.82)), int(round(dz * 0.82))
        put(CENTER + ix, 2, CENTER + iz, cfg["rune"], {"facing": radial_facing(ix, iz)})

def pillar(px, pz, inward_x, inward_z, cfg):
    for x in range(px - 2, px + 3):
        for z in range(pz - 2, pz + 3):
            if abs(x - px) == 2 and abs(z - pz) == 2: continue
            put(x, 0, z, cfg["base"])
    for x in range(px - 1, px + 2):
        for z in range(pz - 1, pz + 2):
            put(x, 1, z, cfg["accent"] if x == px and z == pz else cfg["base"]); put(x, 2, z, cfg["marker"] if x == px and z == pz else cfg["base"])
    for x in range(px - 1, px + 2):
        for z in range(pz - 1, pz + 2):
            if abs(x - px) == 1 and abs(z - pz) == 1: continue
            put(x, 3, z, cfg["accent"])
    for (x, z), facing in {(px, pz - 1): "north", (px + 1, pz): "east", (px, pz + 1): "south", (px - 1, pz): "west"}.items(): put(x, 4, z, cfg["stairs"], stair(facing))
    put(px, 4, pz, cfg["marker"]); put(px, 5, pz, cfg["accent"]); put(px, 6, pz, cfg["marker"]); put(px, 7, pz, cfg["wall"])
    put(px + inward_x, 1, pz + inward_z, cfg["rune"], {"facing": radial_facing(-inward_x, -inward_z)})

def pillars(cfg):
    pillar(CENTER - 9, CENTER - 9, 1, 1, cfg); pillar(CENTER + 9, CENTER - 9, -1, 1, cfg); pillar(CENTER - 9, CENTER + 9, 1, -1, cfg); pillar(CENTER + 9, CENTER + 9, -1, -1, cfg)

TAG_END, TAG_BYTE, TAG_SHORT, TAG_INT, TAG_LONG, TAG_FLOAT, TAG_DOUBLE, TAG_BYTE_ARRAY, TAG_STRING, TAG_LIST, TAG_COMPOUND, TAG_INT_ARRAY, TAG_LONG_ARRAY = range(13)
def utf8(value):
    raw = value.encode("utf-8"); return struct.pack(">H", len(raw)) + raw
def payload(tag_type, value):
    if tag_type == TAG_INT: return struct.pack(">i", value)
    if tag_type == TAG_STRING: return utf8(value)
    if tag_type == TAG_LIST:
        child_type, values = value; return bytes([child_type]) + struct.pack(">i", len(values)) + b"".join(payload(child_type, item) for item in values)
    if tag_type == TAG_COMPOUND: return compound_payload(value)
    raise ValueError(f"Unsupported tag type: {tag_type}")
def named(tag_type, name, value): return bytes([tag_type]) + utf8(name) + payload(tag_type, value)
def compound_payload(values):
    out = bytearray()
    for name, (tag_type, value) in values.items(): out.extend(named(tag_type, name, value))
    out.append(TAG_END); return bytes(out)
def palette_and_states():
    palette, index, state_for_pos = [], {}, {}
    for pos, (name, properties, nbt) in sorted(blocks.items(), key=lambda item: (item[0][1], item[0][2], item[0][0])):
        key = (name, tuple(sorted(properties.items())))
        if key not in index:
            index[key] = len(palette); entry = OrderedDict(); entry["Name"] = (TAG_STRING, name)
            if properties: entry["Properties"] = (TAG_COMPOUND, OrderedDict((k, (TAG_STRING, v)) for k, v in sorted(properties.items())))
            palette.append(entry)
        state_for_pos[pos] = (index[key], nbt)
    return palette, state_for_pos

def write_structure(name):
    palette, states = palette_and_states(); block_entries = []
    for (x, y, z), (state, block_nbt) in sorted(states.items(), key=lambda item: (item[0][1], item[0][2], item[0][0])):
        entry = OrderedDict([("pos", (TAG_LIST, (TAG_INT, [x, y, z]))), ("state", (TAG_INT, state))])
        if block_nbt: entry["nbt"] = (TAG_COMPOUND, block_nbt)
        block_entries.append(entry)
    root = OrderedDict([("DataVersion", (TAG_INT, DATA_VERSION)), ("size", (TAG_LIST, (TAG_INT, [WIDTH, HEIGHT, DEPTH]))), ("palette", (TAG_LIST, (TAG_COMPOUND, palette))), ("blocks", (TAG_LIST, (TAG_COMPOUND, block_entries))), ("entities", (TAG_LIST, (TAG_COMPOUND, [])))])
    raw = named(TAG_COMPOUND, "", root); os.makedirs(OUTPUT_DIR, exist_ok=True); output = os.path.join(OUTPUT_DIR, f"{name}.nbt")
    with open(output, "wb") as file:
        with gzip.GzipFile(fileobj=file, mode="wb", mtime=0) as zipped: zipped.write(raw)
    print(f"Generated {output}: {len(block_entries)} blocks, {len(palette)} states, {os.path.getsize(output)} bytes")

def main():
    for name, cfg in VARIANTS.items():
        blocks.clear(); base_disc(cfg); approaches(cfg); magic_altar(cfg); pillars(cfg); write_structure(name)

if __name__ == "__main__": main()
