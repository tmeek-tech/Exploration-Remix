package com.explorationremix.registry;

import com.explorationremix.item.ChoirKeyItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.RegistryObject;

/**
 * The mod's plain items: the ones that are neither a block, a creature's drop ({@link EREntities}) nor a rune
 * ({@code rune.RuneRegistry}). They go in {@link ERBlocks#ITEMS} with everything else, so one register call covers
 * them all.
 */
public final class ERItems {
    private ERItems() {}

    /** Loads this class, so its items join {@link ERBlocks#ITEMS} before that register goes to the mod bus. */
    public static void load() { }

    /**
     * A choir key (0.21.9): one lies in the reliquary at the heart of every ancient city. Three, set in the three
     * sockets of a Hollow Choir's arena, call the Choir. They do not stack far: three is the whole of a summoning.
     */
    public static final RegistryObject<Item> CHOIR_KEY = ERBlocks.ITEMS.register("choir_key",
            () -> new ChoirKeyItem(new Item.Properties().stacksTo(3).rarity(net.minecraft.world.item.Rarity.RARE)));
}
