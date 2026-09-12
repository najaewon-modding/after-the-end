package net.njw.aftertheend.city;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public final class PlayerPositionTracker {
    private static final Map<UUID, Integer> POSITION_SAVE_TICKS = new HashMap<>();
    private static final Map<UUID, Long> RETURN_DEADLINES = new HashMap<>();
    private static final Map<UUID, Long> LAST_WARNING_SECONDS = new HashMap<>();
    private static final Map<UUID, CityLocationCache> CITY_LOCATION_CACHE = new HashMap<>();

    private PlayerPositionTracker() { }

    public static int incrementPositionSaveTicks(UUID playerId) {
        int ticks = POSITION_SAVE_TICKS.getOrDefault(playerId, 0) + 1;
        POSITION_SAVE_TICKS.put(playerId, ticks);
        return ticks;
    }

    public static void resetPositionSaveTicks(UUID playerId) { POSITION_SAVE_TICKS.remove(playerId); }

    public static long getOrCreateReturnDeadline(UUID playerId, long delayNanos) {
        return RETURN_DEADLINES.computeIfAbsent(playerId, ignored -> {
            LAST_WARNING_SECONDS.remove(playerId);
            return System.nanoTime() + delayNanos;
        });
    }

    public static boolean hasReturnDeadline(UUID playerId) { return RETURN_DEADLINES.containsKey(playerId); }

    public static void resetReturnDeadline(UUID playerId) {
        RETURN_DEADLINES.remove(playerId);
        LAST_WARNING_SECONDS.remove(playerId);
    }

    public static boolean shouldSendReturnWarning(UUID playerId, long remainingSeconds) {
        Long previous = LAST_WARNING_SECONDS.put(playerId, remainingSeconds);
        return previous == null || previous != remainingSeconds;
    }

    public static City getCachedCity(UUID playerId, ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        CityLocationCache cache = CITY_LOCATION_CACHE.get(playerId);
        if (cache == null || !cache.dimension().equals(dimension) || cache.chunkX() != chunkX || cache.chunkZ() != chunkZ) return null;
        return cache.city();
    }

    public static boolean hasCachedLocation(UUID playerId, ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        CityLocationCache cache = CITY_LOCATION_CACHE.get(playerId);
        return cache != null && cache.dimension().equals(dimension) && cache.chunkX() == chunkX && cache.chunkZ() == chunkZ;
    }

    public static void cacheCity(UUID playerId, ResourceKey<Level> dimension, int chunkX, int chunkZ, City city) {
        CITY_LOCATION_CACHE.put(playerId, new CityLocationCache(dimension, chunkX, chunkZ, city));
    }

    public static void invalidateCityCache(UUID playerId) { CITY_LOCATION_CACHE.remove(playerId); }
    public static void invalidateAllCityCaches() { CITY_LOCATION_CACHE.clear(); }

    public static void resetSession(UUID playerId) {
        POSITION_SAVE_TICKS.remove(playerId);
        RETURN_DEADLINES.remove(playerId);
        LAST_WARNING_SECONDS.remove(playerId);
        CITY_LOCATION_CACHE.remove(playerId);
    }

    private record CityLocationCache(ResourceKey<Level> dimension, int chunkX, int chunkZ, City city) { }
}
