package net.njw.aftertheend.city;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.njw.aftertheend.network.CityRecallSoundPayload;

public final class CityMoveRecallEffect {
    private static final DustParticleOptions FRAME = new DustParticleOptions(0x2EC8FF, 0.76F);
    private static final DustParticleOptions STRAND_A = new DustParticleOptions(0x3A8DFF, 0.70F);
    private static final DustParticleOptions STRAND_B = new DustParticleOptions(0x55D8FF, 0.66F);
    private static final DustParticleOptions HIGHLIGHT = new DustParticleOptions(0xD0F6FF, 0.56F);
    private static final double PORTAL_TRIGGER_START_PROGRESS = 0.50D;
    private static final double FINAL_RISE_START_PROGRESS = 0.875D;
    private static final Map<UUID, Long> SOUND_HEARTBEATS = new HashMap<>();
    private static final Set<UUID> PORTAL_TRIGGER_PLAYED = new HashSet<>();
    private static final Set<UUID> FINAL_BURST_PLAYED = new HashSet<>();
    private static long serverTick;

    private CityMoveRecallEffect() { }

    static void tick(ServerPlayer player, int ticks, double progress) {
        UUID playerId = player.getUUID();
        if (ticks <= 1) {
            PORTAL_TRIGGER_PLAYED.remove(playerId);
            FINAL_BURST_PLAYED.remove(playerId);
            if (!SOUND_HEARTBEATS.containsKey(playerId)) sendSoundState(player, true);
        }
        SOUND_HEARTBEATS.put(playerId, serverTick);

        int interval = progress < FINAL_RISE_START_PROGRESS ? 3 : 2;
        if (ticks % interval == 0) spawnVisuals(player, ticks, progress);

        if (progress >= PORTAL_TRIGGER_START_PROGRESS && PORTAL_TRIGGER_PLAYED.add(playerId)) {
            player.level().playSound(null, player.blockPosition(), SoundEvents.PORTAL_TRIGGER,
                    SoundSource.PLAYERS, 1.0F, 1.0F);
        }
        if (progress >= 0.998D && FINAL_BURST_PLAYED.add(playerId)) {
            stopSound(player);
            spawnFinalBurst(player);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        serverTick++;
        if (SOUND_HEARTBEATS.isEmpty()) return;
        for (Map.Entry<UUID, Long> entry : Map.copyOf(SOUND_HEARTBEATS).entrySet()) {
            if (serverTick - entry.getValue() < 2L) continue;
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player != null) sendSoundState(player, false);
            SOUND_HEARTBEATS.remove(entry.getKey());
            PORTAL_TRIGGER_PLAYED.remove(entry.getKey());
            FINAL_BURST_PLAYED.remove(entry.getKey());
        }
    }

    private static void spawnVisuals(ServerPlayer player, int ticks, double progress) {
        ServerLevel level = player.level();
        double x = player.getX();
        double y = player.getY() + 0.025D;
        double z = player.getZ();
        double phase = ticks * (0.11D + progress * 0.20D);
        double pulse = 1.0D + 0.012D * Math.sin(ticks * 0.19D);
        double baseRadius = 0.90D * pulse;

        spawnRing(level, x, y + 0.025D, z, baseRadius, 18, phase, FRAME);
        spawnDriftingParticles(level, x, y, z, ticks, progress, phase, baseRadius);

        if (progress < FINAL_RISE_START_PROGRESS) return;

        double finalPhase = Math.clamp((progress - FINAL_RISE_START_PROGRESS) / (1.0D - FINAL_RISE_START_PROGRESS), 0.0D, 1.0D);
        double rise = finalPhase * finalPhase;
        double density = smoothstep(FINAL_RISE_START_PROGRESS, 0.995D, progress);
        double tighten = smoothstep(0.93D, 1.0D, progress);
        double height = 0.10D + 2.24D * rise;
        double topRadius = 0.60D - 0.08D * tighten;
        double turns = 1.65D + 1.35D * rise + 0.42D * density;
        int strands = finalPhase < 0.52D ? 3 : finalPhase < 0.82D ? 4 : 5;
        int points = 11 + (int) Math.round(9.0D * rise + 6.0D * density);

        for (int strand = 0; strand < strands; strand++) {
            double strandOffset = Math.PI * 2.0D * strand / strands;
            spawnStrand(level, x, y, z, phase, strandOffset, ticks, strand,
                    height, baseRadius, topRadius, turns, points);
        }

        if (rise >= 0.55D) {
            int crownPoints = 8 + (int) Math.round(8.0D * rise + 3.0D * density);
            spawnRing(level, x, y + height, z, topRadius, crownPoints,
                    phase + turns * Math.PI * 2.0D, FRAME);
        }
        if (finalPhase >= 0.62D) {
            spawnSecondaryStrands(level, x, y, z, phase, ticks, finalPhase,
                    height, baseRadius, topRadius, density);
        }
        if (finalPhase >= 0.88D) spawnFinalCoreStrand(level, x, y, z, phase, ticks, finalPhase, height);
    }

    private static void spawnDriftingParticles(ServerLevel level, double x, double y, double z,
                                               int ticks, double progress, double phase, double baseRadius) {
        int count = progress < FINAL_RISE_START_PROGRESS ? 5 : 3;
        for (int i = 0; i < count; i++) {
            double cycle = (ticks * 0.018D + i * 0.213D) % 1.0D;
            double angle = phase * 0.46D + i * 2.399963229728653D + cycle * 1.8D;
            double radius = 0.34D + baseRadius * (0.38D + 0.24D * Math.sin(i * 1.7D + ticks * 0.07D));
            double height = 0.16D + cycle * 1.55D;
            DustParticleOptions particle = (i + ticks / 6) % 4 == 0 ? HIGHLIGHT : STRAND_B;
            level.sendParticles(
                    particle,
                    x + Math.cos(angle) * radius,
                    y + height,
                    z + Math.sin(angle) * radius,
                    1, 0.0D, 0.0D, 0.0D, 0.0D
            );
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

    private static void spawnSecondaryStrands(ServerLevel level, double x, double y, double z,
                                              double phase, int ticks, double finalPhase, double height,
                                              double baseRadius, double topRadius, double density) {
        int strandCount = finalPhase < 0.88D ? 1 : 2;
        int points = 8 + (int) Math.round(density * 10.0D);
        double turns = 1.75D + 1.0D * density;
        for (int strand = 0; strand < strandCount; strand++) {
            double offset = Math.PI * (0.55D + strand) + density * 0.35D;
            int runner = Math.floorMod(ticks / 2 + strand * 6, points);
            for (int step = 0; step < points; step++) {
                double vertical = points <= 1 ? 0.0D : step / (double) (points - 1);
                double radiusCurve = vertical * vertical * (3.0D - 2.0D * vertical);
                double radius = (baseRadius - 0.12D) + ((topRadius - 0.08D) - (baseRadius - 0.12D)) * radiusCurve;
                double angle = -phase * (0.82D + 0.34D * density) + offset
                        + vertical * Math.PI * 2.0D * turns;
                int runnerDistance = Math.abs(step - runner);
                runnerDistance = Math.min(runnerDistance, points - runnerDistance);
                DustParticleOptions particle = runnerDistance == 0 ? HIGHLIGHT : STRAND_B;
                level.sendParticles(
                        particle,
                        x + Math.cos(angle) * radius,
                        y + 0.055D + height * vertical * 0.98D,
                        z + Math.sin(angle) * radius,
                        1, 0.0D, 0.0D, 0.0D, 0.0D
                );
            }
        }
    }

    private static void spawnFinalCoreStrand(ServerLevel level, double x, double y, double z,
                                             double phase, int ticks, double finalPhase, double height) {
        double intensity = smoothstep(0.88D, 1.0D, finalPhase);
        int points = 10 + (int) Math.round(intensity * 8.0D);
        double turns = 1.9D + 1.2D * intensity;
        for (int step = 0; step < points; step++) {
            double vertical = points <= 1 ? 0.0D : step / (double) (points - 1);
            double angle = -phase * 1.35D + vertical * Math.PI * 2.0D * turns;
            double radius = 0.46D - 0.11D * vertical - 0.05D * intensity;
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
        double[] radii = {0.82D, 0.67D, 0.54D, 0.44D};
        for (int i = 0; i < heights.length; i++) {
            spawnRing(level, x, y + heights[i], z, radii[i], 16, phase + i * 0.55D, HIGHLIGHT);
        }
        for (int strand = 0; strand < 5; strand++) {
            double offset = Math.PI * 2.0D * strand / 5.0D;
            for (int step = 0; step < 13; step++) {
                double vertical = step / 12.0D;
                double angle = phase + offset + vertical * Math.PI * 5.6D;
                double radius = 0.82D - 0.44D * vertical;
                level.sendParticles(
                        HIGHLIGHT,
                        x + Math.cos(angle) * radius,
                        y + 0.03D + vertical * 2.30D,
                        z + Math.sin(angle) * radius,
                        1, 0.0D, 0.0D, 0.0D, 0.0D
                );
            }
        }
        level.sendParticles(ParticleTypes.END_ROD, x, y + 1.12D, z, 8, 0.20D, 0.66D, 0.20D, 0.040D);
        level.playSound(null, player.blockPosition(), SoundEvents.PORTAL_TRAVEL, SoundSource.PLAYERS, 1.0F, 1.0F);
    }

    private static void stopSound(ServerPlayer player) {
        if (SOUND_HEARTBEATS.remove(player.getUUID()) != null) sendSoundState(player, false);
    }

    private static void sendSoundState(ServerPlayer player, boolean active) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                player,
                new CityRecallSoundPayload(player.getUUID(), active)
        );
    }

    private static double smoothstep(double edge0, double edge1, double value) {
        double t = Math.clamp((value - edge0) / (edge1 - edge0), 0.0D, 1.0D);
        return t * t * (3.0D - 2.0D * t);
    }
}
