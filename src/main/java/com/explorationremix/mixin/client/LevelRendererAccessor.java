package com.explorationremix.mixin.client;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.server.level.BlockDestructionProgress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Every block being dug that this client knows of, by who digs it, this player included (RuneStoneClient). */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @Accessor("destroyingBlocks")
    Int2ObjectMap<BlockDestructionProgress> explorationremix$digging();
}
