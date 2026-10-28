package com.explorationremix.client.model;

import com.explorationremix.creature.entity.CreatureAnim;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * One model for every creature: parts animate by their role (head, leg, tail, wing, neck, jaw) with a procedural
 * walk, so a new creature is only a new shape file and a texture.
 */
public class CreatureModel<T extends LivingEntity & CreatureAnim> extends HierarchicalModel<T> {
    private record Animated(ModelPart part, CreatureShape.Part spec) {}

    private final ModelPart root;
    private final List<Animated> animated = new ArrayList<>();

    public CreatureModel(ModelPart root, CreatureShape shape) {
        this.root = root;
        for (CreatureShape.Part p : shape.parts) {
            root.getAllParts().filter(mp -> false).findAny();
            getAnyDescendantWithName(p.name).ifPresent(mp -> animated.add(new Animated(mp, p)));
        }
    }

    @Override
    public ModelPart root() { return root; }

    @Override
    public void setupAnim(T e, float limbSwing, float limbSwingAmount, float age, float netHeadYaw, float headPitch) {
        boolean flying = e.isFlying();
        boolean charging = e.isCharging();
        boolean hanging = e.isHanging();
        float swing = limbSwing * 0.9F;
        for (Animated a : animated) {
            ModelPart p = a.part();
            CreatureShape.Part s = a.spec();
            p.resetPose();
            switch (s.role) {
                case "head" -> {
                    p.yRot = hanging ? 0 : netHeadYaw * ((float) Math.PI / 180F) * 0.7F;
                    p.xRot = charging ? 0.45F : (hanging ? 0.3F : headPitch * ((float) Math.PI / 180F) * 0.7F);
                }
                case "neck" -> p.xRot = headPitch * ((float) Math.PI / 180F) * 0.3F;
                case "leg" -> {
                    float f = Mth.cos(swing + s.phase) * s.amp * limbSwingAmount;
                    if ("y".equals(s.axis)) {
                        p.yRot = f * 0.8F * s.side;
                        p.zRot = s.side * (0.2F + Math.max(0, Mth.sin(swing + s.phase)) * 0.35F * limbSwingAmount);
                    } else {
                        p.xRot = f;
                        if (hanging) p.xRot = 0.6F * (s.phase > 1 ? -1 : 1);
                    }
                }
                case "tail" -> {
                    p.yRot = Mth.sin(age * 0.15F + s.phase) * 0.25F + Mth.cos(swing * 0.7F) * 0.3F * limbSwingAmount;
                    p.xRot = Mth.sin(age * 0.1F + s.phase) * 0.08F;
                }
                case "wing" -> {
                    float flap = flying ? (0.25F + Mth.sin(age * 1.4F) * 0.95F) : 1.35F;
                    p.zRot = s.side * flap;
                }
                case "jaw" -> p.yRot = s.side * (0.12F + Mth.sin(age * 0.6F) * 0.12F + (charging ? 0.3F : 0));
                default -> {}
            }
        }
    }
}
