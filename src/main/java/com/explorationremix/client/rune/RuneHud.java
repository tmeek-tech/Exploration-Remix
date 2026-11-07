package com.explorationremix.client.rune;

import com.explorationremix.rune.RunDepth;
import com.explorationremix.rune.RuneConfig;
import com.explorationremix.rune.RuneGear;
import com.explorationremix.rune.engine.CastMachine;
import com.explorationremix.rune.engine.CastReading;
import com.explorationremix.rune.engine.CastState;
import com.explorationremix.rune.engine.HostSpec;
import com.explorationremix.rune.engine.Reading;
import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.gui.overlay.ForgeGui;

/**
 * The runes on the HUD, while the gear in hand carries runes: its energy bar, and under the bar's top a strip of one
 * small square per cast (each in its cast's colour, red when it can never fire). The cast the gear fires next is
 * outlined bright, and pulses amber while there is too little energy for it, with a tick on the bar where it becomes
 * affordable. While the gear waits between casts a thin line sweeps across.
 *
 * <p>It sits beside the hotbar, vertically inside the hotbar's band, on the main hand's side: vanilla draws the off
 * hand's slot on the other side, and nothing on this one except the attack indicator when that is set to show by the
 * hotbar (then this sits past it). The hearts, armour, food, air and XP rows are all above the hotbar, between its
 * ends, so nothing here covers them.
 */
final class RuneHud {
    /** The energy bar (and the whole group's width), and the cast squares. */
    private static final int BAR_W = 45, BAR_H = 4, SQ = 5, STEP = 8;
    /** Space between the hotbar's end and the group; more when the attack indicator stands there. */
    private static final int GAP = 6, GAP_PAST_INDICATOR = 30;
    private static final int BACKING = 0x60000000, BAR_BACK = 0xE0101820, BAR_TOP = 0xFFE6FFFF, BAR_BOTTOM = 0xFF38CFF2;
    private static final int OUTLINE = 0xFFFFFFFF, SWEEP = 0xC0FFFFFF;
    /** The most ticks a wait set by a cast can last (delays and recovery are capped well below this). */
    private static final int LONGEST_WAIT = 400;

    private RuneHud() {}

    /** The overlay, drawn just after the hotbar. */
    static void render(ForgeGui gui, GuiGraphics g, float partialTick, int width, int height) {
        Minecraft mc = gui.getMinecraft();
        if (mc.options.hideGui || mc.screen != null || mc.level == null) return;
        LocalPlayer p = mc.player;
        if (p == null || p.isSpectator()) return;
        ItemStack gear = RuneLook.heldRuneGear(p);
        if (gear == null) return;
        HostSpec spec = RuneGear.spec(gear);
        Reading r = RuneGear.reading(gear, RunDepth.clientDepth());
        if (spec == null || r == null) return;

        long now = mc.level.getGameTime();
        CastState s = RuneGear.state(gear, spec, now);
        float energy = RuneLook.energy(s, spec, now, partialTick);
        float max = Math.max(1.0E-3F, spec.maxEnergy());
        float t = now + partialTick;

        // ---- where: beside the hotbar on the main hand's side, inside the hotbar's 22 px band
        int cx = width / 2;
        int gap = mc.options.attackIndicator().get() == AttackIndicatorStatus.HOTBAR ? GAP_PAST_INDICATOR : GAP;
        int x0 = p.getMainArm() == HumanoidArm.RIGHT ? cx + 91 + gap : cx - 91 - gap - BAR_W;
        x0 = Mth.clamp(x0, 3, Math.max(3, width - BAR_W - 3));
        int squareY = height - 18;            // the squares: height - 18 .. height - 14
        int sweepY = squareY + SQ + 2;        // the sweep: height - 11
        int barY = height - 9;                // the bar: height - 9 .. height - 6

        // a faint backing, so it reads over snow and sky alike
        backing(g, x0 - 2, squareY - 3, x0 + BAR_W + 2, barY + BAR_H + 2);

        // ---- the cast strip
        int n = r.casts().size();
        int next = CastMachine.next(s, r, now, RuneConfig.limits());
        int stripX = x0 + (BAR_W - (n * STEP - (STEP - SQ))) / 2;
        for (int k = 0; k < n; k++) {
            CastReading c = r.casts().get(k);
            int qx = stripX + k * STEP;
            int colour = RuneLook.castColour(c);
            if (k == next) {
                g.fill(qx - 1, squareY - 1, qx + SQ + 1, squareY + SQ + 1, OUTLINE);
                if (c.cost() > energy + 1.0E-3F) colour = RuneLook.mix(RuneLook.AMBER_DIM, RuneLook.AMBER, 0.5F + 0.5F * Mth.sin(t * 0.35F));
                g.fill(qx, squareY, qx + SQ, squareY + SQ, colour);
            } else {
                g.fill(qx, squareY, qx + SQ, squareY + SQ, RuneLook.withAlpha(colour, 0xB4));
            }
        }

        // ---- the wait between casts
        if (now < s.readyAt) {
            int total = waitTotal(s, r, now);
            float done = Mth.clamp(CastMachine.readiness(s, now, total) + partialTick / total, 0.0F, 1.0F);
            int w = Math.round(BAR_W * done);
            if (w > 0) g.fill(x0, sweepY, x0 + w, sweepY + 1, SWEEP);
        }

        // ---- the energy bar
        g.fill(x0, barY, x0 + BAR_W, barY + BAR_H, BAR_BACK);
        int inner = BAR_W - 2;
        int fill = Math.round(inner * Mth.clamp(energy / max, 0.0F, 1.0F));
        if (fill > 0) g.fillGradient(x0 + 1, barY + 1, x0 + 1 + fill, barY + BAR_H - 1, BAR_TOP, BAR_BOTTOM);
        if (next >= 0) {
            int cost = r.casts().get(next).cost();
            if (cost > energy + 1.0E-3F && cost <= max) {
                int tx = x0 + 1 + Math.min(inner - 1, Math.round(inner * cost / max));
                g.fill(tx, barY, tx + 1, barY + BAR_H, RuneLook.AMBER);
            }
        }
    }

    /**
     * How long the current wait lasts in all. A wait a cast set ends {@code readyAt - lastCast} after it; any other
     * (the gear just taken in hand) is taken as the longer of what is left, the row's longest cast delay and its
     * recovery.
     */
    private static int waitTotal(CastState s, Reading r, long now) {
        long sinceCast = s.readyAt - s.lastCast;
        if (sinceCast > 0 && sinceCast <= LONGEST_WAIT) return (int) sinceCast;
        int longest = r.recovery();
        for (CastReading c : r.casts()) longest = Math.max(longest, c.delay());
        return Math.max(1, Math.max(longest, (int) Math.min(LONGEST_WAIT, s.readyAt - now)));
    }

    /** A translucent box with its corner pixels cut. */
    private static void backing(GuiGraphics g, int x0, int y0, int x1, int y1) {
        g.fill(x0 + 1, y0, x1 - 1, y1, BACKING);
        g.fill(x0, y0 + 1, x0 + 1, y1 - 1, BACKING);
        g.fill(x1 - 1, y0 + 1, x1, y1 - 1, BACKING);
    }
}
