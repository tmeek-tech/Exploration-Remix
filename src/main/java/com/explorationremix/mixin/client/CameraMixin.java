package com.explorationremix.mixin.client;

import com.explorationremix.client.rune.table.RuneTableView;
import com.explorationremix.world.lava.LavaPlane;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The enchanting table's rune view takes the camera (RuneTableView): once the game has set it up for the frame, the
 * view eases it to above the table. Seen from its own eyes, so the player's body is never drawn in the way.
 *
 * <p>0.21.6: a camera under the surface of the Basalt Galleries' lava plane ({@link LavaPlane}, no blocks under it) is
 * in lava, with the game's lava fog, the way the game asks of lava blocks: any of the near plane's points under it.
 */
@Mixin(Camera.class)
public abstract class CameraMixin implements RuneTableView.Placeable {
    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setPosition(Vec3 pos);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Override
    public void explorationremix$place(Vec3 at, float yaw, float pitch) {
        setRotation(yaw, pitch);
        setPosition(at);
        detached = false;
    }

    @Inject(method = "setup", at = @At("TAIL"))
    private void explorationremix$tableView(BlockGetter level, Entity entity, boolean thirdPerson, boolean mirrored, float partialTick, CallbackInfo ci) {
        RuneTableView.camera((Camera) (Object) this, partialTick);
    }

    @Inject(method = "getFluidInCamera", at = @At("HEAD"), cancellable = true)
    private void explorationremix$lavaPlaneFog(CallbackInfoReturnable<FogType> cir) {
        LavaPlane plane = LavaPlane.CLIENT;
        Camera self = (Camera) (Object) this;
        if (!plane.on() || !self.isInitialized()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || level.dimension() != Level.OVERWORLD) return;
        Camera.NearPlane near = self.getNearPlane();
        double low = Math.min(0, Math.min(Math.min(near.getTopLeft().y, near.getTopRight().y), Math.min(near.getBottomLeft().y, near.getBottomRight().y)));
        if (plane.under(self.getPosition().y + low, level.getGameTime(), mc.getPartialTick())) cir.setReturnValue(FogType.LAVA);
    }
}
