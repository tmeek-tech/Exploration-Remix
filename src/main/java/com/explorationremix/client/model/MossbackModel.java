package com.explorationremix.client.model;

import com.explorationremix.creature.MossbackEntity;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

/**
 * The Mossback's model, its shape from tools/creatures.py (geometry/mossback.json). Its animation is all procedural:
 * a sprawling walk with the legs in diagonal pairs, the spine bending from side to side in an S with the tail
 * following a beat behind, slow breathing at the throat, the head turning towards what it watches, and for the hunt
 * the frills fanning out, the throat swelling, the front rearing and the jaw gaping. While it holds its catch the
 * head and neck thrash.
 */
public class MossbackModel extends HierarchicalModel<MossbackEntity> {
    private final ModelPart root, chest, hips, neck, head, jaw, throat, frillLeft, frillRight;
    private final ModelPart[] tail = new ModelPart[5];
    private final ModelPart flUpper, flLower, frUpper, frLower, hlUpper, hlLower, hrUpper, hrLower;
    private float partialTick;

    public MossbackModel(ModelPart root) {
        this.root = root;
        this.chest = root.getChild("chest");
        this.hips = chest.getChild("hips");
        this.neck = chest.getChild("neck");
        this.head = neck.getChild("head");
        this.jaw = head.getChild("jaw");
        this.throat = jaw.getChild("throat");
        this.frillLeft = head.getChild("frill_left");
        this.frillRight = head.getChild("frill_right");
        ModelPart t = hips.getChild("tail1");
        tail[0] = t;
        for (int i = 1; i < 5; i++) { t = t.getChild("tail" + (i + 1)); tail[i] = t; }
        this.flUpper = chest.getChild("front_left_upper");
        this.flLower = flUpper.getChild("front_left_lower");
        this.frUpper = chest.getChild("front_right_upper");
        this.frLower = frUpper.getChild("front_right_lower");
        this.hlUpper = hips.getChild("hind_left_upper");
        this.hlLower = hlUpper.getChild("hind_left_lower");
        this.hrUpper = hips.getChild("hind_right_upper");
        this.hrLower = hrUpper.getChild("hind_right_lower");
    }

    @Override
    public ModelPart root() {
        return root;
    }

    @Override
    public void prepareMobModel(MossbackEntity e, float limbSwing, float limbSwingAmount, float partialTick) {
        this.partialTick = partialTick;
    }

    @Override
    public void setupAnim(MossbackEntity e, float limbSwing, float amount, float age, float netHeadYaw, float headPitch) {
        root.getAllParts().forEach(ModelPart::resetPose);
        float pt = partialTick;
        float jawOpen = e.jaw(pt), rear = e.rear(pt), flare = e.frill(pt);
        amount = Math.min(1.0F, amount * 1.6F);

        // --- walk: diagonal pairs (front left with hind right), the spine swinging the other way
        float phase = limbSwing * 0.55F;
        float a = Mth.sin(phase), b = Mth.cos(phase);
        float swing = 0.75F * amount, lift = 0.45F * amount;
        leg(flUpper, flLower, a, b, swing, lift, 1);
        leg(hrUpper, hrLower, a, b, swing, lift, -1);
        leg(frUpper, frLower, -a, -b, swing, lift, -1);
        leg(hlUpper, hlLower, -a, -b, swing, lift, 1);
        float bend = 0.16F * amount;
        chest.yRot += a * bend;
        hips.yRot -= a * bend * 1.6F;
        neck.yRot -= a * bend * 0.8F;
        // the tail follows a beat behind, and sways a little even at rest
        for (int i = 0; i < tail.length; i++) {
            float lag = phase - 0.7F * (i + 1);
            tail[i].yRot += Mth.sin(lag) * (0.12F + 0.05F * i) * amount + Mth.sin(age * 0.05F - i * 0.6F) * 0.05F;
        }

        // --- breathing
        float breath = Mth.sin(age * 0.09F);
        throat.yScale = 1.0F + 0.06F * breath;
        chest.y += 0.15F * breath;

        // --- head: turns to what it watches (on walls and ceilings it just looks ahead)
        if (e.onFloor()) {
            float yaw = Mth.clamp(netHeadYaw, -60.0F, 60.0F) * Mth.DEG_TO_RAD;
            float pitch = Mth.clamp(headPitch, -40.0F, 40.0F) * Mth.DEG_TO_RAD;
            neck.yRot += yaw * 0.45F;
            head.yRot += yaw * 0.55F;
            neck.xRot += pitch * 0.4F;
            head.xRot += pitch * 0.6F;
        }

        // --- the hunt: frills fan out, the throat swells, the front rears and the jaw gapes
        frillLeft.yRot += flare * 1.0F;
        frillRight.yRot -= flare * 1.0F;
        frillLeft.zRot -= flare * 0.25F;
        frillRight.zRot += flare * 0.25F;
        throat.yScale += flare * 0.9F + rear * 0.4F;
        throat.xScale = 1.0F + flare * 0.3F;
        throat.zScale = 1.0F + flare * 0.2F;
        chest.xRot -= rear * 0.35F;
        hips.xRot += rear * 0.3F;
        neck.xRot -= rear * 0.15F;
        head.xRot += rear * 0.2F - jawOpen * 0.15F;
        jaw.xRot += jawOpen * 0.85F;
        flUpper.zRot -= rear * 0.5F;
        frUpper.zRot += rear * 0.5F;

        // --- holding its catch, it thrashes
        if (e.state() == MossbackEntity.GRAB) {
            float thrash = Mth.sin(age * 1.1F);
            neck.yRot += thrash * 0.45F;
            head.yRot += thrash * 0.35F;
            head.zRot += thrash * 0.25F;
            chest.yRot -= thrash * 0.15F;
        }
    }

    /** A sprawling leg: the upper leg sweeps forward and back about the shoulder and lifts while it swings forward. */
    private static void leg(ModelPart upper, ModelPart lower, float a, float b, float swing, float lift, int side) {
        upper.yRot += a * swing * side;                     // side: +1 for the left legs, -1 for the right
        float up = Math.max(0.0F, b) * lift;
        boolean left = upper.x > 0;                          // y grows downward: a left leg lifts with a negative turn
        upper.zRot += left ? -up : up;
        lower.zRot += left ? up * 0.8F : -up * 0.8F;
    }
}
