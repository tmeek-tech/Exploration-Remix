package com.explorationremix.mixin.accessor;

import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DimensionType.class)
public interface DimensionTypeAccessor {
    @Accessor("minY") @Mutable void er$setMinY(int minY);
    @Accessor("height") @Mutable void er$setHeight(int height);
    @Accessor("logicalHeight") @Mutable void er$setLogicalHeight(int logicalHeight);
}
