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
 * Worlds made with older versions hold blocks and items the mod no longer has. 0.4.0's frost ferns and icicles were
 * replaced by Immersive Weathering's in 0.4.1: the frost ferns turn into Immersive Weathering's frosty ferns; the tall
 * ones and the icicles (whose shapes do not carry over) go quietly. Items in chests and inventories become Immersive
 * Weathering's. Since 0.4.2 the silver wall fern has no item of its own: the silver fern's item places it. In 0.19.5
 * (Deep Gardens round 3) the vents turned to vanilla blackstone, so vent rock and live vent rock become blackstone, and
 * the spire tip was renamed glow nodules (its colour carries over: the blockstate keeps its neon property).
 */
public final class ERRemaps {
    private ERRemaps() {}

    private static final String IW = "immersive_weathering:";
    /** Old block -> its replacement, or "" to drop it. */
    private static final Map<String, String> BLOCKS = Map.of(
            "frost_fern", IW + "frosty_fern", "tall_frost_fern", "", "icicle", "", "icicle_plant", "",
            "vent_rock", "minecraft:blackstone", "live_vent_rock", "minecraft:blackstone",
            "spire_tip", ExplorationRemix.MOD_ID + ":glow_nodules");
    private static final Map<String, String> ITEMS = Map.of(
            "frost_fern", IW + "frosty_fern", "tall_frost_fern", IW + "frosty_fern", "icicle", IW + "icicle",
            "silver_wall_fern", ExplorationRemix.MOD_ID + ":silver_fern",
            "vent_rock", "minecraft:blackstone", "live_vent_rock", "minecraft:blackstone",
            "spire_tip", ExplorationRemix.MOD_ID + ":glow_nodules");

    @SubscribeEvent
    public static void remap(MissingMappingsEvent event) {
        for (MissingMappingsEvent.Mapping<Block> m : event.getMappings(ForgeRegistries.Keys.BLOCKS, ExplorationRemix.MOD_ID)) {
            String to = BLOCKS.get(m.getKey().getPath());
            if (to == null) continue;
            ResourceLocation id = to.isEmpty() ? null : new ResourceLocation(to);
            if (id != null && ForgeRegistries.BLOCKS.containsKey(id)) m.remap(ForgeRegistries.BLOCKS.getValue(id));
            else m.ignore();
        }
        for (MissingMappingsEvent.Mapping<Item> m : event.getMappings(ForgeRegistries.Keys.ITEMS, ExplorationRemix.MOD_ID)) {
            String to = ITEMS.get(m.getKey().getPath());
            if (to == null) continue;
            ResourceLocation id = new ResourceLocation(to);
            if (ForgeRegistries.ITEMS.containsKey(id)) m.remap(ForgeRegistries.ITEMS.getValue(id));
            else m.ignore();
        }
    }
}
