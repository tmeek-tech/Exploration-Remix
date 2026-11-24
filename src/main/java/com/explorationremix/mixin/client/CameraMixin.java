package com.explorationremix.mixin.client;

import com.explorationremix.client.rune.table.RuneTableView;
import net.minecraft.client.Camera;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The enchanting table's rune view takes the camera (RuneTableView): once the game has set it up for the frame, the
 * view eases it to above the table. Seen from its own eyes, so the player's body is never drawn in the way.
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
}
