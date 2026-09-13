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
    private static final DustParticleOptions FRAME = new DustParticleOptions(0x2EC8FF, 0.78F);
    private static final DustParticleOptions STRAND_A = new DustParticleOptions(0x3A8DFF, 0.72F);
    private static final DustParticleOptions STRAND_B = new DustParticleOptions(0x55D8FF, 0.68F);
    private static final DustParticleOptions HIGHLIGHT = new DustParticleOptions(0xD0F6FF, 0.58F);
    private static final Set<UUID> FINAL_BURST_PLAYED = new HashSet<>();

    private CityMoveRecallEffect() { }

    static void tick(ServerPlayer player, int ticks, double progress) {
        UUID playerId = player.getUUID();
        if (ticks <= 1) FINAL_BURST_PLAYED.remove(playerId);
        playSound(player, ticks, progress);

        int interval = progress < 0.52D ? 3 : 2;
        if (ticks % interval == 0) spawnVisuals(player, ticks, progress);
        if (progress >= 0.985D && FINAL_BURST_PLAYED.add(playerId)) spawnFinalBurst(player);
    }

    private static void spawnVisuals(ServerPlayer player, int ticks, double progress) {
        ServerLevel level = player.level();
        double x = player.getX();
        double y = player.getY() + 0.025D;
        double z = player.getZ();

        double phase = ticks * (0.105D + progress * 0.185D);
        double bodyGrowth = smoothstep(0.06D, 0.875D, progress);
        double finalRise = smoothstep(0.875D, 0.995D, progress);
        double tighten = smoothstep(0.84D, 1.0D, progress);
        double pulse = 1.0D + 0.018D * Math.sin(ticks * 0.19D);

        double height = 0.18D + 1.25D * bodyGrowth + 0.95D * finalRise;
        double baseRadius = (0.98D - 0.09D * tighten) * pulse;
        double topRadius = (0.78D - 0.14D * tighten) / pulse;
        double turns = 1.35D + 1.45D * bodyGrowth + 0.45D * finalRise;
        int strands = progress < 0.40D ? 2 : progress < 0.875D ? 3 : 4;
        int points = 11 + (int) Math.round(bodyGrowth * 9.0D + finalRise * 5.0D);

        spawnRing(level, x, y + 0.025D, z, baseRadius, 20, phase, FRAME);

        for (int strand = 0; strand < strands; strand++) {
            double strandOffset = Math.PI * 2.0D * strand / strands;
            spawnStrand(level, x, y, z, phase, strandOffset, ticks, strand,
                    height, baseRadius, topRadius, turns, points);
        }

        if (progress >= 0.72D) {
            double crownAlpha = smoothstep(0.72D, 0.90D, progress);
            int crownPoints = 10 + (int) Math.round(crownAlpha * 8.0D);
            spawnRing(level, x, y + height, z, topRadius, crownPoints, phase + turns * Math.PI * 2.0D, FRAME);
        }

        if (progress >= 0.90D) {
            spawnFinalCoreStrand(level, x, y, z, phase, ticks, progress, height);
        }
    }

    private static void spawnStrand(ServerLevel level, double x, double y, double z, double phase,
                                    double strandOffset, int ticks, int strand, double height,
                                    double baseRadius, double topRadius, double turns, int points) {
        int runner = Math.floorMod(ticks / 2 + strand * 4, points);
        for (int step = 0; step < points; step++) {
            double vertical = points <= 1 ? 0.0D : step / (double) (points - 1);
            double radiusCurve = vertical * vertical * (3.0D - 2.0D * vertical);
            double radius = baseRadius + (topRadius - baseRadius) * radiusCurve;
            double angle = phase + strandOffset + vertical * Math.PI * 2.0D * turns;
            int runnerDistance = Math.abs(step - runner);
            runnerDistance = Math.min(runnerDistance, points - runnerDistance);
            DustParticleOptions particle = runnerDistance <= 1
                    ? HIGHLIGHT
                    : (strand & 1) == 0 ? STRAND_A : STRAND_B;
            level.sendParticles(
                    particle,
                    x + Math.cos(angle) * radius,
                    y + 0.035D + height * vertical,
                    z + Math.sin(angle) * radius,
                    1, 0.0D, 0.0D, 0.0D, 0.0D
            );
        }
    }

    private static void spawnFinalCoreStrand(ServerLevel level, double x, double y, double z,
                                             double phase, int ticks, double progress, double height) {
        double intensity = smoothstep(0.90D, 1.0D, progress);
        int points = 10 + (int) Math.round(intensity * 8.0D);
        double turns = 1.8D + 1.3D * intensity;
        for (int step = 0; step < points; step++) {
            double vertical = points <= 1 ? 0.0D : step / (double) (points - 1);
            double angle = -phase * 1.35D + vertical * Math.PI * 2.0D * turns;
            double radius = 0.56D - 0.13D * vertical - 0.06D * intensity;
            DustParticleOptions particle = (step + ticks / 2) % 6 <= 1 ? HIGHLIGHT : STRAND_B;
            level.sendParticles(
                    particle,
                    x + Math.cos(angle) * radius,
                    y + 0.07D + height * vertical * 0.96D,
                    z + Math.sin(angle) * radius,
                    1, 0.0D, 0.0D, 0.0D, 0.0D
            );
        }
    }

    private static void spawnRing(ServerLevel level, double x, double y, double z, double radius,
                                  int points, double phase, DustParticleOptions particle) {
        for (int i = 0; i < points; i++) {
            double angle = phase + Math.PI * 2.0D * i / points;
            level.sendParticles(
                    particle,
                    x + Math.cos(angle) * radius,
                    y,
                    z + Math.sin(angle) * radius,
                    1, 0.0D, 0.0D, 0.0D, 0.0D
            );
        }
    }

    private static void spawnFinalBurst(ServerPlayer player) {
        ServerLevel level = player.level();
        double x = player.getX();
        double y = player.getY() + 0.05D;
        double z = player.getZ();
        double phase = player.tickCount * 0.31D;

        double[] heights = {0.04D, 0.78D, 1.52D, 2.24D};
        double[] radii = {0.90D, 0.73D, 0.58D, 0.46D};
        for (int i = 0; i < heights.length; i++) {
            spawnRing(level, x, y + heights[i], z, radii[i], 16, phase + i * 0.55D, HIGHLIGHT);
        }
        for (int strand = 0; strand < 4; strand++) {
            double offset = Math.PI * 2.0D * strand / 4.0D;
            for (int step = 0; step < 12; step++) {
                double vertical = step / 11.0D;
                double angle = phase + offset + vertical * Math.PI * 5.2D;
                double radius = 0.88D - 0.52D * vertical;
                level.sendParticles(
                        HIGHLIGHT,
                        x + Math.cos(angle) * radius,
                        y + 0.03D + vertical * 2.30D,
                        z + Math.sin(angle) * radius,
                        1, 0.0D, 0.0D, 0.0D, 0.0D
                );
            }
        }
        level.sendParticles(ParticleTypes.END_ROD, x, y + 1.12D, z, 12, 0.24D, 0.72D, 0.24D, 0.045D);
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
