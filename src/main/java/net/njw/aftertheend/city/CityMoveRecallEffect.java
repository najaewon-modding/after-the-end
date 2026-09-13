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
    private static final DustParticleOptions OUTER = new DustParticleOptions(0x38BDF8, 0.95F);
    private static final DustParticleOptions INNER = new DustParticleOptions(0x9BE7FF, 0.72F);
    private static final DustParticleOptions SPIRAL = new DustParticleOptions(0x67D8FF, 0.68F);
    private static final DustParticleOptions CORE = new DustParticleOptions(0xD6F6FF, 0.58F);
    private static final Set<UUID> FINAL_BURST_PLAYED = new HashSet<>();

    private CityMoveRecallEffect() { }

    static void tick(ServerPlayer player, int ticks, double progress) {
        UUID playerId = player.getUUID();
        if (ticks <= 1) FINAL_BURST_PLAYED.remove(playerId);
        playSound(player, ticks, progress);

        int interval = progress < 0.32D ? 4 : progress < 0.72D ? 3 : 2;
        if (ticks % interval == 0) spawnVisuals(player, ticks, progress);
        if (progress >= 0.985D && FINAL_BURST_PLAYED.add(playerId)) spawnFinalBurst(player);
    }

    private static void spawnVisuals(ServerPlayer player, int ticks, double progress) {
        ServerLevel level = player.level();
        double x = player.getX();
        double y = player.getY() + 0.055D;
        double z = player.getZ();
        double phase = ticks * (0.095D + progress * 0.17D);
        double pulse = 1.0D + 0.045D * Math.sin(ticks * 0.19D);
        double gather = smoothstep(0.58D, 1.0D, progress);
        double outerRadius = (1.42D - 0.38D * gather) * pulse;
        double innerRadius = (0.82D - 0.24D * gather) * pulse;
        int outerPoints = 14 + (int) Math.round(progress * 12.0D);
        int innerPoints = 8 + (int) Math.round(progress * 8.0D);

        spawnRing(level, x, y, z, outerRadius, outerPoints, phase, OUTER, ticks, 0.035D);
        if (progress >= 0.10D) {
            spawnRing(level, x, y + 0.035D, z, innerRadius, innerPoints, -phase * 1.32D, INNER, ticks + 7, 0.025D);
        }

        double spiralProgress = smoothstep(0.18D, 1.0D, progress);
        if (spiralProgress <= 0.0D) return;
        int arms = progress < 0.55D ? 2 : progress < 0.82D ? 3 : 4;
        int levels = 3 + (int) Math.round(spiralProgress * 10.0D);
        double maxHeight = 0.28D + 2.05D * spiralProgress;
        double spiralRadius = 0.70D - 0.24D * gather;
        double turns = 1.35D + 2.15D * spiralProgress;

        for (int arm = 0; arm < arms; arm++) {
            double armOffset = Math.PI * 2.0D * arm / arms;
            for (int step = 0; step < levels; step++) {
                double heightRatio = levels <= 1 ? 0.0D : step / (double) (levels - 1);
                double angle = phase * (1.65D + progress) + armOffset + heightRatio * Math.PI * 2.0D * turns;
                double taper = 1.0D - 0.24D * heightRatio * gather;
                double radius = spiralRadius * taper + 0.035D * Math.sin(ticks * 0.16D + step);
                level.sendParticles(
                        SPIRAL,
                        x + Math.cos(angle) * radius,
                        y + 0.12D + maxHeight * heightRatio,
                        z + Math.sin(angle) * radius,
                        1, 0.0D, 0.0D, 0.0D, 0.0D
                );
            }
        }

        if (progress >= 0.76D) {
            double cocoon = smoothstep(0.76D, 1.0D, progress);
            int bands = 2 + (int) Math.round(cocoon * 3.0D);
            for (int band = 0; band < bands; band++) {
                double bandRatio = bands <= 1 ? 0.0D : band / (double) (bands - 1);
                double bandY = y + 0.28D + bandRatio * 1.75D;
                double radius = 0.62D - 0.17D * cocoon + 0.06D * Math.sin(ticks * 0.21D + band);
                spawnRing(level, x, bandY, z, radius, 8 + (int) Math.round(cocoon * 6.0D),
                        phase * (1.5D + band * 0.08D) + band, CORE, ticks + band * 3, 0.018D);
            }
        }
    }

    private static void spawnRing(ServerLevel level, double centerX, double y, double centerZ, double radius,
                                  int points, double phase, DustParticleOptions particle, int waveTick, double waveHeight) {
        for (int i = 0; i < points; i++) {
            double angle = Math.PI * 2.0D * i / points + phase;
            double wave = waveHeight * Math.sin(angle * 3.0D + waveTick * 0.16D);
            level.sendParticles(
                    particle,
                    centerX + Math.cos(angle) * radius,
                    y + wave,
                    centerZ + Math.sin(angle) * radius,
                    1, 0.0D, 0.0D, 0.0D, 0.0D
            );
        }
    }

    private static void spawnFinalBurst(ServerPlayer player) {
        ServerLevel level = player.level();
        double x = player.getX();
        double y = player.getY() + 0.08D;
        double z = player.getZ();
        for (int band = 0; band < 4; band++) {
            double radius = 0.55D + band * 0.24D;
            double height = y + band * 0.42D;
            spawnRing(level, x, height, z, radius, 18, band * 0.7D, CORE, band * 5, 0.02D);
        }
        level.sendParticles(ParticleTypes.END_ROD, x, y + 1.05D, z, 36, 0.48D, 0.95D, 0.48D, 0.08D);
        level.sendParticles(CORE, x, y + 1.0D, z, 30, 0.42D, 0.82D, 0.42D, 0.02D);
        level.playSound(null, player.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.22F);
        level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8F, 1.9F);
    }

    private static void playSound(ServerPlayer player, int ticks, double progress) {
        ServerLevel level = player.level();
        if (ticks == 1) {
            level.playSound(null, player.blockPosition(), SoundEvents.BEACON_AMBIENT, SoundSource.PLAYERS, 0.55F, 0.72F);
            level.playSound(null, player.blockPosition(), SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.PLAYERS, 0.45F, 0.68F);
            return;
        }
        if (ticks % 20 != 0 || progress >= 0.985D) return;
        float pitch = (float) (0.72D + progress * 1.02D);
        float volume = (float) (0.28D + progress * 0.32D);
        level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, volume, pitch);
        if (progress >= 0.50D && ticks % 40 == 0) {
            level.playSound(null, player.blockPosition(), SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.PLAYERS,
                    (float) (0.34D + progress * 0.30D), (float) (0.82D + progress * 0.62D));
        }
    }

    private static double smoothstep(double edge0, double edge1, double value) {
        double t = Math.clamp((value - edge0) / (edge1 - edge0), 0.0D, 1.0D);
        return t * t * (3.0D - 2.0D * t);
    }
}
