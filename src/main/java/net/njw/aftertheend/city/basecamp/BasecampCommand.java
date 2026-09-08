package net.njw.aftertheend.city.basecamp;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.njw.aftertheend.city.City;
import net.njw.aftertheend.city.CityManager;

import java.util.List;

public final class BasecampCommand {
    private BasecampCommand() { }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("basecamp")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("locate").executes(context -> locateNearest(context.getSource())))
                        .then(Commands.literal("list").executes(context -> listCurrentCity(context.getSource())))
        );
    }

    private static int locateNearest(CommandSourceStack source) {
        double sourceX = source.getPosition().x();
        double sourceZ = source.getPosition().z();
        BasecampPlacement nearest = null;
        double nearestDistanceSquared = Double.POSITIVE_INFINITY;

        for (City city : CityManager.getCities(source.getServer())) {
            for (BasecampPlacement placement : BasecampManager.getPlacements(source.getServer(), city.id())) {
                int half = placement.large() ? 13 : 5;
                double centerX = placement.blockX() + half;
                double centerZ = placement.blockZ() + half;
                double dx = centerX - sourceX;
                double dz = centerZ - sourceZ;
                double distanceSquared = dx * dx + dz * dz;
                if (distanceSquared < nearestDistanceSquared) {
                    nearestDistanceSquared = distanceSquared;
                    nearest = placement;
                }
            }
        }

        if (nearest == null) {
            source.sendFailure(Component.literal("No Basecamp has been generated yet."));
            return 0;
        }

        int half = nearest.large() ? 13 : 5;
        int x = nearest.blockX() + half;
        int y = nearest.y() + 2;
        int z = nearest.blockZ() + half;
        long distance = Math.round(Math.sqrt(nearestDistanceSquared));
        source.sendSuccess(
                () -> Component.literal("Nearest Basecamp: [" + x + ", " + y + ", " + z + "] (" + distance + " blocks away)"),
                false
        );
        return 1;
    }

    private static int listCurrentCity(CommandSourceStack source) {
        int blockX = (int) Math.floor(source.getPosition().x());
        int blockZ = (int) Math.floor(source.getPosition().z());
        City currentCity = null;
        for (City city : CityManager.getCities(source.getServer())) {
            if (city.contains(source.getLevel().dimension(), blockX, blockZ)) {
                currentCity = city;
                break;
            }
        }

        if (currentCity == null) {
            source.sendFailure(Component.literal("You are not inside a city."));
            return 0;
        }

        List<BasecampPlacement> placements = BasecampManager.getPlacements(source.getServer(), currentCity.id());
        if (placements.isEmpty()) {
            source.sendFailure(Component.literal("No Basecamp has been generated for city " + currentCity.id() + "."));
            return 0;
        }

        City city = currentCity;
        source.sendSuccess(() -> Component.literal("Basecamps in " + city.id() + ": " + placements.size()), false);
        for (int index = 0; index < placements.size(); index++) {
            BasecampPlacement placement = placements.get(index);
            int half = placement.large() ? 13 : 5;
            int x = placement.blockX() + half;
            int y = placement.y() + 2;
            int z = placement.blockZ() + half;
            String type = placement.large() ? "large" : "small";
            String state = placement.ruined() ? "ruined" : "normal";
            String tpCommand = "/tp " + x + " " + y + " " + z;
            Component coordinates = Component.literal("[" + x + ", " + y + ", " + z + "]")
                    .withStyle(style -> style
                            .withColor(ChatFormatting.AQUA)
                            .withUnderlined(true)
                            .withClickEvent(new ClickEvent.SuggestCommand(tpCommand)));
            Component line = Component.literal((index + 1) + ". " + type + " / " + placement.color() + " / " + state + " ")
                    .append(coordinates);
            source.sendSuccess(() -> line, false);
        }
        return placements.size();
    }
}
