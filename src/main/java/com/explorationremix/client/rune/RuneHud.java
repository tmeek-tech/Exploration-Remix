package com.explorationremix.client.rune;

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
 * The runes on the HUD, while the weapon in hand has a cast: its energy bar, and above it one small square for the cast
 * (light blue, paler once the active rune's second phase is awake, with a small "II" beside it then; red when it can
 * never fire). The square is outlined bright when the cast is ready, and pulses slate to lavender while there is too
 * little energy for it, with a tick on the bar where it becomes affordable. While the weapon waits between casts a thin
 * line sweeps across. A small mark on the bar's bottom edge shows the floor its energy settles back to once a fight goes
 * quiet; while it fades there the bar greys.
 *
 * <p>It sits beside the hotbar, vertically inside the hotbar's band, on the main hand's side: vanilla draws the off
 * hand's slot on the other side, and nothing on this one except the attack indicator when that is set to show by the
 * hotbar (then this sits past it). The hearts, armour, food, air and XP rows are all above the hotbar, between its
 * ends, so nothing here covers them.
 */
final class RuneHud {
    /** The energy bar (and the whole group's width), and the cast's square. */
    private static final int BAR_W = 45, BAR_H = 4, SQ = 5;
    /** Space between the hotbar's end and the group; more when the attack indicator stands there. */
    private static final int GAP = 6, GAP_PAST_INDICATOR = 30;
    private static final int BACKING = 0x60000000, BAR_BACK = 0xE00D1222, BAR_TOP = 0xFFEEF4FF, BAR_BOTTOM = 0xFF6FA8FF;
    private static final int BAR_TOP_FADING = 0xFFD4DAE6, BAR_BOTTOM_FADING = 0xFF6F7F9C, FLOOR_TICK = 0xB0FFFFFF;
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
        Reading r = RuneGear.reading(gear, p);
        if (spec == null || r == null || r.cast() == null) return;

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
        int squareY = height - 18;            // the square: height - 18 .. height - 14
        int sweepY = squareY + SQ + 2;        // the sweep: height - 11
        int barY = height - 9;                // the bar: height - 9 .. height - 6

        // a faint backing, so it reads over snow and sky alike
        backing(g, x0 - 2, squareY - 3, x0 + BAR_W + 2, barY + BAR_H + 2);

        // ---- the cast
        CastReading c = r.cast();
        // ready: not waiting out a delay, and not dearer than the weapon can ever hold (then it is only red)
        boolean ready = !c.red() && now >= s.readyAt;
        boolean shortOf = c.cost() > energy + 1.0E-3F;
        int qx = x0 + (BAR_W - SQ) / 2;
        int colour = RuneLook.castColour(c, r.empowered());
        if (ready) {
            g.fill(qx - 1, squareY - 1, qx + SQ + 1, squareY + SQ + 1, OUTLINE);
            if (shortOf) colour = RuneLook.mix(RuneLook.SHORT_DIM, RuneLook.SHORT, 0.5F + 0.5F * Mth.sin(t * 0.35F));
            g.fill(qx, squareY, qx + SQ, squareY + SQ, colour);
        } else {
            g.fill(qx, squareY, qx + SQ, squareY + SQ, RuneLook.withAlpha(colour, 0xB4));
        }
        // the second phase: a small "II" beside the square
        if (r.empowered() && !c.red()) {
            g.fill(qx + SQ + 3, squareY, qx + SQ + 4, squareY + SQ, RuneLook.CAST_TWO);
            g.fill(qx + SQ + 5, squareY, qx + SQ + 6, squareY + SQ, RuneLook.CAST_TWO);
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
        float floor = Math.min(spec.floor(), max);
        // once the fight has gone quiet, what is above the floor fades, and the bar greys while it does
        boolean fading = now >= CastMachine.quietAt(s, spec) && energy > floor + 0.01F;
        if (fill > 0) g.fillGradient(x0 + 1, barY + 1, x0 + 1 + fill, barY + BAR_H - 1, fading ? BAR_TOP_FADING : BAR_TOP, fading ? BAR_BOTTOM_FADING : BAR_BOTTOM);
        if (floor > 0.0F && floor < max) {
            int fx = x0 + 1 + Math.min(inner - 1, Math.round(inner * floor / max));
            g.fill(fx, barY + BAR_H - 2, fx + 1, barY + BAR_H, FLOOR_TICK);
        }
        if (shortOf && c.cost() <= max) {
            int tx = x0 + 1 + Math.min(inner - 1, Math.round(inner * c.cost() / max));
            g.fill(tx, barY, tx + 1, barY + BAR_H, RuneLook.SHORT);
        }
    }

    /**
     * How long the current wait lasts in all. A wait a cast set ends {@code readyAt - lastCast} after it; any other
     * (the weapon just taken in hand) is taken as the longer of what is left, the cast's delay and the recovery.
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
