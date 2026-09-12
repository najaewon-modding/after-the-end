package net.njw.aftertheend.city.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.city.City;
import net.njw.aftertheend.city.CityLifecycleService;
import net.njw.aftertheend.city.CityManager;
import net.njw.aftertheend.city.CityRegion;
import net.njw.aftertheend.city.generation.CityPregenerationHandler;

public final class CityAdminCommand {
    private static final int BLOCKS_PER_CHUNK = 16;

    private CityAdminCommand() { }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("city")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("create")
                                .executes(context -> createCity(context.getSource(), true))
                                .then(Commands.literal("locked").executes(context -> createCity(context.getSource(), false))))
                        .then(Commands.literal("unlock")
                                .then(Commands.argument("cityId", StringArgumentType.word())
                                        .executes(context -> unlockCity(context.getSource(), StringArgumentType.getString(context, "cityId")))))
                        .then(Commands.literal("delete")
                                .then(Commands.argument("cityId", StringArgumentType.word())
                                        .executes(context -> deleteCity(context.getSource(), StringArgumentType.getString(context, "cityId")))))
                        .then(Commands.literal("load")
                                .then(Commands.argument("cityId", StringArgumentType.word())
                                        .executes(context -> loadCity(context.getSource(), StringArgumentType.getString(context, "cityId")))))
                        .then(Commands.literal("list").executes(context -> listCities(context.getSource())))
                        .then(Commands.literal("info")
                                .then(Commands.argument("cityId", StringArgumentType.word())
                                        .executes(context -> showCityInfo(context.getSource(), StringArgumentType.getString(context, "cityId")))))
                        .then(Commands.literal("max")
                                .executes(context -> showMaxCityCount(context.getSource()))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                        .executes(context -> setMaxCityCount(context.getSource(), IntegerArgumentType.getInteger(context, "count")))))
        );
    }

    private static int createCity(CommandSourceStack source, boolean accessible) {
        try {
            City city = accessible
                    ? CityLifecycleService.createAccessibleCity(source.getServer())
                    : CityLifecycleService.createLockedCity(source.getServer());
            String key = accessible
                    ? "command.njw_after_the_end.city.created_accessible"
                    : "command.njw_after_the_end.city.created_locked";
            source.sendSuccess(() -> Component.translatable(key, city.id().toString()), false);
            sendCityCoordinates(source, city);
            return 1;
        } catch (RuntimeException exception) {
            AfterTheEnd.LOGGER.warn("Failed to create city from admin command.", exception);
            source.sendFailure(Component.translatable("command.njw_after_the_end.city.failed_create"));
            return 0;
        }
    }

    private static int unlockCity(CommandSourceStack source, String cityId) {
        UUID id = parseCityId(source, cityId);
        if (id == null) return 0;
        try {
            CityLifecycleService.unlockCity(source.getServer(), id);
            source.sendSuccess(() -> Component.translatable("command.njw_after_the_end.city.unlocked", cityId), false);
            return 1;
        } catch (RuntimeException exception) {
            AfterTheEnd.LOGGER.warn("Failed to unlock city {} from admin command.", cityId, exception);
            source.sendFailure(Component.translatable("command.njw_after_the_end.city.failed_unlock", cityId));
            return 0;
        }
    }

    private static int deleteCity(CommandSourceStack source, String cityId) {
        UUID id = parseCityId(source, cityId);
        if (id == null) return 0;
        try {
            CityLifecycleService.deleteCity(source.getServer(), id);
            source.sendSuccess(() -> Component.translatable("command.njw_after_the_end.city.deleted", cityId), false);
            return 1;
        } catch (RuntimeException exception) {
            AfterTheEnd.LOGGER.warn("Failed to delete city {} from admin command.", cityId, exception);
            source.sendFailure(Component.translatable("command.njw_after_the_end.city.failed_delete", cityId));
            return 0;
        }
    }

    private static int loadCity(CommandSourceStack source, String cityId) {
        UUID id = parseCityId(source, cityId);
        if (id == null) return 0;
        City city = CityManager.getCity(source.getServer(), id);
        if (city == null) {
            source.sendFailure(Component.translatable("command.njw_after_the_end.city.unknown", cityId));
            return 0;
        }
        try {
            int taskCount = CityPregenerationHandler.startCityLoad(source.getServer(), city);
            if (taskCount == 0) {
                source.sendSuccess(() -> Component.translatable("command.njw_after_the_end.city.chunks_already_loaded", cityId), false);
                return 1;
            }
            source.sendSuccess(() -> Component.translatable("command.njw_after_the_end.city.chunk_loading_started", cityId), true);
            return 1;
        } catch (RuntimeException exception) {
            AfterTheEnd.LOGGER.warn("Failed to load city chunks for {} from admin command.", cityId, exception);
            source.sendFailure(Component.translatable("command.njw_after_the_end.city.failed_load", cityId));
            return 0;
        }
    }

    private static int listCities(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        var cities = CityManager.getCities(server);
        int unlockedCount = CityManager.getAccessibleCities(server).size();
        int lockedCount = CityManager.getLockedCityCount(server);
        int reserveTarget = CityLifecycleService.getLockedCityReserveTarget(server);
        source.sendSuccess(() -> Component.translatable(
                "command.njw_after_the_end.city.summary",
                cities.size(), unlockedCount, CityManager.getMaxCityCount(server),
                lockedCount, reserveTarget
        ), false);
        for (City city : cities) {
            Component state = Component.translatable(CityManager.isCityAccessible(server, city.id())
                    ? "command.njw_after_the_end.city.state.accessible"
                    : "command.njw_after_the_end.city.state.locked");
            source.sendSuccess(() -> Component.translatable("command.njw_after_the_end.city.list_entry", city.id().toString(), state), false);
        }
        return cities.size();
    }

    private static int showCityInfo(CommandSourceStack source, String cityId) {
        UUID id = parseCityId(source, cityId);
        if (id == null) return 0;
        City city = CityManager.getCity(source.getServer(), id);
        if (city == null) {
            source.sendFailure(Component.translatable("command.njw_after_the_end.city.unknown", cityId));
            return 0;
        }
        Component state = Component.translatable(CityManager.isCityAccessible(source.getServer(), city.id())
                ? "command.njw_after_the_end.city.state.accessible"
                : "command.njw_after_the_end.city.state.locked");
        source.sendSuccess(() -> Component.translatable(
                "command.njw_after_the_end.city.info", city.id().toString(), city.name(), state
        ), false);
        sendCityCoordinates(source, city);
        return 1;
    }

    private static int showMaxCityCount(CommandSourceStack source) {
        int maximum = CityManager.getMaxCityCount(source.getServer());
        source.sendSuccess(() -> Component.translatable("command.njw_after_the_end.city.max", maximum), false);
        return maximum;
    }

    private static int setMaxCityCount(CommandSourceStack source, int count) {
        try {
            CityLifecycleService.setMaxCityCount(source.getServer(), count);
            source.sendSuccess(() -> Component.translatable("command.njw_after_the_end.city.max_set", count), false);
            return count;
        } catch (RuntimeException exception) {
            AfterTheEnd.LOGGER.warn("Failed to set maximum city count to {} from admin command.", count, exception);
            source.sendFailure(Component.translatable("command.njw_after_the_end.city.failed_set_max", count));
            return 0;
        }
    }

    private static UUID parseCityId(CommandSourceStack source, String cityId) {
        try {
            return UUID.fromString(cityId);
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.translatable("command.njw_after_the_end.city.invalid_uuid", cityId));
            return null;
        }
    }

    private static void sendCityCoordinates(CommandSourceStack source, City city) {
        sendRegionCoordinates(source, Component.translatable("command.njw_after_the_end.city.dimension.overworld"), city.getRegion(Level.OVERWORLD).orElse(null));
        sendRegionCoordinates(source, Component.translatable("command.njw_after_the_end.city.dimension.nether"), city.getRegion(Level.NETHER).orElse(null));
    }

    private static void sendRegionCoordinates(CommandSourceStack source, Component dimensionName, CityRegion region) {
        if (region == null) return;
        long centerBlockX = (long) region.centerChunkX() * BLOCKS_PER_CHUNK;
        long centerBlockZ = (long) region.centerChunkZ() * BLOCKS_PER_CHUNK;
        source.sendSuccess(() -> Component.translatable(
                "command.njw_after_the_end.city.coordinates",
                dimensionName, region.centerChunkX(), region.centerChunkZ(), centerBlockX, centerBlockZ
        ), false);
    }
}
