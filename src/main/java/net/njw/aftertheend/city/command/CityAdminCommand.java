package net.njw.aftertheend.city.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.njw.aftertheend.city.City;
import net.njw.aftertheend.city.CityLifecycleService;
import net.njw.aftertheend.city.CityManager;
import net.njw.aftertheend.city.CityRegion;
import net.njw.aftertheend.city.generation.CityPregenerationHandler;

public final class CityAdminCommand {
    private static final int BLOCKS_PER_CHUNK = 16;

    private CityAdminCommand() {
    }

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
            City city = accessible ? CityLifecycleService.createAccessibleCity(source.getServer()) : CityLifecycleService.createLockedCity(source.getServer());
            source.sendSuccess(() -> Component.literal((accessible ? "Created accessible city: " : "Created locked city: ") + city.id()), false);
            sendCityCoordinates(source, city);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal("Failed to create city: " + exception.getMessage()));
            return 0;
        }
    }

    private static int unlockCity(CommandSourceStack source, String cityId) {
        try {
            CityLifecycleService.unlockCity(source.getServer(), cityId);
            source.sendSuccess(() -> Component.literal("Unlocked city: " + cityId), false);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
    }

    private static int deleteCity(CommandSourceStack source, String cityId) {
        try {
            CityLifecycleService.deleteCity(source.getServer(), cityId);
            source.sendSuccess(() -> Component.literal("Deleted city: " + cityId), false);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
    }

    private static int loadCity(CommandSourceStack source, String cityId) {
        City city = CityManager.getCity(source.getServer(), cityId);
        if (city == null) {
            source.sendFailure(Component.literal("Unknown city: " + cityId));
            return 0;
        }
        try {
            int taskCount = CityPregenerationHandler.startCityLoad(source.getServer(), city);
            if (taskCount == 0) {
                source.sendSuccess(() -> Component.literal("City chunks are already loaded: " + cityId), false);
                return 1;
            }
            source.sendSuccess(() -> Component.literal("Started city chunk loading: " + cityId + ". All players will be disconnected until loading finishes."), true);
            return 1;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
    }

    private static int listCities(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        var cities = CityManager.getCities(server);
        source.sendSuccess(() -> Component.literal("Cities: " + cities.size() + "/" + CityManager.getMaxCityCount(server)), false);
        for (City city : cities) {
            boolean accessible = CityManager.isCityAccessible(server, city.id());
            source.sendSuccess(() -> Component.literal("- " + city.id() + " (" + (accessible ? "accessible" : "locked") + ")"), false);
        }
        return cities.size();
    }

    private static int showCityInfo(CommandSourceStack source, String cityId) {
        City city = CityManager.getCity(source.getServer(), cityId);
        if (city == null) {
            source.sendFailure(Component.literal("Unknown city: " + cityId));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(city.id() + " / " + city.name() + " / " + (CityManager.isCityAccessible(source.getServer(), city.id()) ? "accessible" : "locked")), false);
        sendCityCoordinates(source, city);
        return 1;
    }

    private static int showMaxCityCount(CommandSourceStack source) {
        int maximum = CityManager.getMaxCityCount(source.getServer());
        source.sendSuccess(() -> Component.literal("Maximum city count: " + maximum), false);
        return maximum;
    }

    private static int setMaxCityCount(CommandSourceStack source, int count) {
        try {
            CityLifecycleService.setMaxCityCount(source.getServer(), count);
            source.sendSuccess(() -> Component.literal("Maximum city count set to " + count), false);
            return count;
        } catch (RuntimeException exception) {
            source.sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
    }

    private static void sendCityCoordinates(CommandSourceStack source, City city) {
        sendRegionCoordinates(source, "Overworld", city.getRegion(Level.OVERWORLD).orElse(null));
        sendRegionCoordinates(source, "Nether", city.getRegion(Level.NETHER).orElse(null));
    }

    private static void sendRegionCoordinates(CommandSourceStack source, String dimensionName, CityRegion region) {
        if (region == null) return;
        long centerBlockX = (long) region.centerChunkX() * BLOCKS_PER_CHUNK;
        long centerBlockZ = (long) region.centerChunkZ() * BLOCKS_PER_CHUNK;
        source.sendSuccess(() -> Component.literal("  " + dimensionName + ": center chunk=(" + region.centerChunkX() + ", " + region.centerChunkZ() + "), block=(" + centerBlockX + ", " + centerBlockZ + ")"), false);
    }
}
