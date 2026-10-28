package com.explorationremix.client.render;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.client.model.CreatureModel;
import com.explorationremix.client.model.CreatureShape;
import com.explorationremix.creature.entity.CreatureAnim;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;

/** Renders any creature from its shape file: texture by id, scale from the shape, upside-down when it hangs. */
public class CreatureRenderer<T extends Mob & CreatureAnim> extends MobRenderer<T, CreatureModel<T>> {
    private final ResourceLocation texture;
    private final float scale;

    public static ModelLayerLocation layer(String id) {
        return new ModelLayerLocation(new ResourceLocation(ExplorationRemix.MOD_ID, id), "main");
    }

    public CreatureRenderer(EntityRendererProvider.Context ctx, String id) {
        super(ctx, new CreatureModel<>(ctx.bakeLayer(layer(id)), CreatureShape.load(id)), CreatureShape.load(id).shadow);
        this.texture = new ResourceLocation(ExplorationRemix.MOD_ID, "textures/entity/" + id + ".png");
        this.scale = CreatureShape.load(id).scale;
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) { return texture; }

    @Override
    protected void scale(T entity, PoseStack poseStack, float partial) {
        poseStack.scale(scale, scale, scale);
    }

    @Override
    protected void setupRotations(T entity, PoseStack poseStack, float bob, float yaw, float partial) {
        super.setupRotations(entity, poseStack, bob, yaw, partial);
        if (entity.isHanging()) {
            poseStack.translate(0.0, entity.getBbHeight(), 0.0);
            poseStack.mulPose(Axis.ZP.rotationDegrees(180.0F));
        }
    }

    @Override
    public void render(T entity, float yaw, float partial, PoseStack poseStack, MultiBufferSource buffer, int light) {
        if (entity.isHidden()) return;
        super.render(entity, yaw, partial, poseStack, buffer, light);
    }
}
