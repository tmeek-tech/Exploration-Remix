package com.explorationremix.rune.armor;

import com.explorationremix.rune.RuneGear;
import com.explorationremix.rune.RuneTags;
import com.explorationremix.rune.engine.ArmorRunes;
import com.explorationremix.rune.engine.ArmorSet;
import com.explorationremix.rune.engine.Piece;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * The runes a player wears, read as a set (ArmorSet), on either side: the server's effects and the player's own game (a
 * glide, a leap, a ledge pull) read the same thing. Read often (every tick, and in movement), so each player's set is
 * kept until a worn piece or its runes change; a stack's runes are a tag of their own, replaced (never changed in place)
 * when the table sets them.
 */
public final class WornRunes {
    private static final class Kept {
        final ItemStack[] stacks = new ItemStack[Piece.values().length];
        final Tag[] tags = new Tag[Piece.values().length];
        ArmorSet set = ArmorSet.NONE;
    }

    /** One map a side: the server's thread and the client's never share one. */
    private static final Map<Player, Kept> SERVER = new WeakHashMap<>(), CLIENT = new WeakHashMap<>();

    private WornRunes() {}

    /** What {@code p} wears. */
    public static ArmorSet of(Player p) {
        Map<Player, Kept> map = p.level().isClientSide ? CLIENT : SERVER;
        Kept k = map.computeIfAbsent(p, x -> new Kept());
        boolean same = true;
        for (Piece pc : Piece.values()) {
            ItemStack s = p.getItemBySlot(RuneGear.slot(pc));
            Tag t = s.getTag() == null ? null : s.getTag().get(RuneTags.LOADOUT);
            if (k.stacks[pc.ordinal()] != s || k.tags[pc.ordinal()] != t) same = false;
        }
        if (same) return k.set;
        ArmorRunes[] pieces = new ArmorRunes[Piece.values().length];
        for (Piece pc : Piece.values()) {
            ItemStack s = p.getItemBySlot(RuneGear.slot(pc));
            k.stacks[pc.ordinal()] = s;
            k.tags[pc.ordinal()] = s.getTag() == null ? null : s.getTag().get(RuneTags.LOADOUT);
            pieces[pc.ordinal()] = RuneGear.piece(s) == pc ? RuneGear.armor(s) : null;
        }
        k.set = new ArmorSet(pieces);
        return k.set;
    }
}
