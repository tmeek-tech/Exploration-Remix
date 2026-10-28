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
}
