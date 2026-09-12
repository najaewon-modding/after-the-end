package net.njw.aftertheend.gametest;

import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.block.ResonanceCrystalBlock;
import net.njw.aftertheend.registry.ModContent;

public final class ModGameTests {
    private static final BlockPos CRYSTAL_POS = new BlockPos(5, 4, 5);
    private static final BlockPos TARGET_POS = new BlockPos(5, 5, 6);
    private static final long CHECK_DELAY_TICKS = 45L;

    private static final DeferredRegister<Consumer<GameTestHelper>> TEST_FUNCTIONS =
            DeferredRegister.create(BuiltInRegistries.TEST_FUNCTION, AfterTheEnd.MODID);

    static {
        TEST_FUNCTIONS.register("resonance_crystal_attacks_when_uncalmed",
                () -> ModGameTests::resonanceCrystalAttacksWhenUncalmed);
        TEST_FUNCTIONS.register("resonance_crystal_stays_safe_when_calmed",
                () -> ModGameTests::resonanceCrystalStaysSafeWhenCalmed);
    }

    private ModGameTests() { }

    public static void register(IEventBus modEventBus) {
        TEST_FUNCTIONS.register(modEventBus);
    }

    private static void resonanceCrystalAttacksWhenUncalmed(GameTestHelper helper) {
        Pig pig = setup(helper, false);
        helper.runAfterDelay(CHECK_DELAY_TICKS, () -> {
            if (!pig.hasEffect(MobEffects.LEVITATION)) {
                helper.fail("Uncalmed Resonance Crystal did not apply Levitation.");
                return;
            }
            helper.succeed();
        });
    }

    private static void resonanceCrystalStaysSafeWhenCalmed(GameTestHelper helper) {
        Pig pig = setup(helper, true);
        helper.runAfterDelay(CHECK_DELAY_TICKS, () -> {
            if (pig.hasEffect(MobEffects.LEVITATION)) {
                helper.fail("Calmed Resonance Crystal applied Levitation.");
                return;
            }
            helper.succeed();
        });
    }

    private static Pig setup(GameTestHelper helper, boolean calmed) {
        helper.setBlock(TARGET_POS, Blocks.AIR.defaultBlockState());
        helper.setBlock(TARGET_POS.above(), Blocks.AIR.defaultBlockState());
        helper.setBlock(CRYSTAL_POS, ModContent.RESONANCE_CRYSTAL.get().defaultBlockState()
                .setValue(ResonanceCrystalBlock.CALMED, calmed));
        return helper.spawnWithNoFreeWill(EntityType.PIG, TARGET_POS);
    }
}
