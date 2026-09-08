package net.njw.aftertheend.city.basecamp;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.city.City;
import net.njw.aftertheend.city.CityRegion;

import java.util.ArrayList;
import java.util.List;

public final class BasecampPlacementService {
    private static final int MAX_CANDIDATE_ATTEMPTS = 256;
    private static final int CITY_EDGE_MARGIN = 8;
    private static final int MIN_BASECAMP_DISTANCE = 160;
    private static final int MAX_SURFACE_DELTA = 3;
    private static final double MAX_FLOATING_FRACTION = 0.25;
    private static final int TREE_CLEAR_MARGIN = 4;
    private static final int TREE_CLEAR_EXTRA_HEIGHT = 24;
    private static final int MAX_TREE_COLUMN_SKIP = 48;

    private static final TemplateSize SMALL = new TemplateSize(11, 7);
    private static final TemplateSize LARGE = new TemplateSize(27, 10);

    private static final List<ColorVariant> COLORS = List.of(
            new ColorVariant("default", "01", "01_ruined"),
            new ColorVariant("mossy", "mossy_clean", "mossy"),
            new ColorVariant("cyan", "cyan", "cyan_ruined"),
            new ColorVariant("green", "green", "green_ruined"),
            new ColorVariant("white", "white", "white_ruined")
    );

    private BasecampPlacementService() {
    }

    public static List<BasecampPlacement> ensureGenerated(MinecraftServer server, City city) {
        if (BasecampManager.isGenerated(server, city.id())) return BasecampManager.getPlacements(server, city.id());

        ServerLevel level = server.getLevel(Level.OVERWORLD);
        CityRegion region = city.getRegion(Level.OVERWORLD).orElseThrow(
                () -> new IllegalStateException("Basecamp generation requires an Overworld city region: " + city.id())
        );
        if (level == null) throw new IllegalStateException("Overworld is not available for Basecamp generation.");

        RandomSource random = RandomSource.create(citySeed(level.getSeed(), city.id()));
        int count = rollBasecampCount(random);
        int largeIndex = random.nextDouble() < count * 0.10 ? random.nextInt(count) : -1;

        List<PlannedBasecamp> plans = new ArrayList<>(count);
        List<PlacedFootprint> reserved = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            boolean large = index == largeIndex;
            boolean ruined = random.nextDouble() < 0.10;
            ColorVariant color = COLORS.get(random.nextInt(COLORS.size()));
            TemplateSize size = large ? LARGE : SMALL;
            String suffix = ruined ? color.ruinedSuffix() : color.normalSuffix();
            String templateName = large ? "basecamp_" + suffix : "basecamp_small_" + suffix;
            Identifier templateId = Identifier.fromNamespaceAndPath("njw_after_the_end", "basecamp/" + templateName);
            StructureTemplate template = server.getStructureManager().get(templateId).orElseThrow(
                    () -> new IllegalStateException("Missing Basecamp structure template: " + templateId)
            );

            PlacementCandidate candidate = findCandidate(level, region, size, reserved, random);
            reserved.add(new PlacedFootprint(candidate.centerX(), candidate.centerZ()));
            plans.add(new PlannedBasecamp(templateId, template, size, color.id(), large, ruined, candidate));
        }

        List<BasecampPlacement> placements = new ArrayList<>(plans.size());
        for (int index = 0; index < plans.size(); index++) {
            PlannedBasecamp plan = plans.get(index);
            clearTreesAndVegetation(level, plan.candidate(), plan.size());
            BlockPos origin = new BlockPos(plan.candidate().originX(), plan.candidate().originY(), plan.candidate().originZ());
            boolean placed = plan.template().placeInWorld(
                    level,
                    origin,
                    origin,
                    new StructurePlaceSettings(),
                    RandomSource.create(citySeed(level.getSeed() ^ index, city.id() + "|" + plan.templateId())),
                    3
            );
            if (!placed) throw new IllegalStateException("Failed to place Basecamp template " + plan.templateId() + " for city " + city.id());

            placements.add(new BasecampPlacement(
                    city.id(),
                    plan.templateId().toString(),
                    origin.getX(),
                    origin.getY(),
                    origin.getZ(),
                    plan.large(),
                    plan.ruined(),
                    plan.color()
            ));
        }

        BasecampManager.markGenerated(server, city.id(), placements);
        long largeCount = placements.stream().filter(BasecampPlacement::large).count();
        long ruinedCount = placements.stream().filter(BasecampPlacement::ruined).count();
        AfterTheEnd.LOGGER.info(
                "Generated {} Basecamp(s) for city {}: large={}, ruined={}",
                placements.size(), city.id(), largeCount, ruinedCount
        );
        return List.copyOf(placements);
    }

    private static int rollBasecampCount(RandomSource random) {
        int roll = random.nextInt(100);
        if (roll < 15) return 3;
        if (roll < 35) return 4;
        if (roll < 75) return 5;
        if (roll < 90) return 6;
        if (roll < 95) return 7;
        return 8;
    }

    private static PlacementCandidate findCandidate(
            ServerLevel level,
            CityRegion region,
            TemplateSize size,
            List<PlacedFootprint> reserved,
            RandomSource random
    ) {
        int half = size.width() / 2;
        int minCenterX = region.minBlockX() + half + CITY_EDGE_MARGIN;
        int maxCenterX = region.maxBlockX() - half - CITY_EDGE_MARGIN;
        int minCenterZ = region.minBlockZ() + half + CITY_EDGE_MARGIN;
        int maxCenterZ = region.maxBlockZ() - half - CITY_EDGE_MARGIN;
        if (minCenterX > maxCenterX || minCenterZ > maxCenterZ) {
            throw new IllegalStateException("City region is too small for Basecamp placement.");
        }

        for (int attempt = 0; attempt < MAX_CANDIDATE_ATTEMPTS; attempt++) {
            int centerX = random.nextInt(minCenterX, maxCenterX + 1);
            int centerZ = random.nextInt(minCenterZ, maxCenterZ + 1);
            if (!isFarEnoughFromOtherBasecamps(centerX, centerZ, reserved)) continue;

            int originX = centerX - half;
            int originZ = centerZ - half;
            TerrainProfile terrain = inspectTerrain(level, originX, originZ, size.width());
            if (terrain == null) continue;
            int originY = terrain.maxSurfaceY() - 1;
            if (originY < level.getMinY() || originY + size.height() - 1 > level.getMaxY()) continue;
            return new PlacementCandidate(centerX, centerZ, originX, originY, originZ);
        }

        throw new IllegalStateException(
                "Could not find suitable terrain for a " + size.width() + "x" + size.width()
                        + " Basecamp after " + MAX_CANDIDATE_ATTEMPTS + " attempts."
        );
    }

    private static boolean isFarEnoughFromOtherBasecamps(int x, int z, List<PlacedFootprint> reserved) {
        long minimumSquared = (long) MIN_BASECAMP_DISTANCE * MIN_BASECAMP_DISTANCE;
        for (PlacedFootprint other : reserved) {
            long dx = (long) x - other.centerX();
            long dz = (long) z - other.centerZ();
            if (dx * dx + dz * dz < minimumSquared) return false;
        }
        return true;
    }

    private static TerrainProfile inspectTerrain(ServerLevel level, int originX, int originZ, int width) {
        int[] surfaces = new int[width * width];
        int maxSurface = Integer.MIN_VALUE;
        int minSurface = Integer.MAX_VALUE;
        int index = 0;

        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < width; dz++) {
                int surfaceY = findNaturalSurfaceY(level, originX + dx, originZ + dz);
                if (surfaceY == Integer.MIN_VALUE) return null;
                surfaces[index++] = surfaceY;
                maxSurface = Math.max(maxSurface, surfaceY);
                minSurface = Math.min(minSurface, surfaceY);
                if (maxSurface - minSurface > MAX_SURFACE_DELTA) return null;
            }
        }

        int floatingCells = 0;
        for (int surfaceY : surfaces) {
            int delta = maxSurface - surfaceY;
            if (delta > MAX_SURFACE_DELTA) return null;
            if (delta >= 2) floatingCells++;
        }
        if (floatingCells > surfaces.length * MAX_FLOATING_FRACTION) return null;
        return new TerrainProfile(maxSurface);
    }

    private static int findNaturalSurfaceY(ServerLevel level, int x, int z) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        if (top < level.getMinY()) return Integer.MIN_VALUE;

        int skippedTreeBlocks = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, top, z);
        while (cursor.getY() >= level.getMinY()) {
            BlockState state = level.getBlockState(cursor);
            if (!state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES)) break;
            if (++skippedTreeBlocks > MAX_TREE_COLUMN_SKIP) return Integer.MIN_VALUE;
            cursor.move(0, -1, 0);
        }
        if (cursor.getY() < level.getMinY()) return Integer.MIN_VALUE;
        if (!level.getFluidState(cursor).isEmpty()) return Integer.MIN_VALUE;
        return cursor.getY() + 1;
    }

    private static void clearTreesAndVegetation(ServerLevel level, PlacementCandidate candidate, TemplateSize size) {
        int minX = candidate.originX() - TREE_CLEAR_MARGIN;
        int maxX = candidate.originX() + size.width() - 1 + TREE_CLEAR_MARGIN;
        int minZ = candidate.originZ() - TREE_CLEAR_MARGIN;
        int maxZ = candidate.originZ() + size.width() - 1 + TREE_CLEAR_MARGIN;
        int minY = Math.max(level.getMinY(), candidate.originY() - 2);
        int maxY = Math.min(level.getMaxY(), candidate.originY() + size.height() + TREE_CLEAR_EXTRA_HEIGHT);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    cursor.set(x, y, z);
                    BlockState state = level.getBlockState(cursor);
                    if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) {
                        level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }

        int structureTop = Math.min(level.getMaxY(), candidate.originY() + size.height() - 1);
        for (int x = candidate.originX(); x < candidate.originX() + size.width(); x++) {
            for (int z = candidate.originZ(); z < candidate.originZ() + size.width(); z++) {
                for (int y = candidate.originY(); y <= structureTop; y++) {
                    cursor.set(x, y, z);
                    BlockState state = level.getBlockState(cursor);
                    if (state.is(BlockTags.REPLACEABLE)) {
                        level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }
    }

    private static long citySeed(long worldSeed, String cityId) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < cityId.length(); i++) {
            hash ^= cityId.charAt(i);
            hash *= 0x100000001b3L;
        }
        long value = worldSeed ^ hash;
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        return value ^ (value >>> 33);
    }

    private record ColorVariant(String id, String normalSuffix, String ruinedSuffix) { }
    private record TemplateSize(int width, int height) { }
    private record PlacedFootprint(int centerX, int centerZ) { }
    private record TerrainProfile(int maxSurfaceY) { }
    private record PlacementCandidate(int centerX, int centerZ, int originX, int originY, int originZ) { }
    private record PlannedBasecamp(
            Identifier templateId,
            StructureTemplate template,
            TemplateSize size,
            String color,
            boolean large,
            boolean ruined,
            PlacementCandidate candidate
    ) { }
}
