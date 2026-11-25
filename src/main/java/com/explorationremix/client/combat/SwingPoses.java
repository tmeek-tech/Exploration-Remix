package com.explorationremix.client.combat;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.client.combat.SwingAnim.Body;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;

/**
 * Players' swings seen from outside (other players, and your own in third person): the swinging arm, the body's twist
 * and lean, set on the player's model after vanilla has posed it (HumanoidModelMixin), easing back into vanilla's pose
 * as the swing returns to rest. A sword held up in guard is held across the body as a shield is, flicked out by a
 * parry. Armour copies the pose, as it always does.
 */
public final class SwingPoses {
    private SwingPoses() {}

    /** After vanilla's own setupAnim on {@code m} for {@code e}. */
    public static void apply(HumanoidModel<?> m, LivingEntity e) {
        if (!(e instanceof Player p) || e.isPassenger() || e.isFallFlying() || e.isSwimming()) return;
        float now = SwingClient.now(Minecraft.getInstance().getFrameTime());
        if (GuardClient.guarding(p)) {
            guard(m, p, now);
            return;
        }
        SwingClient.Swing s = SwingClient.swing(p.getId());
        if (s == null) return;
        if (s.over(now)) return;
        Body b;
        float w;
        if (s.cancelAt >= 0.0F) {
            b = bodyAt(s, s.cancelAt - s.start);
            w = 1.0F - SwingAnim.smooth(Mth.clamp((now - s.cancelAt) / SwingClient.CANCEL_EASE, 0.0F, 1.0F));
        } else {
            float tau = Math.max(0.0F, now - s.start);
            b = bodyAt(s, tau);
            w = SwingAnim.bodyWeight(s.shape(), tau);
        }
        if (w <= 0.0F) return;
        boolean right = p.getMainArm() == HumanoidArm.RIGHT;
        float side = right ? 1.0F : -1.0F;
        ModelPart arm = right ? m.rightArm : m.leftArm;

        m.body.yRot = Mth.lerp(w, m.body.yRot, b.twist() * side);
        m.body.xRot += w * b.lean();
        // the shoulders turn with the body, as in vanilla's own swing
        float by = m.body.yRot;
        m.rightArm.z = Mth.sin(by) * 5.0F;
        m.rightArm.x = -Mth.cos(by) * 5.0F;
        m.leftArm.z = -Mth.sin(by) * 5.0F;
        m.leftArm.x = Mth.cos(by) * 5.0F;
        arm.xRot = Mth.lerp(w, arm.xRot, b.armX());
        arm.yRot = Mth.lerp(w, arm.yRot, b.armY() * side + by);
        arm.zRot = Mth.lerp(w, arm.zRot, b.armZ() * side);
        arm.z += w * b.armForward();
        // the other arm follows the body round
        ModelPart other = right ? m.leftArm : m.rightArm;
        other.yRot += w * by;
    }

    /**
     * A sword up in guard: the arm raised and brought in across the body (still swinging a little as they walk), the
     * sword shoulder turned a touch forward; a parry flicks the arm up and out for a moment, a blow knocks it down and
     * in. The body itself squares up to where they look meanwhile (GuardClient).
     */
    private static void guard(HumanoidModel<?> m, Player p, float now) {
        boolean right = p.getMainArm() == HumanoidArm.RIGHT;
        float side = right ? 1.0F : -1.0F;
        ModelPart arm = right ? m.rightArm : m.leftArm;
        float up = GuardClient.raised(p, now);
        if (up <= 0.0F) return;
        float[] d = devGuard;
        Body b = d != null ? new Body(d[0], d[1], d[2], 0.0F, d[4], 0.0F) : SwingAnim.GUARD_BODY;
        float flick = GuardClient.flick(p, now);
        m.body.yRot = Mth.lerp(up, m.body.yRot, b.twist() * side);
        float by = m.body.yRot;
        m.rightArm.z = Mth.sin(by) * 5.0F;
        m.rightArm.x = -Mth.cos(by) * 5.0F;
        m.leftArm.z = -Mth.sin(by) * 5.0F;
        m.leftArm.x = Mth.cos(by) * 5.0F;
        float armX = (d != null ? 0.0F : arm.xRot * 0.3F) + b.armX() - 0.55F * flick;
        arm.xRot = Mth.lerp(up, arm.xRot, armX);
        arm.yRot = Mth.lerp(up, arm.yRot, (b.armY() + 0.45F * flick) * side + by);
        arm.zRot = Mth.lerp(up, arm.zRot, b.armZ() * side);
        ModelPart other = right ? m.leftArm : m.rightArm;
        other.yRot += up * by;
        if (devLogSet) {
            devLogSet = false;
            ExplorationRemix.LOGGER.info("[pose] guard set: up {} flick {} arm x {} y {} z {} at {} {} {}, body y {}", up, flick, arm.xRot,
                    arm.yRot, arm.zRot, arm.x, arm.y, arm.z, m.body.yRot);
        }
    }

    // ------------------------------------------------------------------------------------------------ dev pictures

    /** The guard's pose seen from outside set outright (arm x, y, z, grip turn, body twist), or null. */
    @Nullable private static float[] devGuard;
    /** Whether to write the next guard pose set, and the next arm drawn with its item, to the log. */
    private static boolean devLogSet, devLogDrawn;

    public static void devGuard(@Nullable float[] pose) { devGuard = pose; }

    public static void devLog() { devLogSet = devLogDrawn = true; }

    /** The arm as the held item is drawn on it (ItemInHandLayerMixin), once after {@link #devLog}. */
    public static void devDrawn(ModelPart arm, float turn) {
        if (!devLogDrawn) return;
        devLogDrawn = false;
        ExplorationRemix.LOGGER.info("[pose] drawn: arm x {} y {} z {} at {} {} {}, grip turn {}", arm.xRot, arm.yRot, arm.zRot, arm.x,
                arm.y, arm.z, turn);
    }

    /**
     * How far (radians, about the arm's own side axis) the held blade turns in the fist of {@code e}'s {@code arm}, its
     * arm raised {@code armX} (the model's turn): during a timed swing the blade comes round towards the line of the
     * arm (all the way for a thrust, so the point drives forward; most of the way for a cut, which then reaches out
     * along its sweep; less for the chop, which falls point first), easing back as the swing returns to rest. Zero
     * for anything else.
     */
    public static float gripTurn(LivingEntity e, HumanoidArm arm, float armX) {
        if (!(e instanceof Player p) || arm != p.getMainArm() || e.isPassenger() || e.isFallFlying() || e.isSwimming()) return 0.0F;
        float now = SwingClient.now(Minecraft.getInstance().getFrameTime());
        if (GuardClient.guarding(p)) {
            // in guard the blade rises from the fist past the other shoulder, as it sits in the hand
            return GuardClient.raised(p, now) * (devGuard != null ? devGuard[3] : GUARD_GRIP);
        }
        SwingClient.Swing s = SwingClient.swing(p.getId());
        if (s == null) return 0.0F;
        if (s.over(now)) return 0.0F;
        float w;
        if (s.cancelAt >= 0.0F) {
            w = 1.0F - SwingAnim.smooth(Mth.clamp((now - s.cancelAt) / SwingClient.CANCEL_EASE, 0.0F, 1.0F));
        } else {
            w = SwingAnim.bodyWeight(s.shape(), Math.max(0.0F, now - s.start));
        }
        if (w <= 0.0F) return 0.0F;
        float k = switch (s.kind) {
            case STRONG -> 1.0F;
            // the smash falls point first, as the chop does
            case JUMP -> 0.6F;
            default -> s.combo >= s.weapon.chain() - 1 ? 0.55F : 0.75F;
        };
        return w * k * armX;
    }

    /** How far the blade turns in the fist in guard (radians, the same way as a swing's turn): held as it sits. */
    static final float GUARD_GRIP = 0.0F;

    /**
     * The body's pose {@code tau} ticks into the swing. A light wind-up that became a strong one leaves from where it
     * had got to, as the hand does in first person.
     */
    static Body bodyAt(SwingClient.Swing s, float tau) { return SwingAnim.body(s.shape(), tau); }
}
