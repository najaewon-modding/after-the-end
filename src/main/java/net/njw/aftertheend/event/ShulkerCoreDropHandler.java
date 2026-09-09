package net.njw.aftertheend.event;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.njw.aftertheend.registry.ModContent;

public final class ShulkerCoreDropHandler {
    private ShulkerCoreDropHandler() { }

    @SubscribeEvent
    public static void onLivingDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof Shulker shulker)) return;
        if (!(shulker.level() instanceof ServerLevel level)) return;
        ItemEntity drop = new ItemEntity(level, shulker.getX(), shulker.getY(), shulker.getZ(), new ItemStack(ModContent.SHULKER_CORE.get()));
        event.getDrops().add(drop);
    }
}
