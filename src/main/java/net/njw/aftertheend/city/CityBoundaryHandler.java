package net.njw.aftertheend.city;

import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

public final class CityBoundaryHandler {
    private static final long TELEPORT_DELAY_NANOS = 5_000_000_000L;
    private static final int POSITION_SAVE_INTERVAL_TICKS = 80;
    private static final double POSITION_SAVE_DISTANCE_SQUARED = 64.0;

    private CityBoundaryHandler() { }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ServerLevel level = player.level();
        UUID playerId = player.getUUID();

        if (!CityRestrictionPolicy.isManagedDimension(level.dimension())) {
            PlayerPositionTracker.resetReturnDeadline(playerId);
            PlayerPositionTracker.resetPositionSaveTicks(playerId);
            PlayerPositionTracker.invalidateCityCache(playerId);
            return;
        }

        MinecraftServer server = level.getServer();
        CitySavedData savedData = server.getDataStorage().computeIfAbsent(CitySavedData.TYPE);
        int chunkX = player.getBlockX() >> 4;
        int chunkZ = player.getBlockZ() >> 4;
        City currentCity;
        if (PlayerPositionTracker.hasCachedLocation(playerId, level.dimension(), chunkX, chunkZ)) {
            currentCity = PlayerPositionTracker.getCachedCity(playerId, level.dimension(), chunkX, chunkZ);
        } else {
            currentCity = CityManager.findAccessibleCityContaining(server, level.dimension(), player.getBlockX(), player.getBlockZ());
            PlayerPositionTracker.cacheCity(playerId, level.dimension(), chunkX, chunkZ, currentCity);
        }

        if (currentCity != null) handleInsideAccessibleCity(player, level, server, currentCity, savedData);
        else handleOutsideAccessibleArea(player, savedData);
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        UUID playerId = player.getUUID();
        if (PlayerPositionTracker.hasReturnDeadline(playerId) && CityRestrictionPolicy.isManagedDimension(player.level().dimension())) {
            player.level().getServer().getDataStorage().computeIfAbsent(CitySavedData.TYPE).markPendingReturn(playerId);
        }
        PlayerPositionTracker.resetSession(playerId);
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ServerLevel level = player.level();
        UUID playerId = player.getUUID();
        PlayerPositionTracker.invalidateCityCache(playerId);
        if (!CityRestrictionPolicy.isManagedDimension(level.dimension())) return;

        MinecraftServer server = level.getServer();
        CitySavedData savedData = server.getDataStorage().computeIfAbsent(CitySavedData.TYPE);
        if (!savedData.hasPendingReturn(playerId)) return;

        if (CityManager.isInsideAccessibleCity(server, level.dimension(), player.getBlockX(), player.getBlockZ())) {
            savedData.clearPendingReturn(playerId);
            saveCurrentPosition(player, level, savedData);
            PlayerPositionTracker.resetSession(playerId);
            return;
        }

        if (returnPlayerToSafePosition(player, savedData)) {
            savedData.clearPendingReturn(playerId);
            PlayerPositionTracker.resetSession(playerId);
        }
    }

    private static void handleInsideAccessibleCity(ServerPlayer player, ServerLevel level, MinecraftServer server, City currentCity, CitySavedData savedData) {
        UUID playerId = player.getUUID();
        boolean returnedFromOutside = PlayerPositionTracker.hasReturnDeadline(playerId);
        PlayerPositionTracker.resetReturnDeadline(playerId);
        if (savedData.hasPendingReturn(playerId)) savedData.clearPendingReturn(playerId);
        CitySavedData.SafePosition lastPosition = savedData.getLastValidPosition(playerId, level.dimension());

        if (lastPosition == null || returnedFromOutside || isSafePositionInDifferentCity(server, currentCity, lastPosition)) {
            saveCurrentPosition(player, level, savedData);
            PlayerPositionTracker.resetPositionSaveTicks(playerId);
            return;
        }

        boolean changedChunk = blockCoordinate(lastPosition.x()) >> 4 != player.getBlockX() >> 4
                || blockCoordinate(lastPosition.z()) >> 4 != player.getBlockZ() >> 4;
        double dx = player.getX() - lastPosition.x();
        double dz = player.getZ() - lastPosition.z();
        boolean movedEnough = dx * dx + dz * dz >= POSITION_SAVE_DISTANCE_SQUARED;
        boolean intervalElapsed = PlayerPositionTracker.incrementPositionSaveTicks(playerId) >= POSITION_SAVE_INTERVAL_TICKS;
        if (changedChunk || movedEnough || intervalElapsed) {
            saveCurrentPosition(player, level, savedData);
            PlayerPositionTracker.resetPositionSaveTicks(playerId);
        }
    }

    private static void handleOutsideAccessibleArea(ServerPlayer player, CitySavedData savedData) {
        UUID playerId = player.getUUID();
        long remainingNanos = PlayerPositionTracker.getOrCreateReturnDeadline(playerId, TELEPORT_DELAY_NANOS) - System.nanoTime();
        if (remainingNanos <= 0L) {
            if (returnPlayerToSafePosition(player, savedData)) {
                PlayerPositionTracker.resetReturnDeadline(playerId);
                PlayerPositionTracker.resetPositionSaveTicks(playerId);
                PlayerPositionTracker.invalidateCityCache(playerId);
            }
            return;
        }
        long remainingSeconds = (remainingNanos + 999_999_999L) / 1_000_000_000L;
        if (PlayerPositionTracker.shouldSendReturnWarning(playerId, remainingSeconds)) {
            player.sendOverlayMessage(Component.translatable("message.njw_after_the_end.boundary.return_warning", remainingSeconds));
        }
    }

    private static boolean returnPlayerToSafePosition(ServerPlayer player, CitySavedData savedData) {
        ServerLevel level = player.level();
        MinecraftServer server = level.getServer();
        UUID playerId = player.getUUID();
        CitySavedData.SafePosition lastPosition = savedData.getLastValidPosition(playerId, level.dimension());

        if (lastPosition != null) {
            City safeCity = CityManager.findAccessibleCityContaining(server, lastPosition.dimension(), blockCoordinate(lastPosition.x()), blockCoordinate(lastPosition.z()));
            if (safeCity != null) {
                teleport(player, lastPosition.x(), lastPosition.y(), lastPosition.z(), lastPosition.yRot(), lastPosition.xRot());
                player.sendOverlayMessage(Component.translatable("message.njw_after_the_end.boundary.returned"));
                return true;
            }
        }

        CitySafePositionService.SafePosition fallback = CitySafePositionService.findStartingCityFallback(server, level);
        if (fallback == null) return false;
        teleport(player, fallback.x(), fallback.y(), fallback.z(), player.getYRot(), player.getXRot());
        savedData.setLastValidPosition(playerId, level.dimension(), fallback.x(), fallback.y(), fallback.z(), player.getYRot(), player.getXRot());
        player.sendOverlayMessage(Component.translatable("message.njw_after_the_end.boundary.returned"));
        return true;
    }

    private static void teleport(ServerPlayer player, double x, double y, double z, float yRot, float xRot) {
        player.setDeltaMovement(0.0, 0.0, 0.0);
        player.teleportTo(x, y, z);
        player.setDeltaMovement(0.0, 0.0, 0.0);
        player.hurtMarked = true;
        player.setYRot(yRot);
        player.setXRot(xRot);
    }

    private static void saveCurrentPosition(ServerPlayer player, ServerLevel level, CitySavedData savedData) {
        savedData.setLastValidPosition(player.getUUID(), level.dimension(), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }

    private static boolean isSafePositionInDifferentCity(MinecraftServer server, City currentCity, CitySavedData.SafePosition safePosition) {
        City previousCity = CityManager.findAccessibleCityContaining(server, safePosition.dimension(), blockCoordinate(safePosition.x()), blockCoordinate(safePosition.z()));
        return previousCity == null || !previousCity.id().equals(currentCity.id());
    }

    private static int blockCoordinate(double coordinate) { return (int) Math.floor(coordinate); }
}
