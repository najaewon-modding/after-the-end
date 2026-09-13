package net.njw.aftertheend.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.njw.aftertheend.registry.ModContent;

public final class PillCommand {
    private PillCommand() { }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("pill")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("give").executes(context -> giveAll(context.getSource())))
        );
    }

    private static int giveAll(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        List<Item> pills = List.of(
                ModContent.REPLICATION_PILL.get(),
                ModContent.SORTING_PILL.get(),
                ModContent.SMELTING_PILL.get(),
                ModContent.SUPPLY_PILL.get(),
                ModContent.CONCOCTION_PILL.get(),
                ModContent.LOGISTICS_PILL.get(),
                ModContent.SWITCHING_PILL.get()
        );
        for (Item pill : pills) {
            ItemStack stack = new ItemStack(pill);
            if (!player.addItem(stack)) player.drop(stack, false);
        }
        source.sendSuccess(() -> Component.translatable("command.njw_after_the_end.pill.given"), false);
        return pills.size();
    }
}
