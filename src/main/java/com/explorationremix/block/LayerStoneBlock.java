package com.explorationremix.block;

import net.minecraft.world.level.block.Block;

/** Stone inside a layer: no tool or drill breaks it, only a blast does. Marker class used by the explosion rules. */
public class LayerStoneBlock extends Block {
    public LayerStoneBlock(Properties properties) { super(properties); }
}
