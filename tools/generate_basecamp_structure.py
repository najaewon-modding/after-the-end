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

STONE = {
    "base": "minecraft:stone_bricks",
    "aged": "minecraft:cracked_stone_bricks",
    "moss": "minecraft:mossy_stone_bricks",
    "floor": "minecraft:smooth_stone",
    "accent": "minecraft:polished_andesite",
    "stairs": "minecraft:stone_brick_stairs",
    "moss_stairs": "minecraft:mossy_stone_brick_stairs",
    "marker": "minecraft:chiseled_stone_bricks",
    "wall": "minecraft:stone_brick_wall",
    "moss_wall": "minecraft:mossy_stone_brick_wall",
}

VARIANTS = {
    "basecamp_01": {**STONE, "style": "classic", "outer": "minecraft:light_gray_glazed_terracotta", "primary": "minecraft:yellow_glazed_terracotta", "secondary": "minecraft:blue_glazed_terracotta", "core": "minecraft:white_glazed_terracotta", "rune": "minecraft:yellow_glazed_terracotta", "point": "minecraft:yellow_glazed_terracotta", "aged_level": 0},
    "basecamp_mossy": {**STONE, "style": "classic", "outer": "minecraft:light_gray_glazed_terracotta", "primary": "minecraft:green_glazed_terracotta", "secondary": "minecraft:cyan_glazed_terracotta", "core": "minecraft:white_glazed_terracotta", "rune": "minecraft:lime_glazed_terracotta", "point": "minecraft:lime_glazed_terracotta", "aged_level": 2},
    "basecamp_red": {**STONE, "style": "classic_red", "outer": "minecraft:red_glazed_terracotta", "primary": "minecraft:orange_glazed_terracotta", "secondary": "minecraft:yellow_glazed_terracotta", "core": "minecraft:white_glazed_terracotta", "rune": "minecraft:red_glazed_terracotta", "point": "minecraft:yellow_glazed_terracotta", "aged_level": 0},
    "basecamp_orange": {**STONE, "style": "classic_orange", "outer": "minecraft:orange_glazed_terracotta", "primary": "minecraft:yellow_glazed_terracotta", "secondary": "minecraft:white_glazed_terracotta", "core": "minecraft:light_gray_glazed_terracotta", "rune": "minecraft:orange_glazed_terracotta", "point": "minecraft:white_glazed_terracotta", "aged_level": 0},
    "basecamp_yellow": {**STONE, "style": "classic_yellow", "outer": "minecraft:yellow_glazed_terracotta", "primary": "minecraft:white_glazed_terracotta", "secondary": "minecraft:orange_glazed_terracotta", "core": "minecraft:white_glazed_terracotta", "rune": "minecraft:yellow_glazed_terracotta", "point": "minecraft:orange_glazed_terracotta", "aged_level": 0},
    "basecamp_lime": {**STONE, "style": "classic_lime", "outer": "minecraft:lime_glazed_terracotta", "primary": "minecraft:green_glazed_terracotta", "secondary": "minecraft:yellow_glazed_terracotta", "core": "minecraft:white_glazed_terracotta", "rune": "minecraft:lime_glazed_terracotta", "point": "minecraft:yellow_glazed_terracotta", "aged_level": 0},
    "basecamp_green": {**STONE, "style": "classic_green", "outer": "minecraft:green_glazed_terracotta", "primary": "minecraft:lime_glazed_terracotta", "secondary": "minecraft:cyan_glazed_terracotta", "core": "minecraft:white_glazed_terracotta", "rune": "minecraft:green_glazed_terracotta", "point": "minecraft:cyan_glazed_terracotta", "aged_level": 0},
    "basecamp_cyan": {**STONE, "style": "classic_cyan", "outer": "minecraft:cyan_glazed_terracotta", "primary": "minecraft:light_blue_glazed_terracotta", "secondary": "minecraft:white_glazed_terracotta", "core": "minecraft:white_glazed_terracotta", "rune": "minecraft:cyan_glazed_terracotta", "point": "minecraft:white_glazed_terracotta", "aged_level": 0},
    "basecamp_light_blue": {**STONE, "style": "classic_light_blue", "outer": "minecraft:light_blue_glazed_terracotta", "primary": "minecraft:white_glazed_terracotta", "secondary": "minecraft:cyan_glazed_terracotta", "core": "minecraft:white_glazed_terracotta", "rune": "minecraft:light_blue_glazed_terracotta", "point": "minecraft:cyan_glazed_terracotta", "aged_level": 0},
    "basecamp_blue": {**STONE, "style": "classic_blue", "outer": "minecraft:blue_glazed_terracotta", "primary": "minecraft:light_blue_glazed_terracotta", "secondary": "minecraft:white_glazed_terracotta", "core": "minecraft:white_glazed_terracotta", "rune": "minecraft:blue_glazed_terracotta", "point": "minecraft:light_blue_glazed_terracotta", "aged_level": 0},
    "basecamp_purple": {**STONE, "style": "classic_purple", "outer": "minecraft:purple_glazed_terracotta", "primary": "minecraft:blue_glazed_terracotta", "secondary": "minecraft:light_blue_glazed_terracotta", "core": "minecraft:white_glazed_terracotta", "rune": "minecraft:purple_glazed_terracotta", "point": "minecraft:light_blue_glazed_terracotta", "aged_level": 0},
    "basecamp_white": {**STONE, "style": "classic_white", "outer": "minecraft:white_glazed_terracotta", "primary": "minecraft:light_gray_glazed_terracotta", "secondary": "minecraft:blue_glazed_terracotta", "core": "minecraft:white_glazed_terracotta", "rune": "minecraft:light_blue_glazed_terracotta", "point": "minecraft:blue_glazed_terracotta", "aged_level": 0},
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

def hash3(x, y, z): return abs(x * 37 + y * 53 + z * 19 + x * z * 3)

def aged_surface(x, y, z, default, cfg):
    level = cfg["aged_level"]
    if level == 0: return default
    h = hash3(x, y, z)
    if level >= 2:
        if h % 17 in (0, 1): return cfg["moss"]
        if h % 5 == 0 or h % 11 == 0: return cfg["aged"]
    return default

def aged_stairs(x, y, z, cfg):
    if cfg["aged_level"] >= 2 and hash3(x, y, z) % 8 == 0: return cfg["moss_stairs"]
    return cfg["stairs"]

def aged_wall(x, y, z, cfg):
    if cfg["aged_level"] >= 2 and hash3(x, y, z) % 5 == 0: return cfg["moss_wall"]
    return cfg["wall"]

def base_disc(cfg):
    for x in range(WIDTH):
        for z in range(DEPTH):
            dx, dz, r = x - CENTER, z - CENTER, dist(x, z)
            if r <= 10.9: put(x, 0, z, aged_surface(x, 0, z, cfg["base"], cfg))
            if r <= 9.25:
                base = cfg["floor"] if (x + z) % 8 else cfg["accent"]
                put(x, 1, z, aged_surface(x, 1, z, base, cfg))
            elif r <= 10.15:
                put(x, 1, z, aged_stairs(x, 1, z, cfg), stair(radial_facing(dx, dz)))
    for dx, dz in ((0, -9), (6, -6), (9, 0), (6, 6), (0, 9), (-6, 6), (-9, 0), (-6, -6)):
        put(CENTER + dx, 1, CENTER + dz, cfg["marker"])

def approaches(cfg):
    for offset in (-1, 0, 1):
        for d in (10, 11, 12, 13):
            for x, z in ((CENTER + offset, CENTER - d), (CENTER + offset, CENTER + d), (CENTER - d, CENTER + offset), (CENTER + d, CENTER + offset)):
                put(x, 0, z, aged_surface(x, 0, z, cfg["base"], cfg))
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

def near_segment(dx, dz, a, b, tolerance=0.48):
    return segment_distance(dx, dz, *a, *b) <= tolerance

def shifted_ring(dx, dz, cx, cz, radius, tolerance=0.42):
    return abs(math.hypot(dx - cx, dz - cz) - radius) <= tolerance

def triangle_variant(dx, dz, apex_z, half_base, base_z, tolerance=0.38):
    a, b, c = (0, apex_z), (-half_base, base_z), (half_base, base_z)
    return min(segment_distance(dx, dz, *a, *b), segment_distance(dx, dz, *b, *c), segment_distance(dx, dz, *c, *a)) <= tolerance

CLASSIC_MOTIFS = {
    "classic_red":        (-4.3, 3.8, 3.0, 2.15, 2.75, None),
    "classic_orange":     (-4.1, 4.0, 2.9, 2.10, 2.70, (0, -3)),
    "classic_yellow":     (-4.5, 3.6, 3.0, 2.20, 2.80, None),
    "classic_lime":       (-4.2, 3.9, 2.8, 2.00, 2.65, (0, 3)),
    "classic_green":      (-4.3, 3.6, 3.2, 2.15, 2.70, None),
    "classic_cyan":       (-4.1, 4.1, 3.0, 2.25, 2.85, (0, -2)),
    "classic_light_blue": (-4.4, 3.7, 2.8, 2.10, 2.75, None),
    "classic_blue":       (-4.2, 3.8, 3.2, 2.20, 2.85, (0, 2)),
    "classic_purple":     (-4.5, 4.0, 2.8, 2.05, 2.70, None),
    "classic_white":      (-4.0, 3.6, 3.1, 2.20, 2.80, (0, -3)),
}

def pattern_block(dx, dz, r, cfg):
    style = cfg["style"]
    if 5.65 <= r <= 6.45:
        return cfg["outer"]
    if style == "classic":
        if on_triangle(dx, dz): return cfg["primary"]
        if 2.15 <= r <= 2.75: return cfg["secondary"]
    elif style in CLASSIC_MOTIFS:
        apex_z, half_base, base_z, inner_min, inner_max, detail = CLASSIC_MOTIFS[style]
        if triangle_variant(dx, dz, apex_z, half_base, base_z): return cfg["primary"]
        if inner_min <= r <= inner_max: return cfg["secondary"]
        if detail is not None:
            _, detail_z = detail
            if dx == 0 and dz == detail_z: return cfg["secondary"]
    if r <= 1.15: return cfg["core"]
    if dx == 0 and dz == 0: return cfg["rune"]
    return None

def pattern_markers(style):
    return [(0, -7), (5, -5), (7, 0), (5, 5), (0, 7), (-5, 5), (-7, 0), (-5, -5)]

def magic_altar(cfg):
    for x in range(CENTER - 7, CENTER + 8):
        for z in range(CENTER - 7, CENTER + 8):
            dx, dz, r = x - CENTER, z - CENTER, dist(x, z)
            if 6.4 < r <= 7.15: put(x, 1, z, aged_stairs(x, 1, z, cfg), stair(radial_facing(dx, dz)))
            if r <= 6.45: put(x, 2, z, aged_surface(x, 2, z, cfg["floor"], cfg))
    for offset in (-1, 0, 1):
        put(CENTER + offset, 2, CENTER - 7, aged_stairs(CENTER + offset, 2, CENTER - 7, cfg), stair("south"))
        put(CENTER + offset, 2, CENTER + 7, aged_stairs(CENTER + offset, 2, CENTER + 7, cfg), stair("north"))
        put(CENTER - 7, 2, CENTER + offset, aged_stairs(CENTER - 7, 2, CENTER + offset, cfg), stair("east"))
        put(CENTER + 7, 2, CENTER + offset, aged_stairs(CENTER + 7, 2, CENTER + offset, cfg), stair("west"))
    for x in range(CENTER - 7, CENTER + 8):
        for z in range(CENTER - 7, CENTER + 8):
            dx, dz, r = x - CENTER, z - CENTER, dist(x, z)
            if r > 6.75: continue
            name = pattern_block(dx, dz, r, cfg)
            if name: put(x, 2, z, name, {"facing": radial_facing(dx, dz)})
    for dx, dz in pattern_markers(cfg["style"]):
        put(CENTER + dx, 2, CENTER + dz, cfg["marker"])
        ix, iz = int(round(dx * 0.80)), int(round(dz * 0.80))
        put(CENTER + ix, 2, CENTER + iz, cfg["point"], {"facing": radial_facing(ix, iz)})

def pillar(px, pz, inward_x, inward_z, cfg):
    for x in range(px - 2, px + 3):
        for z in range(pz - 2, pz + 3):
            if abs(x - px) == 2 and abs(z - pz) == 2: continue
            put(x, 0, z, aged_surface(x, 0, z, cfg["base"], cfg))
    for x in range(px - 1, px + 2):
        for z in range(pz - 1, pz + 2):
            put(x, 1, z, aged_surface(x, 1, z, cfg["accent"] if x == px and z == pz else cfg["base"], cfg))
            put(x, 2, z, cfg["marker"] if x == px and z == pz else aged_surface(x, 2, z, cfg["base"], cfg))
    for x in range(px - 1, px + 2):
        for z in range(pz - 1, pz + 2):
            if abs(x - px) == 1 and abs(z - pz) == 1: continue
            put(x, 3, z, aged_surface(x, 3, z, cfg["accent"], cfg))
    for (x, z), facing in {(px, pz - 1): "north", (px + 1, pz): "east", (px, pz + 1): "south", (px - 1, pz): "west"}.items():
        put(x, 4, z, aged_stairs(x, 4, z, cfg), stair(facing))
    put(px, 4, pz, cfg["marker"])
    put(px, 5, pz, aged_surface(px, 5, pz, cfg["accent"], cfg))
    put(px, 6, pz, cfg["marker"])
    put(px, 7, pz, aged_wall(px, 7, pz, cfg))
    put(px + inward_x, 1, pz + inward_z, cfg["rune"], {"facing": radial_facing(-inward_x, -inward_z)})

def pillars(cfg):
    pillar(CENTER - 9, CENTER - 9, 1, 1, cfg)
    pillar(CENTER + 9, CENTER - 9, -1, 1, cfg)
    pillar(CENTER - 9, CENTER + 9, 1, -1, cfg)
    pillar(CENTER + 9, CENTER + 9, -1, -1, cfg)

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

def write_structure(name):
    palette, states = palette_and_states()
    block_entries = []
    for (x, y, z), (state, block_nbt) in sorted(states.items(), key=lambda item: (item[0][1], item[0][2], item[0][0])):
        entry = OrderedDict([("pos", (TAG_LIST, (TAG_INT, [x, y, z]))), ("state", (TAG_INT, state))])
        if block_nbt: entry["nbt"] = (TAG_COMPOUND, block_nbt)
        block_entries.append(entry)
    root = OrderedDict([("DataVersion", (TAG_INT, DATA_VERSION)), ("size", (TAG_LIST, (TAG_INT, [WIDTH, HEIGHT, DEPTH]))), ("palette", (TAG_LIST, (TAG_COMPOUND, palette))), ("blocks", (TAG_LIST, (TAG_COMPOUND, block_entries))), ("entities", (TAG_LIST, (TAG_COMPOUND, [])))])
    raw = named(TAG_COMPOUND, "", root)
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    output = os.path.join(OUTPUT_DIR, f"{name}.nbt")
    with open(output, "wb") as file:
        with gzip.GzipFile(fileobj=file, mode="wb", mtime=0) as zipped: zipped.write(raw)
    print(f"Generated {name}: {len(block_entries)} blocks, {len(palette)} states, {os.path.getsize(output)} bytes")

def add_mossy_decay(cfg):
    if cfg["aged_level"] < 2: return
    replacements = [(4, 4, 3), (22, 4, 23), (3, 4, 22)]
    for pos in replacements:
        if pos in blocks: put(*pos, "minecraft:cobweb")
    overlays = [
        (5, 3, 5), (21, 3, 5), (5, 3, 21), (21, 3, 21),
        (5, 4, 5), (21, 4, 5), (5, 4, 21), (21, 4, 21),
        (21, 2, 15), (6, 2, 8), (8, 2, 21), (18, 2, 4),
        (3, 2, 10), (23, 2, 17),
    ]
    for pos in overlays:
        if pos not in blocks: put(*pos, "minecraft:cobweb")

def generate(name, cfg):
    blocks.clear()
    base_disc(cfg)
    approaches(cfg)
    magic_altar(cfg)
    pillars(cfg)
    add_mossy_decay(cfg)
    write_structure(name)

def main():
    for name, cfg in VARIANTS.items(): generate(name, cfg)

if __name__ == "__main__": main()
