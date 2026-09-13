package net.njw.aftertheend.registry;

import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.njw.aftertheend.AfterTheEnd;
import net.njw.aftertheend.block.ResonanceCrystalBlock;
import net.njw.aftertheend.block.entity.ResonanceCrystalBlockEntity;
import net.njw.aftertheend.item.ShulkerCoreItem;

public final class ModContent {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(AfterTheEnd.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AfterTheEnd.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, AfterTheEnd.MODID);

    public static final DeferredItem<ShulkerCoreItem> SHULKER_CORE = ITEMS.registerItem(
            "shulker_core",
            ShulkerCoreItem::new
    );

    public static final DeferredItem<Item> REPLICATION_PILL = ITEMS.registerItem("replication_pill", Item::new);
    public static final DeferredItem<Item> SORTING_PILL = ITEMS.registerItem("sorting_pill", Item::new);
    public static final DeferredItem<Item> SMELTING_PILL = ITEMS.registerItem("smelting_pill", Item::new);
    public static final DeferredItem<Item> SUPPLY_PILL = ITEMS.registerItem("supply_pill", Item::new);
    public static final DeferredItem<Item> CONCOCTION_PILL = ITEMS.registerItem("concoction_pill", Item::new);
    public static final DeferredItem<Item> LOGISTICS_PILL = ITEMS.registerItem("logistics_pill", Item::new);
    public static final DeferredItem<Item> SWITCHING_PILL = ITEMS.registerItem("switching_pill", Item::new);

    public static final DeferredBlock<ResonanceCrystalBlock> RESONANCE_CRYSTAL = BLOCKS.registerBlock(
            "resonance_crystal",
            ResonanceCrystalBlock::new,
            () -> BlockBehaviour.Properties.ofFullCopy(Blocks.AMETHYST_BLOCK)
                    .strength(1.5F)
                    .lightLevel(state -> 10)
                    .noOcclusion()
    );

    public static final DeferredItem<BlockItem> RESONANCE_CRYSTAL_ITEM = ITEMS.registerItem(
            "resonance_crystal",
            properties -> new BlockItem(RESONANCE_CRYSTAL.get(), properties.useBlockDescriptionPrefix())
    );

    public static final Supplier<BlockEntityType<ResonanceCrystalBlockEntity>> RESONANCE_CRYSTAL_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register(
            "resonance_crystal",
            () -> new BlockEntityType<>(ResonanceCrystalBlockEntity::new, RESONANCE_CRYSTAL.get())
    );

    private ModContent() { }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
    }
}
