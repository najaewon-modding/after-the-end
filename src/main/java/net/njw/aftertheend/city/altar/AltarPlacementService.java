package net.njw.aftertheend.city.altar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.city.City;
import net.njw.aftertheend.city.CityRegion;

public final class AltarPlacementService {
    private static final int MAX_TERRAIN_TRIM_DEPTH = 2;
    private static final int FEATHER_TRIM_DEPTH = 1;
    private static final int TREE_CLEAR_MARGIN = 2;
    private static final int TREE_CLEAR_EXTRA_HEIGHT = 16;

    private static final TemplateSize SMALL = new TemplateSize(11, 7);
    private static final TemplateSize LARGE = new TemplateSize(27, 10);
    private static final PlacementGeometry SMALL_GEOMETRY = buildPlacementGeometry(SMALL);
    private static final PlacementGeometry LARGE_GEOMETRY = buildPlacementGeometry(LARGE);

    private static final List<ColorVariant> COLORS = List.of(
            new ColorVariant("default", "01", "01_ruined"),
            new ColorVariant("mossy", "mossy_clean", "mossy"),
            new ColorVariant("cyan", "cyan", "cyan_ruined"),
            new ColorVariant("green", "green", "green_ruined"),
            new ColorVariant("white", "white", "white_ruined")
    );

    private AltarPlacementService() { }

    public static List<AltarPlacement> ensureGenerated(MinecraftServer server, City city) {
        if (AltarManager.isGenerated(server, city.id())) return AltarManager.getPlacements(server, city.id());
        return generate(server, city);
    }

    static boolean ensureGeneratedIfMissing(MinecraftServer server, City city) {
        if (AltarManager.isGenerated(server, city.id())) return false;
        generate(server, city);
        return true;
    }

    static PreparationSeed createPreparationSeed(MinecraftServer server, City city) {
        if (AltarManager.isGenerated(server, city.id())) return null;
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        CityRegion region = city.getRegion(Level.OVERWORLD).orElseThrow(
                () -> new IllegalStateException("Altar generation requires an Overworld city region: " + city.id())
        );
        if (level == null) throw new IllegalStateException("Overworld is not available for Altar generation.");
        long seed = citySeed(level.getSeed(), city.id());
        List<AltarSpec> specs = createSpecs(server, seed, city.id());
        List<AltarPlacementPlanner.Request> requests = specs.stream()
                .map(spec -> new AltarPlacementPlanner.Request(spec.index(), spec.large()))
                .toList();
        return new PreparationSeed(city.id(), level, region, seed, specs, requests);
    }

    static Preparation buildPreparation(PreparationSeed preparationSeed) {
        AltarPlacementPlanner.PreparationSession planner = AltarPlacementPlanner.beginPreparation(
                preparationSeed.level(), preparationSeed.region(), preparationSeed.requests(),
                preparationSeed.seed(), preparationSeed.cityId()
        );
        return new Preparation(
                preparationSeed.cityId(), preparationSeed.level(), preparationSeed.seed(),
                preparationSeed.specs(), planner
        );
    }

    static Preparation beginPreparation(MinecraftServer server, City city) {
        PreparationSeed preparationSeed = createPreparationSeed(server, city);
        return preparationSeed == null ? null : buildPreparation(preparationSeed);
    }

    static AltarPlacementPlanner.PreparationStep advancePreparation(Preparation preparation) {
        return AltarPlacementPlanner.advancePreparation(preparation.planner());
    }

    static List<ChunkPos> missingRequiredChunks(Preparation preparation, int candidateIndex) {
        return AltarPlacementPlanner.missingRequiredChunks(preparation.planner(), candidateIndex);
    }

    static void exactEvaluateLoaded(Preparation preparation, int candidateIndex) {
        AltarPlacementPlanner.exactEvaluateLoaded(preparation.planner(), candidateIndex);
    }

    static List<AltarPlacement> completePreparation(
            MinecraftServer server,
            City city,
            Preparation preparation,
            List<AltarPlacementPlanner.Plan> sitePlans
    ) {
        if (!city.id().equals(preparation.cityId())) throw new IllegalArgumentException("Preparation city does not match target city.");
        if (AltarManager.isGenerated(server, city.id())) return AltarManager.getPlacements(server, city.id());
        return placePlans(server, city, preparation.level(), preparation.seed(), preparation.specs(), sitePlans);
    }

    private static List<AltarPlacement> generate(MinecraftServer server, City city) {
        Preparation preparation = beginPreparation(server, city);
        if (preparation == null) return AltarManager.getPlacements(server, city.id());
        while (true) {
            AltarPlacementPlanner.PreparationStep step = advancePreparation(preparation);
            if (step.complete()) return completePreparation(server, city, preparation, step.plans());
            for (ChunkPos chunk : missingRequiredChunks(preparation, step.candidateIndex())) {
                long started = System.nanoTime();
                preparation.level().getChunk(chunk.x(), chunk.z(), step.chunkStatus(), true);
                AfterTheEnd.LOGGER.info(
                        "chunk ({}, {}) {} {}sec city={}", chunk.x(), chunk.z(),
                        AltarPlacementPlanner.statusName(step.chunkStatus()),
                        AltarPlacementPlanner.formatSeconds((System.nanoTime() - started) / 1_000_000_000.0), city.id()
                );
            }
            exactEvaluateLoaded(preparation, step.candidateIndex());
        }
    }

    private static List<AltarSpec> createSpecs(MinecraftServer server, long seed, UUID cityId) {
        RandomSource random = RandomSource.create(seed);
        int count = rollAltarCount(random);
        int largeIndex = random.nextDouble() < count * 0.10 ? random.nextInt(count) : -1;
        List<AltarSpec> specs = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            boolean large = index == largeIndex;
            boolean ruined = random.nextDouble() < 0.10;
            ColorVariant color = COLORS.get(random.nextInt(COLORS.size()));
            TemplateSize size = large ? LARGE : SMALL;
            String suffix = ruined ? color.ruinedSuffix() : color.normalSuffix();
            String templateName = large ? "altar_" + suffix : "altar_small_" + suffix;
            Identifier templateId = Identifier.fromNamespaceAndPath("njw_after_the_end", "altar/" + templateName);
            StructureTemplate template = server.getStructureManager().get(templateId).orElseThrow(
                    () -> new IllegalStateException("Missing Altar structure template: " + templateId)
            );
            specs.add(new AltarSpec(index, templateId, template, size, color.id(), large, ruined));
        }
        return List.copyOf(specs);
    }

    private static List<AltarPlacement> placePlans(
            MinecraftServer server,
            City city,
            ServerLevel level,
            long seed,
            List<AltarSpec> specs,
            List<AltarPlacementPlanner.Plan> sitePlans
    ) {
        List<PlannedAltar> plans = new ArrayList<>(sitePlans.size());
        for (AltarPlacementPlanner.Plan site : sitePlans) {
            AltarSpec spec = specs.get(site.specIndex());
            int half = spec.size().width() / 2;
            int originY = Math.max(
                    level.getMinY(),
                    Math.min(level.getMaxY() - spec.size().height() + 1, site.targetSurfaceY() - 1)
            );
            plans.add(new PlannedAltar(
                    spec,
                    new PlacementCandidate(site.centerX() - half, originY, site.centerZ() - half, originY + 1)
            ));
        }
        plans.sort(Comparator.comparingInt(plan -> plan.spec().index()));

        List<AltarPlacement> placements = new ArrayList<>(plans.size());
        for (PlannedAltar plan : plans) {
            AltarSpec spec = plan.spec();
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
            if (!placed) throw new IllegalStateException(
                    "Failed to place Altar template " + spec.templateId() + " for city " + city.id()
            );
            restoreRuinedSocketSupports(level, origin, spec);
            placements.add(new AltarPlacement(
                    city.id(), spec.templateId().toString(), origin.getX(), origin.getY(), origin.getZ(),
                    spec.large(), spec.ruined(), spec.color()
            ));
        }

        AltarManager.markGenerated(server, city.id(), placements);
        long largeCount = placements.stream().filter(AltarPlacement::large).count();
        long ruinedCount = placements.stream().filter(AltarPlacement::ruined).count();
        AfterTheEnd.LOGGER.info(
                "Generated {} Altar(s) for city {}: large={}, ruined={}",
                placements.size(), city.id(), largeCount, ruinedCount
        );
        return List.copyOf(placements);
    }

    private static void restoreRuinedSocketSupports(ServerLevel level, BlockPos origin, AltarSpec spec) {
        if (!spec.ruined() || spec.large()) return;
        level.setBlock(origin.offset(5, 2, 2), Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), 3);
        level.setBlock(origin.offset(8, 2, 5), Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), 3);
    }

    private static int rollAltarCount(RandomSource random) {
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
        return new SurfaceSample(cursor.getY() + 1);
    }

    private static void clearTreesAndVegetation(ServerLevel level, PlacementCandidate candidate, TemplateSize size) {
        PlacementGeometry geometry = placementGeometry(size);
        int minY = Math.max(level.getMinY(), candidate.originY() - 2);
        int maxY = Math.min(level.getMaxY(), candidate.originY() + size.height() + TREE_CLEAR_EXTRA_HEIGHT);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (HorizontalOffset offset : geometry.treeClearOffsets()) {
            int x = candidate.originX() + offset.dx();
            int z = candidate.originZ() + offset.dz();
            for (int y = minY; y <= maxY; y++) {
                cursor.set(x, y, z);
                BlockState state = level.getBlockState(cursor);
                if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 3);
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
        PlacementGeometry geometry = placementGeometry(size);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (TrimOffset offset : geometry.trimOffsets()) {
            int worldX = candidate.originX() + offset.dx();
            int worldZ = candidate.originZ() + offset.dz();
            SurfaceSample sample = findSurfaceSample(level, worldX, worldZ);
            if (sample == null || sample.surfaceY() <= candidate.targetSurfaceY()) continue;
            int trimDepth = Math.min(offset.maxTrim(), sample.surfaceY() - candidate.targetSurfaceY());
            for (int depth = 0; depth < trimDepth; depth++) {
                int y = sample.surfaceY() - 1 - depth;
                cursor.set(worldX, y, worldZ);
                BlockState state = level.getBlockState(cursor);
                if (state.is(Blocks.BEDROCK) || level.getBlockEntity(cursor) != null) break;
                level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    private static PlacementGeometry placementGeometry(TemplateSize size) {
        return size.width() == LARGE.width() ? LARGE_GEOMETRY : SMALL_GEOMETRY;
    }

    private static PlacementGeometry buildPlacementGeometry(TemplateSize size) {
        List<HorizontalOffset> treeClearOffsets = new ArrayList<>();
        double clearCenter = (size.width() - 1) / 2.0;
        double clearRadius = size.width() / 2.0 + TREE_CLEAR_MARGIN;
        double clearRadiusSquared = clearRadius * clearRadius;
        for (int dx = -TREE_CLEAR_MARGIN; dx <= size.width() - 1 + TREE_CLEAR_MARGIN; dx++) {
            for (int dz = -TREE_CLEAR_MARGIN; dz <= size.width() - 1 + TREE_CLEAR_MARGIN; dz++) {
                double x = dx - clearCenter;
                double z = dz - clearCenter;
                if (x * x + z * z <= clearRadiusSquared) treeClearOffsets.add(new HorizontalOffset(dx, dz));
            }
        }
        List<TrimOffset> trimOffsets = new ArrayList<>();
        double center = (size.width() - 1) / 2.0;
        double coreRadius = size.width() / 2.0;
        double featherRadius = coreRadius + 1.0;
        double coreRadiusSquared = coreRadius * coreRadius;
        double featherRadiusSquared = featherRadius * featherRadius;
        for (int dx = -1; dx <= size.width(); dx++) {
            for (int dz = -1; dz <= size.width(); dz++) {
                double x = dx - center;
                double z = dz - center;
                double distanceSquared = x * x + z * z;
                if (distanceSquared > featherRadiusSquared) continue;
                int maxTrim = distanceSquared <= coreRadiusSquared ? MAX_TERRAIN_TRIM_DEPTH : FEATHER_TRIM_DEPTH;
                trimOffsets.add(new TrimOffset(dx, dz, maxTrim));
            }
        }
        return new PlacementGeometry(List.copyOf(treeClearOffsets), List.copyOf(trimOffsets));
    }

    private static long citySeed(long worldSeed, UUID cityId) {
        return citySeed(worldSeed, cityId.toString());
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
        return value ^ value >>> 33;
    }

    static final class PreparationSeed {
        private final UUID cityId;
        private final ServerLevel level;
        private final CityRegion region;
        private final long seed;
        private final List<AltarSpec> specs;
        private final List<AltarPlacementPlanner.Request> requests;

        private PreparationSeed(UUID cityId, ServerLevel level, CityRegion region, long seed,
                                List<AltarSpec> specs, List<AltarPlacementPlanner.Request> requests) {
            this.cityId = cityId;
            this.level = level;
            this.region = region;
            this.seed = seed;
            this.specs = specs;
            this.requests = requests;
        }

        UUID cityId() { return cityId; }
        ServerLevel level() { return level; }
        CityRegion region() { return region; }
        long seed() { return seed; }
        List<AltarSpec> specs() { return specs; }
        List<AltarPlacementPlanner.Request> requests() { return requests; }
    }

    static final class Preparation {
        private final UUID cityId;
        private final ServerLevel level;
        private final long seed;
        private final List<AltarSpec> specs;
        private final AltarPlacementPlanner.PreparationSession planner;

        private Preparation(
                UUID cityId,
                ServerLevel level,
                long seed,
                List<AltarSpec> specs,
                AltarPlacementPlanner.PreparationSession planner
        ) {
            this.cityId = cityId;
            this.level = level;
            this.seed = seed;
            this.specs = specs;
            this.planner = planner;
        }

        UUID cityId() { return cityId; }
        ServerLevel level() { return level; }
        long seed() { return seed; }
        List<AltarSpec> specs() { return specs; }
        AltarPlacementPlanner.PreparationSession planner() { return planner; }
    }

    private record ColorVariant(String id, String normalSuffix, String ruinedSuffix) { }
    private record TemplateSize(int width, int height) { }
    private record AltarSpec(int index, Identifier templateId, StructureTemplate template, TemplateSize size,
                             String color, boolean large, boolean ruined) { }
    private record SurfaceSample(int surfaceY) { }
    private record PlacementCandidate(int originX, int originY, int originZ, int targetSurfaceY) { }
    private record HorizontalOffset(int dx, int dz) { }
    private record TrimOffset(int dx, int dz, int maxTrim) { }
    private record PlacementGeometry(List<HorizontalOffset> treeClearOffsets, List<TrimOffset> trimOffsets) { }
    private record PlannedAltar(AltarSpec spec, PlacementCandidate candidate) { }
}
