package com.explorationremix.registry;

import com.explorationremix.ExplorationRemix;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
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
 * Weathering's. Since 0.4.2 the silver wall fern has no item of its own: the silver fern's item places it. In 0.19.6
 * (Deep Gardens round 3) the vents turned to vanilla blackstone, so vent rock and live vent rock become blackstone, and
 * the spire tip was renamed vent beads (its colour carries over: the blockstate keeps its neon property). In round 4
 * (0.19.9) den fissure, vent glow and throat vein left the game for vent beads, which take their places in the same
 * colour. The deep Frost Fern Grotto's glow ice (0.20.1) left in 0.20.7 (Taran: "Remove glow ice"): it turns into the
 * packed ice it was set in. In 0.20.9, when each cave layer got its own ambience, the Upper Caves' old loop and the Deep
 * Gardens' hot springs left with their sounds: a world keeps nothing of a sound, so every sound event of the mod's that a
 * world remembers and the mod no longer has goes quietly, with no error in the log. In 0.22.4 the Basalt Galleries'
 * invisible lava glow left (Taran: "remove the invisible light sources"): it goes quietly, and a chunk that held it reads
 * air there (a block a chunk names that the game no longer has loads as air).
 */
public final class ERRemaps {
    private ERRemaps() {}

    private static final String IW = "immersive_weathering:";
    /** Old block -> its replacement, or "" to drop it. */
    private static final String BEADS = ExplorationRemix.MOD_ID + ":vent_beads";
    private static final Map<String, String> BLOCKS = Map.ofEntries(
            Map.entry("frost_fern", IW + "frosty_fern"), Map.entry("tall_frost_fern", ""), Map.entry("icicle", ""),
            Map.entry("icicle_plant", ""),
            Map.entry("vent_rock", "minecraft:blackstone"), Map.entry("live_vent_rock", "minecraft:blackstone"),
            Map.entry("spire_tip", BEADS),
            Map.entry("den_fissure", BEADS), Map.entry("vent_glow", BEADS), Map.entry("throat_vein", BEADS),
            Map.entry("glow_ice", "minecraft:packed_ice"), Map.entry("glow", ""));
    private static final Map<String, String> ITEMS = Map.ofEntries(
            Map.entry("frost_fern", IW + "frosty_fern"), Map.entry("tall_frost_fern", IW + "frosty_fern"),
            Map.entry("icicle", IW + "icicle"), Map.entry("silver_wall_fern", ExplorationRemix.MOD_ID + ":silver_fern"),
            Map.entry("vent_rock", "minecraft:blackstone"), Map.entry("live_vent_rock", "minecraft:blackstone"),
            Map.entry("spire_tip", BEADS),
            Map.entry("den_fissure", BEADS), Map.entry("vent_glow", BEADS), Map.entry("throat_vein", BEADS),
            Map.entry("glow_ice", "minecraft:packed_ice"));

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
        for (MissingMappingsEvent.Mapping<SoundEvent> m : event.getMappings(ForgeRegistries.Keys.SOUND_EVENTS, ExplorationRemix.MOD_ID)) m.ignore();
    }
}
