package com.explorationremix.client.combat;

import com.explorationremix.client.combat.SwingAnim.Body;
import com.explorationremix.combat.swing.SwingKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Players' swings seen from outside (other players, and your own in third person): the swinging arm, the body's twist
 * and lean, set on the player's model after vanilla has posed it (HumanoidModelMixin), easing back into vanilla's pose
 * as the swing returns to rest. Armour copies the pose, as it always does.
 */
public final class SwingPoses {
    private SwingPoses() {}

    /** After vanilla's own setupAnim on {@code m} for {@code e}. */
    public static void apply(HumanoidModel<?> m, LivingEntity e) {
        if (!(e instanceof Player p) || e.isPassenger() || e.isFallFlying() || e.isSwimming()) return;
        SwingClient.Swing s = SwingClient.swing(p.getId());
        if (s == null) return;
        float now = SwingClient.now(Minecraft.getInstance().getFrameTime());
        if (s.over(now)) return;
        Body b;
        float w;
        if (s.cancelAt >= 0.0F) {
            b = bodyAt(s, s.cancelAt - s.start);
            w = 1.0F - SwingAnim.smooth(Mth.clamp((now - s.cancelAt) / SwingClient.CANCEL_EASE, 0.0F, 1.0F));
        } else {
            float tau = Math.max(0.0F, now - s.start);
            b = bodyAt(s, tau);
            w = SwingAnim.bodyWeight(tau, s.land(), s.hold(), s.kind == SwingKind.STRONG);
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
     * How far (radians, about the arm's own side axis) the held blade turns in the fist of {@code e}'s {@code arm}, its
     * arm raised {@code armX} (the model's turn): during a timed swing the blade comes round towards the line of the
     * arm (all the way for a thrust, so the point drives forward; most of the way for a cut, which then reaches out
     * along its sweep; less for the chop, which falls point first), easing back as the swing returns to rest. Zero
     * for anything else.
     */
    public static float gripTurn(LivingEntity e, HumanoidArm arm, float armX) {
        if (!(e instanceof Player p) || arm != p.getMainArm() || e.isPassenger() || e.isFallFlying() || e.isSwimming()) return 0.0F;
        SwingClient.Swing s = SwingClient.swing(p.getId());
        if (s == null) return 0.0F;
        float now = SwingClient.now(Minecraft.getInstance().getFrameTime());
        if (s.over(now)) return 0.0F;
        float w;
        if (s.cancelAt >= 0.0F) {
            w = 1.0F - SwingAnim.smooth(Mth.clamp((now - s.cancelAt) / SwingClient.CANCEL_EASE, 0.0F, 1.0F));
        } else {
            w = SwingAnim.bodyWeight(Math.max(0.0F, now - s.start), s.land(), s.hold(), s.kind == SwingKind.STRONG);
        }
        if (w <= 0.0F) return 0.0F;
        float k = s.kind == SwingKind.STRONG ? 1.0F : s.combo >= s.weapon.chain() - 1 ? 0.55F : 0.75F;
        return w * k * armX;
    }

    /**
     * The body's pose {@code tau} ticks into the swing. A light wind-up that became a strong one leaves from where it
     * had got to, as the hand does in first person.
     */
    static Body bodyAt(SwingClient.Swing s, float tau) {
        boolean strong = s.kind == SwingKind.STRONG;
        SwingAnim.Keys k = s.keys();
        float land = s.land();
        if (strong && s.upgradeAt >= 0.0F && tau < land - SwingAnim.THRUST_LEAD) {
            SwingAnim.Keys light = SwingAnim.keys(SwingKind.LIGHT, s.lightCombo, s.weapon);
            Body mid = SwingAnim.body(light, Math.min(tau, s.upgradeAt), s.weapon.lightWindup(), s.weapon.lightRecover(), false);
            if (tau <= s.upgradeAt) return mid;
            float k2 = Mth.clamp((tau - s.upgradeAt) / Math.max(0.5F, land - SwingAnim.THRUST_LEAD - s.upgradeAt), 0.0F, 1.0F);
            return mid.lerp(k.bodyWindup(), SwingAnim.easeOut(k2));
        }
        return SwingAnim.body(k, tau, land, s.hold(), strong);
    }
}
