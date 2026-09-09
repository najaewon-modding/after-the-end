package net.njw.aftertheend.city.altar;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.city.City;
import net.njw.aftertheend.city.CityLifecycleService;
import net.njw.aftertheend.city.CityManager;
import net.njw.aftertheend.network.AltarActivationPayload;
import net.njw.aftertheend.registry.ModContent;

public final class AltarRitualHandler {
    public static final int MAX_ACTIVATED_ALTARS_PER_CITY = 3;
    private static final int SCAN_INTERVAL_TICKS = 5;
    private static final int COMMIT_TICK = 80;
    private static final int END_TICK = 100;
    private static final int FLASH_COLOR = 0xE2DEE5;
    private static final Identifier RECORDED_DRAGON_EGG_ID = Identifier.fromNamespaceAndPath("njw_just_dragon_eggs", "recorded_dragon_egg");
    private static RitualSequence activeSequence;

    private AltarRitualHandler() { }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null) return;

        if (activeSequence != null) {
            tickSequence(server, level, activeSequence);
            return;
        }

        if (Math.floorMod(level.getGameTime(), SCAN_INTERVAL_TICKS) != 0L) return;
        tryStartRitual(server, level);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        activeSequence = null;
    }

    private static void tryStartRitual(MinecraftServer server, ServerLevel level) {
        City targetCity = CityManager.getNextLockedCity(server);
        if (targetCity == null) return;
        if (CityManager.getAccessibleCities(server).size() >= CityManager.getMaxCityCount(server)) return;

        for (City city : CityManager.getAccessibleCities(server)) {
            if (AltarManager.getActivatedCount(server, city.id()) >= MAX_ACTIVATED_ALTARS_PER_CITY) continue;
            for (AltarPlacement placement : AltarManager.getPlacements(server, city.id())) {
                if (placement.activated()) continue;
                RitualGeometry geometry = geometry(placement);
                if (!isPatternLoaded(level, geometry) || !hasRitualPattern(level, geometry)) continue;

                activeSequence = new RitualSequence(
                        city.id(),
                        targetCity.id(),
                        placement.blockX(),
                        placement.y(),
                        placement.blockZ(),
                        placement.large(),
                        geometry,
                        level.getGameTime()
                );
                level.playSound(null, geometry.center(), SoundEvents.END_PORTAL_FRAME_FILL, SoundSource.BLOCKS, 1.0F, 0.65F);
                level.sendParticles(ParticleTypes.PORTAL, geometry.center().getX() + 0.5, geometry.center().getY() + 0.8,
                        geometry.center().getZ() + 0.5, 48, 2.0, 1.0, 2.0, 0.04);
                broadcastEffect(level, activeSequence, false);
                AfterTheEnd.LOGGER.info("Started Altar activation ritual: city={}, targetCity={}, altar=({}, {}, {})",
                        city.id(), targetCity.id(), placement.blockX(), placement.y(), placement.blockZ());
                return;
            }
        }
    }

    private static void tickSequence(MinecraftServer server, ServerLevel level, RitualSequence sequence) {
        long elapsed = level.getGameTime() - sequence.startGameTime;
        if (elapsed < COMMIT_TICK && !hasRitualPattern(level, sequence.geometry)) {
            cancel(level, sequence, "ritual components changed before activation");
            return;
        }

        if (!sequence.committed && elapsed >= COMMIT_TICK) {
            if (!commit(server, level, sequence)) return;
        }

        if (elapsed >= END_TICK) {
            activeSequence = null;
        }
    }

    private static boolean commit(MinecraftServer server, ServerLevel level, RitualSequence sequence) {
        City nextLocked = CityManager.getNextLockedCity(server);
        if (nextLocked == null || !nextLocked.id().equals(sequence.targetCityId)) {
            cancel(level, sequence, "next locked city changed");
            return false;
        }
        if (!hasRitualPattern(level, sequence.geometry)) {
            cancel(level, sequence, "ritual components are incomplete");
            return false;
        }
        if (AltarManager.getActivatedCount(server, sequence.cityId) >= MAX_ACTIVATED_ALTARS_PER_CITY) {
            cancel(level, sequence, "city already has the maximum number of activated Altars");
            return false;
        }
        if (!AltarManager.setActivated(server, sequence.cityId, sequence.originX, sequence.originY, sequence.originZ, true)) {
            cancel(level, sequence, "Altar placement no longer exists");
            return false;
        }

        try {
            CityLifecycleService.unlockCity(server, sequence.targetCityId);
        } catch (RuntimeException exception) {
            AltarManager.setActivated(server, sequence.cityId, sequence.originX, sequence.originY, sequence.originZ, false);
            cancel(level, sequence, "city unlock failed: " + exception.getMessage());
            AfterTheEnd.LOGGER.error("Altar ritual failed while unlocking city {}", sequence.targetCityId, exception);
            return false;
        }

        level.removeBlock(sequence.geometry.center(), false);
        BlockPos center = sequence.geometry.center();
        level.sendParticles(new DustParticleOptions(FLASH_COLOR, 1.6F), center.getX() + 0.5, center.getY() + 1.0, center.getZ() + 0.5,
                120, 4.0, 2.5, 4.0, 0.08);
        level.sendParticles(ParticleTypes.END_ROD, center.getX() + 0.5, center.getY() + 1.0, center.getZ() + 0.5,
                64, 3.5, 2.0, 3.5, 0.07);
        level.playSound(null, center, SoundEvents.END_PORTAL_SPAWN, SoundSource.BLOCKS, 1.8F, 1.0F);
        sequence.committed = true;
        AfterTheEnd.LOGGER.info("Activated Altar and unlocked city {} from city {}.", sequence.targetCityId, sequence.cityId);
        return true;
    }

    private static void cancel(ServerLevel level, RitualSequence sequence, String reason) {
        broadcastEffect(level, sequence, true);
        AfterTheEnd.LOGGER.info("Cancelled Altar activation ritual at {}: {}", sequence.geometry.center(), reason);
        activeSequence = null;
    }

    private static void broadcastEffect(ServerLevel level, RitualSequence sequence, boolean cancelled) {
        PacketDistributor.sendToAllPlayers(new AltarActivationPayload(
                level.dimension().identifier(),
                sequence.geometry.center(),
                sequence.large,
                sequence.startGameTime,
                cancelled
        ));
    }

    private static RitualGeometry geometry(AltarPlacement placement) {
        int centerOffset = placement.large() ? 13 : 5;
        int socketRadius = placement.large() ? 7 : 3;
        BlockPos center = new BlockPos(placement.blockX() + centerOffset, placement.y() + 3, placement.blockZ() + centerOffset);
        return new RitualGeometry(
                center,
                new BlockPos[] {
                        center.offset(0, 0, -socketRadius),
                        center.offset(socketRadius, 0, 0),
                        center.offset(0, 0, socketRadius),
                        center.offset(-socketRadius, 0, 0)
                }
        );
    }

    private static boolean isPatternLoaded(ServerLevel level, RitualGeometry geometry) {
        if (!level.hasChunkAt(geometry.center())) return false;
        for (BlockPos socket : geometry.sockets()) if (!level.hasChunkAt(socket)) return false;
        return true;
    }

    private static boolean hasRitualPattern(ServerLevel level, RitualGeometry geometry) {
        if (!isPatternLoaded(level, geometry)) return false;
        Identifier centerBlockId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(geometry.center()).getBlock());
        if (!RECORDED_DRAGON_EGG_ID.equals(centerBlockId)) return false;
        for (BlockPos socket : geometry.sockets()) {
            if (!level.getBlockState(socket).is(ModContent.RESONANCE_CRYSTAL.get())) return false;
        }
        return true;
    }

    private record RitualGeometry(BlockPos center, BlockPos[] sockets) { }

    private static final class RitualSequence {
        private final UUID cityId;
        private final UUID targetCityId;
        private final int originX;
        private final int originY;
        private final int originZ;
        private final boolean large;
        private final RitualGeometry geometry;
        private final long startGameTime;
        private boolean committed;

        private RitualSequence(UUID cityId, UUID targetCityId, int originX, int originY, int originZ,
                               boolean large, RitualGeometry geometry, long startGameTime) {
            this.cityId = cityId;
            this.targetCityId = targetCityId;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.large = large;
            this.geometry = geometry;
            this.startGameTime = startGameTime;
        }
    }
}
