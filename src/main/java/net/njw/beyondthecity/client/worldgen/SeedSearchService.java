package net.njw.beyondthecity.client.worldgen;

import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.StructurePlacement;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.njw.beyondthecity.city.structure.StartingAreaRules;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class SeedSearchService {
    public static final int OVERWORLD_LIMIT = StartingAreaRules.OVERWORLD_STRUCTURE_LIMIT_BLOCKS;
    public static final int NETHER_LIMIT = StartingAreaRules.NETHER_STRUCTURE_LIMIT_BLOCKS;
    public static final int MAX_ATTEMPTS = 10_000;

    private SeedSearchService() {
    }

    public static SearchTask createTask(WorldCreationContext context) { return new SearchTask(context); }

    public static long findSuitableSeed(WorldCreationContext context) {
        SearchTask task = createTask(context);
        while (!task.isFinished()) task.advance(256);
        if (!task.hasResult()) throw new IllegalStateException("Could not find a suitable seed within " + MAX_ATTEMPTS + " attempts.");
        return task.result();
    }

    public static final class SearchTask {
        private final WorldCreationContext context;
        private int attempts;
        private Long result;
        private boolean finished;

        private SearchTask(WorldCreationContext context) { this.context = context; }

        public void advance(int attemptBudget) {
            if (finished || attemptBudget <= 0) return;
            int end = Math.min(MAX_ATTEMPTS, attempts + attemptBudget);
            while (attempts < end) {
                attempts++;
                long seed = ThreadLocalRandom.current().nextLong();
                if (hasStrongholdInStartingArea(context, seed) && hasFortressInStartingArea(context, seed)) {
                    result = seed;
                    finished = true;
                    return;
                }
            }
            if (attempts >= MAX_ATTEMPTS) finished = true;
        }

        public boolean isFinished() { return finished; }
        public boolean hasResult() { return result != null; }
        public long result() {
            if (result == null) throw new IllegalStateException("Seed search has no result.");
            return result;
        }
        public int attempts() { return attempts; }
    }

    private static boolean hasStrongholdInStartingArea(WorldCreationContext context, long seed) {
        LevelStem stem = context.selectedDimensions().get(LevelStem.OVERWORLD).orElse(null);
        if (stem == null) return false;
        ChunkGeneratorStructureState state = createStructureState(context, stem, seed);
        if (state == null) return false;
        Holder<Structure> stronghold = context.worldgenLoadContext().getOrThrow(BuiltinStructures.STRONGHOLD);
        state.ensureStructuresGenerated();
        for (StructurePlacement placement : state.getPlacementsForStructure(stronghold)) {
            if (!(placement instanceof ConcentricRingsStructurePlacement rings)) continue;
            List<ChunkPos> positions = state.getRingPositionsFor(rings);
            if (positions == null) continue;
            for (ChunkPos chunkPos : positions) {
                var locatePos = placement.getLocatePos(chunkPos);
                if (isInsideSquare(locatePos.getX(), locatePos.getZ(), OVERWORLD_LIMIT)) return true;
            }
        }
        return false;
    }

    private static boolean hasFortressInStartingArea(WorldCreationContext context, long seed) {
        LevelStem stem = context.selectedDimensions().get(LevelStem.NETHER).orElse(null);
        if (stem == null) return false;
        ChunkGeneratorStructureState state = createStructureState(context, stem, seed);
        if (state == null) return false;
        Holder<Structure> fortress = context.worldgenLoadContext().getOrThrow(BuiltinStructures.FORTRESS);
        int minChunk = Math.floorDiv(-NETHER_LIMIT, 16);
        int maxChunk = Math.floorDiv(NETHER_LIMIT, 16);
        for (StructurePlacement placement : state.getPlacementsForStructure(fortress)) {
            for (int chunkX = minChunk; chunkX <= maxChunk; chunkX++) {
                for (int chunkZ = minChunk; chunkZ <= maxChunk; chunkZ++) {
                    if (!placement.isStructureChunk(state, chunkX, chunkZ)) continue;
                    var locatePos = placement.getLocatePos(new ChunkPos(chunkX, chunkZ));
                    if (isInsideSquare(locatePos.getX(), locatePos.getZ(), NETHER_LIMIT)) return true;
                }
            }
        }
        return false;
    }

    private static ChunkGeneratorStructureState createStructureState(WorldCreationContext context, LevelStem stem, long seed) {
        ChunkGenerator generator = stem.generator();
        if (!(generator instanceof NoiseBasedChunkGenerator noiseGenerator)) return null;
        RegistryAccess.Frozen registries = context.worldgenLoadContext();
        RandomState randomState = RandomState.create(noiseGenerator.generatorSettings().value(), registries.lookupOrThrow(Registries.NOISE), seed);
        return generator.createState(registries.lookupOrThrow(Registries.STRUCTURE_SET), randomState, seed);
    }

    private static boolean isInsideSquare(int x, int z, int limit) { return x >= -limit && x <= limit && z >= -limit && z <= limit; }
}
