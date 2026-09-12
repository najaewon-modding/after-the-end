package net.njw.aftertheend.city.altar;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.block.ResonanceCrystalBlock;
import net.njw.aftertheend.city.City;
import net.njw.aftertheend.city.CityLifecycleService;
import net.njw.aftertheend.city.CityManager;
import net.njw.aftertheend.network.AltarActivationPayload;
import net.njw.aftertheend.network.CitySyncService;
import net.njw.aftertheend.registry.ModContent;
import net.njw.justdragoneggs.block.RecordedDragonEggBlock;
import net.njw.justdragoneggs.block.entity.RecordedDragonEggBlockEntity;

public final class AltarRitualHandler {
    public static final int MAX_ACTIVATED_ALTARS_PER_CITY = 3;
    private static final int RISE_DURATION_TICKS = 80;
    private static final int BEAM_DURATION_TICKS = 100;
    private static final int END_TICK = RISE_DURATION_TICKS + BEAM_DURATION_TICKS;
    private static final int SOUND_INTERVAL_TICKS = 20;
    private static final double EGG_RISE_HEIGHT = 10.0;
    private static final int FLASH_COLOR = 0xE2DEE5;
    private static final int SOCKET_COLOR = 0xC8B7D4;
    private static final Map<AltarKey, RitualSequence> ACTIVE_SEQUENCES = new LinkedHashMap<>();
    private static final Set<AltarKey> PENDING_RITUALS = new LinkedHashSet<>();

    private AltarRitualHandler() { }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (ACTIVE_SEQUENCES.isEmpty()) return;
        MinecraftServer server = event.getServer();
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null) return;
        for (RitualSequence sequence : List.copyOf(ACTIVE_SEQUENCES.values())) {
            if (ACTIVE_SEQUENCES.get(sequence.key) == sequence) tickSequence(server, level, sequence);
        }
    }

    @SubscribeEvent
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.dimension().equals(Level.OVERWORLD)) return;
        ServerPlayer player = event.getEntity() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        handlePlacedBlock(level, event.getPos(), player);
    }

    public static void handlePlacedBlock(ServerLevel level, BlockPos pos, ServerPlayer player) {
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        BlockState state = level.getBlockState(pos);
        if (state.is(ModContent.RESONANCE_CRYSTAL.get())) {
            handleResonanceCrystalPlaced(level, pos, player);
        } else if (state.getBlock() instanceof RecordedDragonEggBlock) {
            handleRecordedDragonEggPlaced(level, pos, player);
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null) return;
        for (City city : CityManager.getAccessibleCities(server)) {
            for (AltarPlacement placement : AltarManager.getPlacements(server, city.id())) {
                if (placement.activated()) continue;
                AltarSite site = new AltarSite(city.id(), placement, geometry(placement), key(city.id(), placement));
                boolean canPrepare = canPrepareAltar(server, city.id(), site.key());
                updateCrystalCalmState(level, site.geometry(), canPrepare);
                if (canPrepare && hasRitualPattern(level, site.geometry())) tryStartRitual(server, level, site);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ServerLevel level = event.getServer().getLevel(Level.OVERWORLD);
        if (level != null) {
            for (RitualSequence sequence : List.copyOf(ACTIVE_SEQUENCES.values())) restoreEgg(level, sequence);
        }
        ACTIVE_SEQUENCES.clear();
        PENDING_RITUALS.clear();
    }

    public static boolean isResonanceSocket(ServerLevel level, BlockPos pos) {
        return level.dimension().equals(Level.OVERWORLD) && findSocketSite(level.getServer(), pos, false) != null;
    }

    public static void retryPending(MinecraftServer server) {
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level != null) tryStartPending(server, level);
    }

    private static void handleResonanceCrystalPlaced(ServerLevel level, BlockPos pos, ServerPlayer player) {
        MinecraftServer server = level.getServer();
        AltarSite site = findSocketSite(server, pos, true);
        if (site == null) return;

        BlockState placedState = level.getBlockState(pos);
        boolean canPrepare = canPrepareAltar(server, site.cityId(), site.key());
        if (placedState.getValue(ResonanceCrystalBlock.CALMED) != canPrepare) {
            level.setBlock(pos, placedState.setValue(ResonanceCrystalBlock.CALMED, canPrepare), 3);
        }

        if (!canPrepare) {
            PENDING_RITUALS.remove(site.key());
            if (player != null && hasRitualPattern(level, site.geometry())) rejectRecordedEgg(level, site, player);
            return;
        }

        int occupied = 0;
        for (BlockPos socket : site.geometry().sockets()) {
            if (level.getBlockState(socket).is(ModContent.RESONANCE_CRYSTAL.get())) occupied++;
        }
        float pitch = 1.02F + Math.min(occupied, 4) * 0.04F;
        level.playSound(null, pos, SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.BLOCKS, 1.15F, pitch);
        level.sendParticles(new DustParticleOptions(SOCKET_COLOR, 0.95F), pos.getX() + 0.5, pos.getY() + 0.6,
                pos.getZ() + 0.5, 14, 0.3, 0.4, 0.3, 0.025);
        if (hasRitualPattern(level, site.geometry())) tryStartRitual(server, level, site);
    }

    private static void handleRecordedDragonEggPlaced(ServerLevel level, BlockPos pos, ServerPlayer player) {
        MinecraftServer server = level.getServer();
        AltarSite site = findCenterSite(server, pos, true);
        if (site == null || !hasCrystalPattern(level, site.geometry())) return;
        if (!canPrepareAltar(server, site.cityId(), site.key())) {
            PENDING_RITUALS.remove(site.key());
            if (player != null) rejectRecordedEgg(level, site, player);
            return;
        }
        tryStartRitual(server, level, site);
    }

    private static void rejectRecordedEgg(ServerLevel level, AltarSite site, ServerPlayer player) {
        BlockState eggState = level.getBlockState(site.geometry().center());
        if (!(eggState.getBlock() instanceof RecordedDragonEggBlock)) return;
        eggState.attack(level, site.geometry().center(), player);
        player.sendOverlayMessage(Component.translatable("message.njw_after_the_end.altar.cannot_activate"));
    }

    static boolean canActivate(int activatedCount, int unlockedCityCount, int maxCityCount) {
        return AltarActivationPolicy.canActivate(
                activatedCount, MAX_ACTIVATED_ALTARS_PER_CITY, unlockedCityCount, maxCityCount
        );
    }

    static boolean unlocksCity(int activatedCount, int unlockedCityCount, int maxCityCount) {
        return AltarActivationPolicy.unlocksCity(
                activatedCount, MAX_ACTIVATED_ALTARS_PER_CITY, unlockedCityCount, maxCityCount
        );
    }

    private static boolean canActivateAltar(MinecraftServer server, UUID cityId) {
        return canActivate(
                AltarManager.getActivatedCount(server, cityId),
                CityManager.getAccessibleCities(server).size(),
                CityManager.getMaxCityCount(server)
        );
    }

    private static boolean canPrepareAltar(MinecraftServer server, UUID cityId, AltarKey key) {
        int activated = AltarManager.getActivatedCount(server, cityId);
        int inFlight = activeSequenceCount(cityId, key);
        if (activated + inFlight >= MAX_ACTIVATED_ALTARS_PER_CITY) return false;
        return activated > 0 || CityManager.getAccessibleCities(server).size() < CityManager.getMaxCityCount(server);
    }

    private static int activeSequenceCount(UUID cityId, AltarKey excludedKey) {
        int count = 0;
        for (RitualSequence sequence : ACTIVE_SEQUENCES.values()) {
            if (sequence.cityId.equals(cityId) && !sequence.key.equals(excludedKey)) count++;
        }
        return count;
    }

    private static void tryStartRitual(MinecraftServer server, ServerLevel level, AltarSite site) {
        AltarKey key = site.key();
        if (ACTIVE_SEQUENCES.containsKey(key) || site.placement().activated() || !hasRitualPattern(level, site.geometry())) {
            PENDING_RITUALS.remove(key);
            return;
        }
        if (!canPrepareAltar(server, site.cityId(), key)) {
            PENDING_RITUALS.remove(key);
            updateCrystalCalmState(level, site.geometry(), false);
            return;
        }

        int activatedCount = AltarManager.getActivatedCount(server, site.cityId());
        if (unlocksCity(activatedCount, CityManager.getAccessibleCities(server).size(), CityManager.getMaxCityCount(server))
                && CityManager.getNextLockedCity(server) == null) {
            PENDING_RITUALS.add(key);
            return;
        }

        RitualGeometry geometry = site.geometry();
        updateCrystalCalmState(level, geometry, true);
        BlockState eggState = level.getBlockState(geometry.center());
        ItemStack eggStack = createRecordedEggStack(level, geometry.center());
        if (eggStack.isEmpty() || !level.removeBlock(geometry.center(), false)) return;

        Display.ItemDisplay floatingEgg = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, level);
        var slot = floatingEgg.getSlot(0);
        if (slot == null || !slot.set(eggStack.copy())) {
            restoreRecordedEggBlock(level, geometry.center(), eggState, eggStack);
            return;
        }
        floatingEgg.setPos(geometry.center().getX() + 0.5, geometry.center().getY() + 0.5, geometry.center().getZ() + 0.5);
        level.addFreshEntity(floatingEgg);

        RitualSequence sequence = new RitualSequence(
                key, site.cityId(), site.placement().large(), geometry,
                level.getGameTime(), eggState, eggStack, floatingEgg
        );
        ACTIVE_SEQUENCES.put(key, sequence);
        PENDING_RITUALS.remove(key);
        level.playSound(null, geometry.center(), SoundEvents.END_PORTAL_FRAME_FILL, SoundSource.BLOCKS, 1.0F, 0.65F);
        level.sendParticles(ParticleTypes.PORTAL, geometry.center().getX() + 0.5, geometry.center().getY() + 0.8,
                geometry.center().getZ() + 0.5, 48, 2.0, 1.0, 2.0, 0.04);
        broadcastEffect(level, sequence, false);
        AfterTheEnd.LOGGER.debug("Started Altar ritual: city={}, altar={}", site.cityId(), key);
    }

    private static void tryStartPending(MinecraftServer server, ServerLevel level) {
        if (PENDING_RITUALS.isEmpty()) return;
        for (AltarKey key : List.copyOf(PENDING_RITUALS)) {
            AltarSite site = findSite(server, key);
            if (site == null || site.placement().activated() || !hasRitualPattern(level, site.geometry())) {
                PENDING_RITUALS.remove(key);
                continue;
            }
            tryStartRitual(server, level, site);
        }
    }

    private static void tickSequence(MinecraftServer server, ServerLevel level, RitualSequence sequence) {
        long elapsed = level.getGameTime() - sequence.startGameTime;
        if (!canActivateAltar(server, sequence.cityId)) {
            cancel(server, level, sequence, "activation no longer allowed");
            return;
        }
        if (!isPatternLoaded(level, sequence.geometry) || !hasCrystalPattern(level, sequence.geometry)) {
            cancel(server, level, sequence, "crystal pattern changed");
            return;
        }
        if (sequence.floatingEgg.isRemoved()) {
            cancel(server, level, sequence, "floating egg disappeared");
            return;
        }

        BlockPos center = sequence.geometry.center();
        if (elapsed < RISE_DURATION_TICKS) {
            double progress = Math.clamp(elapsed / (double) RISE_DURATION_TICKS, 0.0, 1.0);
            double eased = progress * progress * (3.0 - 2.0 * progress);
            sequence.floatingEgg.setPos(center.getX() + 0.5, center.getY() + 0.5 + EGG_RISE_HEIGHT * eased, center.getZ() + 0.5);
            if (elapsed > 0 && elapsed % SOUND_INTERVAL_TICKS == 0) {
                float pitch = 0.78F + 0.18F * (elapsed / (float) RISE_DURATION_TICKS);
                level.playSound(null, center, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 0.8F, pitch);
            }
        } else {
            sequence.floatingEgg.setPos(center.getX() + 0.5, center.getY() + 0.5 + EGG_RISE_HEIGHT, center.getZ() + 0.5);
            if (elapsed == RISE_DURATION_TICKS) {
                level.playSound(null, center, SoundEvents.END_PORTAL_FRAME_FILL, SoundSource.BLOCKS, 1.25F, 1.12F);
                level.sendParticles(ParticleTypes.END_ROD, center.getX() + 0.5, center.getY() + EGG_RISE_HEIGHT + 0.5,
                        center.getZ() + 0.5, 36, 0.6, 0.6, 0.6, 0.03);
            } else if ((elapsed - RISE_DURATION_TICKS) % SOUND_INTERVAL_TICKS == 0) {
                level.playSound(null, center, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 0.72F, 0.74F);
            }
        }

        if (elapsed >= END_TICK) complete(server, level, sequence);
    }

    private static void complete(MinecraftServer server, ServerLevel level, RitualSequence sequence) {
        if (!hasCrystalPattern(level, sequence.geometry)) {
            cancel(server, level, sequence, "crystal pattern incomplete");
            return;
        }
        if (!canActivateAltar(server, sequence.cityId)) {
            cancel(server, level, sequence, "activation no longer allowed");
            return;
        }

        AltarSavedData.ActivationClaim claim = AltarManager.claimActivation(
                server, sequence.cityId, sequence.key.originX(), sequence.key.originY(), sequence.key.originZ(),
                MAX_ACTIVATED_ALTARS_PER_CITY
        );
        if (!claim.claimed()) {
            cancel(server, level, sequence, "Altar activation claim failed");
            return;
        }

        City targetCity = null;
        if (claim.previousActivatedCount() == 0) {
            targetCity = CityManager.getNextLockedCity(server);
            if (targetCity == null) {
                AltarManager.setActivated(server, sequence.cityId, sequence.key.originX(), sequence.key.originY(), sequence.key.originZ(), false);
                PENDING_RITUALS.add(sequence.key);
                cancel(server, level, sequence, "next locked city is not ready");
                return;
            }
        }

        Vec3 eggPosition = sequence.floatingEgg.position();
        sequence.floatingEgg.discard();
        ItemEntity eggDrop = new ItemEntity(level, eggPosition.x, eggPosition.y, eggPosition.z, sequence.eggStack.copy());
        level.addFreshEntity(eggDrop);
        for (BlockPos socket : sequence.geometry.sockets()) level.removeBlock(socket, false);

        level.sendParticles(new DustParticleOptions(FLASH_COLOR, 1.6F), eggPosition.x, eggPosition.y + 0.5, eggPosition.z,
                120, 3.0, 2.0, 3.0, 0.08);
        level.sendParticles(ParticleTypes.END_ROD, eggPosition.x, eggPosition.y + 0.5, eggPosition.z,
                64, 2.5, 1.5, 2.5, 0.07);
        level.playSound(null, sequence.geometry.center(), SoundEvents.END_PORTAL_SPAWN, SoundSource.BLOCKS, 1.9F, 1.0F);
        broadcastEffect(level, sequence, true);

        if (targetCity != null) {
            try {
                CityLifecycleService.unlockCity(server, targetCity.id());
            } catch (RuntimeException exception) {
                AltarManager.setActivated(server, sequence.cityId, sequence.key.originX(), sequence.key.originY(), sequence.key.originZ(), false);
                eggDrop.discard();
                restoreRecordedEggBlock(level, sequence.geometry.center(), sequence.eggState, sequence.eggStack);
                restoreCrystals(level, sequence.geometry, canActivateAltar(server, sequence.cityId));
                finishSequence(server, level, sequence);
                AfterTheEnd.LOGGER.error("Altar ritual failed while unlocking city {}", targetCity.id(), exception);
                return;
            }
        } else {
            CitySyncService.syncToAll(server);
        }
        finishSequence(server, level, sequence);
    }

    private static void cancel(MinecraftServer server, ServerLevel level, RitualSequence sequence, String reason) {
        restoreEgg(level, sequence);
        updateCrystalCalmState(level, sequence.geometry, canPrepareAltar(server, sequence.cityId, sequence.key));
        broadcastEffect(level, sequence, true);
        AfterTheEnd.LOGGER.debug("Cancelled Altar ritual at {}: {}", sequence.geometry.center(), reason);
        finishSequence(server, level, sequence);
    }

    private static void finishSequence(MinecraftServer server, ServerLevel level, RitualSequence sequence) {
        ACTIVE_SEQUENCES.remove(sequence.key, sequence);
        tryStartPending(server, level);
    }

    private static ItemStack createRecordedEggStack(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof RecordedDragonEggBlock)) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(net.njw.justdragoneggs.registry.ModContent.RECORDED_DRAGON_EGG_ITEM.get());
        if (level.getBlockEntity(pos) instanceof RecordedDragonEggBlockEntity egg && egg.record() != null) {
            stack.set(net.njw.justdragoneggs.registry.ModContent.BATTLE_RECORD.get(), egg.record());
        }
        return stack;
    }

    private static void restoreEgg(ServerLevel level, RitualSequence sequence) {
        Vec3 dropPosition = sequence.floatingEgg.position();
        sequence.floatingEgg.discard();
        if (level.getBlockState(sequence.geometry.center()).isAir()) {
            restoreRecordedEggBlock(level, sequence.geometry.center(), sequence.eggState, sequence.eggStack);
            return;
        }
        level.addFreshEntity(new ItemEntity(level, dropPosition.x, dropPosition.y, dropPosition.z, sequence.eggStack.copy()));
    }

    private static void restoreRecordedEggBlock(ServerLevel level, BlockPos pos, BlockState state, ItemStack stack) {
        if (!level.getBlockState(pos).isAir()) return;
        level.setBlock(pos, state, 3);
        var record = stack.get(net.njw.justdragoneggs.registry.ModContent.BATTLE_RECORD.get());
        if (record != null && level.getBlockEntity(pos) instanceof RecordedDragonEggBlockEntity egg) egg.setRecord(record);
    }

    private static void restoreCrystals(ServerLevel level, RitualGeometry geometry, boolean calmed) {
        for (BlockPos socket : geometry.sockets()) {
            level.setBlock(socket, ModContent.RESONANCE_CRYSTAL.get().defaultBlockState()
                    .setValue(ResonanceCrystalBlock.CALMED, calmed), 3);
        }
    }

    private static void updateCrystalCalmState(ServerLevel level, RitualGeometry geometry, boolean calmed) {
        for (BlockPos socket : geometry.sockets()) {
            BlockState state = level.getBlockState(socket);
            if (state.is(ModContent.RESONANCE_CRYSTAL.get()) && state.getValue(ResonanceCrystalBlock.CALMED) != calmed) {
                level.setBlock(socket, state.setValue(ResonanceCrystalBlock.CALMED, calmed), 3);
            }
        }
    }

    private static void broadcastEffect(ServerLevel level, RitualSequence sequence, boolean cancelled) {
        PacketDistributor.sendToAllPlayers(new AltarActivationPayload(
                level.dimension().identifier(), sequence.geometry.center(), sequence.large, sequence.startGameTime, cancelled
        ));
    }

    private static AltarSite findSocketSite(MinecraftServer server, BlockPos pos, boolean inactiveOnly) {
        City city = CityManager.findAccessibleCityContaining(server, Level.OVERWORLD, pos.getX(), pos.getZ());
        if (city == null) return null;
        for (AltarPlacement placement : AltarManager.getPlacements(server, city.id())) {
            if (inactiveOnly && placement.activated()) continue;
            RitualGeometry geometry = geometry(placement);
            for (BlockPos socket : geometry.sockets()) {
                if (socket.equals(pos)) return new AltarSite(city.id(), placement, geometry, key(city.id(), placement));
            }
        }
        return null;
    }

    private static AltarSite findCenterSite(MinecraftServer server, BlockPos pos, boolean inactiveOnly) {
        City city = CityManager.findAccessibleCityContaining(server, Level.OVERWORLD, pos.getX(), pos.getZ());
        if (city == null) return null;
        for (AltarPlacement placement : AltarManager.getPlacements(server, city.id())) {
            if (inactiveOnly && placement.activated()) continue;
            RitualGeometry geometry = geometry(placement);
            if (geometry.center().equals(pos)) return new AltarSite(city.id(), placement, geometry, key(city.id(), placement));
        }
        return null;
    }

    private static AltarSite findSite(MinecraftServer server, AltarKey key) {
        if (!CityManager.isCityAccessible(server, key.cityId())) return null;
        for (AltarPlacement placement : AltarManager.getPlacements(server, key.cityId())) {
            if (placement.blockX() == key.originX() && placement.y() == key.originY() && placement.blockZ() == key.originZ()) {
                return new AltarSite(key.cityId(), placement, geometry(placement), key);
            }
        }
        return null;
    }

    private static AltarKey key(UUID cityId, AltarPlacement placement) {
        return new AltarKey(cityId, placement.blockX(), placement.y(), placement.blockZ());
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
        return isPatternLoaded(level, geometry)
                && level.getBlockState(geometry.center()).getBlock() instanceof RecordedDragonEggBlock
                && hasCrystalPattern(level, geometry);
    }

    private static boolean hasCrystalPattern(ServerLevel level, RitualGeometry geometry) {
        for (BlockPos socket : geometry.sockets()) {
            if (!level.getBlockState(socket).is(ModContent.RESONANCE_CRYSTAL.get())) return false;
        }
        return true;
    }

    private record AltarKey(UUID cityId, int originX, int originY, int originZ) { }
    private record AltarSite(UUID cityId, AltarPlacement placement, RitualGeometry geometry, AltarKey key) { }
    private record RitualGeometry(BlockPos center, BlockPos[] sockets) { }

    private static final class RitualSequence {
        private final AltarKey key;
        private final UUID cityId;
        private final boolean large;
        private final RitualGeometry geometry;
        private final long startGameTime;
        private final BlockState eggState;
        private final ItemStack eggStack;
        private final Display.ItemDisplay floatingEgg;

        private RitualSequence(AltarKey key, UUID cityId, boolean large, RitualGeometry geometry,
                               long startGameTime, BlockState eggState, ItemStack eggStack, Display.ItemDisplay floatingEgg) {
            this.key = key;
            this.cityId = cityId;
            this.large = large;
            this.geometry = geometry;
            this.startGameTime = startGameTime;
            this.eggState = eggState;
            this.eggStack = eggStack;
            this.floatingEgg = floatingEgg;
        }
    }
}
