package net.njw.aftertheend.city.altar;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.njw.aftertheend.city.City;
import net.njw.aftertheend.city.CityManager;

public final class AltarCommand {
    private AltarCommand() { }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("altar")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("list").executes(context -> listCurrentCity(context.getSource())))
        );
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
            source.sendFailure(Component.translatable("command.njw_after_the_end.altar.not_in_city"));
            return 0;
        }

        List<AltarPlacement> placements = AltarManager.getPlacements(source.getServer(), currentCity.id());
        if (placements.isEmpty()) {
            source.sendFailure(Component.translatable("command.njw_after_the_end.altar.none_in_city", currentCity.id().toString()));
            return 0;
        }

        City city = currentCity;
        source.sendSuccess(() -> Component.translatable(
                "command.njw_after_the_end.altar.list_header", city.id().toString(), placements.size()
        ), false);
        for (int index = 0; index < placements.size(); index++) {
            AltarPlacement placement = placements.get(index);
            int half = placement.large() ? 13 : 5;
            int x = placement.blockX() + half;
            int y = placement.y() + 2;
            int z = placement.blockZ() + half;
            Component type = Component.translatable(placement.large()
                    ? "command.njw_after_the_end.altar.type.large"
                    : "command.njw_after_the_end.altar.type.small");
            Component color = Component.translatable("command.njw_after_the_end.altar.color." + placement.color());
            Component state = Component.translatable(placement.ruined()
                    ? "command.njw_after_the_end.altar.state.ruined"
                    : "command.njw_after_the_end.altar.state.normal");
            Component coordinates = coordinates(x, y, z);
            Component line = Component.translatable(
                    "command.njw_after_the_end.altar.list_entry", index + 1, type, color, state, coordinates
            );
            source.sendSuccess(() -> line, false);
        }
        return placements.size();
    }

    private static Component coordinates(int x, int y, int z) {
        String tpCommand = "/tp " + x + " " + y + " " + z;
        return Component.literal("[" + x + ", " + y + ", " + z + "]").withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.SuggestCommand(tpCommand)));
    }
}
