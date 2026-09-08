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
import java.util.Comparator;
import java.util.List;

public final class BasecampPlacementService {
    private static final int MAX_TERRAIN_TRIM_DEPTH = 2;
    private static final int FEATHER_TRIM_DEPTH = 1;
    private static final int TREE_CLEAR_MARGIN = 2;
    private static final int TREE_CLEAR_EXTRA_HEIGHT = 16;

    private static final TemplateSize SMALL = new TemplateSize(11, 7);
    private static final TemplateSize LARGE = new TemplateSize(27, 10);

    private static final List<ColorVariant> COLORS = List.of(
            new ColorVariant("default", "01", "01_ruined"),
            new ColorVariant("mossy", "mossy_clean", "mossy"),
            new ColorVariant("cyan", "cyan", "cyan_ruined"),
            new ColorVariant("green", "green", "green_ruined"),
            new ColorVariant("white", "white", "white_ruined")
    );

    private BasecampPlacementService() { }

    public static List<BasecampPlacement> ensureGenerated(MinecraftServer server, City city) {
        if (BasecampManager.isGenerated(server, city.id())) return BasecampManager.getPlacements(server, city.id());

        ServerLevel level = server.getLevel(Level.OVERWORLD);
        CityRegion region = city.getRegion(Level.OVERWORLD).orElseThrow(
                () -> new IllegalStateException("Basecamp generation requires an Overworld city region: " + city.id())
        );
        if (level == null) throw new IllegalStateException("Overworld is not available for Basecamp generation.");

        long seed = citySeed(level.getSeed(), city.id());
        RandomSource random = RandomSource.create(seed);
        int count = rollBasecampCount(random);
        int largeIndex = random.nextDouble() < count * 0.10 ? random.nextInt(count) : -1;

        List<BasecampSpec> specs = new ArrayList<>(count);
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
            specs.add(new BasecampSpec(index, templateId, template, size, color.id(), large, ruined));
        }

        List<BasecampPlacementPlanner.Request> requests = specs.stream()
                .map(spec -> new BasecampPlacementPlanner.Request(spec.index(), spec.large()))
                .toList();
        List<BasecampPlacementPlanner.Plan> sitePlans = BasecampPlacementPlanner.plan(level, region, requests, seed, city.id());

        List<PlannedBasecamp> plans = new ArrayList<>(sitePlans.size());
        for (BasecampPlacementPlanner.Plan site : sitePlans) {
            BasecampSpec spec = specs.get(site.specIndex());
            int half = spec.size().width() / 2;
            int originY = Math.max(level.getMinY(), Math.min(level.getMaxY() - spec.size().height() + 1, site.targetSurfaceY() - 1));
            PlacementCandidate candidate = new PlacementCandidate(
                    site.centerX(), site.centerZ(), site.centerX() - half, originY, site.centerZ() - half,
                    originY + 1, site.terrainScore()
            );
            plans.add(new PlannedBasecamp(spec, candidate));
        }
        plans.sort(Comparator.comparingInt(plan -> plan.spec().index()));

        List<BasecampPlacement> placements = new ArrayList<>(plans.size());
        for (PlannedBasecamp plan : plans) {
            BasecampSpec spec = plan.spec();
            PlacementCandidate candidate = plan.candidate();
            clearTreesAndVegetation(level, candidate, spec.size());
            trimTerrain(level, candidate, spec.size());

            BlockPos origin = new BlockPos(candidate.originX(), candidate.originY(), candidate.originZ());
            boolean placed = spec.template().placeInWorld(
                    level,
                    origin,
                    origin,
                    new StructurePlaceSettings(),
                    RandomSource.create(citySeed(seed ^ spec.index(), city.id() + "|" + spec.templateId())),
                    3
            );
            if (!placed) throw new IllegalStateException("Failed to place Basecamp template " + spec.templateId() + " for city " + city.id());

            placements.add(new BasecampPlacement(
                    city.id(), spec.templateId().toString(), origin.getX(), origin.getY(), origin.getZ(),
                    spec.large(), spec.ruined(), spec.color()
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
        double clearCenterX = candidate.originX() + (size.width() - 1) / 2.0;
        double clearCenterZ = candidate.originZ() + (size.width() - 1) / 2.0;
        double clearRadius = size.width() / 2.0 + TREE_CLEAR_MARGIN;
        double clearRadiusSquared = clearRadius * clearRadius;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                double dx = x - clearCenterX;
                double dz = z - clearCenterZ;
                if (dx * dx + dz * dz > clearRadiusSquared) continue;
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
                    if (state.is(BlockTags.REPLACEABLE)) level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    private static void trimTerrain(ServerLevel level, PlacementCandidate candidate, TemplateSize size) {
        double center = (size.width() - 1) / 2.0;
        double coreRadius = size.width() / 2.0;
        double featherRadius = coreRadius + 1.0;
        int minLocal = -1;
        int maxLocal = size.width();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int dx = minLocal; dx <= maxLocal; dx++) {
            for (int dz = minLocal; dz <= maxLocal; dz++) {
                double x = dx - center;
                double z = dz - center;
                double distanceSquared = x * x + z * z;
                if (distanceSquared > featherRadius * featherRadius) continue;

                boolean core = distanceSquared <= coreRadius * coreRadius;
                int maxTrim = core ? MAX_TERRAIN_TRIM_DEPTH : FEATHER_TRIM_DEPTH;
                int worldX = candidate.originX() + dx;
                int worldZ = candidate.originZ() + dz;
                SurfaceSample sample = findSurfaceSample(level, worldX, worldZ);
                if (sample == null || sample.surfaceY() <= candidate.targetSurfaceY()) continue;

                int trimDepth = Math.min(maxTrim, sample.surfaceY() - candidate.targetSurfaceY());
                for (int depth = 0; depth < trimDepth; depth++) {
                    int y = sample.surfaceY() - 1 - depth;
                    cursor.set(worldX, y, worldZ);
                    BlockState state = level.getBlockState(cursor);
                    if (state.is(Blocks.BEDROCK) || level.getBlockEntity(cursor) != null) break;
                    level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 3);
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
    private record BasecampSpec(int index, Identifier templateId, StructureTemplate template, TemplateSize size,
                                String color, boolean large, boolean ruined) { }
    private record SurfaceSample(int surfaceY, boolean fluid) { }
    private record PlacementCandidate(int centerX, int centerZ, int originX, int originY, int originZ,
                                      int targetSurfaceY, double score) { }
    private record PlannedBasecamp(BasecampSpec spec, PlacementCandidate candidate) { }
}
