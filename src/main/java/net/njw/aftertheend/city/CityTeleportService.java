package net.njw.aftertheend.city;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.network.CityTeleportRequestPayload;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CityTeleportService {
    private static final long CAST_DURATION_NANOS = 3_000_000_000L;
    private static final long COOLDOWN_DURATION_NANOS = 3_000_000_000L;
    private static final long GLOBAL_SEARCH_BUDGET_NANOS_PER_TICK = 5_000_000L;
    private static final double MOVEMENT_CANCEL_DISTANCE_SQUARED = 0.01D;
    private static final int BLOCKS_PER_CHUNK = 16;
    private static final int SAFE_SEARCH_RADIUS = 512;
    private static final int RADIAL_STEP = 32;
    private static final int RADIAL_DIRECTION_COUNT = 16;
    private static final int LOCAL_SEARCH_RADIUS = 2;
    private static final int MONSTER_CHECK_INTERVAL_TICKS = 5;
    private static final double MONSTER_HORIZONTAL_RANGE = 8.0D;
    private static final double MONSTER_VERTICAL_RANGE = 5.0D;
    private static final List<SearchOffset> SEARCH_OFFSETS = createSearchOffsets();
    private static final List<SearchOffset> LOCAL_OFFSETS = createLocalOffsets();
    private static final Map<UUID, CastSession> ACTIVE_CASTS = new HashMap<>();
    private static final Map<UUID, Long> COOLDOWN_DEADLINES = new HashMap<>();

    private CityTeleportService() {
    }

    public static void handleRequest(CityTeleportRequestPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) startCast(player, payload.cityId());
    }

    private static void startCast(ServerPlayer player, String cityId) {
        UUID playerId = player.getUUID();
        if (ACTIVE_CASTS.containsKey(playerId)) {
            player.sendOverlayMessage(Component.translatable("message.njw_after_the_end.city_move.already_casting"));
            return;
        }

        long now = System.nanoTime();
        Long cooldownDeadline = COOLDOWN_DEADLINES.get(playerId);
        if (cooldownDeadline != null) {
            long remaining = cooldownDeadline - now;
            if (remaining > 0L) {
                player.sendOverlayMessage(Component.translatable("message.njw_after_the_end.city_move.cooldown", formatSeconds(remaining)));
                return;
            }
            COOLDOWN_DEADLINES.remove(playerId);
        }

        ServerLevel level = player.level();
        MinecraftServer server = level.getServer();
        City city = CityManager.getCity(server, cityId);
        if (city == null) {
            player.sendOverlayMessage(Component.translatable("message.njw_after_the_end.city_move.unknown_city"));
            return;
        }
        if (!CityManager.isCityAccessible(server, cityId)) {
            player.sendOverlayMessage(Component.translatable("message.njw_after_the_end.city_move.city_locked"));
            return;
        }
        CityRegion region = city.getRegion(level.dimension()).orElse(null);
        if (region == null) {
            player.sendOverlayMessage(Component.translatable("message.njw_after_the_end.city_move.dimension_unavailable"));
            return;
        }
        if (hasRestPreventingMonsterNearby(player)) {
            player.sendOverlayMessage(Component.translatable("message.njw_after_the_end.city_move.monsters_nearby"));
            return;
        }

        CitySavedData.CityArrivalPosition stored = CityManager.getCityArrivalPosition(server, cityId, level.dimension());
        SafeDestination cached = stored == null ? null : new SafeDestination(stored.blockX(), stored.y(), stored.blockZ());
        DestinationSearchTask search = new DestinationSearchTask(level, region, cityId, cached);
        ServerBossEvent bossBar = createCastBossBar(level, player, city);
        ACTIVE_CASTS.put(playerId, new CastSession(cityId, level.dimension(), player.position(), now, now + CAST_DURATION_NANOS, search, bossBar));
        player.sendOverlayMessage(Component.translatable("message.njw_after_the_end.city_move.preparing", Component.literal(city.name())));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (ACTIVE_CASTS.isEmpty()) return;
        MinecraftServer server = event.getServer();
        List<UUID> playerIds = List.copyOf(ACTIVE_CASTS.keySet());
        long searchDeadline = System.nanoTime() + GLOBAL_SEARCH_BUDGET_NANOS_PER_TICK;
        long fairShare = Math.max(100_000L, GLOBAL_SEARCH_BUDGET_NANOS_PER_TICK / Math.max(1, playerIds.size()));

        for (UUID playerId : playerIds) {
            CastSession session = ACTIVE_CASTS.get(playerId);
            if (session == null) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) { removeCastSession(playerId); continue; }
            if (!player.isAlive()) { cancelCast(player, "message.njw_after_the_end.city_move.interrupted"); continue; }
            if (!player.level().dimension().equals(session.dimension)) { cancelCast(player, "message.njw_after_the_end.city_move.interrupted_dimension"); continue; }
            if (player.position().distanceToSqr(session.startPosition) > MOVEMENT_CANCEL_DISTANCE_SQUARED) { cancelCast(player, "message.njw_after_the_end.city_move.interrupted_movement"); continue; }

            session.ticks++;
            if (session.ticks % MONSTER_CHECK_INTERVAL_TICKS == 0 && hasRestPreventingMonsterNearby(player)) {
                cancelCast(player, "message.njw_after_the_end.city_move.interrupted_monsters");
                continue;
            }

            long remainingBudget = searchDeadline - System.nanoTime();
            if (remainingBudget > 0L && !session.search.isComplete()) session.search.advance(Math.min(fairShare, remainingBudget));

            long now = System.nanoTime();
            float progress = (float) Math.max(0.0D, Math.min(1.0D, (double) (now - session.startedAtNanos) / CAST_DURATION_NANOS));
            session.bossBar.setProgress(progress);
            if (now < session.completesAtNanos) continue;

            if (!session.search.isComplete()) {
                AfterTheEnd.LOGGER.warn("City Move destination search timed out. player={}, city={}, {}", playerId, session.cityId, session.search.debugSummary());
                cancelCast(player, "message.njw_after_the_end.city_move.failed_timeout");
                continue;
            }
            if (!session.search.hasDestination()) {
                AfterTheEnd.LOGGER.warn("City Move destination search completed without destination. player={}, city={}, {}", playerId, session.cityId, session.search.debugSummary());
                cancelCast(player, "message.njw_after_the_end.city_move.failed_no_destination");
                continue;
            }

            SafeDestination destination = session.search.destination();
            City city = CityManager.getCity(server, session.cityId);
            if (city == null || !CityManager.isCityAccessible(server, session.cityId)) { cancelCast(player, "message.njw_after_the_end.city_move.failed_city_unavailable"); continue; }
            CityRegion region = city.getRegion(session.dimension).orElse(null);
            if (region == null || !region.containsBlock(destination.blockX(), destination.blockZ())) {
                CityManager.clearCityArrivalPosition(server, session.cityId, session.dimension);
                cancelCast(player, "message.njw_after_the_end.city_move.failed_destination_invalid");
                continue;
            }
            if (!isSafeStandingPosition(player.level(), destination.blockX(), destination.y(), destination.blockZ())) {
                CityManager.clearCityArrivalPosition(server, session.cityId, session.dimension);
                cancelCast(player, "message.njw_after_the_end.city_move.failed_destination_unsafe");
                continue;
            }
            if (hasRestPreventingMonsterNearby(player)) { cancelCast(player, "message.njw_after_the_end.city_move.interrupted_monsters"); continue; }

            destination = resolveSharedArrival(player.level(), city, destination);
            completeTeleport(player, city, destination, now);
        }
    }

    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && event.getInflictedDamage() > 0.0F && ACTIVE_CASTS.containsKey(player.getUUID())) {
            cancelCast(player, "message.njw_after_the_end.city_move.interrupted_damage");
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) removeCastSession(player.getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        for (CastSession session : ACTIVE_CASTS.values()) closeBossBar(session.bossBar);
        ACTIVE_CASTS.clear();
        COOLDOWN_DEADLINES.clear();
    }

    private static SafeDestination resolveSharedArrival(ServerLevel level, City city, SafeDestination calculated) {
        MinecraftServer server = level.getServer();
        ResourceKey<Level> dimension = level.dimension();
        CitySavedData.CityArrivalPosition stored = CityManager.getCityArrivalPosition(server, city.id(), dimension);
        if (stored != null) {
            int x = stored.blockX(), y = stored.y(), z = stored.blockZ();
            if (city.contains(dimension, x, z)) {
                level.getChunk(x >> 4, z >> 4);
                if (isSafeStandingPosition(level, x, y, z)) return new SafeDestination(x, y, z);
            }
            CityManager.clearCityArrivalPosition(server, city.id(), dimension);
        }
        CityManager.setCityArrivalPosition(server, city.id(), dimension, calculated.blockX(), calculated.y(), calculated.blockZ());
        return calculated;
    }

    private static void completeTeleport(ServerPlayer player, City city, SafeDestination destination, long now) {
        UUID playerId = player.getUUID();
        ServerLevel level = player.level();
        MinecraftServer server = level.getServer();
        double x = destination.blockX() + 0.5D;
        double y = destination.y();
        double z = destination.blockZ() + 0.5D;
        player.setDeltaMovement(0.0D, 0.0D, 0.0D);
        player.teleportTo(x, y, z);
        player.setDeltaMovement(0.0D, 0.0D, 0.0D);
        player.hurtMarked = true;
        PlayerPositionTracker.resetReturnDeadline(playerId);
        PlayerPositionTracker.resetPositionSaveTicks(playerId);
        PlayerPositionTracker.invalidateCityCache(playerId);
        CitySavedData savedData = server.getDataStorage().computeIfAbsent(CitySavedData.TYPE);
        savedData.setLastValidPosition(playerId, level.dimension(), x, y, z, player.getYRot(), player.getXRot());
        if (savedData.hasPendingReturn(playerId)) savedData.clearPendingReturn(playerId);
        removeCastSession(playerId);
        COOLDOWN_DEADLINES.put(playerId, now + COOLDOWN_DURATION_NANOS);
        player.sendOverlayMessage(Component.translatable("message.njw_after_the_end.city_move.moved", Component.literal(city.name())));
    }

    private static void cancelCast(ServerPlayer player, String translationKey) {
        if (!ACTIVE_CASTS.containsKey(player.getUUID())) return;
        removeCastSession(player.getUUID());
        player.sendOverlayMessage(Component.translatable(translationKey));
    }

    private static void removeCastSession(UUID playerId) {
        CastSession session = ACTIVE_CASTS.remove(playerId);
        if (session != null) closeBossBar(session.bossBar);
    }

    private static ServerBossEvent createCastBossBar(ServerLevel level, ServerPlayer player, City city) {
        ServerBossEvent bar = new ServerBossEvent(Mth.createInsecureUUID(level.getRandom()), Component.translatable("message.njw_after_the_end.city_move.boss_bar", Component.literal(city.name())), BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);
        bar.setProgress(0.0F);
        bar.setPlayBossMusic(false);
        bar.setCreateWorldFog(false);
        bar.setDarkenScreen(false);
        bar.addPlayer(player);
        return bar;
    }

    private static void closeBossBar(ServerBossEvent bossBar) {
        bossBar.setVisible(false);
        bossBar.removeAllPlayers();
    }

    private static boolean hasRestPreventingMonsterNearby(ServerPlayer player) {
        ServerLevel level = player.level();
        Vec3 center = Vec3.atBottomCenterOf(player.blockPosition());
        AABB box = new AABB(center.x() - MONSTER_HORIZONTAL_RANGE, center.y() - MONSTER_VERTICAL_RANGE, center.z() - MONSTER_HORIZONTAL_RANGE,
                center.x() + MONSTER_HORIZONTAL_RANGE, center.y() + MONSTER_VERTICAL_RANGE, center.z() + MONSTER_HORIZONTAL_RANGE);
        return !level.getEntitiesOfClass(Monster.class, box, monster -> monster.isPreventingPlayerRest(level, player)).isEmpty();
    }

    private static String formatSeconds(long nanos) {
        double seconds = Math.ceil(nanos / 100_000_000.0D) / 10.0D;
        return String.format(Locale.ROOT, "%.1f", seconds);
    }

    private static final class DestinationSearchTask {
        private final ServerLevel level;
        private final CityRegion region;
        private final String cityId;
        private final SafeDestination cached;
        private final Set<Long> loadedChunks = new HashSet<>();
        private SearchStage stage;
        private int offsetIndex;
        private int localIndex;
        private int localCenterX;
        private int localCenterZ;
        private int netherScanY;
        private int currentX;
        private int currentZ;
        private SafeDestination destination;
        private int candidatesChecked;

        private DestinationSearchTask(ServerLevel level, CityRegion region, String cityId, SafeDestination cached) {
            this.level = level;
            this.region = region;
            this.cityId = cityId;
            this.cached = cached;
            stage = cached == null ? SearchStage.SELECT_CANDIDATE : SearchStage.CACHED;
        }

        private void advance(long budgetNanos) {
            long deadline = System.nanoTime() + Math.max(1L, budgetNanos);
            while (!isComplete() && System.nanoTime() < deadline) advanceOne();
        }

        private void advanceOne() {
            switch (stage) {
                case CACHED -> inspectCached();
                case SELECT_CANDIDATE -> selectCandidate();
                case LOCAL -> inspectLocalCandidate();
                case NETHER_SCAN -> advanceNetherScan();
                case COMPLETE -> { }
            }
        }

        private void inspectCached() {
            if (cached == null || !region.containsBlock(cached.blockX(), cached.blockZ())) {
                clearCached();
                return;
            }
            ensureChunkLoaded(cached.blockX(), cached.blockZ());
            if (isSafeStandingPosition(level, cached.blockX(), cached.y(), cached.blockZ())) {
                destination = cached;
                stage = SearchStage.COMPLETE;
            } else clearCached();
        }

        private void clearCached() {
            CityManager.clearCityArrivalPosition(level.getServer(), cityId, level.dimension());
            stage = SearchStage.SELECT_CANDIDATE;
        }

        private void selectCandidate() {
            if (offsetIndex >= SEARCH_OFFSETS.size()) {
                stage = SearchStage.COMPLETE;
                return;
            }
            SearchOffset offset = SEARCH_OFFSETS.get(offsetIndex++);
            currentX = region.centerChunkX() * BLOCKS_PER_CHUNK + offset.dx();
            currentZ = region.centerChunkZ() * BLOCKS_PER_CHUNK + offset.dz();
            if (!region.containsBlock(currentX, currentZ)) return;
            candidatesChecked++;

            if (Level.NETHER.equals(level.dimension())) {
                ensureChunkLoaded(currentX, currentZ);
                netherScanY = level.getMaxY() - 2;
                stage = SearchStage.NETHER_SCAN;
                return;
            }

            int baseHeight = level.getChunkSource().getGenerator().getBaseHeight(currentX, currentZ, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, level, level.getChunkSource().randomState());
            if (baseHeight <= level.getSeaLevel() || baseHeight <= level.getMinY() || baseHeight >= level.getMaxY()) return;
            localCenterX = currentX;
            localCenterZ = currentZ;
            localIndex = 0;
            stage = SearchStage.LOCAL;
        }

        private void inspectLocalCandidate() {
            if (localIndex >= LOCAL_OFFSETS.size()) {
                stage = SearchStage.SELECT_CANDIDATE;
                return;
            }
            SearchOffset offset = LOCAL_OFFSETS.get(localIndex++);
            int x = localCenterX + offset.dx();
            int z = localCenterZ + offset.dz();
            if (!region.containsBlock(x, z)) return;
            ensureChunkLoaded(x, z);
            Integer y = findOverworldSurfaceY(level, x, z);
            if (y != null) {
                destination = new SafeDestination(x, y, z);
                stage = SearchStage.COMPLETE;
            }
        }

        private void advanceNetherScan() {
            int minY = level.getMinY() + 1;
            int checked = 0;
            while (netherScanY >= minY && checked++ < 16) {
                int y = netherScanY--;
                if (isSafeStandingPosition(level, currentX, y, currentZ)) {
                    destination = new SafeDestination(currentX, y, currentZ);
                    stage = SearchStage.COMPLETE;
                    return;
                }
            }
            if (netherScanY < minY) stage = SearchStage.SELECT_CANDIDATE;
        }

        private void ensureChunkLoaded(int blockX, int blockZ) {
            int chunkX = blockX >> 4;
            int chunkZ = blockZ >> 4;
            long key = (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
            if (loadedChunks.add(key)) level.getChunk(chunkX, chunkZ);
        }

        private boolean isComplete() { return stage == SearchStage.COMPLETE; }
        private boolean hasDestination() { return destination != null; }
        private SafeDestination destination() { return destination; }
        private String debugSummary() { return "stage=" + stage + ", candidates=" + candidatesChecked + ", loadedChunks=" + loadedChunks.size(); }
    }

    private static Integer findOverworldSurfaceY(ServerLevel level, int blockX, int blockZ) {
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
        if (isSafeStandingPosition(level, blockX, surfaceY, blockZ)) return surfaceY;
        for (int offset = 1; offset <= 3; offset++) {
            int y = surfaceY - offset;
            if (y <= level.getMinY()) break;
            if (isSafeStandingPosition(level, blockX, y, blockZ)) return y;
        }
        return null;
    }

    private static boolean isSafeStandingPosition(ServerLevel level, int blockX, int y, int blockZ) {
        if (y <= level.getMinY() || y + 1 >= level.getMaxY()) return false;
        BlockPos floorPos = new BlockPos(blockX, y - 1, blockZ);
        BlockPos feetPos = new BlockPos(blockX, y, blockZ);
        BlockPos headPos = new BlockPos(blockX, y + 1, blockZ);
        BlockState floorState = level.getBlockState(floorPos);
        return isSafeFloor(level, floorPos, floorState) && isSafePlayerSpace(level, feetPos) && isSafePlayerSpace(level, headPos);
    }

    private static boolean isSafeFloor(ServerLevel level, BlockPos floorPos, BlockState state) {
        if (!state.getFluidState().isEmpty()) return false;
        if (state.is(Blocks.BEDROCK) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.CACTUS)) return false;
        return state.isFaceSturdy(level, floorPos, Direction.UP);
    }

    private static boolean isSafePlayerSpace(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().isEmpty() || !state.getCollisionShape(level, pos).isEmpty()) return false;
        return !state.is(Blocks.FIRE) && !state.is(Blocks.SOUL_FIRE) && !state.is(Blocks.POWDER_SNOW) && !state.is(Blocks.SWEET_BERRY_BUSH)
                && !state.is(Blocks.WITHER_ROSE) && !state.is(Blocks.NETHER_PORTAL) && !state.is(Blocks.END_PORTAL) && !state.is(Blocks.END_GATEWAY);
    }

    private static List<SearchOffset> createSearchOffsets() {
        List<SearchOffset> result = new ArrayList<>();
        result.add(new SearchOffset(0, 0, 0));
        Set<Long> used = new HashSet<>();
        used.add(0L);
        for (int radius = RADIAL_STEP; radius <= SAFE_SEARCH_RADIUS; radius += RADIAL_STEP) {
            for (int direction = 0; direction < RADIAL_DIRECTION_COUNT; direction++) {
                double angle = 2.0D * Math.PI * direction / RADIAL_DIRECTION_COUNT;
                int dx = (int) Math.round(Math.cos(angle) * radius);
                int dz = (int) Math.round(Math.sin(angle) * radius);
                long key = (((long) dx) << 32) ^ (dz & 0xffffffffL);
                if (used.add(key)) result.add(new SearchOffset(dx, dz, radius * radius));
            }
        }
        return List.copyOf(result);
    }

    private static List<SearchOffset> createLocalOffsets() {
        List<SearchOffset> result = new ArrayList<>();
        for (int dx = -LOCAL_SEARCH_RADIUS; dx <= LOCAL_SEARCH_RADIUS; dx++) {
            for (int dz = -LOCAL_SEARCH_RADIUS; dz <= LOCAL_SEARCH_RADIUS; dz++) result.add(new SearchOffset(dx, dz, dx * dx + dz * dz));
        }
        result.sort(Comparator.comparingInt(SearchOffset::distanceSquared));
        return List.copyOf(result);
    }

    private enum SearchStage { CACHED, SELECT_CANDIDATE, LOCAL, NETHER_SCAN, COMPLETE }
    private record SearchOffset(int dx, int dz, int distanceSquared) { }
    private record SafeDestination(int blockX, int y, int blockZ) { }

    private static final class CastSession {
        private final String cityId;
        private final ResourceKey<Level> dimension;
        private final Vec3 startPosition;
        private final long startedAtNanos;
        private final long completesAtNanos;
        private final DestinationSearchTask search;
        private final ServerBossEvent bossBar;
        private int ticks;

        private CastSession(String cityId, ResourceKey<Level> dimension, Vec3 startPosition, long startedAtNanos, long completesAtNanos, DestinationSearchTask search, ServerBossEvent bossBar) {
            this.cityId = cityId;
            this.dimension = dimension;
            this.startPosition = startPosition;
            this.startedAtNanos = startedAtNanos;
            this.completesAtNanos = completesAtNanos;
            this.search = search;
            this.bossBar = bossBar;
        }
    }
}
