package com.explorationremix.registry;

import com.explorationremix.ExplorationRemix;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

public final class ERTags {
    private ERTags() {}

    /** Blocks that lantern pods and roots can hang from besides a solid ceiling. */
    public static final TagKey<Block> HANGING_SUPPORTS = TagKey.create(Registries.BLOCK, new ResourceLocation(ExplorationRemix.MOD_ID, "hanging_supports"));

    /** Every plant and crystal the caves grow, ours and vanilla's: what /remix census counts as growth. */
    public static final TagKey<Block> PLANTS = TagKey.create(Registries.BLOCK, new ResourceLocation(ExplorationRemix.MOD_ID, "plants"));

    /**
     * 0.9.0: the Basalt Galleries' natural stone kinds. A lava bomb's blast treats them as soft, and while the terrain
     * reset is on a quake or an eruption never turns one into an item (the reset regrows it where it was).
     */
    public static final TagKey<Block> GALLERIES_ROCK = TagKey.create(Registries.BLOCK, new ResourceLocation(ExplorationRemix.MOD_ID, "galleries_rock"));

    /** 0.9.0: ceiling rock a big quake can shake loose, and the only rock a lava bomb's crater throws out. */
    public static final TagKey<Block> QUAKE_DEBRIS = TagKey.create(Registries.BLOCK, new ResourceLocation(ExplorationRemix.MOD_ID, "quake_debris"));
}
