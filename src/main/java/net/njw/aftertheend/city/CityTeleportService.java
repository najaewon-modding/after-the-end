package net.njw.aftertheend.city;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
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
import net.njw.aftertheend.city.altar.AltarManager;
import net.njw.aftertheend.city.altar.AltarPlacement;
import net.njw.aftertheend.city.altar.AltarTravelAccess;
import net.njw.aftertheend.network.CityArrivalAltarRequestPayload;
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
    private static final long CAST_DURATION_NANOS = 8_000_000_000L;
    private static final long ARRIVAL_CAST_DURATION_NANOS = 3_000_000_000L;
    private static final long COOLDOWN_DURATION_NANOS = 3_000_000_000L;
    private static final long GLOBAL_SEARCH_BUDGET_NANOS_PER_TICK = 5_000_000L;
    private static final double MOVEMENT_CANCEL_DISTANCE_SQUARED = 0.01D;
    private static final int BLOCKS_PER_CHUNK = 16;
    private static final int SAFE_SEARCH_RADIUS = 512;
    private static final int RADIAL_STEP = 32;
    private static final int RADIAL_DIRECTION_COUNT = 16;
    private static final int LOCAL_SEARCH_RADIUS = 2;
    private static final int ALTAR_SEARCH_MARGIN = 3;
    private static final int ALTAR_CENTER_STANDING_Y_OFFSET = 3;
    private static final int MONSTER_CHECK_INTERVAL_TICKS = 5;
    private static final double MONSTER_HORIZONTAL_RANGE = 8.0D;
    private static final double MONSTER_VERTICAL_RANGE = 5.0D;
    private static final int CITY_MOVE_EFFECT_INTERVAL_TICKS = 4;
    private static final int CITY_MOVE_OUTER_RING_POINTS = 18;
    private static final int CITY_MOVE_INNER_RING_POINTS = 10;
    private static final int CITY_MOVE_SPIRAL_POINTS = 5;
    private static final DustParticleOptions CITY_MOVE_OUTER_PARTICLE = new DustParticleOptions(0x38BDF8, 0.95F);
    private static final DustParticleOptions CITY_MOVE_INNER_PARTICLE = new DustParticleOptions(0x9BE7FF, 0.72F);
    private static final DustParticleOptions CITY_MOVE_SPIRAL_PARTICLE = new DustParticleOptions(0x67D8FF, 0.65F);
    private static final List<SearchOffset> SEARCH_OFFSETS = createSearchOffsets();
    private static final List<SearchOffset> LOCAL_OFFSETS = createLocalOffsets();
    private static final List<SearchOffset> SMALL_ALTAR_OFFSETS = createAltarSearchOffsets(false);
    private static final List<SearchOffset> LARGE_ALTAR_OFFSETS = createAltarSearchOffsets(true);
    private static final Map<UUID, CastSession> ACTIVE_CASTS = new HashMap<>();
    private static final Map<UUID, ArrivalCastSession> ACTIVE_ARRIVAL_CASTS = new HashMap<>();
    private static final Map<UUID, Long> COOLDOWN_DEADLINES = new HashMap<>();

    private CityTeleportService() { }

    public static void handleRequest(CityTeleportRequestPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) startCast(player, payload.cityId());
    }

    public static void handleArrivalAltarRequest(CityArrivalAltarRequestPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) startArrivalAltarCast(player);
    }

    private static void startCast(ServerPlayer player, UUID cityId) {
        UUID playerId = player.getUUID();
        if (ACTIVE_CASTS.containsKey(playerId) || ACTIVE_ARRIVAL_CASTS.containsKey(playerId)) {
            player.sendOverlayMessage(Component.translatable("message.njw_after_the_end.city_move.already_casting"));
            return;
        }

        ServerLevel level = player.level();
        if (!Level.OVERWORLD.equals(level.dimension())) {
            player.sendOverlayMessage(Component.translatable("message.njw_after_the_end.city_move.overworld_only"));
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
        CityRegion region = city.getRegion(Level.OVERWORLD).orElse(null);
        if (region == null) {
            player.sendOverlayMessage(Component.translatable("message.njw_after_the_end.city_move.dimension_unavailable"));
            return;
        }
        if (hasRestPreventingMonsterNearby(player)) {
            player.sendOverlayMessage(Component.translatable("message.njw_after_the_end.city_move.monsters_nearby"));
            return;
        }

        AltarPlacement arrivalAltar = resolveArrivalAltar(server, city);
        DestinationSearchTask search = new DestinationSearchTask(level, region, arrivalAltar);
        ServerBossEvent bossBar = createBossBar(
                level, player,
                Component.translatable("message.njw_after_the_end.city_move.boss_bar", Component.literal(city.name()))
        );
        ACTIVE_CASTS.put(playerId, new CastSession(cityId, level.dimension(), player.position(), now,
                now + CAST_DURATION_NANOS, search, bossBar));
        player.sendOverlayMessage(Component.translatable("message.njw_after_the_end.city_move.preparing", Component.literal(city.name())));
    }

    private static void startArrivalAltarCast(ServerPlayer player) {
        UUID playerId = player.getUUID();
        if (ACTIVE_CASTS.containsKey(playerId) || ACTIVE_ARRIVAL_CASTS.containsKey(playerId)) {
            player.sendOverlayMessage(Component.translatable("message.njw_after_the_end.arrival_altar.already_casting"));
            return;
        }
        ServerLevel level = player.level();
        if (!Level.OVERWORLD.equals(level.dimension())) {
            player.sendOverlayMessage(Component.translatable("message.njw_after_the_end.arrival_altar.overworld_only"));
            return;
        }

        MinecraftServer server = level.getServer();
        City city = CityManager.findAccessibleCityContaining(server, Level.OVERWORLD, player.getBlockX(), player.getBlockZ());
        if (city == null) {
            player.sendOverlayMessage(Component.translatable("message.njw_after_the_end.arrival_altar.not_in_city"));
            return;
        }
        AltarPlacement altar = findActivatedAltarContaining(server, city, player);
        if (altar == null) {
            player.sendOverlayMessage(Component.translatable("message.njw_after_the_end.arrival_altar.requires_activated_altar"));
            return;
        }
        if (hasRestPreventingMonsterNearby(player)) {
            player.sendOverlayMessage(Component.translatable("message.njw_after_the_end.arrival_altar.monsters_nearby"));
            return;
        }

        long now = System.nanoTime();
        ServerBossEvent bossBar = createBossBar(
                level, player, Component.translatable("message.njw_after_the_end.arrival_altar.boss_bar")
        );
        ACTIVE_ARRIVAL_CASTS.put(playerId, new ArrivalCastSession(
                city.id(), level.dimension(), altar.blockX(), altar.y(), altar.blockZ(),
                player.position(), now, now + ARRIVAL_CAST_DURATION_NANOS, bossBar
        ));
        player.sendOverlayMessage(Component.translatable("message.njw_after_the_end.arrival_altar.preparing"));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (ACTIVE_CASTS.isEmpty() && ACTIVE_ARRIVAL_CASTS.isEmpty()) return;
        MinecraftServer server = event.getServer();
        tickCityMoveCasts(server);
        tickArrivalAltarCasts(server);
    }

    private static void tickCityMoveCasts(MinecraftServer server) {
        if (ACTIVE_CASTS.isEmpty()) return;
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
            CityMoveRecallEffect.tick(player, session.ticks,
                    progress(System.nanoTime(), session.startedAtNanos, CAST_DURATION_NANOS));

            long remainingBudget = searchDeadline - System.nanoTime();
            if (remainingBudget > 0L && !session.search.isComplete()) session.search.advance(Math.min(fairShare, remainingBudget));

            long now = System.nanoTime();
            session.bossBar.setProgress(progress(now, session.startedAtNanos, CAST_DURATION_NANOS));
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
            CityRegion region = city.getRegion(Level.OVERWORLD).orElse(null);
            if (region == null || !region.containsBlock(destination.blockX(), destination.blockZ())) {
                cancelCast(player, "message.njw_after_the_end.city_move.failed_destination_invalid");
                continue;
            }
            if (!isSafeStandingPosition(player.level(), destination.blockX(), destination.y(), destination.blockZ())) {
                cancelCast(player, "message.njw_after_the_end.city_move.failed_destination_unsafe");
                continue;
            }
            if (hasRestPreventingMonsterNearby(player)) { cancelCast(player, "message.njw_after_the_end.city_move.interrupted_monsters"); continue; }
            completeTeleport(player, city, destination, now);
        }
    }

    private static void tickArrivalAltarCasts(MinecraftServer server) {
        if (ACTIVE_ARRIVAL_CASTS.isEmpty()) return;
        for (UUID playerId : List.copyOf(ACTIVE_ARRIVAL_CASTS.keySet())) {
            ArrivalCastSession session = ACTIVE_ARRIVAL_CASTS.get(playerId);
            if (session == null) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) { removeArrivalCastSession(playerId); continue; }
            if (!player.isAlive()) { cancelArrivalCast(player, "message.njw_after_the_end.arrival_altar.interrupted"); continue; }
            if (!player.level().dimension().equals(session.dimension)) { cancelArrivalCast(player, "message.njw_after_the_end.arrival_altar.interrupted_dimension"); continue; }
            if (player.position().distanceToSqr(session.startPosition) > MOVEMENT_CANCEL_DISTANCE_SQUARED) { cancelArrivalCast(player, "message.njw_after_the_end.arrival_altar.interrupted_movement"); continue; }

            session.ticks++;
            if (session.ticks % MONSTER_CHECK_INTERVAL_TICKS == 0 && hasRestPreventingMonsterNearby(player)) {
                cancelArrivalCast(player, "message.njw_after_the_end.arrival_altar.interrupted_monsters");
                continue;
            }

            long now = System.nanoTime();
            session.bossBar.setProgress(progress(now, session.startedAtNanos, ARRIVAL_CAST_DURATION_NANOS));
            if (now < session.completesAtNanos) continue;

            City city = CityManager.getCity(server, session.cityId);
            if (city == null || !CityManager.isCityAccessible(server, session.cityId)) {
                cancelArrivalCast(player, "message.njw_after_the_end.arrival_altar.failed_city_unavailable");
                continue;
            }
            AltarPlacement altar = findActivatedAltar(server, session.cityId, session.altarBlockX, session.altarY, session.altarBlockZ);
            if (altar == null || !AltarTravelAccess.isInsideInteractionArea(
                    player.getX(), player.getY(), player.getZ(), altar.blockX(), altar.y(), altar.blockZ(), altar.large())) {
                cancelArrivalCast(player, "message.njw_after_the_end.arrival_altar.failed_altar_unavailable");
                continue;
            }
            if (hasRestPreventingMonsterNearby(player)) {
                cancelArrivalCast(player, "message.njw_after_the_end.arrival_altar.interrupted_monsters");
                continue;
            }

            CityManager.setCityArrivalAltar(server, city.id(), Level.OVERWORLD, altar.blockX(), altar.y(), altar.blockZ());
            removeArrivalCastSession(playerId);
            player.sendOverlayMessage(Component.translatable(
                    "message.njw_after_the_end.arrival_altar.saved", Component.literal(city.name())
            ));
        }
    }

    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getInflictedDamage() <= 0.0F) return;
        UUID playerId = player.getUUID();
        if (ACTIVE_CASTS.containsKey(playerId)) cancelCast(player, "message.njw_after_the_end.city_move.interrupted_damage");
        if (ACTIVE_ARRIVAL_CASTS.containsKey(playerId)) cancelArrivalCast(player, "message.njw_after_the_end.arrival_altar.interrupted_damage");
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        removeCastSession(player.getUUID());
        removeArrivalCastSession(player.getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        for (CastSession session : ACTIVE_CASTS.values()) closeBossBar(session.bossBar);
        for (ArrivalCastSession session : ACTIVE_ARRIVAL_CASTS.values()) closeBossBar(session.bossBar);
        ACTIVE_CASTS.clear();
        ACTIVE_ARRIVAL_CASTS.clear();
        COOLDOWN_DEADLINES.clear();
    }

    private static AltarPlacement resolveArrivalAltar(MinecraftServer server, City city) {
        CitySavedData.CityArrivalAltar stored = CityManager.getCityArrivalAltar(server, city.id(), Level.OVERWORLD);
        if (stored != null) {
            AltarPlacement altar = findActivatedAltar(server, city.id(), stored.blockX(), stored.y(), stored.blockZ());
            if (altar != null) return altar;
            CityManager.clearCityArrivalAltar(server, city.id(), Level.OVERWORLD);
        }
        for (AltarPlacement altar : AltarManager.getPlacements(server, city.id())) if (altar.activated()) return altar;
        return null;
    }

    private static AltarPlacement findActivatedAltarContaining(MinecraftServer server, City city, ServerPlayer player) {
        AltarPlacement best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (AltarPlacement altar : AltarManager.getPlacements(server, city.id())) {
            if (!altar.activated() || !AltarTravelAccess.isInsideInteractionArea(
                    player.getX(), player.getY(), player.getZ(), altar.blockX(), altar.y(), altar.blockZ(), altar.large())) continue;
            double centerX = altar.blockX() + AltarTravelAccess.centerOffset(altar.large()) + 0.5D;
            double centerZ = altar.blockZ() + AltarTravelAccess.centerOffset(altar.large()) + 0.5D;
            double dx = player.getX() - centerX;
            double dz = player.getZ() - centerZ;
            double distance = dx * dx + dz * dz;
            if (distance < bestDistance) { bestDistance = distance; best = altar; }
        }
        return best;
    }

    private static AltarPlacement findActivatedAltar(MinecraftServer server, UUID cityId, int blockX, int y, int blockZ) {
        for (AltarPlacement altar : AltarManager.getPlacements(server, cityId)) {
            if (altar.activated() && altar.blockX() == blockX && altar.y() == y && altar.blockZ() == blockZ) return altar;
        }
        return null;
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

    private static void cancelArrivalCast(ServerPlayer player, String translationKey) {
        if (!ACTIVE_ARRIVAL_CASTS.containsKey(player.getUUID())) return;
        removeArrivalCastSession(player.getUUID());
        player.sendOverlayMessage(Component.translatable(translationKey));
    }

    private static void removeCastSession(UUID playerId) {
        CastSession session = ACTIVE_CASTS.remove(playerId);
        if (session != null) closeBossBar(session.bossBar);
    }

    private static void removeArrivalCastSession(UUID playerId) {
        ArrivalCastSession session = ACTIVE_ARRIVAL_CASTS.remove(playerId);
        if (session != null) closeBossBar(session.bossBar);
    }

    private static ServerBossEvent createBossBar(ServerLevel level, ServerPlayer player, Component title) {
        ServerBossEvent bar = new ServerBossEvent(
                Mth.createInsecureUUID(level.getRandom()), title,
                BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS
        );
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

    private static void spawnCityMoveEffect(ServerPlayer player, CastSession session) {
        if (session.ticks % CITY_MOVE_EFFECT_INTERVAL_TICKS != 0) return;
        ServerLevel level = player.level();
        double progress = Math.clamp((System.nanoTime() - session.startedAtNanos) / (double) CAST_DURATION_NANOS, 0.0D, 1.0D);
        double phase = session.ticks * 0.11D;
        double pulse = 1.0D + 0.055D * Math.sin(session.ticks * 0.18D);
        double x = player.getX();
        double y = player.getY() + 0.055D;
        double z = player.getZ();

        spawnCityMoveRing(level, x, y, z, 1.38D * pulse, CITY_MOVE_OUTER_RING_POINTS, phase, CITY_MOVE_OUTER_PARTICLE, session.ticks);
        spawnCityMoveRing(level, x, y + 0.035D, z, 0.78D * pulse, CITY_MOVE_INNER_RING_POINTS, -phase * 1.35D, CITY_MOVE_INNER_PARTICLE, session.ticks + 7);

        double spiralPhase = phase * 2.0D;
        for (int i = 0; i < CITY_MOVE_SPIRAL_POINTS; i++) {
            double angle = spiralPhase + (Math.PI * 2.0D * i / CITY_MOVE_SPIRAL_POINTS);
            double radius = 0.42D + 0.11D * Math.sin(session.ticks * 0.13D + i);
            double height = 0.18D + i * 0.31D + 0.18D * progress;
            level.sendParticles(
                    CITY_MOVE_SPIRAL_PARTICLE,
                    x + Math.cos(angle) * radius, y + height, z + Math.sin(angle) * radius,
                    1, 0.0D, 0.0D, 0.0D, 0.0D
            );
        }
    }

    private static void spawnCityMoveRing(ServerLevel level, double centerX, double y, double centerZ, double radius,
                                          int points, double phase, DustParticleOptions particle, int waveTick) {
        for (int i = 0; i < points; i++) {
            double angle = Math.PI * 2.0D * i / points + phase;
            double wave = 0.035D * Math.sin(angle * 3.0D + waveTick * 0.16D);
            level.sendParticles(
                    particle,
                    centerX + Math.cos(angle) * radius, y + wave, centerZ + Math.sin(angle) * radius,
                    1, 0.0D, 0.0D, 0.0D, 0.0D
            );
        }
    }

    private static boolean hasRestPreventingMonsterNearby(ServerPlayer player) {
        ServerLevel level = player.level();
        Vec3 center = Vec3.atBottomCenterOf(player.blockPosition());
        AABB box = new AABB(
                center.x() - MONSTER_HORIZONTAL_RANGE, center.y() - MONSTER_VERTICAL_RANGE, center.z() - MONSTER_HORIZONTAL_RANGE,
                center.x() + MONSTER_HORIZONTAL_RANGE, center.y() + MONSTER_VERTICAL_RANGE, center.z() + MONSTER_HORIZONTAL_RANGE
        );
        return !level.getEntitiesOfClass(Monster.class, box, monster -> monster.isPreventingPlayerRest(level, player)).isEmpty();
    }

    private static float progress(long now, long startedAt, long duration) {
        return (float) Math.max(0.0D, Math.min(1.0D, (double) (now - startedAt) / duration));
    }

    private static String formatSeconds(long nanos) {
        double seconds = Math.ceil(nanos / 100_000_000.0D) / 10.0D;
        return String.format(Locale.ROOT, "%.1f", seconds);
    }

    private static final class DestinationSearchTask {
        private final ServerLevel level;
        private final CityRegion region;
        private final AltarPlacement altar;
        private final List<SearchOffset> altarOffsets;
        private final Set<Long> loadedChunks = new HashSet<>();
        private SearchStage stage;
        private int altarOffsetIndex;
        private int offsetIndex;
        private int localIndex;
        private int localCenterX;
        private int localCenterZ;
        private SafeDestination destination;
        private int candidatesChecked;

        private DestinationSearchTask(ServerLevel level, CityRegion region, AltarPlacement altar) {
            this.level = level;
            this.region = region;
            this.altar = altar;
            this.altarOffsets = altar == null ? List.of() : (altar.large() ? LARGE_ALTAR_OFFSETS : SMALL_ALTAR_OFFSETS);
            this.stage = altar == null ? SearchStage.SELECT_CANDIDATE : SearchStage.ALTAR_CENTER;
        }

        private void advance(long budgetNanos) {
            long deadline = System.nanoTime() + Math.max(1L, budgetNanos);
            while (!isComplete() && System.nanoTime() < deadline) advanceOne();
        }

        private void advanceOne() {
            switch (stage) {
                case ALTAR_CENTER -> inspectAltarCenter();
                case ALTAR_TOP -> inspectAltarTop();
                case ALTAR_SURROUNDINGS -> inspectAltarSurrounding();
                case SELECT_CANDIDATE -> selectCandidate();
                case LOCAL -> inspectLocalCandidate();
                case COMPLETE -> { }
            }
        }

        private void inspectAltarCenter() {
            int x = altar.blockX() + AltarTravelAccess.centerOffset(altar.large());
            int z = altar.blockZ() + AltarTravelAccess.centerOffset(altar.large());
            int y = altar.y() + ALTAR_CENTER_STANDING_Y_OFFSET;
            candidatesChecked++;
            ensureChunkLoaded(x, z);
            if (region.containsBlock(x, z) && isSafeStandingPosition(level, x, y, z)) {
                destination = new SafeDestination(x, y, z);
                stage = SearchStage.COMPLETE;
                return;
            }
            stage = SearchStage.ALTAR_TOP;
        }

        private void inspectAltarTop() {
            int x = altar.blockX() + AltarTravelAccess.centerOffset(altar.large());
            int z = altar.blockZ() + AltarTravelAccess.centerOffset(altar.large());
            int y = altar.y() + AltarTravelAccess.structureHeight(altar.large());
            candidatesChecked++;
            ensureChunkLoaded(x, z);
            if (region.containsBlock(x, z) && isSafeStandingPosition(level, x, y, z)) {
                destination = new SafeDestination(x, y, z);
                stage = SearchStage.COMPLETE;
                return;
            }
            stage = SearchStage.ALTAR_SURROUNDINGS;
        }

        private void inspectAltarSurrounding() {
            if (altarOffsetIndex >= altarOffsets.size()) {
                stage = SearchStage.SELECT_CANDIDATE;
                return;
            }
            SearchOffset offset = altarOffsets.get(altarOffsetIndex++);
            int centerX = altar.blockX() + AltarTravelAccess.centerOffset(altar.large());
            int centerZ = altar.blockZ() + AltarTravelAccess.centerOffset(altar.large());
            int x = centerX + offset.dx();
            int z = centerZ + offset.dz();
            if (!region.containsBlock(x, z)) return;
            candidatesChecked++;
            ensureChunkLoaded(x, z);
            Integer y = findOverworldSurfaceY(level, x, z);
            if (y != null) {
                destination = new SafeDestination(x, y, z);
                stage = SearchStage.COMPLETE;
            }
        }

        private void selectCandidate() {
            if (offsetIndex >= SEARCH_OFFSETS.size()) {
                stage = SearchStage.COMPLETE;
                return;
            }
            SearchOffset offset = SEARCH_OFFSETS.get(offsetIndex++);
            int x = region.centerChunkX() * BLOCKS_PER_CHUNK + offset.dx();
            int z = region.centerChunkZ() * BLOCKS_PER_CHUNK + offset.dz();
            if (!region.containsBlock(x, z)) return;
            candidatesChecked++;
            int baseHeight = level.getChunkSource().getGenerator().getBaseHeight(
                    x, z, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, level, level.getChunkSource().randomState()
            );
            if (baseHeight <= level.getSeaLevel() || baseHeight <= level.getMinY() || baseHeight >= level.getMaxY()) return;
            localCenterX = x;
            localCenterZ = z;
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

        private void ensureChunkLoaded(int blockX, int blockZ) {
            int chunkX = blockX >> 4;
            int chunkZ = blockZ >> 4;
            long key = (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
            if (loadedChunks.add(key)) level.getChunk(chunkX, chunkZ);
        }

        private boolean isComplete() { return stage == SearchStage.COMPLETE; }
        private boolean hasDestination() { return destination != null; }
        private SafeDestination destination() { return destination; }
        private String debugSummary() {
            return "stage=" + stage + ", altar=" + (altar == null ? "none" : altar.blockX() + "," + altar.y() + "," + altar.blockZ())
                    + ", candidates=" + candidatesChecked + ", loadedChunks=" + loadedChunks.size();
        }
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
        if (state.is(Blocks.BEDROCK) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.CACTUS)) return false;
        return state.isFaceSturdy(level, floorPos, Direction.UP);
    }

    private static boolean isSafePlayerSpace(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().isEmpty() || !state.getCollisionShape(level, pos).isEmpty()) return false;
        return !state.is(Blocks.FIRE) && !state.is(Blocks.SOUL_FIRE) && !state.is(Blocks.POWDER_SNOW)
                && !state.is(Blocks.SWEET_BERRY_BUSH) && !state.is(Blocks.WITHER_ROSE)
                && !state.is(Blocks.NETHER_PORTAL) && !state.is(Blocks.END_PORTAL) && !state.is(Blocks.END_GATEWAY);
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
            for (int dz = -LOCAL_SEARCH_RADIUS; dz <= LOCAL_SEARCH_RADIUS; dz++) {
                result.add(new SearchOffset(dx, dz, dx * dx + dz * dz));
            }
        }
        result.sort(Comparator.comparingInt(SearchOffset::distanceSquared));
        return List.copyOf(result);
    }

    private static List<SearchOffset> createAltarSearchOffsets(boolean large) {
        int radius = AltarTravelAccess.centerOffset(large) + ALTAR_SEARCH_MARGIN;
        List<SearchOffset> result = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) continue;
                result.add(new SearchOffset(dx, dz, dx * dx + dz * dz));
            }
        }
        result.sort(Comparator.comparingInt(SearchOffset::distanceSquared));
        return List.copyOf(result);
    }

    private enum SearchStage { ALTAR_CENTER, ALTAR_TOP, ALTAR_SURROUNDINGS, SELECT_CANDIDATE, LOCAL, COMPLETE }
    private record SearchOffset(int dx, int dz, int distanceSquared) { }
    private record SafeDestination(int blockX, int y, int blockZ) { }

    private static final class CastSession {
        private final UUID cityId;
        private final ResourceKey<Level> dimension;
        private final Vec3 startPosition;
        private final long startedAtNanos;
        private final long completesAtNanos;
        private final DestinationSearchTask search;
        private final ServerBossEvent bossBar;
        private int ticks;

        private CastSession(UUID cityId, ResourceKey<Level> dimension, Vec3 startPosition, long startedAtNanos,
                            long completesAtNanos, DestinationSearchTask search, ServerBossEvent bossBar) {
            this.cityId = cityId;
            this.dimension = dimension;
            this.startPosition = startPosition;
            this.startedAtNanos = startedAtNanos;
            this.completesAtNanos = completesAtNanos;
            this.search = search;
            this.bossBar = bossBar;
        }
    }

    private static final class ArrivalCastSession {
        private final UUID cityId;
        private final ResourceKey<Level> dimension;
        private final int altarBlockX;
        private final int altarY;
        private final int altarBlockZ;
        private final Vec3 startPosition;
        private final long startedAtNanos;
        private final long completesAtNanos;
        private final ServerBossEvent bossBar;
        private int ticks;

        private ArrivalCastSession(UUID cityId, ResourceKey<Level> dimension, int altarBlockX, int altarY, int altarBlockZ,
                                   Vec3 startPosition, long startedAtNanos, long completesAtNanos, ServerBossEvent bossBar) {
            this.cityId = cityId;
            this.dimension = dimension;
            this.altarBlockX = altarBlockX;
            this.altarY = altarY;
            this.altarBlockZ = altarBlockZ;
            this.startPosition = startPosition;
            this.startedAtNanos = startedAtNanos;
            this.completesAtNanos = completesAtNanos;
            this.bossBar = bossBar;
        }
    }
}
