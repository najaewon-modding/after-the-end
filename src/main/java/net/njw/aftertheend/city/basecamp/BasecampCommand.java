package net.njw.aftertheend.city.basecamp;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.njw.aftertheend.city.City;
import net.njw.aftertheend.city.CityManager;

public final class BasecampCommand {
    private BasecampCommand() { }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("basecamp")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("locate").executes(context -> locateNearest(context.getSource())))
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
}
