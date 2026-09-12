package net.njw.aftertheend.gametest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.block.ResonanceCrystalBlock;
import net.njw.aftertheend.city.City;
import net.njw.aftertheend.city.CityManager;
import net.njw.aftertheend.city.CityRegion;
import net.njw.aftertheend.city.altar.AltarManager;
import net.njw.aftertheend.city.altar.AltarPlacement;
import net.njw.aftertheend.city.altar.AltarRitualHandler;
import net.njw.aftertheend.registry.ModContent;
import net.njw.justdragoneggs.block.RecordedDragonEggBlock;

public final class ModGameTests {
    private static final BlockPos CRYSTAL_POS = new BlockPos(5, 4, 5);
    private static final BlockPos TARGET_POS = new BlockPos(5, 5, 6);
    private static final long CRYSTAL_CHECK_DELAY_TICKS = 45L;
    private static final long RITUAL_CHECK_DELAY_TICKS = 205L;
    private static final int TEST_Y = 197;

    private static final DeferredRegister<Consumer<GameTestHelper>> TEST_FUNCTIONS =
            DeferredRegister.create(BuiltInRegistries.TEST_FUNCTION, AfterTheEnd.MODID);

    static {
        TEST_FUNCTIONS.register("resonance_crystal_attacks_when_uncalmed",
                () -> ModGameTests::resonanceCrystalAttacksWhenUncalmed);
        TEST_FUNCTIONS.register("resonance_crystal_stays_safe_when_calmed",
                () -> ModGameTests::resonanceCrystalStaysSafeWhenCalmed);
        TEST_FUNCTIONS.register("simultaneous_first_altar_rituals_unlock_once",
                () -> ModGameTests::simultaneousFirstAltarRitualsUnlockOnce);
        TEST_FUNCTIONS.register("second_and_third_altar_rituals_do_not_unlock_city",
                () -> ModGameTests::secondAndThirdAltarRitualsDoNotUnlockCity);
        TEST_FUNCTIONS.register("max_city_count_rejects_first_ritual",
                () -> ModGameTests::maxCityCountRejectsFirstRitual);
    }

    private ModGameTests() { }

    public static void register(IEventBus modEventBus) {
        TEST_FUNCTIONS.register(modEventBus);
    }

    private static void resonanceCrystalAttacksWhenUncalmed(GameTestHelper helper) {
        Pig pig = setupCrystalTest(helper, false);
        helper.runAfterDelay(CRYSTAL_CHECK_DELAY_TICKS, () -> {
            if (!pig.hasEffect(MobEffects.LEVITATION)) {
                helper.fail("Uncalmed Resonance Crystal did not apply Levitation.");
                return;
            }
            helper.succeed();
        });
    }

    private static void resonanceCrystalStaysSafeWhenCalmed(GameTestHelper helper) {
        Pig pig = setupCrystalTest(helper, true);
        helper.runAfterDelay(CRYSTAL_CHECK_DELAY_TICKS, () -> {
            if (pig.hasEffect(MobEffects.LEVITATION)) {
                helper.fail("Calmed Resonance Crystal applied Levitation.");
                return;
            }
            helper.succeed();
        });
    }

    private static Pig setupCrystalTest(GameTestHelper helper, boolean calmed) {
        helper.setBlock(TARGET_POS, Blocks.AIR.defaultBlockState());
        helper.setBlock(TARGET_POS.above(), Blocks.AIR.defaultBlockState());
        helper.setBlock(CRYSTAL_POS, ModContent.RESONANCE_CRYSTAL.get().defaultBlockState()
                .setValue(ResonanceCrystalBlock.CALMED, calmed));
        return helper.spawnWithNoFreeWill(EntityType.PIG, TARGET_POS);
    }

    private static void simultaneousFirstAltarRitualsUnlockOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        int oldMax = CityManager.getMaxCityCount(server);
        int baselineAccessible = CityManager.getAccessibleCities(server).size();
        TestCity testCity = createTestCity(server, new BlockPos(5000, TEST_Y, 5000), 2, 0);
        City target = CityManager.getNextLockedCity(server);
        if (target == null) {
            cleanupTestCity(server, testCity);
            helper.fail("No ready locked city exists for first-ritual unlock test.");
            return;
        }
        UUID targetId = target.id();
        CityManager.setMaxCityCount(server, Math.max(oldMax, baselineAccessible + 3));
        startRitual(level, testCity.placements().get(0), null);
        startRitual(level, testCity.placements().get(1), null);

        helper.runAfterDelay(RITUAL_CHECK_DELAY_TICKS, () -> {
            try {
                if (AltarManager.getActivatedCount(server, testCity.city().id()) != 2) {
                    helper.fail("Two concurrent first Altar rituals did not both activate.");
                    return;
                }
                if (CityManager.getAccessibleCities(server).size() != baselineAccessible + 2) {
                    helper.fail("Concurrent first Altar rituals did not unlock exactly one city.");
                    return;
                }
                if (!CityManager.isCityAccessible(server, targetId)) {
                    helper.fail("The next locked city was not unlocked by the winning first ritual.");
                    return;
                }
                helper.succeed();
            } finally {
                cleanupCity(server, targetId);
                cleanupTestCity(server, testCity);
                restoreMaxCityCount(server, oldMax);
            }
        });
    }

    private static void secondAndThirdAltarRitualsDoNotUnlockCity(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        int oldMax = CityManager.getMaxCityCount(server);
        int baselineAccessible = CityManager.getAccessibleCities(server).size();
        TestCity testCity = createTestCity(server, new BlockPos(6000, TEST_Y, 6000), 3, 1);
        int expectedAccessible = baselineAccessible + 1;
        CityManager.setMaxCityCount(server, Math.max(oldMax, expectedAccessible));
        startRitual(level, testCity.placements().get(1), null);
        startRitual(level, testCity.placements().get(2), null);

        helper.runAfterDelay(RITUAL_CHECK_DELAY_TICKS, () -> {
            try {
                if (AltarManager.getActivatedCount(server, testCity.city().id()) != 3) {
                    helper.fail("Concurrent second and third Altar rituals did not reach three activated Altars.");
                    return;
                }
                if (CityManager.getAccessibleCities(server).size() != expectedAccessible) {
                    helper.fail("Second or third Altar ritual unexpectedly unlocked another city.");
                    return;
                }
                helper.succeed();
            } finally {
                cleanupTestCity(server, testCity);
                restoreMaxCityCount(server, oldMax);
            }
        });
    }

    @SuppressWarnings("removal")
    private static void maxCityCountRejectsFirstRitual(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        int oldMax = CityManager.getMaxCityCount(server);
        int baselineAccessible = CityManager.getAccessibleCities(server).size();
        TestCity testCity = createTestCity(server, new BlockPos(7000, TEST_Y, 7000), 1, 0);
        AltarPlacement placement = testCity.placements().getFirst();
        CityManager.setMaxCityCount(server, baselineAccessible + 1);
        RitualGeometry geometry = geometry(placement);
        buildTeleportPlatform(level, geometry.center());

        for (BlockPos socket : geometry.sockets()) {
            level.setBlock(socket, ModContent.RESONANCE_CRYSTAL.get().defaultBlockState(), 3);
            AltarRitualHandler.handlePlacedBlock(level, socket, null);
        }
        for (BlockPos socket : geometry.sockets()) {
            if (level.getBlockState(socket).getValue(ResonanceCrystalBlock.CALMED)) {
                cleanupTestCity(server, testCity);
                restoreMaxCityCount(server, oldMax);
                helper.fail("Resonance Crystal calmed even though the maximum city count was reached.");
                return;
            }
        }

        level.setBlock(geometry.center(), net.njw.justdragoneggs.registry.ModContent.RECORDED_DRAGON_EGG.get().defaultBlockState(), 3);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        AltarRitualHandler.handlePlacedBlock(level, geometry.center(), player);
        helper.runAfterDelay(5L, () -> {
            try {
                if (level.getBlockState(geometry.center()).getBlock() instanceof RecordedDragonEggBlock) {
                    helper.fail("Recorded Dragon Egg did not teleport when first Altar activation was blocked by the city cap.");
                    return;
                }
                if (AltarManager.getActivatedCount(server, testCity.city().id()) != 0) {
                    helper.fail("Altar activated even though the maximum city count was reached.");
                    return;
                }
                if (CityManager.getAccessibleCities(server).size() != baselineAccessible + 1) {
                    helper.fail("Blocked first Altar ritual changed the unlocked city count.");
                    return;
                }
                helper.succeed();
            } finally {
                cleanupTestCity(server, testCity);
                restoreMaxCityCount(server, oldMax);
                player.discard();
            }
        });
    }

    private static TestCity createTestCity(MinecraftServer server, BlockPos firstOrigin, int altarCount, int preactivatedCount) {
        UUID cityId = UUID.randomUUID();
        List<AltarPlacement> placements = new ArrayList<>(altarCount);
        for (int index = 0; index < altarCount; index++) {
            BlockPos origin = firstOrigin.offset(index * 32, 0, 0);
            placements.add(new AltarPlacement(
                    cityId, "njw_after_the_end:gametest", origin.getX(), origin.getY(), origin.getZ(),
                    false, false, "default", index < preactivatedCount
            ));
        }
        int centerChunkX = (firstOrigin.getX() + Math.max(0, altarCount - 1) * 16) >> 4;
        int centerChunkZ = firstOrigin.getZ() >> 4;
        City city = new City(cityId, "GameTest " + cityId, Map.of(
                Level.OVERWORLD, new CityRegion(centerChunkX, centerChunkZ, 12, 12)
        ));
        CityManager.addAccessibleCity(server, city);
        AltarManager.markGenerated(server, cityId, placements);
        Set<ChunkPos> forcedChunks = forceAltarChunks(server.getLevel(Level.OVERWORLD), placements);
        return new TestCity(city, List.copyOf(placements), forcedChunks);
    }

    private static Set<ChunkPos> forceAltarChunks(ServerLevel level, List<AltarPlacement> placements) {
        Set<ChunkPos> chunks = new HashSet<>();
        for (AltarPlacement placement : placements) {
            RitualGeometry geometry = geometry(placement);
            addForcedChunk(level, chunks, geometry.center());
            for (BlockPos socket : geometry.sockets()) addForcedChunk(level, chunks, socket);
        }
        return Set.copyOf(chunks);
    }

    private static void addForcedChunk(ServerLevel level, Set<ChunkPos> chunks, BlockPos pos) {
        ChunkPos chunk = new ChunkPos(pos);
        if (chunks.add(chunk)) level.setChunkForced(chunk.x, chunk.z, true);
    }

    private static void startRitual(ServerLevel level, AltarPlacement placement, ServerPlayer player) {
        RitualGeometry geometry = geometry(placement);
        for (BlockPos socket : geometry.sockets()) {
            level.setBlock(socket, ModContent.RESONANCE_CRYSTAL.get().defaultBlockState(), 3);
            AltarRitualHandler.handlePlacedBlock(level, socket, player);
        }
        level.setBlock(geometry.center(), net.njw.justdragoneggs.registry.ModContent.RECORDED_DRAGON_EGG.get().defaultBlockState(), 3);
        AltarRitualHandler.handlePlacedBlock(level, geometry.center(), player);
    }

    private static RitualGeometry geometry(AltarPlacement placement) {
        BlockPos center = new BlockPos(placement.blockX() + 5, placement.y() + 3, placement.blockZ() + 5);
        return new RitualGeometry(center, List.of(
                center.offset(0, 0, -3),
                center.offset(3, 0, 0),
                center.offset(0, 0, 3),
                center.offset(-3, 0, 0)
        ));
    }

    private static void buildTeleportPlatform(ServerLevel level, BlockPos center) {
        for (int dx = -16; dx <= 16; dx++) {
            for (int dz = -16; dz <= 16; dz++) {
                level.setBlock(center.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(center.offset(dx, 0, dz), Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    private static void cleanupTestCity(MinecraftServer server, TestCity testCity) {
        for (ChunkPos chunk : testCity.forcedChunks()) {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            if (level != null) level.setChunkForced(chunk.x, chunk.z, false);
        }
        cleanupCity(server, testCity.city().id());
    }

    private static void cleanupCity(MinecraftServer server, UUID cityId) {
        if (CityManager.getCity(server, cityId) == null) return;
        AltarManager.removeCity(server, cityId);
        CityManager.removeCity(server, cityId);
    }

    private static void restoreMaxCityCount(MinecraftServer server, int oldMax) {
        if (oldMax >= CityManager.getAccessibleCities(server).size()) CityManager.setMaxCityCount(server, oldMax);
    }

    private record TestCity(City city, List<AltarPlacement> placements, Set<ChunkPos> forcedChunks) { }
    private record RitualGeometry(BlockPos center, List<BlockPos> sockets) { }
}
