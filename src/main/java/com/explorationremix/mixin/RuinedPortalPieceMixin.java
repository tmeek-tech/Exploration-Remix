package com.explorationremix.mixin;

import com.explorationremix.world.DeepWorld;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.structures.RuinedPortalPiece;
import net.minecraft.world.level.levelgen.structure.templatesystem.AlwaysTrueTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.ProcessorRule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No lava in the Upper Caves: a ruined portal buried in the rock or set into a mountain turns all of its lava to magma,
 * as one on the ocean floor does, instead of keeping most of it. Cold ones keep vanilla's netherrack; portals on the
 * surface and in the Nether are untouched.
 */
@Mixin(RuinedPortalPiece.class)
public abstract class RuinedPortalPieceMixin {
    @Inject(method = "getLavaProcessorRule", at = @At("HEAD"), cancellable = true)
    private static void er$magmaInRock(RuinedPortalPiece.VerticalPlacement placement, RuinedPortalPiece.Properties properties, CallbackInfoReturnable<ProcessorRule> cir) {
        if (!DeepWorld.installed() || properties.cold) return;
        if (placement == RuinedPortalPiece.VerticalPlacement.UNDERGROUND || placement == RuinedPortalPiece.VerticalPlacement.IN_MOUNTAIN) {
            cir.setReturnValue(new ProcessorRule(new BlockMatchTest(Blocks.LAVA), AlwaysTrueTest.INSTANCE, Blocks.MAGMA_BLOCK.defaultBlockState()));
        }
    }
}
