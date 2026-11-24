package com.explorationremix.rune;

import com.explorationremix.rune.engine.CastState;
import com.explorationremix.rune.engine.Loadout;
import com.explorationremix.rune.engine.RuneId;
import com.explorationremix.rune.engine.Socket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The tags a weapon keeps, read and written as plain NBT, so what is saved can be checked without a game:
 * <ul>
 *   <li>{@value #LOADOUT}: {@code {v: 2, a: "spark", m: "swift", p: "ember"}}, a socket left out when it is empty;</li>
 *   <li>{@value #CAST}: {@code {i: weapon id, e: energy, t: tick, g: last gained energy (-1), c: cursor, r: ready at,
 *   l: last cast}}. An id can be given before anything has happened to the weapon: the state is only there once
 *   {@code t} is, and until then the weapon reads as fresh (with its starting energy).</li>
 * </ul>
 * Before 0.20.3 runes sat in rows, {@value #OLD_ROW}: {@code {v: 1, s: ["spark", "", "burst"], b: <break mask>}}. A
 * weapon that still has its row reads as its first active rune, first modifier and first passive; the server sets
 * those in its sockets and hands the rest back ({@link #convert}). A rune id the game doesn't know (from a later
 * version, or edited data) reads as nothing and is kept nowhere.
 */
public final class RuneTags {
    public static final String LOADOUT = "explorationremix:loadout";
    public static final String OLD_ROW = "explorationremix:runes";
    public static final String CAST = "explorationremix:cast";
    static final int VERSION = 2;
    private static final String[] KEYS = {"a", "m", "p"};

    private RuneTags() {}

    // ------------------------------------------------------------------------------------------------ the sockets

    /** The runes in a weapon's sockets (an old row read as it will be set: {@link #convert}). */
    public static Loadout readLoadout(@Nullable CompoundTag gear) {
        if (gear == null) return Loadout.EMPTY;
        if (gear.contains(LOADOUT, Tag.TAG_COMPOUND)) {
            CompoundTag t = gear.getCompound(LOADOUT);
            Loadout l = Loadout.EMPTY;
            for (Socket s : Socket.values()) {
                RuneId r = RuneId.byId(t.getString(KEYS[s.ordinal()]));
                if (r != null && r.kind == s.kind) l = l.with(s, r);
            }
            return l;
        }
        return convert(oldRow(gear)).kept();
    }

    /** The sockets' tag; null for no runes (the weapon then keeps no tag at all). */
    @Nullable
    public static CompoundTag writeLoadout(Loadout l) {
        if (l.isEmpty()) return null;
        CompoundTag t = new CompoundTag();
        t.putInt("v", VERSION);
        for (Socket s : Socket.values()) {
            RuneId r = l.get(s);
            if (r != null) t.putString(KEYS[s.ordinal()], r.id);
        }
        return t;
    }

    /** Whether it holds any rune the game knows. */
    public static boolean hasRunes(@Nullable CompoundTag gear) { return !readLoadout(gear).isEmpty(); }

    /** Every rune it keeps: in its sockets, and in an old row not set into them yet (all of the row's runes). */
    public static List<RuneId> storedRunes(@Nullable CompoundTag gear) {
        List<RuneId> out = new ArrayList<>();
        if (gear == null) return out;
        if (gear.contains(LOADOUT, Tag.TAG_COMPOUND)) out.addAll(readLoadout(gear).runes());
        out.addAll(oldRow(gear));
        return out;
    }

    // ------------------------------------------------------------------------------------------------ old rows

    /** Whether the weapon still keeps a row from before 0.20.3. */
    public static boolean hasOldRow(@Nullable CompoundTag gear) { return gear != null && gear.contains(OLD_ROW, Tag.TAG_COMPOUND); }

    /** The runes in an old row, in order (empty slots and unknown ids left out). */
    public static List<RuneId> oldRow(@Nullable CompoundTag gear) {
        List<RuneId> out = new ArrayList<>();
        if (!hasOldRow(gear)) return out;
        ListTag list = gear.getCompound(OLD_ROW).getList("s", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            RuneId r = RuneId.byId(list.getString(i));
            if (r != null) out.add(r);
        }
        return out;
    }

    /** What a row becomes: the first rune of each kind in its socket, and the rest to hand back. */
    public record Converted(Loadout kept, List<RuneId> extras) {}

    public static Converted convert(List<RuneId> row) {
        Loadout l = Loadout.EMPTY;
        List<RuneId> extras = new ArrayList<>();
        for (RuneId r : row) {
            Socket s = Socket.of(r.kind);
            if (l.get(s) == null) l = l.with(s, r);
            else extras.add(r);
        }
        return new Converted(l, extras);
    }

    /**
     * A weapon with an old row, set in sockets: the sockets it already has win (only edited data has both), and every
     * rune that doesn't fit goes to {@code extras}. Null if it has no old row.
     */
    @Nullable
    public static Converted convertGear(@Nullable CompoundTag gear) {
        if (!hasOldRow(gear)) return null;
        List<RuneId> row = oldRow(gear);
        if (gear.contains(LOADOUT, Tag.TAG_COMPOUND)) return new Converted(readLoadout(withoutRow(gear)), row);
        return convert(row);
    }

    private static CompoundTag withoutRow(CompoundTag gear) {
        CompoundTag t = gear.copy();
        t.remove(OLD_ROW);
        return t;
    }

    // ------------------------------------------------------------------------------------------------ the casting state

    /** The casting state stored, or null if nothing has happened to the weapon yet (it is fresh). */
    @Nullable
    public static CastState readState(@Nullable CompoundTag gear) {
        if (gear == null || !gear.contains(CAST, Tag.TAG_COMPOUND)) return null;
        CompoundTag c = gear.getCompound(CAST);
        if (!c.contains("t", Tag.TAG_LONG)) return null;
        return new CastState(c.getFloat("e"), c.getLong("t"), c.contains("g") ? c.getLong("g") : -1, c.getInt("c"), c.getLong("r"),
                c.contains("l") ? c.getLong("l") : Long.MIN_VALUE / 4);
    }

    /** The state's tag, with the weapon's id ({@code 0}: none yet). */
    public static CompoundTag writeState(CastState s, int gearId) {
        CompoundTag c = new CompoundTag();
        if (gearId != 0) c.putInt("i", gearId);
        c.putFloat("e", s.energy);
        c.putLong("t", s.energyTick);
        c.putLong("g", s.lastGain);
        c.putInt("c", s.cursor);
        c.putLong("r", s.readyAt);
        c.putLong("l", s.lastCast);
        return c;
    }

    /** The weapon's id, or 0 if it has none yet. */
    public static int gearId(@Nullable CompoundTag gear) {
        return gear != null && gear.contains(CAST, Tag.TAG_COMPOUND) ? gear.getCompound(CAST).getInt("i") : 0;
    }

    /** {@code gear}'s state tag with {@code id} given to it, keeping whatever state is there. */
    public static CompoundTag withGearId(@Nullable CompoundTag gear, int id) {
        CompoundTag c = gear != null && gear.contains(CAST, Tag.TAG_COMPOUND) ? gear.getCompound(CAST).copy() : new CompoundTag();
        c.putInt("i", id);
        return c;
    }
}
