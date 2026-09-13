package net.njw.aftertheend.event;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.njw.aftertheend.registry.ModContent;

public final class ShulkerCoreDropHandler {
    private ShulkerCoreDropHandler() { }

    @SubscribeEvent
    public static void onLivingDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof Shulker shulker)) return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer)) return;
        if (!(shulker.level() instanceof ServerLevel level)) return;
        boolean shellDropped = event.getDrops().stream().anyMatch(drop -> drop.getItem().is(Items.SHULKER_SHELL));
        if (!shouldDropCore(shellDropped, shulker.getRandom().nextDouble())) return;
        ItemEntity drop = new ItemEntity(level, shulker.getX(), shulker.getY(), shulker.getZ(), new ItemStack(ModContent.SHULKER_CORE.get()));
        event.getDrops().add(drop);
    }

    static boolean shouldDropCore(boolean shellDropped, double roll) {
        return shellDropped && roll < 0.5D;
    }
}
