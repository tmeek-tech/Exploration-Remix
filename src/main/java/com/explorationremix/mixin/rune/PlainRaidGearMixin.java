package com.explorationremix.mixin.rune;

import com.explorationremix.rune.loot.Plain;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Vindicator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A raid's pillagers and vindicators come with plain weapons too (0.22.6): their later waves' Multishot crossbows and
 * Sharpness axes are just a crossbow and an axe.
 */
@Mixin({Pillager.class, Vindicator.class})
public abstract class PlainRaidGearMixin {
    @Inject(method = "applyRaidBuffs", at = @At("TAIL"))
    private void explorationremix$plainRaidGear(int wave, boolean unused, CallbackInfo ci) {
        Mob self = (Mob) (Object) this;
        self.setItemSlot(EquipmentSlot.MAINHAND, Plain.plain(self.getMainHandItem()));
    }
}
