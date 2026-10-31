package com.explorationremix.registry;

import com.explorationremix.ExplorationRemix;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.MissingMappingsEvent;

import java.util.Map;

/**
 * Worlds made with 0.4.0 hold blocks and items 0.4.1 no longer has: its frost ferns and icicles, replaced by Immersive
 * Weathering's. The frost ferns turn into Immersive Weathering's frosty ferns; the tall ones and the icicles (whose
 * shapes do not carry over) go quietly. Items in chests and inventories become Immersive Weathering's.
 */
public final class ERRemaps {
    private ERRemaps() {}

    private static final String IW = "immersive_weathering";
    /** Old block -> Immersive Weathering's block, or "" to drop it. */
    private static final Map<String, String> BLOCKS = Map.of(
            "frost_fern", "frosty_fern", "tall_frost_fern", "", "icicle", "", "icicle_plant", "");
    private static final Map<String, String> ITEMS = Map.of(
            "frost_fern", "frosty_fern", "tall_frost_fern", "frosty_fern", "icicle", "icicle");

    @SubscribeEvent
    public static void remap(MissingMappingsEvent event) {
        for (MissingMappingsEvent.Mapping<Block> m : event.getMappings(ForgeRegistries.Keys.BLOCKS, ExplorationRemix.MOD_ID)) {
            String to = BLOCKS.get(m.getKey().getPath());
            if (to == null) continue;
            ResourceLocation id = new ResourceLocation(IW, to);
            if (!to.isEmpty() && ForgeRegistries.BLOCKS.containsKey(id)) m.remap(ForgeRegistries.BLOCKS.getValue(id));
            else m.ignore();
        }
        for (MissingMappingsEvent.Mapping<Item> m : event.getMappings(ForgeRegistries.Keys.ITEMS, ExplorationRemix.MOD_ID)) {
            String to = ITEMS.get(m.getKey().getPath());
            if (to == null) continue;
            ResourceLocation id = new ResourceLocation(IW, to);
            if (ForgeRegistries.ITEMS.containsKey(id)) m.remap(ForgeRegistries.ITEMS.getValue(id));
            else m.ignore();
        }
    }
}
