package com.explorationremix.client.foes;

import com.explorationremix.client.rune.fx.RuneFxClient;
import com.explorationremix.combat.foes.FoeKind;
import com.explorationremix.combat.foes.FoeMove;
import com.explorationremix.combat.foes.FoeRules;
import com.explorationremix.config.ERClientConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.IllagerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.BowItem;

import javax.annotation.Nullable;

/**
 * Vanilla's hostile mobs' bodies (0.23.6), posed on top of vanilla's own animation each draw: the wind-up before each
 * blow, the blow, the stumble after a miss (the server says which move a mob is in and since when, see
 * {@link FoeClient}); a flinch when hit, a sway while reeling, a zombie's shamble and a skeleton's stalk. Two parts:
 * the model's parts ({@link #parts}, after its setupAnim, and again for a layer that poses its own copy, a stray's
 * clothes), and the whole body ({@link #body}: lean, twist, crouch and squash, about its feet).
 * <p>
 * Each move has a rest pose (vanilla's), a cocked one (the wind-up's end), the blow's, and for a miss a spent one; a
 * part goes from one to the next by the move's weights ({@link Beat}). Only fields vanilla sets each draw are moved,
 * or a part's own pose is put back first (a witch's arms), so nothing is left over into the next draw.
 */
public final class FoePoses {
    private FoePoses() {}

    /** Where a mob is in its move: how far into the cocked pose, the blow's, and the spent one after a miss. */
    static final class Beat {
        FoeMove move = FoeMove.NONE;
        FoeMove.Phase phase = FoeMove.Phase.IDLE;
        float cock, hit, spent, el, t;

        void clear() {
            move = FoeMove.NONE;
            phase = FoeMove.Phase.IDLE;
            cock = hit = spent = el = t = 0.0F;
        }

        /** How busy the body is with a move (walks and sways give way to it). */
        float busy() { return Math.max(cock, Math.max(hit, spent)); }
    }

    /** Drawing is on one thread: one beat at a time. */
    private static final Beat BEAT = new Beat();

    private static boolean on() {
        try {
            return ERClientConfig.INSTANCE.foePoses.get();
        } catch (IllegalStateException notLoaded) {
            return true;
        }
    }

    // ------------------------------------------------------------------------------------------------ timing

    static float ease(float x) {
        x = Mth.clamp(x, 0.0F, 1.0F);
        return x * x * (3.0F - 2.0F * x);
    }

    /** Fast out of the gate, settling at the end: a blow. */
    static float snap(float x) {
        x = 1.0F - Mth.clamp(x, 0.0F, 1.0F);
        return 1.0F - x * x * x;
    }

    /** A recovery longer than this (ticks) follows a miss. */
    private static int whiffAbove(FoeMove m) {
        return switch (m) {
            case SLAM -> (FoeRules.ZOMBIE.recover() + FoeRules.ZOMBIE.whiff()) / 2;
            case SWIPE -> (FoeRules.SKELETON.recover() + FoeRules.SKELETON.whiff()) / 2;
            case CHOP -> (FoeRules.VINDICATOR.recover() + FoeRules.VINDICATOR.whiff()) / 2;
            case CLAW -> (FoeRules.ENDERMAN.recover() + FoeRules.ENDERMAN.whiff()) / 2;
            case KICK -> (FoeRules.KICK.recover() + FoeRules.KICK.whiff()) / 2;
            case BITE -> (FoeRules.BITE.recover() + FoeRules.BITE.whiff()) / 2;
            case POUNCE, DROP -> 16;
            default -> Integer.MAX_VALUE;
        };
    }

    static Beat beat(LivingEntity e, float pt) {
        Beat b = BEAT;
        b.clear();
        FoeClient.State s = FoeClient.state(e.getId());
        if (s == null) return b;
        float el = FoeClient.elapsed(s, pt), len = s.ticks(), p = el / len, over = el - len;
        b.move = s.move();
        b.phase = s.phase();
        b.el = el;
        b.t = Mth.clamp(p, 0.0F, 1.0F);
        switch (s.phase()) {
            case WINDUP -> {
                // held a moment at its end if the blow is late in coming, then let go
                b.cock = over <= 0.0F ? ease(p) : over < 6.0F ? 1.0F : Math.max(0.0F, 1.0F - (over - 6.0F) / 4.0F);
            }
            case STRIKE -> {
                b.cock = 1.0F;
                b.hit = over <= 0.0F ? snap(p) : 1.0F;
                if (over > 10.0F) {
                    float f = Math.max(0.0F, 1.0F - (over - 10.0F) / 5.0F);
                    b.cock *= f;
                    b.hit *= f;
                }
            }
            case RECOVER -> {
                boolean whiff = s.ticks() > whiffAbove(s.move());
                // after a miss it stays down a while before it comes back
                float r = whiff ? 1.0F - ease((p - 0.35F) / 0.65F) : 1.0F - ease(p);
                b.hit = r;
                if (whiff) b.spent = r * Math.min(1.0F, el / 3.0F);
            }
            default -> {}
        }
        return b;
    }

    /** Its flinch as it is hit, sharp at once and gone in half a second (less while it powers through a blow). */
    private static float flinch(LivingEntity e, float pt, Beat b) {
        if (e.hurtTime <= 0 || e.hurtDuration <= 0) return 0.0F;
        float f = Mth.clamp((e.hurtTime - pt) / e.hurtDuration, 0.0F, 1.0F);
        f *= f;
        return b.phase == FoeMove.Phase.WINDUP || b.phase == FoeMove.Phase.STRIKE ? f * 0.4F : f;
    }

    /** How deep in its reel it is (eased out over its last few ticks), as the stagger's stars show. */
    private static float reel(LivingEntity e, float pt) {
        return Mth.clamp(RuneFxClient.reelLeft(e.getId(), pt) / 6.0F, 0.0F, 1.0F);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static float lerp(float w, float a, float b) { return a + (b - a) * w; }

    /** Turns {@code p} {@code w} of the way to the rotation given. */
    private static void rot(@Nullable ModelPart p, float w, float x, float y, float z) {
        if (p == null || w <= 0.0F) return;
        p.xRot = lerp(w, p.xRot, x);
        p.yRot = lerp(w, p.yRot, y);
        p.zRot = lerp(w, p.zRot, z);
    }

    @Nullable
    private static ModelPart child(@Nullable ModelPart root, String name) {
        return root != null && root.hasChild(name) ? root.getChild(name) : null;
    }

    // ------------------------------------------------------------------------------------------------ parts

    /** Poses the model's parts for the mob's move, flinch, reel and walk (after vanilla's setupAnim). */
    public static void parts(LivingEntity e, EntityModel<?> model, float pt) {
        FoeKind k = FoeKind.of(e);
        if (k == null || !on()) return;
        Beat b = beat(e, pt);
        float hurt = flinch(e, pt, b), reel = reel(e, pt), now = FoeClient.now(pt);
        float walk = Math.min(1.0F, e.walkAnimation.speed(pt)), pos = e.walkAnimation.position(pt);
        switch (k) {
            case ZOMBIE -> {
                if (model instanceof HumanoidModel<?> h) zombie(h, b, hurt, reel, walk, pos, now);
            }
            case SKELETON -> {
                if (model instanceof HumanoidModel<?> h) skeleton(h, e, b, hurt, reel, walk, now);
            }
            case ENDERMAN -> {
                if (model instanceof HumanoidModel<?> h) enderman(h, b, hurt, reel, now);
            }
            case SPIDER, CAVE_SPIDER -> {
                if (model instanceof HierarchicalModel<?> m) spider(m.root(), b, hurt, reel, now);
            }
            case CREEPER -> {
                if (model instanceof HierarchicalModel<?> m && e instanceof Creeper c) creeper(m.root(), c.getSwelling(pt), hurt, reel, now);
            }
            case WITCH -> {
                if (model instanceof HierarchicalModel<?> m) witch(m.root(), b, hurt, reel, now);
            }
            case VINDICATOR, PILLAGER -> {
                if (model instanceof IllagerModel<?> m) illager(m, e, b, hurt, reel, now);
            }
            default -> {}
        }
    }

    /**
     * Both arms {@code w} of the way to {@code x} (forward and up is negative), rolled {@code out} (the right arm's; the
     * left's mirrored). The roll is about the body's front-to-back line, after the arm is raised: below 0 swings a
     * raised arm out to the side and a hanging one in, above 0 the other way.
     */
    private static void arms(HumanoidModel<?> h, float w, float x, float out) {
        rot(h.rightArm, w, x, 0.0F, out);
        rot(h.leftArm, w, x, 0.0F, -out);
    }

    /** A flinch and a reel on a humanoid: the head snaps back and the arms jolt; reeling, the head hangs and the arms drop. */
    private static void humanoidHurt(HumanoidModel<?> h, float hurt, float reel, float now) {
        if (hurt > 0.0F) {
            h.head.xRot -= 0.3F * hurt;
            h.rightArm.xRot += 0.3F * hurt;
            h.leftArm.xRot += 0.3F * hurt;
            h.rightArm.zRot += 0.15F * hurt;
            h.leftArm.zRot -= 0.15F * hurt;
        }
        if (reel > 0.0F) {
            arms(h, reel, -0.12F, 0.12F);
            h.head.xRot = lerp(reel, h.head.xRot, 0.55F);
            h.head.zRot += Mth.sin(now * 0.23F) * 0.22F * reel;
        }
    }

    /**
     * A zombie's slam: both arms up over its head and back (its head up at its quarry, a tremor as it holds it), then
     * down together in front as it steps in; after a miss it stays hunched over its fists. Walking, it shambles: its
     * head lolls and its arms sway with its steps.
     */
    private static void zombie(HumanoidModel<?> h, Beat b, float hurt, float reel, float walk, float pos, float now) {
        h.head.zRot = 0.0F;
        if (b.move == FoeMove.SLAM) {
            float c = b.cock;
            // up over its head, a little back and apart
            arms(h, c, -3.4F, -0.3F);
            if (b.phase == FoeMove.Phase.WINDUP && b.t > 0.55F) {
                float tremor = Mth.sin(now * 2.3F) * 0.035F * c;
                h.rightArm.xRot += tremor;
                h.leftArm.xRot -= tremor;
            }
            h.head.xRot -= 0.2F * c;
            rot(h.rightLeg, c, 0.15F, 0.0F, 0.06F);
            rot(h.leftLeg, c, -0.1F, 0.0F, -0.06F);
            // down in front, fists together
            arms(h, b.hit, -0.55F, -0.08F);
            rot(h.rightLeg, b.hit, -0.45F, 0.0F, 0.02F);
            rot(h.leftLeg, b.hit, 0.3F, 0.0F, -0.02F);
            h.head.xRot += 0.15F * b.hit;
            arms(h, b.spent, -0.2F, 0.1F);
            h.head.xRot += 0.25F * b.spent;
        }
        float shamble = walk * (1.0F - b.busy());
        if (shamble > 0.0F) {
            float step = Mth.sin(pos * 0.6662F);
            h.head.zRot += step * 0.13F * shamble;
            h.rightArm.zRot += Mth.sin(pos * 0.6662F + 1.2F) * 0.07F * shamble;
            h.leftArm.zRot += Mth.sin(pos * 0.6662F + 1.2F) * 0.07F * shamble;
        }
        humanoidHurt(h, hurt, reel, now);
        h.hat.copyFrom(h.head);
    }

    /**
     * A skeleton: with nothing to shoot with, one arm drawn up and back and swept across; its kick (leg raised, then
     * shot out, arms out for balance); the last of its draw pulled further back before the arrow goes, and the recoil.
     * Stalking with its bow up, it takes shorter steps.
     */
    private static void skeleton(HumanoidModel<?> h, LivingEntity e, Beat b, float hurt, float reel, float walk, float now) {
        h.head.zRot = 0.0F;
        float c = b.cock;
        switch (b.move) {
            case SWIPE -> {
                // the arm out to its right and a little back at shoulder height, then swept across to its left
                rot(h.rightArm, c, -1.9F, 1.9F, 0.0F);
                rot(h.leftArm, c, -0.6F, 0.0F, -0.3F);
                rot(h.rightArm, b.hit, -1.45F, -0.6F, 0.0F);
                rot(h.leftArm, b.hit, -0.3F, 0.0F, -0.1F);
                rot(h.rightArm, b.spent, -0.9F, -0.7F, 0.0F);
            }
            case KICK -> {
                rot(h.rightLeg, c, -0.75F, 0.0F, 0.02F);
                rot(h.leftLeg, c, 0.1F, 0.0F, -0.02F);
                rot(h.rightArm, c, -0.35F, 0.0F, 0.55F);
                rot(h.leftArm, c, -0.35F, 0.0F, -0.55F);
                rot(h.rightLeg, b.hit, -1.55F, 0.0F, 0.02F);
                rot(h.leftLeg, b.hit, 0.2F, 0.0F, -0.02F);
                rot(h.rightArm, b.hit, -0.2F, 0.0F, 0.7F);
                rot(h.leftArm, b.hit, -0.2F, 0.0F, -0.7F);
            }
            case SHOT -> {
                // the string pulled the last bit back; after the arrow goes, the bow kicks up and the hand flies back
                h.leftArm.yRot += 0.16F * c;
                h.rightArm.yRot -= 0.05F * c;
                h.rightArm.xRot -= 0.3F * b.hit;
                h.leftArm.xRot -= 0.25F * b.hit;
                h.leftArm.yRot += 0.3F * b.hit;
            }
            default -> {}
        }
        float stalk = stalking(e, walk) * (1.0F - b.busy());
        if (stalk > 0.0F) {
            h.rightLeg.xRot *= 1.0F - 0.3F * stalk;
            h.leftLeg.xRot *= 1.0F - 0.3F * stalk;
            // the body leans in (see body): the head keeps its eyes on its quarry
            h.head.xRot -= 0.11F * stalk;
        }
        humanoidHurt(h, hurt, reel, now);
        h.hat.copyFrom(h.head);
    }

    /** A skeleton walking with its bow up at a quarry: how much it stalks. */
    private static float stalking(LivingEntity e, float walk) {
        return e instanceof Mob m && m.isAggressive() && e.isHolding(is -> is.getItem() instanceof BowItem) ? walk : 0.0F;
    }

    /** An enderman's blow: both long arms raised high over it and apart, then brought down together; hunched after a miss. */
    private static void enderman(HumanoidModel<?> h, Beat b, float hurt, float reel, float now) {
        h.head.zRot = 0.0F;
        if (b.move == FoeMove.CLAW) {
            float c = b.cock;
            arms(h, c, -3.3F, -0.35F);
            h.head.xRot -= 0.25F * c;
            arms(h, b.hit, -0.75F, -0.08F);
            arms(h, b.spent, -0.35F, 0.12F);
            h.head.xRot += 0.2F * b.spent;
        }
        humanoidHurt(h, hurt, reel, now);
        h.hat.copyFrom(h.head);
    }

    /** A vindicator's chop (its axe raised over its head, then down), and a pillager steadying its crossbow before the bolt. */
    private static void illager(IllagerModel<?> m, LivingEntity e, Beat b, float hurt, float reel, float now) {
        HumanoidArm side = e.getMainArm();
        // its arms are private to the model: found by name, as the model finds them
        ModelPart root = m.root();
        if (!root.hasChild("right_arm") || !root.hasChild("left_arm")) return;
        ModelPart main = root.getChild(side == HumanoidArm.RIGHT ? "right_arm" : "left_arm");
        ModelPart off = root.getChild(side == HumanoidArm.RIGHT ? "left_arm" : "right_arm");
        float s = side == HumanoidArm.RIGHT ? 1.0F : -1.0F, c = b.cock;
        if (b.move == FoeMove.CHOP) {
            // the axe up over its head and a little out (a raised arm rolls out the other way from a hanging one)
            rot(main, c, -3.25F, 0.0F, -0.1F * s);
            rot(off, c, -0.9F, 0.0F, -0.2F * s);
            rot(main, b.hit, -0.55F, 0.15F * s, 0.05F * s);
            rot(off, b.hit, -0.3F, 0.0F, -0.1F * s);
            rot(main, b.spent, -0.3F, 0.2F * s, 0.05F * s);
        } else if (b.move == FoeMove.BOLT) {
            m.getHead().xRot += 0.06F * c;
        }
        ModelPart head = m.getHead();
        if (hurt > 0.0F) head.xRot -= 0.3F * hurt;
        if (reel > 0.0F) {
            head.xRot = lerp(reel, head.xRot, 0.5F);
            rot(main, reel, -0.1F, 0.0F, 0.1F * s);
            rot(off, reel, -0.1F, 0.0F, -0.1F * s);
        }
    }

    /**
     * A witch's throw: its arms (vanilla's crossed pair) raised and drawn back over its shoulder, then flung forward. The
     * arms keep no pose of their own in vanilla, so theirs is put back first each draw.
     */
    private static void witch(ModelPart root, Beat b, float hurt, float reel, float now) {
        ModelPart arms = child(root, "arms"), head = child(root, "head");
        if (arms == null) return;
        arms.resetPose();
        if (b.move == FoeMove.THROW) {
            float c = b.cock;
            rot(arms, c, -2.3F, 0.5F, -0.2F);
            arms.y = lerp(c, arms.y, 1.0F);
            rot(arms, b.hit, -0.6F, -0.35F, 0.1F);
            arms.y = lerp(b.hit, arms.y, 3.0F);
        }
        if (head != null) {
            if (hurt > 0.0F) head.xRot -= 0.3F * hurt;
            if (reel > 0.0F) head.xRot = lerp(reel, head.xRot, 0.5F);
        }
        if (reel > 0.0F) arms.xRot = lerp(reel, arms.xRot, -0.35F);
    }

    /**
     * A spider: crouched to pounce, its front legs up and trembling and its hind legs spread to push; in the air, front
     * legs reaching and hind legs trailing; sprawled after a miss. Its bite: front raised and legs up, then down on its
     * quarry. Dropping from above, it peers over the edge, legs gathered under it.
     */
    private static void spider(ModelPart root, Beat b, float hurt, float reel, float now) {
        ModelPart head = child(root, "head");
        ModelPart rf = child(root, "right_front_leg"), lf = child(root, "left_front_leg");
        ModelPart rmf = child(root, "right_middle_front_leg"), lmf = child(root, "left_middle_front_leg");
        ModelPart rmh = child(root, "right_middle_hind_leg"), lmh = child(root, "left_middle_hind_leg");
        ModelPart rh = child(root, "right_hind_leg"), lh = child(root, "left_hind_leg");
        if (head == null || rf == null || lf == null || rmf == null || lmf == null || rmh == null || lmh == null || rh == null || lh == null) return;
        float c = b.cock;
        switch (b.move) {
            case POUNCE -> {
                legs(rf, lf, c, 0.25F, -1.05F);
                legs(rmf, lmf, c, -0.35F, -0.45F);
                legs(rmh, lmh, c, -0.5F, 0.45F);
                legs(rh, lh, c, -0.55F, 0.95F);
                head.xRot -= 0.35F * c;
                if (b.phase == FoeMove.Phase.WINDUP && b.t > 0.4F) {
                    float tremor = Mth.sin(now * 2.4F) * 0.07F * c;
                    rf.zRot += tremor;
                    lf.zRot -= tremor;
                }
                flight(rf, lf, rmf, lmf, rmh, lmh, rh, lh, b.hit);
                sprawl(rf, lf, rmf, lmf, rmh, lmh, rh, lh, b.spent);
            }
            case DROP -> {
                legs(rf, lf, c, -1.0F, -0.9F);
                legs(rmf, lmf, c, -0.9F, -0.4F);
                legs(rmh, lmh, c, -0.9F, 0.4F);
                legs(rh, lh, c, -0.9F, 0.8F);
                head.xRot += 0.35F * c;
                flight(rf, lf, rmf, lmf, rmh, lmh, rh, lh, b.hit);
                sprawl(rf, lf, rmf, lmf, rmh, lmh, rh, lh, b.spent);
            }
            case BITE -> {
                legs(rf, lf, c, 0.15F, -1.0F);
                head.xRot -= 0.45F * c;
                legs(rf, lf, b.hit, -1.0F, -1.15F);
                head.xRot += 0.4F * b.hit;
                legs(rf, lf, b.spent, -0.9F, -1.0F);
            }
            default -> {}
        }
        if (hurt > 0.0F) {
            // legs snatched in under it
            droop(rf, lf, rmf, lmf, rmh, lmh, rh, lh, 0.25F * hurt);
            head.xRot -= 0.2F * hurt;
        }
        if (reel > 0.0F) {
            droop(rf, lf, rmf, lmf, rmh, lmh, rh, lh, 0.35F * reel);
            head.xRot = lerp(reel, head.xRot, 0.4F);
        }
    }

    /**
     * A pair of spider legs {@code w} of the way to a droop of {@code z} (the right leg's; above 0 raises its tip) and a
     * swing of {@code y} (the right leg's; below 0 forward), the left leg mirrored.
     */
    private static void legs(ModelPart right, ModelPart left, float w, float z, float y) {
        if (w <= 0.0F) return;
        right.zRot = lerp(w, right.zRot, z);
        right.yRot = lerp(w, right.yRot, y);
        left.zRot = lerp(w, left.zRot, -z);
        left.yRot = lerp(w, left.yRot, -y);
    }

    /** In the air: front legs reaching forward, the rest swept back. */
    private static void flight(ModelPart rf, ModelPart lf, ModelPart rmf, ModelPart lmf, ModelPart rmh, ModelPart lmh, ModelPart rh, ModelPart lh, float w) {
        legs(rf, lf, w, -0.05F, -1.35F);
        legs(rmf, lmf, w, -0.3F, -0.75F);
        legs(rmh, lmh, w, -0.35F, 0.55F);
        legs(rh, lh, w, -0.3F, 1.25F);
    }

    /** Flat on the ground after a miss, every leg splayed. */
    private static void sprawl(ModelPart rf, ModelPart lf, ModelPart rmf, ModelPart lmf, ModelPart rmh, ModelPart lmh, ModelPart rh, ModelPart lh, float w) {
        if (w <= 0.0F) return;
        for (ModelPart r : new ModelPart[]{rf, rmf, rmh, rh}) r.zRot = lerp(w, r.zRot, -0.2F);
        for (ModelPart l : new ModelPart[]{lf, lmf, lmh, lh}) l.zRot = lerp(w, l.zRot, 0.2F);
    }

    /** Every leg's tip pulled down by {@code d} (radians). */
    private static void droop(ModelPart rf, ModelPart lf, ModelPart rmf, ModelPart lmf, ModelPart rmh, ModelPart lmh, ModelPart rh, ModelPart lh, float d) {
        rf.zRot -= d;
        rmf.zRot -= d;
        rmh.zRot -= d;
        rh.zRot -= d;
        lf.zRot += d;
        lmf.zRot += d;
        lmh.zRot += d;
        lh.zRot += d;
    }

    /**
     * A creeper as it swells: its legs splay out front and back as it crouches (its feet stay on the ground, see body),
     * its head dips and twitches.
     */
    private static void creeper(ModelPart root, float swell, float hurt, float reel, float now) {
        ModelPart head = child(root, "head");
        ModelPart rf = child(root, "right_front_leg"), lf = child(root, "left_front_leg");
        ModelPart rh = child(root, "right_hind_leg"), lh = child(root, "left_hind_leg");
        float s = ease(swell);
        if (s > 0.0F && rf != null && lf != null && rh != null && lh != null) {
            rf.xRot = lerp(s, rf.xRot, -CREEPER_SPLAY);
            lf.xRot = lerp(s, lf.xRot, -CREEPER_SPLAY);
            rh.xRot = lerp(s, rh.xRot, CREEPER_SPLAY);
            lh.xRot = lerp(s, lh.xRot, CREEPER_SPLAY);
        }
        if (head != null) {
            head.xRot += 0.28F * s;
            head.yRot += Mth.sin(now * 1.9F) * 0.06F * s;
            if (hurt > 0.0F) head.xRot -= 0.3F * hurt;
            if (reel > 0.0F) head.xRot = lerp(reel, head.xRot, 0.5F);
        }
    }

    /** How far a swelling creeper's legs splay (radians, front legs forward and hind legs back). */
    private static final float CREEPER_SPLAY = 0.75F;

    // ------------------------------------------------------------------------------------------------ the whole body

    /**
     * The whole body (after its turn to face its way, about its feet): leaning into a blow or back from it, twisting,
     * crouching, swaying as it reels or shambles, a slime squashing. Its front is towards -z here.
     */
    public static void body(LivingEntity e, PoseStack ps, float pt) {
        FoeKind k = FoeKind.of(e);
        if (k == null || !on()) return;
        Beat b = beat(e, pt);
        float hurt = flinch(e, pt, b), reel = reel(e, pt), now = FoeClient.now(pt);
        float walk = Math.min(1.0F, e.walkAnimation.speed(pt)), pos = e.walkAnimation.position(pt);
        float c = b.cock, hi = b.hit, sp = b.spent;
        // lean: forward (degrees, front down); twist: to its right; roll: to its left; lift: up (blocks)
        float lean = 0.0F, twist = 0.0F, roll = 0.0F, lift = 0.0F, wide = 1.0F, tall = 1.0F;
        switch (b.move) {
            case SLAM -> {
                lean = lerp(c, lean, -9.0F);
                lean = lerp(hi, lean, 18.0F);
                lean = lerp(sp, lean, 28.0F);
            }
            case CLAW -> {
                lean = lerp(c, lean, -9.0F);
                lean = lerp(hi, lean, 16.0F);
                lean = lerp(sp, lean, 22.0F);
            }
            case CHOP -> {
                lean = lerp(c, lean, -6.0F);
                lean = lerp(hi, lean, 14.0F);
                lean = lerp(sp, lean, 18.0F);
            }
            case SWIPE -> {
                twist = lerp(c, twist, 22.0F);
                twist = lerp(hi, twist, -18.0F);
                lean = lerp(c, lean, -4.0F);
                lean = lerp(hi, lean, 8.0F);
                lean = lerp(sp, lean, 12.0F);
            }
            case KICK -> {
                lean = lerp(c, lean, -5.0F);
                lean = lerp(hi, lean, -14.0F);
            }
            case SHOT -> lean = -3.0F * c - 3.0F * hi;
            case BOLT -> lean = -3.0F * c;
            case THROW -> {
                twist = lerp(c, twist, 16.0F);
                twist = lerp(hi, twist, -12.0F);
                lean = lerp(c, lean, -7.0F);
                lean = lerp(hi, lean, 9.0F);
            }
            case POUNCE -> {
                // front up off the ground, crouched; front down as it flies; flat after a miss
                lean = lerp(c, lean, -12.0F);
                lift = lerp(c, lift, -0.1F);
                lean = lerp(hi, lean, 6.0F);
                lift = lerp(hi, lift, 0.0F);
                lean = lerp(sp, lean, 4.0F);
                lift = lerp(sp, lift, -0.12F);
            }
            case DROP -> {
                lean = lerp(c, lean, 10.0F);
                lift = lerp(c, lift, -0.06F);
                lean = lerp(hi, lean, 14.0F);
                lift = lerp(hi, lift, 0.0F);
                lift = lerp(sp, lift, -0.12F);
            }
            case BITE -> {
                lean = lerp(c, lean, -10.0F);
                lean = lerp(hi, lean, 7.0F);
            }
            case SQUASH -> {
                // squashed down to spring; stretched as it leaves the ground; squashed again as it lands
                if (b.phase == FoeMove.Phase.WINDUP) {
                    tall = lerp(c, 1.0F, 0.62F);
                    wide = lerp(c, 1.0F, 1.28F) + Mth.sin(now * 3.0F) * 0.02F * c * b.t;
                } else if (b.phase == FoeMove.Phase.STRIKE) {
                    float st = b.el < 3.0F ? snap(b.el / 3.0F) : Math.max(0.15F, 1.0F - (b.el - 3.0F) / 8.0F);
                    tall = lerp(st, 1.0F, 1.3F);
                    wide = lerp(st, 1.0F, 0.85F);
                } else if (b.phase == FoeMove.Phase.RECOVER) {
                    tall = 1.0F - 0.35F * hi;
                    wide = 1.0F + 0.3F * hi;
                }
            }
            default -> {}
        }
        switch (k) {
            case ZOMBIE -> {
                float shamble = walk * (1.0F - b.busy());
                roll += Mth.sin(pos * 0.6662F) * 3.5F * shamble;
                lean += 4.0F * shamble;
            }
            case SKELETON -> {
                float stalk = stalking(e, walk) * (1.0F - b.busy());
                lean += 6.0F * stalk;
                lift -= 0.04F * stalk;
            }
            case CREEPER -> {
                if (e instanceof Creeper cr) {
                    float s = ease(cr.getSwelling(pt));
                    // its feet stay where they were as its legs splay (6 pixel legs)
                    lift -= 6.0F * (1.0F - Mth.cos(CREEPER_SPLAY * s)) / 16.0F;
                    roll += Mth.sin(now * 9.1F) * 1.6F * s * s;
                    lean += Mth.sin(now * 7.3F) * 1.1F * s * s;
                }
            }
            case SLIME -> tall *= 1.0F - 0.1F * hurt;
            default -> {}
        }
        // a flinch rocks it back; reeling it slumps and sways
        lean -= 7.0F * hurt;
        if (reel > 0.0F) {
            lean += 9.0F * reel;
            roll += Mth.sin(now * 0.22F) * 7.0F * reel;
        }
        if (lift != 0.0F) ps.translate(0.0F, lift, 0.0F);
        if (twist != 0.0F) ps.mulPose(Axis.YP.rotationDegrees(-twist));
        if (lean != 0.0F) ps.mulPose(Axis.XP.rotationDegrees(-lean));
        if (roll != 0.0F) ps.mulPose(Axis.ZP.rotationDegrees(roll));
        if (wide != 1.0F || tall != 1.0F) ps.scale(wide, tall, wide);
    }
}
