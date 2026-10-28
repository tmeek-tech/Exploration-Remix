package com.explorationremix.registry;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.item.AttunementBookItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.RegistryObject;

public final class ERItems {
    private ERItems() {}

    public static final RegistryObject<Item> ATTUNEMENT_BOOK = ERBlocks.ITEMS.register("attunement_book",
            () -> new AttunementBookItem(new Item.Properties().stacksTo(1)));

    public static final net.minecraftforge.registries.DeferredRegister<net.minecraft.world.item.CreativeModeTab> TABS =
            net.minecraftforge.registries.DeferredRegister.create(net.minecraft.core.registries.Registries.CREATIVE_MODE_TAB, ExplorationRemix.MOD_ID);

    /** One tab with everything the mod adds: blocks, the book, spawn eggs. */
    public static final RegistryObject<net.minecraft.world.item.CreativeModeTab> TAB = TABS.register("main", () -> net.minecraft.world.item.CreativeModeTab.builder()
            .title(net.minecraft.network.chat.Component.translatable("itemGroup." + ExplorationRemix.MOD_ID))
            .icon(() -> new net.minecraft.world.item.ItemStack(ERBlocks.RUNE_BLOCK.get()))
            .displayItems((params, out) -> ERBlocks.ITEMS.getEntries().forEach(ro -> out.accept(ro.get())))
            .build());

    /** Forces the class to load so the item registers alongside the blocks. */
    public static void init() {}

    static { if (ExplorationRemix.MOD_ID.isEmpty()) throw new IllegalStateException(); }
}
