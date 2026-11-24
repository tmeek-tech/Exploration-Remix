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
 * What the HUD, the tooltips and the slot decorations share, so they never disagree: the cast's colour, the red of a
 * cast the weapon can never pay for, and a weapon's energy as it stands this frame.
 */
final class RuneLook {
    /** The cast: the active rune's light blue, and paler once its second phase is awake. */
    static final int CAST = 0xFF8EC2FF, CAST_TWO = 0xFFD6E6FF;
    /** A cast that costs more than the weapon can ever hold: it never fires. */
    static final int RED = 0xFFE03C3C;
    /** The cast, while the weapon has too little energy for it (it pulses between these): slate to lavender. */
    static final int SHORT_DIM = 0xFF2B3550, SHORT = 0xFFB7A8FF;

    private RuneLook() {}

    static int castColour(CastReading c, boolean empowered) { return c.red() ? RED : empowered ? CAST_TWO : CAST; }

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
