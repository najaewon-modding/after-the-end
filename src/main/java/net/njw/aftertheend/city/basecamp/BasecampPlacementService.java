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
    private static final int PREFERRED_CANDIDATE_ATTEMPTS = 96;
    private static final int CITY_EDGE_MARGIN = 8;
    private static final int PREFERRED_BASECAMP_DISTANCE = 160;
    private static final int MIN_STRUCTURE_GAP = 12;
    private static final int PREFERRED_MAX_SURFACE_DELTA = 3;
    private static final double PREFERRED_MAX_FLOATING_FRACTION = 0.25;
    private static final int TREE_CLEAR_MARGIN = 4;
    private static final int TREE_CLEAR_EXTRA_HEIGHT = 24;

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
            reserved.add(new PlacedFootprint(candidate.centerX(), candidate.centerZ(), size.width() / 2));
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
        SearchBounds bounds = searchBounds(region, size);
        ScoredCandidate best = null;

        for (int attempt = 0; attempt < PREFERRED_CANDIDATE_ATTEMPTS; attempt++) {
            int centerX = random.nextInt(bounds.minCenterX(), bounds.maxCenterX() + 1);
            int centerZ = random.nextInt(bounds.minCenterZ(), bounds.maxCenterZ() + 1);
            if (!hasStructuralClearance(centerX, centerZ, size, reserved, MIN_STRUCTURE_GAP)) continue;

            int originX = centerX - size.width() / 2;
            int originZ = centerZ - size.width() / 2;
            TerrainProfile terrain = inspectSampledTerrain(level, originX, originZ, size.width());
            if (terrain == null) continue;

            double nearestDistance = nearestBasecampDistance(centerX, centerZ, reserved);
            double score = terrain.score() + spacingPenalty(nearestDistance);
            ScoredCandidate scored = new ScoredCandidate(centerX, centerZ, score, terrain.preferred());
            if (best == null || scored.score() < best.score()) best = scored;

            if (terrain.preferred() && nearestDistance >= PREFERRED_BASECAMP_DISTANCE) {
                return finalizeCandidate(level, size, centerX, centerZ);
            }
        }

        if (best == null) best = findGuaranteedFallback(level, bounds, size, reserved);
        PlacementCandidate fallback = finalizeCandidate(level, size, best.centerX(), best.centerZ());
        AfterTheEnd.LOGGER.warn(
                "Using relaxed Basecamp terrain for {}x{} template at ({}, {}), score={}",
                size.width(), size.width(), fallback.centerX(), fallback.centerZ(), String.format("%.2f", best.score())
        );
        return fallback;
    }

    private static SearchBounds searchBounds(CityRegion region, TemplateSize size) {
        int half = size.width() / 2;
        int minCenterX = region.minBlockX() + half + CITY_EDGE_MARGIN;
        int maxCenterX = region.maxBlockX() - half - CITY_EDGE_MARGIN;
        int minCenterZ = region.minBlockZ() + half + CITY_EDGE_MARGIN;
        int maxCenterZ = region.maxBlockZ() - half - CITY_EDGE_MARGIN;
        if (minCenterX > maxCenterX || minCenterZ > maxCenterZ) {
            throw new IllegalStateException("City region is physically too small for a Basecamp template.");
        }
        return new SearchBounds(minCenterX, maxCenterX, minCenterZ, maxCenterZ);
    }

    private static ScoredCandidate findGuaranteedFallback(
            ServerLevel level,
            SearchBounds bounds,
            TemplateSize size,
            List<PlacedFootprint> reserved
    ) {
        ScoredCandidate best = null;
        int steps = 16;
        for (int gx = 0; gx < steps; gx++) {
            int centerX = interpolate(bounds.minCenterX(), bounds.maxCenterX(), gx, steps);
            for (int gz = 0; gz < steps; gz++) {
                int centerZ = interpolate(bounds.minCenterZ(), bounds.maxCenterZ(), gz, steps);
                if (!hasStructuralClearance(centerX, centerZ, size, reserved, 0)) continue;
                int originX = centerX - size.width() / 2;
                int originZ = centerZ - size.width() / 2;
                TerrainProfile terrain = inspectSampledTerrain(level, originX, originZ, size.width());
                if (terrain == null) continue;
                double score = terrain.score() + spacingPenalty(nearestBasecampDistance(centerX, centerZ, reserved));
                ScoredCandidate scored = new ScoredCandidate(centerX, centerZ, score, false);
                if (best == null || scored.score() < best.score()) best = scored;
            }
        }
        if (best != null) return best;
        throw new IllegalStateException("City region cannot physically fit all required Basecamp structures without overlap.");
    }

    private static int interpolate(int min, int max, int index, int count) {
        if (count <= 1 || min == max) return min;
        return min + (int) Math.round((max - (double) min) * index / (count - 1.0));
    }

    private static PlacementCandidate finalizeCandidate(ServerLevel level, TemplateSize size, int centerX, int centerZ) {
        int originX = centerX - size.width() / 2;
        int originZ = centerZ - size.width() / 2;
        int maxSurfaceY = inspectFullMaxSurface(level, originX, originZ, size.width());
        int originY = maxSurfaceY - 1;
        int maximumOriginY = level.getMaxY() - size.height() + 1;
        if (originY > maximumOriginY) originY = maximumOriginY;
        if (originY < level.getMinY()) originY = level.getMinY();
        return new PlacementCandidate(centerX, centerZ, originX, originY, originZ);
    }

    private static boolean hasStructuralClearance(
            int centerX,
            int centerZ,
            TemplateSize size,
            List<PlacedFootprint> reserved,
            int gap
    ) {
        int half = size.width() / 2;
        for (PlacedFootprint other : reserved) {
            int required = half + other.halfWidth() + gap;
            if (Math.abs(centerX - other.centerX()) <= required && Math.abs(centerZ - other.centerZ()) <= required) return false;
        }
        return true;
    }

    private static double nearestBasecampDistance(int centerX, int centerZ, List<PlacedFootprint> reserved) {
        if (reserved.isEmpty()) return Double.POSITIVE_INFINITY;
        double nearestSquared = Double.POSITIVE_INFINITY;
        for (PlacedFootprint other : reserved) {
            double dx = centerX - (double) other.centerX();
            double dz = centerZ - (double) other.centerZ();
            nearestSquared = Math.min(nearestSquared, dx * dx + dz * dz);
        }
        return Math.sqrt(nearestSquared);
    }

    private static double spacingPenalty(double nearestDistance) {
        if (!Double.isFinite(nearestDistance) || nearestDistance >= PREFERRED_BASECAMP_DISTANCE) return 0.0;
        double ratio = (PREFERRED_BASECAMP_DISTANCE - nearestDistance) / PREFERRED_BASECAMP_DISTANCE;
        return ratio * ratio * 250.0;
    }

    private static TerrainProfile inspectSampledTerrain(ServerLevel level, int originX, int originZ, int width) {
        int[] offsets = sampleOffsets(width);
        int[] surfaces = new int[offsets.length * offsets.length];
        int maxSurface = Integer.MIN_VALUE;
        int minSurface = Integer.MAX_VALUE;
        int fluidCells = 0;
        int index = 0;

        for (int dx : offsets) {
            for (int dz : offsets) {
                SurfaceSample sample = findSurfaceSample(level, originX + dx, originZ + dz);
                if (sample == null) return null;
                surfaces[index++] = sample.surfaceY();
                maxSurface = Math.max(maxSurface, sample.surfaceY());
                minSurface = Math.min(minSurface, sample.surfaceY());
                if (sample.fluid()) fluidCells++;
            }
        }

        int floatingCells = 0;
        int totalGap = 0;
        for (int surfaceY : surfaces) {
            int gap = maxSurface - surfaceY;
            totalGap += gap;
            if (gap >= 2) floatingCells++;
        }

        double floatingFraction = floatingCells / (double) surfaces.length;
        double fluidFraction = fluidCells / (double) surfaces.length;
        double averageGap = totalGap / (double) surfaces.length;
        int surfaceDelta = maxSurface - minSurface;
        boolean preferred = surfaceDelta <= PREFERRED_MAX_SURFACE_DELTA
                && floatingFraction <= PREFERRED_MAX_FLOATING_FRACTION
                && fluidCells == 0;
        double score = surfaceDelta * 20.0
                + averageGap * 15.0
                + floatingFraction * 120.0
                + fluidFraction * 1000.0;
        return new TerrainProfile(score, preferred);
    }

    private static int inspectFullMaxSurface(ServerLevel level, int originX, int originZ, int width) {
        int maxSurface = level.getMinY() + 1;
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < width; dz++) {
                SurfaceSample sample = findSurfaceSample(level, originX + dx, originZ + dz);
                if (sample != null) maxSurface = Math.max(maxSurface, sample.surfaceY());
            }
        }
        return maxSurface;
    }

    private static int[] sampleOffsets(int width) {
        return new int[]{0, width / 4, width / 2, (width * 3) / 4, width - 1};
    }

    private static SurfaceSample findSurfaceSample(ServerLevel level, int x, int z) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        if (top < level.getMinY()) return null;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, top, z);
        while (cursor.getY() >= level.getMinY()) {
            BlockState state = level.getBlockState(cursor);
            if (!state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES)) break;
            cursor.move(0, -1, 0);
        }
        if (cursor.getY() < level.getMinY()) return null;
        return new SurfaceSample(cursor.getY() + 1, !level.getFluidState(cursor).isEmpty());
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
    private record PlacedFootprint(int centerX, int centerZ, int halfWidth) { }
    private record SearchBounds(int minCenterX, int maxCenterX, int minCenterZ, int maxCenterZ) { }
    private record SurfaceSample(int surfaceY, boolean fluid) { }
    private record TerrainProfile(double score, boolean preferred) { }
    private record ScoredCandidate(int centerX, int centerZ, double score, boolean preferred) { }
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
