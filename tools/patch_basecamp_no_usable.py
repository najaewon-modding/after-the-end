from pathlib import Path

path = Path('src/main/java/net/njw/aftertheend/city/basecamp/BasecampPlacementPlanner.java')
text = path.read_text()

old = '''        List<ChunkEvaluation> evaluated = new ArrayList<>(sampledChunks.size());
        for (ChunkSeed chunk : sampledChunks) {
            Site small = findBestSiteInChunk(level, chunk, SMALL, smallBounds, false);
            Site large = hasLarge ? findBestSiteInChunk(level, chunk, LARGE, largeBounds, false) : null;
            evaluated.add(new ChunkEvaluation(chunk, small, large));
        }

        List<ChunkEvaluation> usable = evaluated.stream().filter(candidate -> candidate.small() != null).toList();
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

        double targetDistance = preferredDistance(region, count);
        SelectionResult selection = optimizeJointSelection(
                usable,
                count,
                hasLarge,
                targetDistance,
                RandomSource.create(mix(seed ^ 0x6a09e667f3bcc909L))
        );

        List<ChosenSite> chosen = new ArrayList<>(count);
        for (int position = 0; position < selection.selected().length; position++) {
            ChunkEvaluation candidate = usable.get(selection.selected()[position]);
'''
new = '''        List<ChunkEvaluation> evaluated = new ArrayList<>(sampledChunks.size());
        for (ChunkSeed chunk : sampledChunks) {
            Site small = evaluateSiteInChunk(level, chunk, SMALL, smallBounds, false);
            Site large = hasLarge ? evaluateSiteInChunk(level, chunk, LARGE, largeBounds, false) : null;
            evaluated.add(new ChunkEvaluation(chunk, small, large));
        }

        // Every FPS chunk remains a candidate. Bad or unreadable terrain is represented only by a very large score;
        // terrain quality never removes a chunk from the optimizer.
        double targetDistance = preferredDistance(region, count);
        SelectionResult selection = optimizeJointSelection(
                evaluated,
                count,
                hasLarge,
                targetDistance,
                RandomSource.create(mix(seed ^ 0x6a09e667f3bcc909L))
        );

        List<ChosenSite> chosen = new ArrayList<>(count);
        for (int position = 0; position < selection.selected().length; position++) {
            ChunkEvaluation candidate = evaluated.get(selection.selected()[position]);
'''
if old not in text:
    raise SystemExit('plan block not found')
text = text.replace(old, new)

text = text.replace(
    '        writeDebugReport(cityId, seed, region, targetDistance, evaluated, selection, usable, result);',
    '        writeDebugReport(cityId, seed, region, targetDistance, evaluated, selection, result);'
)

marker = '''    private static Site findBestSiteInChunk(
'''
insert = '''    private static Site evaluateSiteInChunk(
            ServerLevel level,
            ChunkSeed chunk,
            TemplateSize size,
            SearchBounds bounds,
            boolean exhaustive
    ) {
        Site site = findBestSiteInChunk(level, chunk, size, bounds, exhaustive);
        if (site != null) return site;
        AfterTheEnd.LOGGER.debug(
                "Basecamp candidate chunk ({}, {}) size={} could not be terrain-scored normally; assigning fallback score instead of removing candidate",
                chunk.chunkX(), chunk.chunkZ(), size.width()
        );
        return emergencySiteInChunk(level, chunk, size, bounds);
    }

'''
if marker not in text:
    raise SystemExit('findBestSite marker not found')
text = text.replace(marker, insert + marker, 1)

# Remove obsolete emergency candidate augmentation: FPS always contributes exactly the sampled candidate set.
start = text.find('    private static List<ChunkEvaluation> augmentEmergencyCandidates(')
end = text.find('    private static SelectionResult optimizeJointSelection(', start)
if start < 0 or end < 0:
    raise SystemExit('augmentation block not found')
text = text[:start] + text[end:]

text = text.replace(
'''            List<ChunkEvaluation> allCandidates,
            SelectionResult selection,
            List<ChunkEvaluation> usable,
            List<Plan> plans
''',
'''            List<ChunkEvaluation> allCandidates,
            SelectionResult selection,
            List<Plan> plans
'''
)
text = text.replace(
'''            Set<Integer> selectedSampleIndices = new HashSet<>();
            for (int usableIndex : selection.selected()) selectedSampleIndices.add(usable.get(usableIndex).chunk().sampleIndex());
''',
'''            Set<Integer> selectedSampleIndices = new HashSet<>();
            for (int candidateIndex : selection.selected()) selectedSampleIndices.add(allCandidates.get(candidateIndex).chunk().sampleIndex());
'''
)

# Optimizer must see all FPS candidates; make that invariant explicit.
text = text.replace(
'''        int candidateCount = candidates.size();
        SelectionResult globalBest = null;
''',
'''        int candidateCount = candidates.size();
        if (candidateCount < count) {
            throw new IllegalStateException("Basecamp FPS candidate count " + candidateCount + " is smaller than requested structure count " + count);
        }
        SelectionResult globalBest = null;
''', 1)

path.write_text(text)
