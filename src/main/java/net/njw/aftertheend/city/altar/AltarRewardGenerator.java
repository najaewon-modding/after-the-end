package net.njw.aftertheend.city.altar;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.njw.aftertheend.registry.ModContent;

final class AltarRewardGenerator {
    private static final List<RewardEntry> OVERWORLD_REWARDS = List.of(
            new RewardEntry(Items.IRON_INGOT, 16, 32, 20, false),
            new RewardEntry(Items.GOLD_INGOT, 8, 20, 16, false),
            new RewardEntry(Items.EMERALD, 6, 16, 14, false),
            new RewardEntry(Items.EXPERIENCE_BOTTLE, 8, 24, 14, false),
            new RewardEntry(Items.LAPIS_LAZULI, 12, 28, 12, false),
            new RewardEntry(Items.DIAMOND, 2, 5, 8, false)
    );
    private static final List<RewardEntry> NETHER_REWARDS = List.of(
            new RewardEntry(Items.QUARTZ, 16, 32, 18, false),
            new RewardEntry(Items.GLOWSTONE_DUST, 16, 32, 16, false),
            new RewardEntry(Items.BLAZE_ROD, 4, 10, 14, false),
            new RewardEntry(Items.MAGMA_CREAM, 4, 10, 12, false),
            new RewardEntry(Items.GHAST_TEAR, 2, 5, 8, false),
            new RewardEntry(Items.CRYING_OBSIDIAN, 2, 6, 8, false)
    );
    private static final List<RewardEntry> END_REWARDS = List.of(
            new RewardEntry(Items.ENDER_PEARL, 6, 16, 18, false),
            new RewardEntry(Items.CHORUS_FRUIT, 8, 20, 14, false),
            new RewardEntry(Items.POPPED_CHORUS_FRUIT, 6, 16, 12, false),
            new RewardEntry(Items.END_ROD, 4, 12, 10, false),
            new RewardEntry(Items.PURPUR_BLOCK, 8, 24, 10, false),
            new RewardEntry(Items.SHULKER_SHELL, 1, 3, 6, false)
    );
    private static final List<RewardEntry> TREASURE_REWARDS = List.of(
            new RewardEntry(Items.EXPERIENCE_BOTTLE, 16, 32, 20, false),
            new RewardEntry(Items.GOLDEN_APPLE, 1, 2, 18, false),
            new RewardEntry(Items.BOOK, 1, 1, 18, true),
            new RewardEntry(Items.DIAMOND, 2, 4, 12, false),
            new RewardEntry(Items.EMERALD, 12, 24, 12, false)
    );
    private static final List<RewardEntry> RARE_REWARDS = List.of(
            new RewardEntry(Items.WITHER_SKELETON_SKULL, 1, 1, 50, false),
            new RewardEntry(Items.ANCIENT_DEBRIS, 1, 2, 40, false),
            new RewardEntry(Items.ENCHANTED_GOLDEN_APPLE, 1, 1, 10, false)
    );

    private AltarRewardGenerator() { }

    static void fill(ServerLevel level, ChestBlockEntity chest) {
        RandomSource random = level.getRandom();
        List<ItemStack> rewards = new ArrayList<>(10);
        rewards.add(randomPill(random));
        addRolls(level, random, rewards, OVERWORLD_REWARDS, 2);
        addRolls(level, random, rewards, NETHER_REWARDS, 2);
        addRolls(level, random, rewards, END_REWARDS, 2);
        addRolls(level, random, rewards, TREASURE_REWARDS, 2);
        if (random.nextFloat() < 0.25F) rewards.add(roll(level, random, RARE_REWARDS));
        placeInRandomSlots(chest, random, rewards);
        chest.setChanged();
    }

    private static ItemStack randomPill(RandomSource random) {
        List<Item> pills = List.of(
                ModContent.REPLICATION_PILL.get(),
                ModContent.SORTING_PILL.get(),
                ModContent.SMELTING_PILL.get(),
                ModContent.SUPPLY_PILL.get(),
                ModContent.CONCOCTION_PILL.get(),
                ModContent.LOGISTICS_PILL.get(),
                ModContent.SWITCHING_PILL.get()
        );
        return new ItemStack(pills.get(random.nextInt(pills.size())));
    }

    private static void addRolls(ServerLevel level, RandomSource random, List<ItemStack> rewards,
                                 List<RewardEntry> pool, int rolls) {
        for (int i = 0; i < rolls; i++) rewards.add(roll(level, random, pool));
    }

    private static ItemStack roll(ServerLevel level, RandomSource random, List<RewardEntry> pool) {
        int totalWeight = 0;
        for (RewardEntry entry : pool) totalWeight += entry.weight();
        int value = random.nextInt(totalWeight);
        for (RewardEntry entry : pool) {
            value -= entry.weight();
            if (value < 0) return createStack(level, random, entry);
        }
        throw new IllegalStateException("Empty altar reward pool");
    }

    private static ItemStack createStack(ServerLevel level, RandomSource random, RewardEntry entry) {
        if (entry.enchantedBook()) {
            return EnchantmentHelper.enchantItem(
                    random, new ItemStack(Items.BOOK), 30, level.registryAccess(), Optional.empty()
            );
        }
        int count = entry.minCount() + random.nextInt(entry.maxCount() - entry.minCount() + 1);
        return new ItemStack(entry.item(), count);
    }

    private static void placeInRandomSlots(ChestBlockEntity chest, RandomSource random, List<ItemStack> rewards) {
        List<Integer> slots = new ArrayList<>(chest.getContainerSize());
        for (int slot = 0; slot < chest.getContainerSize(); slot++) slots.add(slot);
        for (ItemStack reward : rewards) {
            int slotIndex = random.nextInt(slots.size());
            chest.setItem(slots.remove(slotIndex), reward);
        }
    }

    private record RewardEntry(Item item, int minCount, int maxCount, int weight, boolean enchantedBook) { }
}
