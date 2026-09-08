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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class BasecampPlacementService {
    private static final int CANDIDATE_GRID_AXIS = 8;
    private static final int REFINED_CANDIDATE_COUNT = 10;
    private static final int FALLBACK_GRID_AXIS = 20;
    private static final int CITY_EDGE_MARGIN = 8;
    private static final int PREFERRED_BASECAMP_DISTANCE = 160;
    private static final int PREFERRED_EDGE_DISTANCE = 64;
    private static final int MIN_STRUCTURE_GAP = 12;
    private static final int MAX_TERRAIN_TRIM_DEPTH = 2;
    private static final int FEATHER_TRIM_DEPTH = 1;
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

        List<BasecampSpec> placementOrder = new ArrayList<>(specs);
        placementOrder.sort(Comparator.comparing(BasecampSpec::large).reversed().thenComparingInt(BasecampSpec::index));

        SearchBounds commonBounds = searchBounds(region, LARGE);
        List<CandidatePoint> candidatePool = buildCandidatePool(
                commonBounds,
                RandomSource.create(citySeed(seed, city.id() + "|candidate-pool"))
        );

        List<PlacedFootprint> reserved = new ArrayList<>(count);
        List<PlannedBasecamp> plans = new ArrayList<>(count);
        for (BasecampSpec spec : placementOrder) {
            PlacementCandidate candidate = findBestCandidate(level, region, spec.size(), reserved, candidatePool);
            reserved.add(new PlacedFootprint(candidate.centerX(), candidate.centerZ(), spec.size().width() / 2));
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

    private static List<CandidatePoint> buildCandidatePool(SearchBounds bounds, RandomSource random) {
        List<CandidatePoint> result = new ArrayList<>(CANDIDATE_GRID_AXIS * CANDIDATE_GRID_AXIS + 1);
        Set<Long> seen = new HashSet<>();
        for (int gx = 0; gx < CANDIDATE_GRID_AXIS; gx++) {
            int minX = cellBoundary(bounds.minCenterX(), bounds.maxCenterX(), gx, CANDIDATE_GRID_AXIS);
            int maxX = cellBoundary(bounds.minCenterX(), bounds.maxCenterX(), gx + 1, CANDIDATE_GRID_AXIS);
            for (int gz = 0; gz < CANDIDATE_GRID_AXIS; gz++) {
                int minZ = cellBoundary(bounds.minCenterZ(), bounds.maxCenterZ(), gz, CANDIDATE_GRID_AXIS);
                int maxZ = cellBoundary(bounds.minCenterZ(), bounds.maxCenterZ(), gz + 1, CANDIDATE_GRID_AXIS);
                int x = randomInclusive(random, minX, Math.max(minX, maxX));
                int z = randomInclusive(random, minZ, Math.max(minZ, maxZ));
                addCandidate(result, seen, x, z);
            }
        }
        addCandidate(result, seen,
                (bounds.minCenterX() + bounds.maxCenterX()) / 2,
                (bounds.minCenterZ() + bounds.maxCenterZ()) / 2);
        return List.copyOf(result);
    }

    private static int cellBoundary(int min, int max, int index, int cells) {
        if (index <= 0) return min;
        if (index >= cells) return max;
        return min + (int) Math.round((max - (double) min) * index / cells);
    }

    private static int randomInclusive(RandomSource random, int min, int max) {
        if (min >= max) return min;
        return random.nextInt(min, max + 1);
    }

    private static void addCandidate(List<CandidatePoint> result, Set<Long> seen, int x, int z) {
        long key = ((long) x << 32) ^ (z & 0xffffffffL);
        if (seen.add(key)) result.add(new CandidatePoint(x, z));
    }

    private static PlacementCandidate findBestCandidate(
            ServerLevel level,
            CityRegion region,
            TemplateSize size,
            List<PlacedFootprint> reserved,
            List<CandidatePoint> candidatePool
    ) {
        SearchBounds bounds = searchBounds(region, size);
        List<ScoredCandidate> coarse = new ArrayList<>();

        for (CandidatePoint point : candidatePool) {
            if (!bounds.contains(point.x(), point.z())) continue;
            if (!hasStructuralClearance(point.x(), point.z(), size, reserved, MIN_STRUCTURE_GAP)) continue;
            TerrainAssessment terrain = assessTerrain(level, point.x(), point.z(), size, true);
            if (terrain == null) continue;
            coarse.add(scoreCandidate(bounds, point.x(), point.z(), terrain, reserved));
        }

        coarse.sort(Comparator.comparingDouble(ScoredCandidate::score));
        ScoredCandidate best = null;
        int refinementCount = Math.min(REFINED_CANDIDATE_COUNT, coarse.size());
        for (int i = 0; i < refinementCount; i++) {
            ScoredCandidate candidate = coarse.get(i);
            TerrainAssessment terrain = assessTerrain(level, candidate.centerX(), candidate.centerZ(), size, false);
            if (terrain == null) continue;
            ScoredCandidate refined = scoreCandidate(bounds, candidate.centerX(), candidate.centerZ(), terrain, reserved);
            if (best == null || refined.score() < best.score()) best = refined;
        }

        if (best == null) best = findGuaranteedFallback(level, bounds, size, reserved);
        int half = size.width() / 2;
        int targetSurfaceY = best.terrain().targetSurfaceY();
        int originY = Math.max(level.getMinY(), Math.min(level.getMaxY() - size.height() + 1, targetSurfaceY - 1));
        PlacementCandidate result = new PlacementCandidate(
                best.centerX(), best.centerZ(), best.centerX() - half, originY, best.centerZ() - half,
                originY + 1, best.score()
        );
        AfterTheEnd.LOGGER.debug(
                "Selected Basecamp terrain: size={}x{}, center=({}, {}), surfaceY={}, score={}",
                size.width(), size.width(), result.centerX(), result.centerZ(), result.targetSurfaceY(),
                String.format("%.2f", result.score())
        );
        return result;
    }

    private static ScoredCandidate findGuaranteedFallback(
            ServerLevel level,
            SearchBounds bounds,
            TemplateSize size,
            List<PlacedFootprint> reserved
    ) {
        ScoredCandidate best = null;
        for (int gx = 0; gx < FALLBACK_GRID_AXIS; gx++) {
            int centerX = interpolate(bounds.minCenterX(), bounds.maxCenterX(), gx, FALLBACK_GRID_AXIS);
            for (int gz = 0; gz < FALLBACK_GRID_AXIS; gz++) {
                int centerZ = interpolate(bounds.minCenterZ(), bounds.maxCenterZ(), gz, FALLBACK_GRID_AXIS);
                if (!hasStructuralClearance(centerX, centerZ, size, reserved, 0)) continue;
                TerrainAssessment terrain = assessTerrain(level, centerX, centerZ, size, true);
                if (terrain == null) continue;
                ScoredCandidate scored = scoreCandidate(bounds, centerX, centerZ, terrain, reserved);
                if (best == null || scored.score() < best.score()) best = scored;
            }
        }
        if (best == null) {
            throw new IllegalStateException("City region cannot physically fit all required Basecamp structures without overlap.");
        }
        TerrainAssessment full = assessTerrain(level, best.centerX(), best.centerZ(), size, false);
        if (full != null) best = scoreCandidate(bounds, best.centerX(), best.centerZ(), full, reserved);
        AfterTheEnd.LOGGER.warn(
                "Basecamp used guaranteed fallback at ({}, {}), size={}x{}, score={}",
                best.centerX(), best.centerZ(), size.width(), size.width(), String.format("%.2f", best.score())
        );
        return best;
    }

    private static int interpolate(int min, int max, int index, int count) {
        if (count <= 1 || min == max) return min;
        return min + (int) Math.round((max - (double) min) * index / (count - 1.0));
    }

    private static ScoredCandidate scoreCandidate(
            SearchBounds bounds,
            int centerX,
            int centerZ,
            TerrainAssessment terrain,
            List<PlacedFootprint> reserved
    ) {
        double score = terrain.score()
                + spacingPenalty(nearestBasecampDistance(centerX, centerZ, reserved))
                + edgePenalty(bounds, centerX, centerZ);
        return new ScoredCandidate(centerX, centerZ, score, terrain);
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
        return ratio * ratio * 180.0;
    }

    private static double edgePenalty(SearchBounds bounds, int centerX, int centerZ) {
        int edgeDistance = Math.min(
                Math.min(centerX - bounds.minCenterX(), bounds.maxCenterX() - centerX),
                Math.min(centerZ - bounds.minCenterZ(), bounds.maxCenterZ() - centerZ)
        );
        if (edgeDistance >= PREFERRED_EDGE_DISTANCE) return 0.0;
        double ratio = (PREFERRED_EDGE_DISTANCE - edgeDistance) / (double) PREFERRED_EDGE_DISTANCE;
        return ratio * ratio * 30.0;
    }

    private static TerrainAssessment assessTerrain(
            ServerLevel level,
            int centerX,
            int centerZ,
            TemplateSize size,
            boolean sampled
    ) {
        int half = size.width() / 2;
        int originX = centerX - half;
        int originZ = centerZ - half;
        List<SurfaceSample> samples = new ArrayList<>();

        if (sampled) {
            int[] offsets = sampleOffsets(size.width());
            for (int dx : offsets) {
                for (int dz : offsets) {
                    if (!isCoreFootprintCell(size, dx, dz)) continue;
                    SurfaceSample sample = findSurfaceSample(level, originX + dx, originZ + dz);
                    if (sample == null) return null;
                    samples.add(sample);
                }
            }
        } else {
            for (int dx = 0; dx < size.width(); dx++) {
                for (int dz = 0; dz < size.width(); dz++) {
                    if (!isCoreFootprintCell(size, dx, dz)) continue;
                    SurfaceSample sample = findSurfaceSample(level, originX + dx, originZ + dz);
                    if (sample == null) return null;
                    samples.add(sample);
                }
            }
        }
        if (samples.isEmpty()) return null;
        return optimizeSurface(samples);
    }

    private static TerrainAssessment optimizeSurface(List<SurfaceSample> samples) {
        int maxSurface = samples.stream().mapToInt(SurfaceSample::surfaceY).max().orElseThrow();
        int minSurface = samples.stream().mapToInt(SurfaceSample::surfaceY).min().orElseThrow();
        int minimumTarget = maxSurface - MAX_TERRAIN_TRIM_DEPTH;
        TerrainAssessment best = null;

        for (int target = minimumTarget; target <= maxSurface; target++) {
            double cutCost = 0.0;
            double floatCost = 0.0;
            int cutCells = 0;
            int floatingCells = 0;
            int severeFloatingCells = 0;
            int fluidCells = 0;

            for (SurfaceSample sample : samples) {
                int difference = sample.surfaceY() - target;
                if (difference > 0) {
                    cutCells++;
                    cutCost += difference == 1 ? 1.5 : 4.0;
                } else if (difference < 0) {
                    int gap = -difference;
                    floatingCells++;
                    if (gap == 1) floatCost += 0.15;
                    else if (gap == 2) floatCost += 0.8;
                    else {
                        severeFloatingCells++;
                        double excess = gap - 2.0;
                        floatCost += 3.0 + excess * excess * 18.0;
                    }
                }
                if (sample.fluid()) fluidCells++;
            }

            double count = samples.size();
            double cutFraction = cutCells / count;
            double floatingFraction = floatingCells / count;
            double severeFloatingFraction = severeFloatingCells / count;
            double fluidFraction = fluidCells / count;
            double roughness = maxSurface - minSurface;
            double score = (cutCost + floatCost) / count
                    + cutFraction * 8.0
                    + floatingFraction * 6.0
                    + severeFloatingFraction * 220.0
                    + fluidFraction * 1200.0
                    + roughness * 2.5;

            TerrainAssessment assessment = new TerrainAssessment(
                    target, score, roughness, cutFraction, floatingFraction, severeFloatingFraction, fluidFraction
            );
            if (best == null || assessment.score() < best.score()) best = assessment;
        }
        return best;
    }

    private static int[] sampleOffsets(int width) {
        return new int[]{0, width / 4, width / 2, (width * 3) / 4, width - 1};
    }

    private static boolean isCoreFootprintCell(TemplateSize size, int dx, int dz) {
        double center = (size.width() - 1) / 2.0;
        double x = dx - center;
        double z = dz - center;
        double radius = size.width() / 2.0;
        return x * x + z * z <= radius * radius;
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
                    if (state.is(BlockTags.REPLACEABLE)) level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    private static void trimTerrain(ServerLevel level, PlacementCandidate candidate, TemplateSize size) {
        double center = (size.width() - 1) / 2.0;
        double coreRadius = size.width() / 2.0;
        double featherRadius = coreRadius + 1.5;
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
    private record PlacedFootprint(int centerX, int centerZ, int halfWidth) { }
    private record CandidatePoint(int x, int z) { }
    private record SearchBounds(int minCenterX, int maxCenterX, int minCenterZ, int maxCenterZ) {
        boolean contains(int x, int z) {
            return x >= minCenterX && x <= maxCenterX && z >= minCenterZ && z <= maxCenterZ;
        }
    }
    private record SurfaceSample(int surfaceY, boolean fluid) { }
    private record TerrainAssessment(int targetSurfaceY, double score, double roughness, double cutFraction,
                                     double floatingFraction, double severeFloatingFraction, double fluidFraction) { }
    private record ScoredCandidate(int centerX, int centerZ, double score, TerrainAssessment terrain) { }
    private record PlacementCandidate(int centerX, int centerZ, int originX, int originY, int originZ,
                                      int targetSurfaceY, double score) { }
    private record PlannedBasecamp(BasecampSpec spec, PlacementCandidate candidate) { }
}
