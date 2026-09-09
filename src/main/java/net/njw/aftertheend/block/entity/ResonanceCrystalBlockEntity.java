package net.njw.aftertheend.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.njw.aftertheend.registry.ModContent;

public final class ResonanceCrystalBlockEntity extends BlockEntity {
    public static final int PULSE_INTERVAL_TICKS = 60;
    public static final int ATTACK_TICK = 5;
    public static final double ATTACK_RADIUS = 5.0;
    public static final int LEVITATION_DURATION_TICKS = 60;
    private static final int PULSE_COLOR = 0xC8B7D4;

    public ResonanceCrystalBlockEntity(BlockPos pos, BlockState state) {
        super(ModContent.RESONANCE_CRYSTAL_BLOCK_ENTITY.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ResonanceCrystalBlockEntity blockEntity) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (Math.floorMod(serverLevel.getGameTime(), PULSE_INTERVAL_TICKS) != ATTACK_TICK) return;
        pulse(serverLevel, pos);
    }

    private static void pulse(ServerLevel level, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        double radiusSquared = ATTACK_RADIUS * ATTACK_RADIUS;
        AABB search = new AABB(pos).inflate(ATTACK_RADIUS);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, search, LivingEntity::isAlive)) {
            if (entity.getBoundingBox().getCenter().distanceToSqr(center) > radiusSquared) continue;
            entity.addEffect(new MobEffectInstance(MobEffects.LEVITATION, LEVITATION_DURATION_TICKS, 0, false, true, true));
        }

        level.sendParticles(new DustParticleOptions(PULSE_COLOR, 1.15F), center.x, center.y + 0.5, center.z, 42, 2.4, 1.5, 2.4, 0.035);
        level.sendParticles(ParticleTypes.END_ROD, center.x, center.y + 0.5, center.z, 14, 2.0, 1.2, 2.0, 0.045);
        level.playSound(null, pos, SoundEvents.SHULKER_SHOOT, SoundSource.BLOCKS, 0.8F, 0.72F);
    }

    public static float horizontalScale(float gameTime) {
        float phase = positiveModulo(gameTime, PULSE_INTERVAL_TICKS);
        if (phase < 3.0F) return lerp(1.0F, 0.78F, phase / 3.0F);
        if (phase < 5.0F) return lerp(0.78F, 1.30F, (phase - 3.0F) / 2.0F);
        if (phase < 8.0F) return lerp(1.30F, 0.94F, (phase - 5.0F) / 3.0F);
        if (phase < 12.0F) return lerp(0.94F, 1.05F, (phase - 8.0F) / 4.0F);
        if (phase < 16.0F) return lerp(1.05F, 1.0F, (phase - 12.0F) / 4.0F);
        return 1.0F;
    }

    public static float verticalScale(float gameTime) {
        float phase = positiveModulo(gameTime, PULSE_INTERVAL_TICKS);
        if (phase < 3.0F) return lerp(1.0F, 0.88F, phase / 3.0F);
        if (phase < 5.0F) return lerp(0.88F, 1.15F, (phase - 3.0F) / 2.0F);
        if (phase < 8.0F) return lerp(1.15F, 0.97F, (phase - 5.0F) / 3.0F);
        if (phase < 12.0F) return lerp(0.97F, 1.03F, (phase - 8.0F) / 4.0F);
        if (phase < 16.0F) return lerp(1.03F, 1.0F, (phase - 12.0F) / 4.0F);
        return 1.0F;
    }

    private static float positiveModulo(float value, int divisor) {
        float result = value % divisor;
        return result < 0.0F ? result + divisor : result;
    }

    private static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }
}
