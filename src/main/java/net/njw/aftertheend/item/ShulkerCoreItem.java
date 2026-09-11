package net.njw.aftertheend.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class ShulkerCoreItem extends Item {
    public ShulkerCoreItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }
}
