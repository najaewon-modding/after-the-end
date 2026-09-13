package net.njw.aftertheend.city;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

final class CityMoveRecallEffect {
    private static final DustParticleOptions BASE = new DustParticleOptions(0x28C7FF, 0.82F);
    private static final DustParticleOptions SPIRAL = new DustParticleOptions(0x429BFF, 0.72F);
    private static final DustParticleOptions CORE = new DustParticleOptions(0xBDEFFF, 0.60F);
    private static final Set<UUID> FINAL_BURST_PLAYED = new HashSet<>();

    private CityMoveRecallEffect() { }

    static void tick(ServerPlayer player, int ticks, double progress) {
        UUID playerId = player.getUUID();
        if (ticks <= 1) FINAL_BURST_PLAYED.remove(playerId);
        playSound(player, ticks, progress);

        int interval = progress < 0.28D ? 3 : 2;
        if (ticks % interval == 0) spawnVisuals(player, ticks, progress);
        if (progress >= 0.985D && FINAL_BURST_PLAYED.add(playerId)) spawnFinalBurst(player);
    }

    private static void spawnVisuals(ServerPlayer player, int ticks, double progress) {
        ServerLevel level = player.level();
        double x = player.getX();
        double y = player.getY() + 0.025D;
        double z = player.getZ();
        double phase = ticks * (0.075D + progress * 0.22D);
        double rise = smoothstep(0.04D, 0.90D, progress);
        double tighten = smoothstep(0.70D, 1.0D, progress);
        double pulse = 1.0D + 0.035D * Math.sin(ticks * 0.20D);
        double height = 0.24D + 2.42D * rise;
        double baseRadius = (1.28D - 0.18D * tighten) * pulse;
        double topRadius = 0.46D - 0.12D * tighten;
        int arms = progress < 0.44D ? 2 : progress < 0.76D ? 3 : 4;
        int levels = 7 + (int) Math.round(rise * 12.0D);
        double turns = 1.10D + 2.55D * rise;

        for (int arm = 0; arm < arms; arm++) {
            double armOffset = Math.PI * 2.0D * arm / arms;
            spawnGroundIntake(level, x, y, z, baseRadius, phase, armOffset, ticks, progress);
            for (int step = 0; step < levels; step++) {
                double vertical = levels <= 1 ? 0.0D : step / (double) (levels - 1);
                double curve = vertical * vertical * (3.0D - 2.0D * vertical);
                double radius = baseRadius + (topRadius - baseRadius) * curve;
                radius += 0.025D * Math.sin(ticks * 0.17D + step * 0.7D + arm);
                double angle = phase * (1.35D + progress * 1.45D) + armOffset
                        + vertical * Math.PI * 2.0D * turns;
                DustParticleOptions particle = vertical < 0.22D ? BASE : vertical > 0.78D ? CORE : SPIRAL;
                level.sendParticles(
                        particle,
                        x + Math.cos(angle) * radius,
                        y + 0.035D + height * vertical,
                        z + Math.sin(angle) * radius,
                        1, 0.0D, 0.0D, 0.0D, 0.0D
                );
            }
        }

        if (progress >= 0.64D) spawnInnerVortex(level, x, y, z, phase, ticks, progress, height, tighten);
        if (progress >= 0.88D) spawnFinalSuction(level, x, y, z, phase, ticks, progress, height);
    }

    private static void spawnGroundIntake(ServerLevel level, double x, double y, double z, double baseRadius,
                                          double phase, double armOffset, int ticks, double progress) {
        int points = progress < 0.55D ? 4 : 6;
        for (int step = 0; step < points; step++) {
            double ratio = points <= 1 ? 1.0D : step / (double) (points - 1);
            double radius = baseRadius + 0.48D * (1.0D - ratio);
            double angle = phase * (1.12D + progress) + armOffset - (1.0D - ratio) * 1.20D;
            double lift = 0.018D + ratio * 0.055D + 0.012D * Math.sin(ticks * 0.15D + step);
            level.sendParticles(
                    BASE,
                    x + Math.cos(angle) * radius,
                    y + lift,
                    z + Math.sin(angle) * radius,
                    1, 0.0D, 0.0D, 0.0D, 0.0D
            );
        }
    }

    private static void spawnInnerVortex(ServerLevel level, double x, double y, double z, double phase,
                                         int ticks, double progress, double height, double tighten) {
        double intensity = smoothstep(0.64D, 1.0D, progress);
        int arms = progress < 0.84D ? 1 : 2;
        int levels = 5 + (int) Math.round(intensity * 9.0D);
        for (int arm = 0; arm < arms; arm++) {
            double armOffset = Math.PI * 2.0D * arm / arms + Math.PI * 0.42D;
            for (int step = 0; step < levels; step++) {
                double vertical = levels <= 1 ? 0.0D : step / (double) (levels - 1);
                double radius = (0.72D - 0.18D * vertical - 0.13D * tighten)
                        + 0.025D * Math.sin(ticks * 0.19D + step);
                double angle = -phase * (1.9D + progress * 1.2D) + armOffset
                        + vertical * Math.PI * 2.0D * (2.0D + 1.4D * intensity);
                level.sendParticles(
                        CORE,
                        x + Math.cos(angle) * radius,
                        y + 0.08D + height * vertical * 0.92D,
                        z + Math.sin(angle) * radius,
                        1, 0.0D, 0.0D, 0.0D, 0.0D
                );
            }
        }
    }

    private static void spawnFinalSuction(ServerLevel level, double x, double y, double z, double phase,
                                          int ticks, double progress, double height) {
        double intensity = smoothstep(0.88D, 1.0D, progress);
        int streaks = 5 + (int) Math.round(intensity * 5.0D);
        for (int i = 0; i < streaks; i++) {
            double vertical = i / (double) Math.max(1, streaks - 1);
            double angle = phase * 3.2D + i * 2.399963229728653D;
            double radius = 0.86D - 0.48D * vertical - 0.14D * intensity;
            level.sendParticles(
                    CORE,
                    x + Math.cos(angle) * radius,
                    y + 0.12D + height * vertical,
                    z + Math.sin(angle) * radius,
                    1, 0.0D, 0.0D, 0.0D, 0.0D
            );
        }
    }

    private static void spawnFinalBurst(ServerPlayer player) {
        ServerLevel level = player.level();
        double x = player.getX();
        double y = player.getY() + 0.08D;
        double z = player.getZ();
        for (int arm = 0; arm < 4; arm++) {
            double armOffset = Math.PI * 2.0D * arm / 4.0D;
            for (int step = 0; step < 9; step++) {
                double vertical = step / 8.0D;
                double angle = armOffset + vertical * Math.PI * 4.5D;
                double radius = 0.92D - 0.58D * vertical;
                level.sendParticles(
                        CORE,
                        x + Math.cos(angle) * radius,
                        y + vertical * 2.25D,
                        z + Math.sin(angle) * radius,
                        1, 0.0D, 0.0D, 0.0D, 0.0D
                );
            }
        }
        level.sendParticles(ParticleTypes.END_ROD, x, y + 1.05D, z, 42, 0.40D, 1.00D, 0.40D, 0.09D);
        level.sendParticles(CORE, x, y + 1.0D, z, 34, 0.34D, 0.90D, 0.34D, 0.025D);
        level.playSound(null, player.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.15F, 1.28F);
        level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.95F, 1.95F);
        level.playSound(null, player.blockPosition(), SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.PLAYERS, 0.80F, 1.65F);
    }

    private static void playSound(ServerPlayer player, int ticks, double progress) {
        ServerLevel level = player.level();
        if (ticks == 1) {
            level.playSound(null, player.blockPosition(), SoundEvents.BEACON_AMBIENT, SoundSource.PLAYERS, 0.82F, 0.66F);
            level.playSound(null, player.blockPosition(), SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.PLAYERS, 0.62F, 0.68F);
            return;
        }

        if (ticks % 40 == 0 && progress < 0.96D) {
            level.playSound(null, player.blockPosition(), SoundEvents.BEACON_AMBIENT, SoundSource.PLAYERS,
                    (float) (0.42D + progress * 0.20D), (float) (0.70D + progress * 0.34D));
        }

        int pulseInterval = progress < 0.35D ? 16 : progress < 0.65D ? 12 : progress < 0.84D ? 8 : 5;
        if (ticks % pulseInterval != 0 || progress >= 0.985D) return;

        float pitch = (float) (0.66D + progress * 1.18D);
        float chimeVolume = (float) (0.34D + progress * 0.42D);
        float chargeVolume = (float) (0.40D + progress * 0.36D);
        level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, chimeVolume, pitch);
        level.playSound(null, player.blockPosition(), SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.PLAYERS,
                chargeVolume, (float) (0.72D + progress * 0.84D));
    }

    private static double smoothstep(double edge0, double edge1, double value) {
        double t = Math.clamp((value - edge0) / (edge1 - edge0), 0.0D, 1.0D);
        return t * t * (3.0D - 2.0D * t);
    }
}
