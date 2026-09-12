package net.njw.aftertheend.city.altar;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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

public final class AltarRitualHandler {
    public static final int MAX_ACTIVATED_ALTARS_PER_CITY = 3;
    private static final int SCAN_INTERVAL_TICKS = 5;
    private static final int RISE_DURATION_TICKS = 80;
    private static final int BEAM_DURATION_TICKS = 100;
    private static final int END_TICK = RISE_DURATION_TICKS + BEAM_DURATION_TICKS;
    private static final int SOUND_INTERVAL_TICKS = 20;
    private static final double EGG_RISE_HEIGHT = 10.0;
    private static final int FLASH_COLOR = 0xE2DEE5;
    private static final int SOCKET_COLOR = 0xC8B7D4;
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
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;

        if (event.getPlacedBlock().is(ModContent.RESONANCE_CRYSTAL.get())) {
            handleResonanceCrystalPlaced(level, event.getPos());
            return;
        }

        Identifier placedBlockId = BuiltInRegistries.BLOCK.getKey(event.getPlacedBlock().getBlock());
        if (!RECORDED_DRAGON_EGG_ID.equals(placedBlockId)) return;
        if (event.getEntity() instanceof ServerPlayer player) handleRecordedDragonEggPlaced(level, event.getPos(), player);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (activeSequence == null) return;
        ServerLevel level = event.getServer().getLevel(Level.OVERWORLD);
        if (level != null) restoreEgg(level, activeSequence);
        activeSequence = null;
    }

    public static boolean isResonanceSocket(ServerLevel level, BlockPos pos) {
        if (!level.dimension().equals(Level.OVERWORLD)) return false;
        return findSocketSite(level.getServer(), pos, false) != null;
    }

    private static void handleResonanceCrystalPlaced(ServerLevel level, BlockPos pos) {
        AltarSite site = findSocketSite(level.getServer(), pos, true);
        if (site == null) return;

        BlockState placedState = level.getBlockState(pos);
        boolean canActivate = canActivateAltar(level.getServer(), site.cityId());
        if (!canActivate) {
            if (placedState.getValue(ResonanceCrystalBlock.CALMED)) {
                level.setBlock(pos, placedState.setValue(ResonanceCrystalBlock.CALMED, false), 3);
            }
            return;
        }

        if (!placedState.getValue(ResonanceCrystalBlock.CALMED)) {
            level.setBlock(pos, placedState.setValue(ResonanceCrystalBlock.CALMED, true), 3);
        }

        int occupied = 0;
        for (BlockPos socket : site.geometry().sockets()) {
            if (level.getBlockState(socket).is(ModContent.RESONANCE_CRYSTAL.get())) occupied++;
        }
        float pitch = 1.02F + Math.min(occupied, 4) * 0.04F;
        level.playSound(null, pos, SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.BLOCKS, 1.15F, pitch);
        level.sendParticles(new DustParticleOptions(SOCKET_COLOR, 0.95F), pos.getX() + 0.5, pos.getY() + 0.6,
                pos.getZ() + 0.5, 14, 0.3, 0.4, 0.3, 0.025);
    }

    private static void handleRecordedDragonEggPlaced(ServerLevel level, BlockPos pos, ServerPlayer player) {
        AltarSite site = findCenterSite(level.getServer(), pos, true);
        if (site == null || !hasCrystalPattern(level, site.geometry())) return;
        if (canActivateAltar(level.getServer(), site.cityId())) return;

        BlockState eggState = level.getBlockState(pos);
        eggState.attack(level, pos, player);
        player.sendOverlayMessage(Component.translatable("message.njw_after_the_end.altar.cannot_activate"));
    }

    private static boolean canActivateAltar(MinecraftServer server, UUID cityId) {
        int activated = AltarManager.getActivatedCount(server, cityId);
        if (activated >= MAX_ACTIVATED_ALTARS_PER_CITY) return false;
        return activated > 0 || CityManager.getAccessibleCities(server).size() < CityManager.getMaxCityCount(server);
    }

    private static void tryStartRitual(MinecraftServer server, ServerLevel level) {
        for (City city : CityManager.getAccessibleCities(server)) {
            int activatedCount = AltarManager.getActivatedCount(server, city.id());
            if (activatedCount >= MAX_ACTIVATED_ALTARS_PER_CITY) continue;
            if (!canActivateAltar(server, city.id())) continue;

            UUID targetCityId = null;
            if (activatedCount == 0) {
                City targetCity = CityManager.getNextLockedCity(server);
                if (targetCity == null) continue;
                targetCityId = targetCity.id();
            }

            for (AltarPlacement placement : AltarManager.getPlacements(server, city.id())) {
                if (placement.activated()) continue;
                RitualGeometry geometry = geometry(placement);
                if (!hasRitualPattern(level, geometry)) continue;

                BlockState eggState = level.getBlockState(geometry.center());
                ItemStack eggStack = new ItemStack(eggState.getBlock());
                if (eggStack.isEmpty()) continue;
                if (!level.removeBlock(geometry.center(), false)) continue;

                Display.ItemDisplay floatingEgg = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, level);
                var slot = floatingEgg.getSlot(0);
                if (slot == null || !slot.set(eggStack.copy())) {
                    level.setBlock(geometry.center(), eggState, 3);
                    continue;
                }
                floatingEgg.setPos(geometry.center().getX() + 0.5, geometry.center().getY() + 0.5, geometry.center().getZ() + 0.5);
                level.addFreshEntity(floatingEgg);

                activeSequence = new RitualSequence(
                        city.id(), targetCityId, placement.blockX(), placement.y(), placement.blockZ(), placement.large(),
                        geometry, level.getGameTime(), eggState, eggStack, floatingEgg
                );
                level.playSound(null, geometry.center(), SoundEvents.END_PORTAL_FRAME_FILL, SoundSource.BLOCKS, 1.0F, 0.65F);
                level.sendParticles(ParticleTypes.PORTAL, geometry.center().getX() + 0.5, geometry.center().getY() + 0.8,
                        geometry.center().getZ() + 0.5, 48, 2.0, 1.0, 2.0, 0.04);
                broadcastEffect(level, activeSequence, false);
                AfterTheEnd.LOGGER.info("Started Altar activation ritual: city={}, targetCity={}, altar=({}, {}, {})",
                        city.id(), targetCityId, placement.blockX(), placement.y(), placement.blockZ());
                return;
            }
        }
    }

    private static void tickSequence(MinecraftServer server, ServerLevel level, RitualSequence sequence) {
        long elapsed = level.getGameTime() - sequence.startGameTime;
        if (!canActivateAltar(server, sequence.cityId)) {
            cancel(server, level, sequence, "Altar activation is no longer allowed");
            return;
        }
        if (!isPatternLoaded(level, sequence.geometry) || !hasCrystalPattern(level, sequence.geometry)) {
            cancel(server, level, sequence, "ritual crystals changed before activation");
            return;
        }
        if (sequence.floatingEgg.isRemoved()) {
            cancel(server, level, sequence, "floating Dragon Egg disappeared before activation");
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
            cancel(server, level, sequence, "ritual crystals are incomplete");
            return;
        }

        int activatedCount = AltarManager.getActivatedCount(server, sequence.cityId);
        if (activatedCount >= MAX_ACTIVATED_ALTARS_PER_CITY) {
            cancel(server, level, sequence, "city already has the maximum number of activated Altars");
            return;
        }
        if (!canActivateAltar(server, sequence.cityId)) {
            cancel(server, level, sequence, "Altar activation is no longer allowed");
            return;
        }

        boolean firstActivation = activatedCount == 0;
        boolean unlocksCity = sequence.targetCityId != null;
        if (firstActivation != unlocksCity) {
            cancel(server, level, sequence, "Altar activation order changed");
            return;
        }
        if (unlocksCity) {
            City nextLocked = CityManager.getNextLockedCity(server);
            if (nextLocked == null || !nextLocked.id().equals(sequence.targetCityId)) {
                cancel(server, level, sequence, "next locked city changed");
                return;
            }
        }

        if (!AltarManager.setActivated(server, sequence.cityId, sequence.originX, sequence.originY, sequence.originZ, true)) {
            cancel(server, level, sequence, "Altar placement no longer exists");
            return;
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

        if (unlocksCity) {
            try {
                CityLifecycleService.unlockCity(server, sequence.targetCityId);
            } catch (RuntimeException exception) {
                AltarManager.setActivated(server, sequence.cityId, sequence.originX, sequence.originY, sequence.originZ, false);
                eggDrop.discard();
                if (level.getBlockState(sequence.geometry.center()).isAir()) {
                    level.setBlock(sequence.geometry.center(), sequence.eggState, 3);
                }
                restoreCrystals(level, sequence.geometry, canActivateAltar(server, sequence.cityId));
                AfterTheEnd.LOGGER.error("Altar ritual failed while unlocking city {}", sequence.targetCityId, exception);
                activeSequence = null;
                return;
            }
            AfterTheEnd.LOGGER.info("Activated first Altar and unlocked city {} from city {}.",
                    sequence.targetCityId, sequence.cityId);
        } else {
            CitySyncService.syncToAll(server);
            AfterTheEnd.LOGGER.info("Activated additional Altar in city {}.", sequence.cityId);
        }

        activeSequence = null;
    }

    private static void cancel(MinecraftServer server, ServerLevel level, RitualSequence sequence, String reason) {
        restoreEgg(level, sequence);
        updateCrystalCalmState(level, sequence.geometry, canActivateAltar(server, sequence.cityId));
        broadcastEffect(level, sequence, true);
        AfterTheEnd.LOGGER.info("Cancelled Altar activation ritual at {}: {}", sequence.geometry.center(), reason);
        activeSequence = null;
    }

    private static void restoreEgg(ServerLevel level, RitualSequence sequence) {
        Vec3 dropPosition = sequence.floatingEgg.position();
        sequence.floatingEgg.discard();
        if (level.getBlockState(sequence.geometry.center()).isAir()) {
            level.setBlock(sequence.geometry.center(), sequence.eggState, 3);
            return;
        }
        level.addFreshEntity(new ItemEntity(level, dropPosition.x, dropPosition.y, dropPosition.z, sequence.eggStack.copy()));
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
            if (!state.is(ModContent.RESONANCE_CRYSTAL.get())) continue;
            if (state.getValue(ResonanceCrystalBlock.CALMED) != calmed) {
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
        for (City city : CityManager.getAccessibleCities(server)) {
            for (AltarPlacement placement : AltarManager.getPlacements(server, city.id())) {
                if (inactiveOnly && placement.activated()) continue;
                RitualGeometry geometry = geometry(placement);
                for (BlockPos socket : geometry.sockets()) {
                    if (socket.equals(pos)) return new AltarSite(city.id(), placement, geometry);
                }
            }
        }
        return null;
    }

    private static AltarSite findCenterSite(MinecraftServer server, BlockPos pos, boolean inactiveOnly) {
        for (City city : CityManager.getAccessibleCities(server)) {
            for (AltarPlacement placement : AltarManager.getPlacements(server, city.id())) {
                if (inactiveOnly && placement.activated()) continue;
                RitualGeometry geometry = geometry(placement);
                if (geometry.center().equals(pos)) return new AltarSite(city.id(), placement, geometry);
            }
        }
        return null;
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
        return RECORDED_DRAGON_EGG_ID.equals(centerBlockId) && hasCrystalPattern(level, geometry);
    }

    private static boolean hasCrystalPattern(ServerLevel level, RitualGeometry geometry) {
        for (BlockPos socket : geometry.sockets()) {
            if (!level.getBlockState(socket).is(ModContent.RESONANCE_CRYSTAL.get())) return false;
        }
        return true;
    }

    private record AltarSite(UUID cityId, AltarPlacement placement, RitualGeometry geometry) { }
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
        private final BlockState eggState;
        private final ItemStack eggStack;
        private final Display.ItemDisplay floatingEgg;

        private RitualSequence(UUID cityId, UUID targetCityId, int originX, int originY, int originZ, boolean large,
                               RitualGeometry geometry, long startGameTime, BlockState eggState, ItemStack eggStack,
                               Display.ItemDisplay floatingEgg) {
            this.cityId = cityId;
            this.targetCityId = targetCityId;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.large = large;
            this.geometry = geometry;
            this.startGameTime = startGameTime;
            this.eggState = eggState;
            this.eggStack = eggStack;
            this.floatingEgg = floatingEgg;
        }
    }
}
