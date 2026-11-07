package com.explorationremix.client.rune;

import com.explorationremix.rune.RuneGear;
import com.explorationremix.rune.engine.CastMachine;
import com.explorationremix.rune.engine.CastReading;
import com.explorationremix.rune.engine.CastState;
import com.explorationremix.rune.engine.HostSpec;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/**
 * What the rune screen, the HUD, the tooltips and the slot decorations share, so they never disagree: one colour per
 * cast, the red of a cast the gear can never pay for, and a piece of gear's energy as it stands this frame.
 */
final class RuneLook {
    /**
     * Cast 1, 2, 3... in order. Mid-bright on purpose: they read on the rune screen's light grey and on the dark backing
     * the HUD draws over the world. No red or amber among them (those mean "never" and "not enough energy").
     */
    private static final int[] CASTS = {0xFF3D8BFF, 0xFFE0B000, 0xFF45C13A, 0xFFB05CFF, 0xFF19C2B4, 0xFFFF5CA8};
    /** A cast that costs more than the gear can ever hold: it is skipped. */
    static final int RED = 0xFFE03C3C;
    /** The next cast, while the gear has too little energy for it (it pulses between these). */
    static final int AMBER_DIM = 0xFF6E4A00, AMBER = 0xFFFFB000;

    private RuneLook() {}

    static int castColour(int index) { return CASTS[Math.floorMod(index, CASTS.length)]; }

    static int castColour(CastReading c) { return c.red() ? RED : castColour(c.index()); }

    /**
     * Energy this frame: worked out from game time (the gear stores only when its energy last changed), between this
     * tick and the next, so a filling bar moves smoothly.
     */
    static float energy(CastState s, HostSpec spec, long now, float partialTick) {
        float a = CastMachine.energyAt(s, spec, now), b = CastMachine.energyAt(s, spec, now + 1);
        return Mth.lerp(Mth.clamp(partialTick, 0.0F, 1.0F), a, b);
    }

    /** Energy as the player is shown it: whole points, never more than the gear really has. */
    static int whole(float energy) { return (int) Math.floor(energy + 1.0E-3F); }

    /** The gear the HUD follows: the main hand's if it carries runes, else the off hand's; null if neither. */
    @Nullable
    static ItemStack heldRuneGear(Player p) {
        ItemStack main = p.getMainHandItem();
        if (RuneGear.isGear(main) && RuneGear.hasRunes(main)) return main;
        ItemStack off = p.getOffhandItem();
        if (RuneGear.isGear(off) && RuneGear.hasRunes(off)) return off;
        return null;
    }

    /** {@code a} to {@code b} by {@code k} (0 to 1), channel by channel, alpha included. */
    static int mix(int a, int b, float k) {
        float t = Mth.clamp(k, 0.0F, 1.0F);
        int out = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int ca = (a >>> shift) & 255, cb = (b >>> shift) & 255;
            out |= Math.round(ca + (cb - ca) * t) << shift;
        }
        return out;
    }

    /** The same colour with another alpha (0 to 255). */
    static int withAlpha(int argb, int alpha) { return (alpha & 255) << 24 | (argb & 0xFFFFFF); }
}
