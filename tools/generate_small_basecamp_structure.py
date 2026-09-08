#!/usr/bin/env python3
import gzip
import math
import os
import struct
from collections import OrderedDict

WIDTH, HEIGHT, DEPTH = 11, 7, 11
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
    "basecamp_small_01": {**STONE, "outer": "minecraft:light_gray_glazed_terracotta", "primary": "minecraft:yellow_glazed_terracotta", "secondary": "minecraft:blue_glazed_terracotta", "core": "minecraft:white_glazed_terracotta", "rune": "minecraft:yellow_glazed_terracotta", "point": "minecraft:yellow_glazed_terracotta", "decayed": False},
    "basecamp_small_01_ruined": {**STONE, "outer": "minecraft:light_gray_glazed_terracotta", "primary": "minecraft:yellow_glazed_terracotta", "secondary": "minecraft:blue_glazed_terracotta", "core": "minecraft:white_glazed_terracotta", "rune": "minecraft:yellow_glazed_terracotta", "point": "minecraft:yellow_glazed_terracotta", "decayed": True},
    "basecamp_small_mossy_clean": {**STONE, "outer": "minecraft:light_gray_glazed_terracotta", "primary": "minecraft:green_glazed_terracotta", "secondary": "minecraft:cyan_glazed_terracotta", "core": "minecraft:white_glazed_terracotta", "rune": "minecraft:lime_glazed_terracotta", "point": "minecraft:lime_glazed_terracotta", "decayed": False},
    "basecamp_small_mossy": {**STONE, "outer": "minecraft:light_gray_glazed_terracotta", "primary": "minecraft:green_glazed_terracotta", "secondary": "minecraft:cyan_glazed_terracotta", "core": "minecraft:white_glazed_terracotta", "rune": "minecraft:lime_glazed_terracotta", "point": "minecraft:lime_glazed_terracotta", "decayed": True},
    "basecamp_small_cyan": {**STONE, "outer": "minecraft:cyan_glazed_terracotta", "primary": "minecraft:light_blue_glazed_terracotta", "secondary": "minecraft:white_glazed_terracotta", "core": "minecraft:white_glazed_terracotta", "rune": "minecraft:cyan_glazed_terracotta", "point": "minecraft:white_glazed_terracotta", "decayed": False},
    "basecamp_small_cyan_ruined": {**STONE, "outer": "minecraft:cyan_glazed_terracotta", "primary": "minecraft:light_blue_glazed_terracotta", "secondary": "minecraft:white_glazed_terracotta", "core": "minecraft:white_glazed_terracotta", "rune": "minecraft:cyan_glazed_terracotta", "point": "minecraft:white_glazed_terracotta", "decayed": True},
    "basecamp_small_green": {**STONE, "outer": "minecraft:green_glazed_terracotta", "primary": "minecraft:lime_glazed_terracotta", "secondary": "minecraft:cyan_glazed_terracotta", "core": "minecraft:white_glazed_terracotta", "rune": "minecraft:green_glazed_terracotta", "point": "minecraft:lime_glazed_terracotta", "decayed": False},
    "basecamp_small_green_ruined": {**STONE, "outer": "minecraft:green_glazed_terracotta", "primary": "minecraft:lime_glazed_terracotta", "secondary": "minecraft:cyan_glazed_terracotta", "core": "minecraft:white_glazed_terracotta", "rune": "minecraft:green_glazed_terracotta", "point": "minecraft:lime_glazed_terracotta", "decayed": True},
    "basecamp_small_white": {**STONE, "outer": "minecraft:white_glazed_terracotta", "primary": "minecraft:light_gray_glazed_terracotta", "secondary": "minecraft:blue_glazed_terracotta", "core": "minecraft:white_glazed_terracotta", "rune": "minecraft:light_blue_glazed_terracotta", "point": "minecraft:blue_glazed_terracotta", "decayed": False},
    "basecamp_small_white_ruined": {**STONE, "outer": "minecraft:white_glazed_terracotta", "primary": "minecraft:light_gray_glazed_terracotta", "secondary": "minecraft:blue_glazed_terracotta", "core": "minecraft:white_glazed_terracotta", "rune": "minecraft:light_blue_glazed_terracotta", "point": "minecraft:blue_glazed_terracotta", "decayed": True},
}

def put(x, y, z, name, properties=None, nbt=None):
    if not (0 <= x < WIDTH and 0 <= y < HEIGHT and 0 <= z < DEPTH):
        raise ValueError(f"Block outside structure: {(x, y, z)}")
    blocks[(x, y, z)] = (name, properties or {}, nbt)

def dist(x, z): return math.hypot(x - CENTER, z - CENTER)
def slab(): return {"type": "bottom", "waterlogged": "false"}
def stair(facing): return {"facing": facing, "half": "bottom", "shape": "straight", "waterlogged": "false"}
def hash3(x, y, z): return abs(x * 37 + y * 53 + z * 19 + x * z * 3)

def radial_facing(dx, dz):
    if abs(dx) >= abs(dz): return "east" if dx >= 0 else "west"
    return "south" if dz >= 0 else "north"

def weathered(x, y, z, default, cfg):
    if not cfg["decayed"]: return default
    h = hash3(x, y, z)
    if h % 13 == 0: return cfg["moss"]
    if h % 4 == 0 or h % 7 == 0: return cfg["aged"]
    return default

def weathered_stairs(x, y, z, cfg):
    if cfg["decayed"] and hash3(x, y, z) % 6 == 0: return cfg["moss_stairs"]
    return cfg["stairs"]

def segment_distance(px, pz, ax, az, bx, bz):
    vx, vz, wx, wz = bx - ax, bz - az, px - ax, pz - az
    length_sq = vx * vx + vz * vz
    if length_sq == 0: return math.hypot(px - ax, pz - az)
    t = max(0.0, min(1.0, (vx * wx + vz * wz) / length_sq))
    return math.hypot(px - (ax + t * vx), pz - (az + t * vz))

def on_triangle(dx, dz):
    a, b, c = (0.0, -2.15), (-1.9, 1.45), (1.9, 1.45)
    return min(segment_distance(dx, dz, *a, *b), segment_distance(dx, dz, *b, *c), segment_distance(dx, dz, *c, *a)) <= 0.38

def base_disc(cfg):
    for x in range(WIDTH):
        for z in range(DEPTH):
            dx, dz, r = x - CENTER, z - CENTER, dist(x, z)
            if r <= 4.95:
                put(x, 0, z, weathered(x, 0, z, cfg["base"], cfg))
            if r <= 4.15:
                base = cfg["accent"] if (x + z) % 7 == 0 else cfg["floor"]
                put(x, 1, z, weathered(x, 1, z, base, cfg))
            elif r <= 4.85:
                put(x, 1, z, weathered_stairs(x, 1, z, cfg), stair(radial_facing(dx, dz)))
    for offset in (-1, 0, 1):
        put(CENTER + offset, 1, 0, "minecraft:smooth_stone_slab", slab())
        put(CENTER + offset, 1, DEPTH - 1, "minecraft:smooth_stone_slab", slab())
        put(0, 1, CENTER + offset, "minecraft:smooth_stone_slab", slab())
        put(WIDTH - 1, 1, CENTER + offset, "minecraft:smooth_stone_slab", slab())
    for dx, dz in ((0, -4), (3, -3), (4, 0), (3, 3), (0, 4), (-3, 3), (-4, 0), (-3, -3)):
        put(CENTER + dx, 1, CENTER + dz, cfg["marker"])

def pattern_block(dx, dz, r, cfg):
    if 2.55 <= r <= 3.20: return cfg["outer"]
    if on_triangle(dx, dz): return cfg["primary"]
    if 0.90 <= r <= 1.48: return cfg["secondary"]
    if r <= 0.70: return cfg["core"]
    return None

def magic_altar(cfg):
    for x in range(CENTER - 4, CENTER + 5):
        for z in range(CENTER - 4, CENTER + 5):
            dx, dz, r = x - CENTER, z - CENTER, dist(x, z)
            if 3.15 < r <= 3.80:
                put(x, 1, z, weathered_stairs(x, 1, z, cfg), stair(radial_facing(dx, dz)))
            if r <= 3.20:
                put(x, 2, z, weathered(x, 2, z, cfg["floor"], cfg))
    for offset in (-1, 0, 1):
        put(CENTER + offset, 2, CENTER - 4, weathered_stairs(CENTER + offset, 2, CENTER - 4, cfg), stair("south"))
        put(CENTER + offset, 2, CENTER + 4, weathered_stairs(CENTER + offset, 2, CENTER + 4, cfg), stair("north"))
        put(CENTER - 4, 2, CENTER + offset, weathered_stairs(CENTER - 4, 2, CENTER + offset, cfg), stair("east"))
        put(CENTER + 4, 2, CENTER + offset, weathered_stairs(CENTER + 4, 2, CENTER + offset, cfg), stair("west"))
    for x in range(CENTER - 3, CENTER + 4):
        for z in range(CENTER - 3, CENTER + 4):
            dx, dz, r = x - CENTER, z - CENTER, dist(x, z)
            if r > 3.20: continue
            name = pattern_block(dx, dz, r, cfg)
            if name: put(x, 2, z, name, {"facing": radial_facing(dx, dz)})
    point_positions = ((0, -3), (2, -2), (3, 0), (2, 2), (0, 3), (-2, 2), (-3, 0), (-2, -2))
    for dx, dz in point_positions:
        put(CENTER + dx, 2, CENTER + dz, cfg["point"], {"facing": radial_facing(dx, dz)})
    put(CENTER, 2, CENTER, cfg["rune"], {"facing": "north"})

def pillar(px, pz, inward_x, inward_z, cfg):
    for dx, dz in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
        put(px + dx, 0, pz + dz, weathered(px + dx, 0, pz + dz, cfg["base"], cfg))
    for dx, dz in ((0, 0), (inward_x, 0), (0, inward_z), (inward_x, inward_z)):
        put(px + dx, 1, pz + dz, weathered(px + dx, 1, pz + dz, cfg["base"], cfg))
    put(px, 2, pz, cfg["marker"])
    put(px + inward_x, 2, pz, weathered_stairs(px + inward_x, 2, pz, cfg), stair("east" if inward_x > 0 else "west"))
    put(px, 2, pz + inward_z, weathered_stairs(px, 2, pz + inward_z, cfg), stair("south" if inward_z > 0 else "north"))
    put(px, 3, pz, weathered(px, 3, pz, cfg["accent"], cfg))
    put(px, 4, pz, cfg["marker"])
    put(px, 5, pz, cfg["moss_wall"] if cfg["decayed"] and hash3(px, 5, pz) % 2 == 0 else cfg["wall"])
    put(px + inward_x, 1, pz + inward_z, cfg["rune"], {"facing": radial_facing(-inward_x, -inward_z)})

def pillars(cfg):
    pillar(1, 1, 1, 1, cfg)
    pillar(9, 1, -1, 1, cfg)
    pillar(1, 9, 1, -1, cfg)
    pillar(9, 9, -1, -1, cfg)

def add_ruin_decay(cfg):
    if not cfg["decayed"]: return

    # Restore the lighter overall ruin, then concentrate the extra
    # damage on the top circular altar where it is visually obvious.
    missing = [
        # Top altar: three contiguous bites through the circular edge.
        (4, 2, 2), (5, 2, 2), (6, 2, 2),
        (8, 2, 4), (8, 2, 5), (8, 2, 6),
        (3, 2, 7), (4, 2, 8),

        # Lower platform / approaches: restrained earlier damage.
        (5, 1, 1), (9, 1, 6), (2, 1, 7),

        # Obelisks: earlier moderate height loss.
        (1, 5, 1), (1, 4, 1), (9, 5, 9),
    ]
    for pos in missing: blocks.pop(pos, None)

    cracked = [
        # Broken top-edge neighbors.
        (3, 2, 3), (7, 2, 3), (7, 2, 4), (7, 2, 7), (3, 2, 6),
        # Earlier restrained fracture accents.
        (4, 1, 1), (6, 1, 1), (8, 1, 6), (2, 1, 6),
        (1, 3, 1), (2, 1, 1), (8, 1, 9), (9, 3, 9),
    ]
    for pos in cracked:
        if pos in blocks: put(*pos, cfg["aged"])

    mossy = [(2, 1, 2), (3, 1, 2), (2, 1, 8), (3, 1, 8), (8, 1, 8), (7, 1, 8), (1, 2, 9)]
    for pos in mossy:
        if pos in blocks: put(*pos, cfg["moss"])

    webs = [(1, 4, 1), (2, 3, 1), (8, 3, 9), (5, 3, 2), (8, 3, 5), (4, 3, 8)]
    for pos in webs:
        if pos not in blocks: put(*pos, "minecraft:cobweb")
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

def generate(name, cfg):
    blocks.clear()
    base_disc(cfg)
    magic_altar(cfg)
    pillars(cfg)
    add_ruin_decay(cfg)
    write_structure(name)

def main():
    for name, cfg in VARIANTS.items(): generate(name, cfg)

if __name__ == "__main__": main()
