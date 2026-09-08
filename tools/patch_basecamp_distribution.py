from pathlib import Path

path = Path("src/main/java/net/njw/aftertheend/city/basecamp/BasecampPlacementService.java")
text = path.read_text()

text = text.replace("private static final int CANDIDATE_GRID_AXIS = 8;", "private static final int CANDIDATE_GRID_AXIS = 16;")
text = text.replace("private static final int REFINED_CANDIDATE_COUNT = 10;", "private static final int REFINED_CANDIDATE_COUNT = 96;")
text = text.replace("private static final int TREE_CLEAR_MARGIN = 4;", "private static final int TREE_CLEAR_MARGIN = 2;")
text = text.replace("private static final int TREE_CLEAR_EXTRA_HEIGHT = 24;", "private static final int TREE_CLEAR_EXTRA_HEIGHT = 16;")

start = text.index("    private static PlacementCandidate findBestCandidate(")
end = text.index("\n    private static ScoredCandidate findGuaranteedFallback(", start)
replacement = '''    private static PlacementCandidate findBestCandidate(
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
            coarse.add(scoreTerrainCandidate(bounds, point.x(), point.z(), terrain));
        }

        coarse.sort(Comparator.comparingDouble(ScoredCandidate::score));
        List<ScoredCandidate> refined = new ArrayList<>();
        int refinementCount = Math.min(REFINED_CANDIDATE_COUNT, coarse.size());
        for (int i = 0; i < refinementCount; i++) {
            ScoredCandidate candidate = coarse.get(i);
            TerrainAssessment terrain = assessTerrain(level, candidate.centerX(), candidate.centerZ(), size, false);
            if (terrain == null) continue;
            refined.add(scoreTerrainCandidate(bounds, candidate.centerX(), candidate.centerZ(), terrain));
        }

        ScoredCandidate best = refined.isEmpty()
                ? findGuaranteedFallback(level, bounds, size, reserved)
                : chooseSpreadCandidate(refined, reserved);

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

    private static ScoredCandidate scoreTerrainCandidate(
            SearchBounds bounds,
            int centerX,
            int centerZ,
            TerrainAssessment terrain
    ) {
        return new ScoredCandidate(centerX, centerZ, terrain.score() + edgePenalty(bounds, centerX, centerZ), terrain);
    }

    private static ScoredCandidate chooseSpreadCandidate(
            List<ScoredCandidate> candidates,
            List<PlacedFootprint> reserved
    ) {
        candidates.sort(Comparator.comparingDouble(ScoredCandidate::score));
        double bestTerrainScore = candidates.getFirst().score();
        List<ScoredCandidate> acceptable = candidates.stream()
                .filter(candidate -> withinQuarterConstraint(candidate.terrain()))
                .filter(candidate -> candidate.score() <= bestTerrainScore + 120.0)
                .toList();

        if (acceptable.isEmpty()) {
            acceptable = candidates.subList(0, Math.min(24, candidates.size()));
        }
        if (reserved.isEmpty()) {
            return acceptable.stream().min(Comparator.comparingDouble(ScoredCandidate::score)).orElseThrow();
        }

        ScoredCandidate best = null;
        double bestDistance = Double.NEGATIVE_INFINITY;
        for (ScoredCandidate candidate : acceptable) {
            double distance = nearestBasecampDistance(candidate.centerX(), candidate.centerZ(), reserved);
            if (best == null
                    || distance > bestDistance + 1.0e-6
                    || (Math.abs(distance - bestDistance) <= 1.0e-6 && candidate.score() < best.score())) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static boolean withinQuarterConstraint(TerrainAssessment terrain) {
        return terrain.cutFraction() <= 0.25 && terrain.floatingFraction() <= 0.25;
    }
'''
text = text[:start] + replacement + text[end:]

start = text.index("    private static TerrainAssessment optimizeSurface(")
end = text.index("\n    private static int[] sampleOffsets(", start)
replacement = '''    private static TerrainAssessment optimizeSurface(List<SurfaceSample> samples) {
        int maxSurface = samples.stream().mapToInt(SurfaceSample::surfaceY).max().orElseThrow();
        int minSurface = samples.stream().mapToInt(SurfaceSample::surfaceY).min().orElseThrow();
        TerrainAssessment best = null;

        for (int target = minSurface; target <= maxSurface; target++) {
            double burialDepthCost = 0.0;
            double floatingDepthCost = 0.0;
            int buriedCells = 0;
            int floatingCells = 0;
            int severeFloatingCells = 0;
            int fluidCells = 0;

            for (SurfaceSample sample : samples) {
                int difference = sample.surfaceY() - target;
                if (difference > 0) {
                    buriedCells++;
                    burialDepthCost += burialDepthPenalty(difference);
                } else if (difference < 0) {
                    int gap = -difference;
                    floatingCells++;
                    if (gap >= 4) severeFloatingCells++;
                    floatingDepthCost += floatingDepthPenalty(gap);
                }
                if (sample.fluid()) fluidCells++;
            }

            double count = samples.size();
            double buriedFraction = buriedCells / count;
            double floatingFraction = floatingCells / count;
            double severeFloatingFraction = severeFloatingCells / count;
            double fluidFraction = fluidCells / count;
            double roughness = maxSurface - minSurface;
            double score = (burialDepthCost + floatingDepthCost) / count
                    + quarterFractionPenalty(buriedFraction)
                    + quarterFractionPenalty(floatingFraction)
                    + severeFloatingFraction * 80.0
                    + fluidFraction * 1200.0
                    + roughness * 0.35;

            TerrainAssessment assessment = new TerrainAssessment(
                    target, score, roughness, buriedFraction, floatingFraction, severeFloatingFraction, fluidFraction
            );
            if (best == null || assessment.score() < best.score()) best = assessment;
        }
        return best;
    }

    private static double burialDepthPenalty(int depth) {
        if (depth <= 1) return 0.10;
        if (depth == 2) return 0.30;
        if (depth == 3) return 0.80;
        double excess = depth - 3.0;
        return 1.5 + excess * excess * 3.0;
    }

    private static double floatingDepthPenalty(int depth) {
        if (depth <= 1) return 0.10;
        if (depth == 2) return 0.35;
        if (depth == 3) return 1.00;
        double excess = depth - 3.0;
        return 2.0 + excess * excess * 5.0;
    }

    private static double quarterFractionPenalty(double fraction) {
        if (fraction <= 0.25) {
            double ratio = fraction / 0.25;
            return ratio * ratio * 6.0;
        }
        double excess = fraction - 0.25;
        return 6.0 + excess * 4000.0 + excess * excess * 30000.0;
    }
'''
text = text[:start] + replacement + text[end:]

old = '''        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
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
'''
new = '''        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
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
'''
if old not in text:
    raise SystemExit("tree clear block not found")
text = text.replace(old, new, 1)
text = text.replace("double featherRadius = coreRadius + 1.5;", "double featherRadius = coreRadius + 1.0;")

path.write_text(text)
