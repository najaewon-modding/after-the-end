from pathlib import Path

path = Path('src/main/java/net/njw/aftertheend/city/basecamp/BasecampPlacementPlanner.java')
text = path.read_text()

old = '''        List<ChunkEvaluation> usable = evaluated.stream().filter(candidate -> candidate.small() != null).toList();
        if (usable.size() < count) {
            AfterTheEnd.LOGGER.warn(
                    "Basecamp planner {} found only {} sampled usable chunks for {} structures; augmenting with emergency candidates",
                    cityId, usable.size(), count
            );
            evaluated = augmentEmergencyCandidates(level, region, evaluated, hasLarge, count);
            usable = evaluated.stream().filter(candidate -> candidate.small() != null).toList();
        }

        double targetDistance = preferredDistance(region, count);'''
new = '''        List<ChunkEvaluation> usable = evaluated.stream().filter(candidate -> candidate.small() != null).toList();
        boolean missingLargeCandidate = hasLarge && usable.stream().noneMatch(candidate -> candidate.large() != null);
        if (usable.size() < count || missingLargeCandidate) {
            AfterTheEnd.LOGGER.warn(
                    "Basecamp planner {} found only {} sampled usable chunks for {} structures (largeCandidate={}); augmenting with guaranteed emergency candidates",
                    cityId, usable.size(), count, !missingLargeCandidate
            );
            evaluated = augmentEmergencyCandidates(level, region, evaluated, hasLarge, count);
            usable = evaluated.stream().filter(candidate -> candidate.small() != null).toList();
        }
        if (usable.size() < count) {
            throw new IllegalStateException(
                    "Basecamp planner could not obtain enough distinct city chunks even after emergency augmentation: "
                            + usable.size() + " < " + count
            );
        }

        double targetDistance = preferredDistance(region, count);'''
if old not in text:
    raise SystemExit('plan usable block not found')
text = text.replace(old, new)

old = '''    private static Site findBestSiteInChunk(
            ServerLevel level,
            ChunkSeed chunk,
            TemplateSize size,
            SearchBounds bounds,
            boolean exhaustive
    ) {
        int chunkMinX = chunk.chunkX() << 4;
        int chunkMinZ = chunk.chunkZ() << 4;
        int minX = Math.max(chunkMinX, bounds.minCenterX());
        int maxX = Math.min(chunkMinX + 15, bounds.maxCenterX());
        int minZ = Math.max(chunkMinZ, bounds.minCenterZ());
        int maxZ = Math.min(chunkMinZ + 15, bounds.maxCenterZ());
        if (minX > maxX || minZ > maxZ) return null;

        int[] xs = exhaustive ? range(minX, maxX) : sampledAxis(minX, maxX);
        int[] zs = exhaustive ? range(minZ, maxZ) : sampledAxis(minZ, maxZ);
        Site best = null;
        for (int x : xs) {
            for (int z : zs) {
                TerrainAssessment terrain = assessTerrain(level, x, z, size, !exhaustive);
                if (terrain == null) continue;
                double score = terrain.score() + edgePenalty(bounds, x, z);
                if (best == null || score < best.score()) best = new Site(x, z, score, terrain);
            }
        }
        return best;
    }'''
new = '''    private static Site findBestSiteInChunk(
            ServerLevel level,
            ChunkSeed chunk,
            TemplateSize size,
            SearchBounds bounds,
            boolean exhaustive
    ) {
        int chunkMinX = chunk.chunkX() << 4;
        int chunkMinZ = chunk.chunkZ() << 4;
        int minX = Math.max(chunkMinX, bounds.minCenterX());
        int maxX = Math.min(chunkMinX + 15, bounds.maxCenterX());
        int minZ = Math.max(chunkMinZ, bounds.minCenterZ());
        int maxZ = Math.min(chunkMinZ + 15, bounds.maxCenterZ());
        if (minX > maxX || minZ > maxZ) return null;

        try {
            level.getChunk(chunk.chunkX(), chunk.chunkZ());
            int[] xs = exhaustive ? range(minX, maxX) : sampledAxis(minX, maxX);
            int[] zs = exhaustive ? range(minZ, maxZ) : sampledAxis(minZ, maxZ);
            Site best = null;
            for (int x : xs) {
                for (int z : zs) {
                    TerrainAssessment terrain = assessTerrain(level, x, z, size, !exhaustive);
                    if (terrain == null) continue;
                    double score = terrain.score() + edgePenalty(bounds, x, z);
                    if (best == null || score < best.score()) best = new Site(x, z, score, terrain);
                }
            }
            return best;
        } catch (RuntimeException exception) {
            AfterTheEnd.LOGGER.warn(
                    "Basecamp terrain evaluation failed for sampled chunk ({}, {}), size={}; using another candidate",
                    chunk.chunkX(), chunk.chunkZ(), size.width(), exception
            );
            return null;
        }
    }'''
if old not in text:
    raise SystemExit('findBestSiteInChunk block not found')
text = text.replace(old, new)

old = '''    private static Site emergencySiteInChunk(ServerLevel level, ChunkSeed chunk, TemplateSize size, SearchBounds bounds) {
        int chunkMinX = chunk.chunkX() << 4;
        int chunkMinZ = chunk.chunkZ() << 4;
        int x = clamp(chunkMinX + 8, bounds.minCenterX(), bounds.maxCenterX());
        int z = clamp(chunkMinZ + 8, bounds.minCenterZ(), bounds.maxCenterZ());
        level.getChunk(x >> 4, z >> 4);
        int target = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        target = Math.max(level.getMinY() + 1, Math.min(level.getMaxY(), target));
        TerrainAssessment terrain = new TerrainAssessment(target, EFFECTIVE_REJECT_PENALTY * 10.0, 0.0, 1.0, 0.0, 0.0);
        return new Site(x, z, terrain.score(), terrain);
    }'''
new = '''    private static Site emergencySiteInChunk(ServerLevel level, ChunkSeed chunk, TemplateSize size, SearchBounds bounds) {
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
    }'''
if old not in text:
    raise SystemExit('emergencySiteInChunk block not found')
text = text.replace(old, new)

old = '''                Site small = findBestSiteInChunk(level, chunk, SMALL, smallBounds, false);
                if (small == null) continue;
                Site large = hasLarge ? findBestSiteInChunk(level, chunk, LARGE, largeBounds, false) : null;
                result.add(new ChunkEvaluation(chunk, small, large));
                usable++;'''
new = '''                Site small = findBestSiteInChunk(level, chunk, SMALL, smallBounds, false);
                if (small == null) small = emergencySiteInChunk(level, chunk, SMALL, smallBounds);
                Site large = hasLarge ? findBestSiteInChunk(level, chunk, LARGE, largeBounds, false) : null;
                if (hasLarge && large == null) large = emergencySiteInChunk(level, chunk, LARGE, largeBounds);
                result.add(new ChunkEvaluation(chunk, small, large));
                usable++;'''
if old not in text:
    raise SystemExit('augment candidate block not found')
text = text.replace(old, new)

old = '''    private static int[] bestTerrainInitialization(List<ChunkEvaluation> candidates, int count, boolean hasLarge) {
        Integer[] order = new Integer[candidates.size()];'''
new = '''    private static int[] bestTerrainInitialization(List<ChunkEvaluation> candidates, int count, boolean hasLarge) {
        if (candidates.size() < count) {
            throw new IllegalStateException("Basecamp optimizer requires at least " + count + " candidates, got " + candidates.size());
        }
        Integer[] order = new Integer[candidates.size()];'''
if old not in text:
    raise SystemExit('bestTerrainInitialization header not found')
text = text.replace(old, new)

old = '''    private static int[] randomInitialization(int candidateCount, int count, RandomSource random) {
        int[] indices = new int[candidateCount];'''
new = '''    private static int[] randomInitialization(int candidateCount, int count, RandomSource random) {
        if (candidateCount < count) {
            throw new IllegalStateException("Basecamp optimizer requires at least " + count + " candidates, got " + candidateCount);
        }
        int[] indices = new int[candidateCount];'''
if old not in text:
    raise SystemExit('randomInitialization header not found')
text = text.replace(old, new)

path.write_text(text)
