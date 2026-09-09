package net.njw.aftertheend.city.basecamp;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.city.CityRegion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class BasecampPlacementPlanner {
    private static final int FPS_CANDIDATE_COUNT = 64;
    private static final int CENTER_AXIS_SAMPLES = 4;
    private static final int OPTIMIZER_RESTARTS = 16;
    private static final int OPTIMIZER_MAX_PASSES = 12;
    private static final int CITY_EDGE_MARGIN = 8;
    private static final int MIN_STRUCTURE_GAP = 12;
    private static final double QUARTER_LIMIT = 0.25;
    private static final double EFFECTIVE_REJECT_LIMIT = 0.30;
    private static final double EFFECTIVE_REJECT_PENALTY = 1_000_000.0;
    private static final double OVERLAP_PENALTY = 100_000_000.0;
    private static final double MIN_DISTANCE_WEIGHT = 260.0;
    private static final double PAIR_DISTANCE_WEIGHT = 18.0;
    private static final double AVERAGE_DISTANCE_REWARD = 3.0;

    private static final TemplateSize SMALL = new TemplateSize(11);
    private static final TemplateSize LARGE = new TemplateSize(27);
    private static final int FOOTPRINT_SAMPLE_COUNT = 9;
    private static final Map<FootprintStencilKey, FootprintStencil> FOOTPRINT_STENCILS = new ConcurrentHashMap<>();

    private BasecampPlacementPlanner() { }

    static List<Plan> plan(ServerLevel level, CityRegion region, List<Request> requests, long seed, UUID cityId) {
        if (requests.isEmpty()) return List.of();

        int count = requests.size();
        boolean hasLarge = requests.stream().anyMatch(Request::large);
        List<ChunkSeed> sampledChunks = farthestPointSampleChunks(region, seed);
        SearchBounds smallBounds = searchBounds(region, SMALL);
        SearchBounds largeBounds = searchBounds(region, LARGE);

        List<ChunkEvaluation> evaluated = new ArrayList<>(sampledChunks.size());
        for (int index = 0; index < sampledChunks.size(); index++) {
            ChunkSeed chunk = sampledChunks.get(index);
            Site small = evaluateSiteInChunk(level, chunk, SMALL, smallBounds, index + 1, sampledChunks.size());
            evaluated.add(new ChunkEvaluation(chunk, small));
        }

        // Candidate chunks are selected entirely from Small terrain scores and spacing. Large is evaluated only
        // after the final N chunks are fixed, so the FPS candidate pool never pays the Large evaluation cost.
        double targetDistance = preferredDistance(region, count);
        SelectionResult selection = optimizeJointSelection(
                evaluated,
                count,
                targetDistance,
                RandomSource.create(mix(seed ^ 0x6a09e667f3bcc909L))
        );

        int largePosition = -1;
        Site bestLargeSite = null;
        if (hasLarge) {
            for (int position = 0; position < selection.selected().length; position++) {
                ChunkEvaluation candidate = evaluated.get(selection.selected()[position]);
                Site large = evaluateSiteInChunk(
                        level, candidate.chunk(), LARGE, largeBounds, position + 1, selection.selected().length
                );
                if (bestLargeSite == null || large.score() < bestLargeSite.score()) {
                    bestLargeSite = large;
                    largePosition = position;
                }
            }
        }

        List<ChosenSite> chosen = new ArrayList<>(count);
        for (int position = 0; position < selection.selected().length; position++) {
            ChunkEvaluation candidate = evaluated.get(selection.selected()[position]);
            if (position == largePosition) {
                chosen.add(new ChosenSite(candidate, bestLargeSite, true));
                continue;
            }
            chosen.add(new ChosenSite(candidate, candidate.small(), false));
        }

        List<Request> smallRequests = requests.stream().filter(request -> !request.large()).sorted(Comparator.comparingInt(Request::specIndex)).toList();
        Request largeRequest = requests.stream().filter(Request::large).findFirst().orElse(null);
        List<ChosenSite> smallChosen = chosen.stream().filter(site -> !site.large()).sorted(Comparator.comparingInt(site -> site.candidate().chunk().sampleIndex())).toList();
        ChosenSite largeChosen = chosen.stream().filter(ChosenSite::large).findFirst().orElse(null);

        List<Plan> result = new ArrayList<>(count);
        if (largeRequest != null && largeChosen != null) result.add(toPlan(largeRequest.specIndex(), largeChosen));
        for (int i = 0; i < Math.min(smallRequests.size(), smallChosen.size()); i++) {
            result.add(toPlan(smallRequests.get(i).specIndex(), smallChosen.get(i)));
        }
        result.sort(Comparator.comparingInt(Plan::specIndex));

        writeDebugReport(cityId, seed, region, targetDistance, evaluated, selection, result);
        AfterTheEnd.LOGGER.info(
                "Basecamp planner {}: FPS candidates={}, structures={}, targetDistance={} blocks, objective={}",
                cityId, sampledChunks.size(), count, format(targetDistance), format(selection.objective())
        );
        for (Plan plan : result) {
            AfterTheEnd.LOGGER.info(
                    "Basecamp planner {} spec={} {} chunk=({}, {}) center=({}, {}, {}) terrain={} buried={} floating={} water={}",
                    cityId, plan.specIndex(), plan.large() ? "large" : "small", plan.chunkX(), plan.chunkZ(),
                    plan.centerX(), plan.targetSurfaceY(), plan.centerZ(), format(plan.terrainScore()),
                    percent(plan.buriedFraction()), percent(plan.floatingFraction()), percent(plan.submergedFraction())
            );
        }
        return List.copyOf(result);
    }

    private static Plan toPlan(int specIndex, ChosenSite chosen) {
        Site site = chosen.site();
        TerrainAssessment terrain = site.terrain();
        ChunkSeed chunk = chosen.candidate().chunk();
        return new Plan(
                specIndex, chunk.sampleIndex(), chunk.chunkX(), chunk.chunkZ(), site.centerX(), site.centerZ(),
                terrain.targetSurfaceY(), site.score(), terrain.buriedFraction(), terrain.floatingFraction(),
                terrain.submergedFraction(), chosen.large()
        );
    }

    private static List<ChunkSeed> farthestPointSampleChunks(CityRegion region, long seed) {
        int width = region.widthChunks();
        int height = region.heightChunks();
        int total = width * height;
        int wanted = Math.min(FPS_CANDIDATE_COUNT, total);
        if (wanted <= 0) return List.of();

        int[] chunkX = new int[total];
        int[] chunkZ = new int[total];
        int cursor = 0;
        for (int x = region.minChunkX(); x <= region.maxChunkX(); x++) {
            for (int z = region.minChunkZ(); z <= region.maxChunkZ(); z++) {
                chunkX[cursor] = x;
                chunkZ[cursor] = z;
                cursor++;
            }
        }

        boolean[] selected = new boolean[total];
        int[] nearestDistanceSquared = new int[total];
        Arrays.fill(nearestDistanceSquared, Integer.MAX_VALUE);
        RandomSource random = RandomSource.create(mix(seed ^ 0xbb67ae8584caa73bL));
        int current = random.nextInt(total);
        List<ChunkSeed> result = new ArrayList<>(wanted);

        for (int sampleIndex = 0; sampleIndex < wanted; sampleIndex++) {
            selected[current] = true;
            result.add(new ChunkSeed(sampleIndex, chunkX[current], chunkZ[current]));

            int bestIndex = -1;
            int bestDistance = -1;
            int selectedX = chunkX[current];
            int selectedZ = chunkZ[current];
            for (int i = 0; i < total; i++) {
                if (selected[i]) continue;
                int dx = chunkX[i] - selectedX;
                int dz = chunkZ[i] - selectedZ;
                int distanceSquared = dx * dx + dz * dz;
                if (distanceSquared < nearestDistanceSquared[i]) nearestDistanceSquared[i] = distanceSquared;
                if (nearestDistanceSquared[i] > bestDistance) {
                    bestDistance = nearestDistanceSquared[i];
                    bestIndex = i;
                }
            }
            if (bestIndex < 0) break;
            current = bestIndex;
        }
        return List.copyOf(result);
    }

    private static Site evaluateSiteInChunk(
            ServerLevel level,
            ChunkSeed chunk,
            TemplateSize size,
            SearchBounds bounds,
            int progressIndex,
            int progressTotal
    ) {
        long startedNanos = System.nanoTime();
        int chunkMinX = chunk.chunkX() << 4;
        int chunkMinZ = chunk.chunkZ() << 4;
        int minX = Math.max(chunkMinX, bounds.minCenterX());
        int maxX = Math.min(chunkMinX + 15, bounds.maxCenterX());
        int minZ = Math.max(chunkMinZ, bounds.minCenterZ());
        int maxZ = Math.min(chunkMinZ + 15, bounds.maxCenterZ());
        if (minX > maxX || minZ > maxZ) {
            Site fallback = emergencySiteInChunk(level, chunk, size, bounds);
            logEvaluation(progressIndex, progressTotal, elapsedSeconds(startedNanos), chunk, size, fallback, false, 0, 0, 0, 0, true, false, true);
            return fallback;
        }

        Site best = null;
        int totalCenters = 0;
        int validCenters = 0;
        int nullSampleCount = 0;
        int cachedSurfaceCount = 0;
        boolean exceptionThrown = false;
        try {
            level.getChunk(chunk.chunkX(), chunk.chunkZ());
            FootprintStencil footprint = footprintStencil(
                    minX - chunkMinX, maxX - chunkMinX,
                    minZ - chunkMinZ, maxZ - chunkMinZ,
                    size
            );
            totalCenters = footprint.centerXs().length;

            SurfaceSample[] surfaceSamples = new SurfaceSample[footprint.sampleXs().length];
            for (int sampleIndex = 0; sampleIndex < surfaceSamples.length; sampleIndex++) {
                SurfaceSample sample = readSurfaceSample(
                        level,
                        chunkMinX + footprint.sampleXs()[sampleIndex],
                        chunkMinZ + footprint.sampleZs()[sampleIndex]
                );
                surfaceSamples[sampleIndex] = sample;
                if (sample == null) nullSampleCount++;
                else cachedSurfaceCount++;
            }

            int[] supportYs = new int[FOOTPRINT_SAMPLE_COUNT];
            int[] fluidTopYs = new int[FOOTPRINT_SAMPLE_COUNT];
            for (int centerIndex = 0; centerIndex < totalCenters; centerIndex++) {
                TerrainAssessment terrain = assessTerrain(footprint, surfaceSamples, centerIndex, supportYs, fluidTopYs);
                if (terrain == null) continue;
                validCenters++;
                int x = chunkMinX + footprint.centerXs()[centerIndex];
                int z = chunkMinZ + footprint.centerZs()[centerIndex];
                double score = terrain.score() + edgePenalty(bounds, x, z);
                if (best == null || score < best.score()) best = new Site(x, z, score, terrain);
            }
        } catch (RuntimeException exception) {
            exceptionThrown = true;
            AfterTheEnd.LOGGER.warn(
                    "Basecamp terrain evaluation failed for sampled chunk ({}, {}), size={}; using fallback",
                    chunk.chunkX(), chunk.chunkZ(), size.width(), exception
            );
        }

        boolean fallbackUsed = best == null;
        Site result = fallbackUsed ? emergencySiteInChunk(level, chunk, size, bounds) : best;
        logEvaluation(
                progressIndex, progressTotal, elapsedSeconds(startedNanos), chunk, size, result, nullSampleCount > 0,
                nullSampleCount, cachedSurfaceCount, validCenters, totalCenters,
                fallbackUsed, exceptionThrown, false
        );
        return result;
    }

    private static FootprintStencil footprintStencil(
            int minLocalX,
            int maxLocalX,
            int minLocalZ,
            int maxLocalZ,
            TemplateSize size
    ) {
        FootprintStencilKey key = new FootprintStencilKey(minLocalX, maxLocalX, minLocalZ, maxLocalZ, size.width());
        return FOOTPRINT_STENCILS.computeIfAbsent(key, BasecampPlacementPlanner::buildFootprintStencil);
    }

    private static FootprintStencil buildFootprintStencil(FootprintStencilKey key) {
        int[] xs = sampledAxis(key.minLocalX(), key.maxLocalX());
        int[] zs = sampledAxis(key.minLocalZ(), key.maxLocalZ());
        int totalCenters = xs.length * zs.length;
        int maximumSamples = totalCenters * FOOTPRINT_SAMPLE_COUNT;
        int[] centerXs = new int[totalCenters];
        int[] centerZs = new int[totalCenters];
        int[] sampleXs = new int[maximumSamples];
        int[] sampleZs = new int[maximumSamples];
        int[] sampleIndices = new int[maximumSamples];
        int uniqueSamples = 0;
        int centerIndex = 0;
        int half = key.width() / 2;

        for (int centerX : xs) {
            for (int centerZ : zs) {
                centerXs[centerIndex] = centerX;
                centerZs[centerIndex] = centerZ;
                int originX = centerX - half;
                int originZ = centerZ - half;
                int centerSampleBase = centerIndex * FOOTPRINT_SAMPLE_COUNT;
                int centerSampleOffset = 0;
                for (int dxIndex = 0; dxIndex < 3; dxIndex++) {
                    int sampleX = originX + footprintSampleOffset(key.width(), dxIndex);
                    for (int dzIndex = 0; dzIndex < 3; dzIndex++) {
                        int sampleZ = originZ + footprintSampleOffset(key.width(), dzIndex);
                        int sampleIndex = findSampleIndex(sampleXs, sampleZs, uniqueSamples, sampleX, sampleZ);
                        if (sampleIndex < 0) {
                            sampleIndex = uniqueSamples++;
                            sampleXs[sampleIndex] = sampleX;
                            sampleZs[sampleIndex] = sampleZ;
                        }
                        sampleIndices[centerSampleBase + centerSampleOffset++] = sampleIndex;
                    }
                }
                centerIndex++;
            }
        }

        return new FootprintStencil(
                centerXs,
                centerZs,
                Arrays.copyOf(sampleXs, uniqueSamples),
                Arrays.copyOf(sampleZs, uniqueSamples),
                sampleIndices
        );
    }

    private static int findSampleIndex(int[] sampleXs, int[] sampleZs, int count, int x, int z) {
        for (int index = 0; index < count; index++) {
            if (sampleXs[index] == x && sampleZs[index] == z) return index;
        }
        return -1;
    }

    private static int footprintSampleOffset(int width, int index) {
        if (index == 0) return 0;
        if (index == 1) return width / 2;
        return width - 1;
    }

    private static void logEvaluation(
            int progressIndex,
            int progressTotal,
            double elapsedSeconds,
            ChunkSeed chunk,
            TemplateSize size,
            Site site,
            boolean surfaceSampleNull,
            int nullSampleCount,
            int cachedSurfaceCount,
            int validCenters,
            int totalCenters,
            boolean fallback,
            boolean exception,
            boolean boundsInvalid
    ) {
        AfterTheEnd.LOGGER.info(
                "Basecamp eval [{}/{}] {}sec chunk=({}, {}) size={} score={} surfaceSampleNull={} nullSampleCount={} cachedSurfaceCount={} validCenters={}/{} fallback={} exception={} boundsInvalid={}",
                progressIndex, progressTotal, formatSeconds(elapsedSeconds), chunk.chunkX(), chunk.chunkZ(), size.width(), format(site.score()),
                surfaceSampleNull, nullSampleCount, cachedSurfaceCount, validCenters, totalCenters,
                fallback, exception, boundsInvalid
        );
    }

    private static Site emergencySiteInChunk(ServerLevel level, ChunkSeed chunk, TemplateSize size, SearchBounds bounds) {
        int chunkMinX = chunk.chunkX() << 4;
        int chunkMinZ = chunk.chunkZ() << 4;
        int x = clamp(chunkMinX + 8, bounds.minCenterX(), bounds.maxCenterX());
        int z = clamp(chunkMinZ + 8, bounds.minCenterZ(), bounds.maxCenterZ());
        int target;
        try {
            level.getChunk(x >> 4, z >> 4);
            target = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        } catch (RuntimeException exception) {
            target = level.getSeaLevel();
            AfterTheEnd.LOGGER.warn(
                    "Basecamp emergency height lookup failed at ({}, {}); using sea-level fallback Y={}",
                    x, z, target, exception
            );
        }
        target = Math.max(level.getMinY() + 1, Math.min(level.getMaxY(), target));
        TerrainAssessment terrain = new TerrainAssessment(target, EFFECTIVE_REJECT_PENALTY * 10.0, 0.0, 1.0, 0.0, 0.0);
        return new Site(x, z, terrain.score(), terrain);
    }

    private static SelectionResult optimizeJointSelection(
            List<ChunkEvaluation> candidates,
            int count,
            double targetDistance,
            RandomSource random
    ) {
        int candidateCount = candidates.size();
        if (candidateCount < count) {
            throw new IllegalStateException("Basecamp FPS candidate count " + candidateCount + " is smaller than requested structure count " + count);
        }
        OptimizerContext context = buildOptimizerContext(candidates, targetDistance);
        SelectionResult globalBest = null;
        List<Double> restartObjectives = new ArrayList<>();

        for (int restart = 0; restart < OPTIMIZER_RESTARTS; restart++) {
            int[] selected = restart == 0
                    ? bestTerrainInitialization(context, count)
                    : randomInitialization(candidateCount, count, random);
            SelectionResult local = improveByJointSwaps(selected, context);
            restartObjectives.add(local.objective());
            if (globalBest == null || local.objective() < globalBest.objective()) globalBest = local;
        }

        if (globalBest == null) {
            int[] selected = bestTerrainInitialization(context, count);
            globalBest = new SelectionResult(Arrays.copyOf(selected, selected.length), evaluateObjective(selected, context), List.of());
        }
        return new SelectionResult(globalBest.selected(), globalBest.objective(), List.copyOf(restartObjectives));
    }

    private static OptimizerContext buildOptimizerContext(List<ChunkEvaluation> candidates, double targetDistance) {
        int count = candidates.size();
        double targetScale = Math.max(1.0, targetDistance);
        double[] terrainScores = new double[count];
        double[][] pairDistances = new double[count][count];
        double[][] pairDistancePenalties = new double[count][count];
        boolean[][] pairOverlaps = new boolean[count][count];

        for (int i = 0; i < count; i++) {
            Site site = candidates.get(i).small();
            terrainScores[i] = site == null ? Double.POSITIVE_INFINITY : site.score();
        }
        int half = SMALL.width() / 2;
        for (int i = 0; i < count; i++) {
            Site a = candidates.get(i).small();
            for (int j = i + 1; j < count; j++) {
                Site b = candidates.get(j).small();
                if (a == null || b == null) {
                    pairDistances[i][j] = pairDistances[j][i] = Double.POSITIVE_INFINITY;
                    continue;
                }
                boolean overlap = overlaps(a, half, b, half);
                double pairDistance = distance(a.centerX(), a.centerZ(), b.centerX(), b.centerZ());
                double normalized = pairDistance / targetScale;
                double pairDistancePenalty = PAIR_DISTANCE_WEIGHT / (0.20 + normalized * normalized);
                pairOverlaps[i][j] = pairOverlaps[j][i] = overlap;
                pairDistances[i][j] = pairDistances[j][i] = pairDistance;
                pairDistancePenalties[i][j] = pairDistancePenalties[j][i] = pairDistancePenalty;
            }
        }
        return new OptimizerContext(terrainScores, pairDistances, pairDistancePenalties, pairOverlaps, targetScale);
    }

    private static int[] bestTerrainInitialization(OptimizerContext context, int count) {
        if (context.terrainScores().length < count) {
            throw new IllegalStateException("Basecamp optimizer requires at least " + count + " candidates, got " + context.terrainScores().length);
        }
        boolean[] chosen = new boolean[context.terrainScores().length];
        int[] selected = new int[count];
        for (int slot = 0; slot < count; slot++) {
            int bestIndex = -1;
            double bestScore = 0.0;
            for (int candidate = 0; candidate < context.terrainScores().length; candidate++) {
                if (chosen[candidate]) continue;
                double score = context.terrainScores()[candidate];
                if (bestIndex < 0 || Double.compare(score, bestScore) < 0) {
                    bestIndex = candidate;
                    bestScore = score;
                }
            }
            selected[slot] = bestIndex;
            chosen[bestIndex] = true;
        }
        return selected;
    }

    private static int[] randomInitialization(int candidateCount, int count, RandomSource random) {
        if (candidateCount < count) {
            throw new IllegalStateException("Basecamp optimizer requires at least " + count + " candidates, got " + candidateCount);
        }
        int[] indices = new int[candidateCount];
        for (int i = 0; i < candidateCount; i++) indices[i] = i;
        for (int i = 0; i < count; i++) {
            int swap = i + random.nextInt(candidateCount - i);
            int temp = indices[i];
            indices[i] = indices[swap];
            indices[swap] = temp;
        }
        return Arrays.copyOf(indices, count);
    }

    private static SelectionResult improveByJointSwaps(int[] initial, OptimizerContext context) {
        int[] current = Arrays.copyOf(initial, initial.length);
        double currentObjective = evaluateObjective(current, context);

        for (int pass = 0; pass < OPTIMIZER_MAX_PASSES; pass++) {
            boolean[] chosen = new boolean[context.terrainScores().length];
            for (int index : current) chosen[index] = true;
            double bestObjective = currentObjective;
            int bestSlot = -1;
            int bestCandidate = -1;

            for (int slot = 0; slot < current.length; slot++) {
                int old = current[slot];
                for (int candidate = 0; candidate < context.terrainScores().length; candidate++) {
                    if (chosen[candidate]) continue;
                    current[slot] = candidate;
                    double objective = evaluateObjective(current, context);
                    if (objective + 1.0e-9 < bestObjective) {
                        bestObjective = objective;
                        bestSlot = slot;
                        bestCandidate = candidate;
                    }
                }
                current[slot] = old;
            }

            if (bestSlot < 0) break;
            current[bestSlot] = bestCandidate;
            currentObjective = bestObjective;
        }
        return new SelectionResult(Arrays.copyOf(current, current.length), currentObjective, List.of());
    }

    private static double evaluateObjective(int[] selected, OptimizerContext context) {
        double terrain = 0.0;
        for (int index : selected) {
            double score = context.terrainScores()[index];
            if (Double.isInfinite(score)) return Double.POSITIVE_INFINITY;
            terrain += score;
        }

        double pairPenalty = 0.0;
        double minDistance = Double.POSITIVE_INFINITY;
        double distanceSum = 0.0;
        int pairCount = 0;
        for (int i = 0; i < selected.length; i++) {
            int a = selected[i];
            for (int j = i + 1; j < selected.length; j++) {
                int b = selected[j];
                if (context.pairOverlaps()[a][b]) pairPenalty += OVERLAP_PENALTY;
                double pairDistance = context.pairDistances()[a][b];
                minDistance = Math.min(minDistance, pairDistance);
                distanceSum += pairDistance;
                pairCount++;
                pairPenalty += context.pairDistancePenalties()[a][b];
            }
        }

        double minPenalty = 0.0;
        double averageReward = 0.0;
        if (pairCount > 0) {
            double shortfall = Math.max(0.0, 1.0 - minDistance / context.targetScale());
            minPenalty = MIN_DISTANCE_WEIGHT * shortfall * shortfall;
            double averageDistance = distanceSum / pairCount;
            averageReward = AVERAGE_DISTANCE_REWARD * averageDistance / context.targetScale();
        }
        return terrain + pairPenalty + minPenalty - averageReward;
    }

    private static boolean overlaps(Site a, int halfA, Site b, int halfB) {
        int required = halfA + halfB + MIN_STRUCTURE_GAP;
        return Math.abs(a.centerX() - b.centerX()) <= required && Math.abs(a.centerZ() - b.centerZ()) <= required;
    }

    private static double preferredDistance(CityRegion region, int count) {
        double shorterSide = Math.min(region.widthChunks(), region.heightChunks()) * 16.0;
        return 0.80 * shorterSide / Math.sqrt(Math.max(1, count));
    }

    private static TerrainAssessment assessTerrain(
            FootprintStencil footprint,
            SurfaceSample[] surfaceSamples,
            int centerIndex,
            int[] supportYs,
            int[] fluidTopYs
    ) {
        int sampleBase = centerIndex * FOOTPRINT_SAMPLE_COUNT;
        for (int sampleOffset = 0; sampleOffset < FOOTPRINT_SAMPLE_COUNT; sampleOffset++) {
            SurfaceSample sample = surfaceSamples[footprint.sampleIndices()[sampleBase + sampleOffset]];
            if (sample == null) return null;
            supportYs[sampleOffset] = sample.supportY();
            fluidTopYs[sampleOffset] = sample.fluidTopY();
        }
        return optimizeSurface(supportYs, fluidTopYs);
    }

    private static TerrainAssessment optimizeSurface(int[] supportYs, int[] fluidTopYs) {
        int minSurface = supportYs[0];
        int maxSurface = supportYs[0];
        for (int index = 1; index < supportYs.length; index++) {
            minSurface = Math.min(minSurface, supportYs[index]);
            maxSurface = Math.max(maxSurface, supportYs[index]);
        }

        int bestTarget = minSurface;
        double bestScore = Double.POSITIVE_INFINITY;
        double bestBuriedFraction = 0.0;
        double bestFloatingFraction = 0.0;
        double bestSubmergedFraction = 0.0;
        double roughness = maxSurface - minSurface;

        for (int target = minSurface; target <= maxSurface; target++) {
            double burialDepthCost = 0.0;
            double floatingDepthCost = 0.0;
            double waterDepthCost = 0.0;
            int buriedCells = 0;
            int floatingCells = 0;
            int submergedCells = 0;

            for (int index = 0; index < supportYs.length; index++) {
                int difference = supportYs[index] - target;
                if (difference > 0) {
                    buriedCells++;
                    burialDepthCost += burialDepthPenalty(difference);
                } else if (difference < 0) {
                    floatingCells++;
                    floatingDepthCost += floatingDepthPenalty(-difference);
                }
                if (fluidTopYs[index] >= target) {
                    submergedCells++;
                    waterDepthCost += fluidTopYs[index] - target + 1.0;
                }
            }

            double count = supportYs.length;
            double buriedFraction = buriedCells / count;
            double floatingFraction = floatingCells / count;
            double submergedFraction = submergedCells / count;
            double score = (burialDepthCost + floatingDepthCost) / count
                    + quarterFractionPenalty(buriedFraction)
                    + quarterFractionPenalty(floatingFraction)
                    + quarterFractionPenalty(submergedFraction)
                    + (waterDepthCost / count) * 20.0
                    + roughness * 0.30;

            if (score < bestScore) {
                bestTarget = target;
                bestScore = score;
                bestBuriedFraction = buriedFraction;
                bestFloatingFraction = floatingFraction;
                bestSubmergedFraction = submergedFraction;
            }
        }
        return new TerrainAssessment(
                bestTarget, bestScore, roughness, bestBuriedFraction, bestFloatingFraction, bestSubmergedFraction
        );
    }

    private static double burialDepthPenalty(int depth) {
        if (depth <= 1) return 0.08;
        if (depth == 2) return 0.20;
        if (depth == 3) return 0.55;
        double excess = depth - 3.0;
        return 1.0 + excess * excess * 2.5;
    }

    private static double floatingDepthPenalty(int depth) {
        if (depth <= 1) return 0.10;
        if (depth == 2) return 0.28;
        if (depth == 3) return 0.75;
        double excess = depth - 3.0;
        return 1.4 + excess * excess * 4.0;
    }

    private static double quarterFractionPenalty(double fraction) {
        if (fraction <= QUARTER_LIMIT) {
            double ratio = fraction / QUARTER_LIMIT;
            return ratio * ratio * 8.0;
        }
        if (fraction < EFFECTIVE_REJECT_LIMIT) {
            double transition = (fraction - QUARTER_LIMIT) / (EFFECTIVE_REJECT_LIMIT - QUARTER_LIMIT);
            return 8.0 + transition * transition * 1_992.0;
        }
        double excess = fraction - EFFECTIVE_REJECT_LIMIT;
        return EFFECTIVE_REJECT_PENALTY + excess * 2_000_000.0 + excess * excess * 5_000_000.0;
    }

    private static SurfaceSample readSurfaceSample(ServerLevel level, int x, int z) {
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        if (top < level.getMinY()) return null;

        int fluidTopY = Integer.MIN_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, top, z);
        for (int y = top; y >= level.getMinY(); y--) {
            cursor.setY(y);
            BlockState state = level.getBlockState(cursor);
            if (!level.getFluidState(cursor).isEmpty()) {
                if (fluidTopY == Integer.MIN_VALUE) fluidTopY = y;
                continue;
            }
            if (state.isAir() || state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.is(BlockTags.REPLACEABLE)) continue;
            return new SurfaceSample(y + 1, fluidTopY);
        }
        return null;
    }

    private static SearchBounds searchBounds(CityRegion region, TemplateSize size) {
        int half = size.width() / 2;
        int minCenterX = region.minBlockX() + half + CITY_EDGE_MARGIN;
        int maxCenterX = region.maxBlockX() - half - CITY_EDGE_MARGIN;
        int minCenterZ = region.minBlockZ() + half + CITY_EDGE_MARGIN;
        int maxCenterZ = region.maxBlockZ() - half - CITY_EDGE_MARGIN;
        if (minCenterX > maxCenterX || minCenterZ > maxCenterZ) {
            int centerX = (region.minBlockX() + region.maxBlockX()) / 2;
            int centerZ = (region.minBlockZ() + region.maxBlockZ()) / 2;
            return new SearchBounds(centerX, centerX, centerZ, centerZ);
        }
        return new SearchBounds(minCenterX, maxCenterX, minCenterZ, maxCenterZ);
    }

    private static double edgePenalty(SearchBounds bounds, int centerX, int centerZ) {
        int edgeDistance = Math.min(
                Math.min(centerX - bounds.minCenterX(), bounds.maxCenterX() - centerX),
                Math.min(centerZ - bounds.minCenterZ(), bounds.maxCenterZ() - centerZ)
        );
        if (edgeDistance >= 32) return 0.0;
        double ratio = (32 - Math.max(0, edgeDistance)) / 32.0;
        return ratio * ratio * 4.0;
    }

    private static int[] sampledAxis(int min, int max) {
        if (min >= max) return new int[]{min};
        int[] values = new int[CENTER_AXIS_SAMPLES];
        int count = 0;
        for (int i = 0; i < CENTER_AXIS_SAMPLES; i++) {
            int value = min + (int) Math.round((max - (double) min) * i / (CENTER_AXIS_SAMPLES - 1.0));
            if (count == 0 || values[count - 1] != value) values[count++] = value;
        }
        return count == values.length ? values : java.util.Arrays.copyOf(values, count);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double distance(int x1, int z1, int x2, int z2) {
        double dx = x1 - (double) x2;
        double dz = z1 - (double) z2;
        return Math.sqrt(dx * dx + dz * dz);
    }


    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        return value ^ (value >>> 33);
    }

    private static void writeDebugReport(
            UUID cityId,
            long seed,
            CityRegion region,
            double targetDistance,
            List<ChunkEvaluation> allCandidates,
            SelectionResult selection,
            List<Plan> plans
    ) {
        try {
            Path directory = Path.of("logs", "after-the-end", "basecamp-placement");
            Files.createDirectories(directory);
            String safeCityId = cityId.toString().replaceAll("[^A-Za-z0-9._-]", "_");
            Path path = directory.resolve(safeCityId + "-" + Long.toUnsignedString(seed, 16) + ".csv");
            Set<Integer> selectedSampleIndices = new HashSet<>();
            for (int candidateIndex : selection.selected()) selectedSampleIndices.add(allCandidates.get(candidateIndex).chunk().sampleIndex());

            StringBuilder out = new StringBuilder(64 * 1024);
            out.append("# cityId,").append(cityId).append('\n');
            out.append("# seed,").append(Long.toUnsignedString(seed)).append('\n');
            out.append("# regionChunks,").append(region.minChunkX()).append(',').append(region.minChunkZ()).append(',')
                    .append(region.maxChunkX()).append(',').append(region.maxChunkZ()).append('\n');
            out.append("# fpsCandidateCount,").append(Math.min(FPS_CANDIDATE_COUNT, region.widthChunks() * region.heightChunks())).append('\n');
            out.append("# targetDistanceBlocks,").append(format(targetDistance)).append('\n');
            out.append("# optimizerObjective,").append(format(selection.objective())).append('\n');
            out.append("# optimizerRestartObjectives,");
            for (int i = 0; i < selection.restartObjectives().size(); i++) {
                if (i > 0) out.append('|');
                out.append(format(selection.restartObjectives().get(i)));
            }
            out.append('\n');
            out.append("row,sampleIndex,chunkX,chunkZ,size,centerX,centerZ,targetY,terrainScore,buriedFraction,floatingFraction,waterFraction,selected,specIndex\n");

            for (ChunkEvaluation candidate : allCandidates) {
                appendSiteCsv(out, "candidate", candidate.chunk(), "small", candidate.small(), selectedSampleIndices.contains(candidate.chunk().sampleIndex()), "");
            }
            for (Plan plan : plans) {
                out.append("final,").append(plan.sampleIndex()).append(',').append(plan.chunkX()).append(',').append(plan.chunkZ()).append(',')
                        .append(plan.large() ? "large" : "small").append(',').append(plan.centerX()).append(',').append(plan.centerZ()).append(',')
                        .append(plan.targetSurfaceY()).append(',').append(format(plan.terrainScore())).append(',')
                        .append(format(plan.buriedFraction())).append(',').append(format(plan.floatingFraction())).append(',')
                        .append(format(plan.submergedFraction())).append(",true,").append(plan.specIndex()).append('\n');
            }
            Files.writeString(path, out.toString(), StandardCharsets.UTF_8);
            AfterTheEnd.LOGGER.info("Basecamp placement debug report for {}: {}", cityId, path.toAbsolutePath());
        } catch (IOException exception) {
            AfterTheEnd.LOGGER.warn("Failed to write Basecamp placement debug report for {}", cityId, exception);
        }
    }

    private static void appendSiteCsv(
            StringBuilder out,
            String row,
            ChunkSeed chunk,
            String size,
            Site site,
            boolean selected,
            String specIndex
    ) {
        if (site == null) {
            out.append(row).append(',').append(chunk.sampleIndex()).append(',').append(chunk.chunkX()).append(',').append(chunk.chunkZ()).append(',')
                    .append(size).append(",,,,,,,, ").append(selected).append(',').append(specIndex).append('\n');
            return;
        }
        TerrainAssessment terrain = site.terrain();
        out.append(row).append(',').append(chunk.sampleIndex()).append(',').append(chunk.chunkX()).append(',').append(chunk.chunkZ()).append(',')
                .append(size).append(',').append(site.centerX()).append(',').append(site.centerZ()).append(',').append(terrain.targetSurfaceY()).append(',')
                .append(format(site.score())).append(',').append(format(terrain.buriedFraction())).append(',')
                .append(format(terrain.floatingFraction())).append(',').append(format(terrain.submergedFraction())).append(',')
                .append(selected).append(',').append(specIndex).append('\n');
    }

    private static double elapsedSeconds(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000_000.0;
    }

    private static String formatSeconds(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100.0);
    }

    record Request(int specIndex, boolean large) { }

    record Plan(
            int specIndex,
            int sampleIndex,
            int chunkX,
            int chunkZ,
            int centerX,
            int centerZ,
            int targetSurfaceY,
            double terrainScore,
            double buriedFraction,
            double floatingFraction,
            double submergedFraction,
            boolean large
    ) { }

    private record TemplateSize(int width) { }
    private record ChunkSeed(int sampleIndex, int chunkX, int chunkZ) { }
    private record SurfaceSample(int supportY, int fluidTopY) { }
    private record FootprintStencilKey(int minLocalX, int maxLocalX, int minLocalZ, int maxLocalZ, int width) { }
    private record FootprintStencil(int[] centerXs, int[] centerZs, int[] sampleXs, int[] sampleZs, int[] sampleIndices) { }
    private record TerrainAssessment(int targetSurfaceY, double score, double roughness, double buriedFraction,
                                     double floatingFraction, double submergedFraction) { }
    private record Site(int centerX, int centerZ, double score, TerrainAssessment terrain) { }
    private record ChunkEvaluation(ChunkSeed chunk, Site small) { }
    private record ChosenSite(ChunkEvaluation candidate, Site site, boolean large) { }
    private record SearchBounds(int minCenterX, int maxCenterX, int minCenterZ, int maxCenterZ) { }
    private record OptimizerContext(double[] terrainScores, double[][] pairDistances, double[][] pairDistancePenalties,
                                    boolean[][] pairOverlaps, double targetScale) { }
    private record SelectionResult(int[] selected, double objective, List<Double> restartObjectives) { }
}
