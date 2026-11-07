package com.explorationremix.rune;

import com.explorationremix.rune.engine.CastState;
import com.explorationremix.rune.engine.Row;
import com.explorationremix.rune.engine.RuneId;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The two tags a piece of gear keeps, read and written as plain NBT, so what is saved can be checked without a game:
 * <ul>
 *   <li>{@value #ROW}: {@code {v: 1, s: ["spark", "", "burst"], b: <break mask>}};</li>
 *   <li>{@value #CAST}: {@code {i: gear id, e: energy, t: tick, g: regenerating since (-1), c: cursor, r: ready at,
 *   l: last cast}}. A gear id can be given before anything has happened to the gear: the state is only there once
 *   {@code t} is, and until then the gear reads as fresh (with its starting energy).</li>
 * </ul>
 * A rune id the game doesn't know (from a later version, or edited data) reads as an empty slot and is kept nowhere.
 */
public final class RuneTags {
    public static final String ROW = "explorationremix:runes";
    public static final String CAST = "explorationremix:cast";
    static final int VERSION = 1;

    private RuneTags() {}

    // ------------------------------------------------------------------------------------------------ the row

    /** The row stored in a gear's tag, {@code slots} long: runes stored past its end are not read. */
    public static Row readRow(@Nullable CompoundTag gear, int slots) {
        RuneId[] out = new RuneId[Math.max(0, slots)];
        int breaks = 0;
        ListTag list = stored(gear);
        if (list != null) {
            for (int i = 0; i < Math.min(out.length, list.size()); i++) out[i] = RuneId.byId(list.getString(i));
            breaks = gear.getCompound(ROW).getInt("b");
        }
        return new Row(out, breaks);
    }

    /** Runes stored past the last of {@code slots} slots (only edited data can put them there). */
    public static List<RuneId> readOverflow(@Nullable CompoundTag gear, int slots) {
        List<RuneId> out = new ArrayList<>();
        ListTag list = stored(gear);
        if (list == null) return out;
        for (int i = Math.max(0, slots); i < list.size(); i++) {
            RuneId r = RuneId.byId(list.getString(i));
            if (r != null) out.add(r);
        }
        return out;
    }

    /** Every rune stored, in order, in the slots and past them. */
    public static List<RuneId> storedRunes(@Nullable CompoundTag gear) { return readOverflow(gear, 0); }

    /** Whether any slot holds a rune the game knows. */
    public static boolean hasRunes(@Nullable CompoundTag gear) {
        ListTag list = stored(gear);
        if (list == null) return false;
        for (int i = 0; i < list.size(); i++) if (RuneId.byId(list.getString(i)) != null) return true;
        return false;
    }

    /** The row's tag; null for an empty row with no breaks (the gear then keeps no row tag at all). */
    @Nullable
    public static CompoundTag writeRow(Row row) {
        if (row.isEmpty() && row.breaks() == 0) return null;
        CompoundTag t = new CompoundTag();
        t.putInt("v", VERSION);
        ListTag list = new ListTag();
        for (int i = 0; i < row.size(); i++) list.add(StringTag.valueOf(row.get(i) == null ? "" : row.get(i).id));
        t.put("s", list);
        t.putInt("b", row.breaks());
        return t;
    }

    @Nullable
    private static ListTag stored(@Nullable CompoundTag gear) {
        if (gear == null || !gear.contains(ROW, Tag.TAG_COMPOUND)) return null;
        return gear.getCompound(ROW).getList("s", Tag.TAG_STRING);
    }

    // ------------------------------------------------------------------------------------------------ the casting state

    /** The casting state stored, or null if nothing has happened to the gear yet (it is fresh). */
    @Nullable
    public static CastState readState(@Nullable CompoundTag gear) {
        if (gear == null || !gear.contains(CAST, Tag.TAG_COMPOUND)) return null;
        CompoundTag c = gear.getCompound(CAST);
        if (!c.contains("t", Tag.TAG_LONG)) return null;
        return new CastState(c.getFloat("e"), c.getLong("t"), c.contains("g") ? c.getLong("g") : -1, c.getInt("c"), c.getLong("r"),
                c.contains("l") ? c.getLong("l") : Long.MIN_VALUE / 4);
    }

    /** The state's tag, with the gear's id ({@code 0}: none yet). */
    public static CompoundTag writeState(CastState s, int gearId) {
        CompoundTag c = new CompoundTag();
        if (gearId != 0) c.putInt("i", gearId);
        c.putFloat("e", s.energy);
        c.putLong("t", s.energyTick);
        c.putLong("g", s.regenSince);
        c.putInt("c", s.cursor);
        c.putLong("r", s.readyAt);
        c.putLong("l", s.lastCast);
        return c;
    }

    /** The gear's id, or 0 if it has none yet. */
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
