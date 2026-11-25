package com.explorationremix.rune;

import com.explorationremix.rune.engine.ArmorRunes;
import com.explorationremix.rune.engine.CastState;
import com.explorationremix.rune.engine.Fits;
import com.explorationremix.rune.engine.Host;
import com.explorationremix.rune.engine.HostSpec;
import com.explorationremix.rune.engine.Loadout;
import com.explorationremix.rune.engine.Reader;
import com.explorationremix.rune.engine.Piece;
import com.explorationremix.rune.engine.Reading;
import com.explorationremix.rune.engine.RuneId;
import com.explorationremix.rune.item.RuneItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.Tiers;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Where a weapon or a piece of armor keeps its runes, and what kind of gear it is. Two tags on the stack (read and
 * written by {@link RuneTags}):
 * <ul>
 *   <li>{@value #LOADOUT_TAG}: {@code {v: 2, a: "spark", m: "swift", p: "ember"}} on a weapon, {@code {v: 2, e: "leap",
 *   c: "mend", d: "vigor"}} on armor, written only when a rune is set at the enchanting table;</li>
 *   <li>{@value #CAST_TAG}: {@code {e: energy, t: tick, g: last gained energy (-1), c: cursor, r: ready at, l: last
 *   cast, i: weapon id}}, written only when something happens to the weapon (a cast, a hit, a mote, taken in hand or
 *   put away); the energy in between is worked out from the ticks, so the stack is not rewritten every tick. The id
 *   is how a Siphon mote finds the weapon that earned it.</li>
 * </ul>
 * A rune in a socket is only its id: rune items carry nothing else, so taking a rune out makes the same item again.
 */
public final class RuneGear {
    public static final String LOADOUT_TAG = RuneTags.LOADOUT;
    public static final String CAST_TAG = RuneTags.CAST;

    private RuneGear() {}

    /** The kind of weapon this is, or null if it carries no runes. */
    @Nullable
    public static Host host(ItemStack stack) {
        if (stack.isEmpty()) return null;
        if (stack.getItem() instanceof SwordItem) return Host.SWORD;
        if (stack.getItem() instanceof CrossbowItem) return Host.CROSSBOW;
        if (stack.getItem() instanceof BowItem) return Host.BOW;
        return null;
    }

    /** Whether it is a weapon that casts (a sword, a bow or a crossbow). */
    public static boolean isGear(ItemStack stack) { return host(stack) != null; }

    /** The piece of armor this is (any mod's armor, by the slot it is worn in), or null. */
    @Nullable
    public static Piece piece(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof ArmorItem a)) return null;
        return piece(a.getEquipmentSlot());
    }

    /** The piece worn in an armor slot, or null for a hand. */
    @Nullable
    public static Piece piece(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> Piece.HELMET;
            case CHEST -> Piece.CHEST;
            case LEGS -> Piece.LEGS;
            case FEET -> Piece.BOOTS;
            default -> null;
        };
    }

    /** The armor slot a piece is worn in. */
    public static EquipmentSlot slot(Piece p) {
        return switch (p) {
            case HELMET -> EquipmentSlot.HEAD;
            case CHEST -> EquipmentSlot.CHEST;
            case LEGS -> EquipmentSlot.LEGS;
            case BOOTS -> EquipmentSlot.FEET;
        };
    }

    public static boolean isArmor(ItemStack stack) { return piece(stack) != null; }

    /** Whether it takes runes at all: a weapon or a piece of armor. */
    public static boolean takesRunes(ItemStack stack) { return isGear(stack) || isArmor(stack); }

    /** Which runes it takes ({@link Fits} bits), 0 for none. */
    public static int fits(ItemStack stack) {
        Host h = host(stack);
        if (h != null) return Fits.of(h);
        Piece p = piece(stack);
        return p == null ? 0 : Fits.of(p);
    }

    /**
     * This weapon's numbers: the most energy it holds and its power by its kind and material, and the floor its energy
     * settles back to and how fast, from the server's settings.
     */
    @Nullable
    public static HostSpec spec(ItemStack stack) {
        Host h = host(stack);
        if (h == null) return null;
        HostSpec base = switch (h) {
            case SWORD -> sword((SwordItem) stack.getItem());
            case BOW, CROSSBOW -> new HostSpec(h, 60, 0, 1.0F, HostSpec.Fade.DEFAULT);
        };
        return base.withFloor(Math.round(base.maxEnergy() * RuneConfig.floorShare())).withFade(RuneConfig.fade());
    }

    private static HostSpec sword(SwordItem item) {
        Tier t = item.getTier();
        float max;
        if (t == Tiers.WOOD) max = 40;
        else if (t == Tiers.STONE) max = 50;
        else if (t == Tiers.IRON) max = 70;
        else if (t == Tiers.GOLD) max = 90;
        else if (t == Tiers.DIAMOND) max = 80;
        else if (t == Tiers.NETHERITE) max = 100;
        else max = 40 + 10 * Math.max(0, Math.min(6, t.getLevel()));
        // an iron sword (6 attack damage) hits with plain rune damage; a netherite one a third harder
        float power = Math.max(0.6F, Math.min(1.6F, (item.getDamage() + 1.0F) / 6.0F));
        return new HostSpec(Host.SWORD, max, 0, power, HostSpec.Fade.DEFAULT);
    }

    // ------------------------------------------------------------------------------------------------ the sockets

    /** The weapon's runes (an old row read as it will be set). */
    public static Loadout loadout(ItemStack stack) { return RuneTags.readLoadout(stack.getTag()); }

    /** Writes the runes (no runes removes the tag). */
    public static void setLoadout(ItemStack stack, Loadout l) {
        CompoundTag t = RuneTags.writeLoadout(l);
        stack.removeTagKey(RuneTags.OLD_ROW);
        if (t == null) stack.removeTagKey(LOADOUT_TAG);
        else stack.addTagElement(LOADOUT_TAG, t);
    }

    /** The weapon's passive rune if it fits the weapon, else null: read straight from its tag, for checks made often. */
    @Nullable
    public static RuneId passive(ItemStack stack) {
        Host h = host(stack);
        if (h == null || !stack.hasTag()) return null;
        RuneId p = RuneTags.readLoadout(stack.getTag()).passive();
        return p != null && p.fits(Fits.of(h)) ? p : null;
    }

    /** Whether it keeps any rune (fitting or not: what it would give back). */
    public static boolean hasRunes(ItemStack stack) { return RuneTags.hasRunes(stack.getTag()); }

    /** A piece of armor's runes (only the ones that fit it), or EMPTY. */
    public static ArmorRunes armor(ItemStack stack) {
        Piece p = piece(stack);
        return p == null ? ArmorRunes.EMPTY : RuneTags.readArmor(stack.getTag()).fitting(p);
    }

    /** Writes a piece of armor's runes (no runes removes the tag). */
    public static void setArmor(ItemStack stack, ArmorRunes a) {
        CompoundTag t = RuneTags.writeArmor(a);
        if (t == null) stack.removeTagKey(LOADOUT_TAG);
        else stack.addTagElement(LOADOUT_TAG, t);
    }

    /**
     * Gear keeping runes that don't fit it (0.22.6: sword actives on a bow, Glow on a sword, Lantern or Thirst in a
     * weapon's passive socket): those come back to the player, and the rest stay where they are. Server only; the
     * runes handed back, none if all fit. An old row is left for {@link #convertOldRow}.
     */
    public static List<RuneId> handBack(ItemStack stack, Player player) {
        if (player.level().isClientSide || RuneTags.hasOldRow(stack.getTag())) return List.of();
        List<RuneId> all = RuneTags.socketed(stack.getTag());
        if (all.isEmpty()) return List.of();
        List<RuneId> back;
        Host h = host(stack);
        Piece p = piece(stack);
        if (h != null) {
            Loadout kept = RuneTags.readLoadout(stack.getTag()).fitting(Fits.of(h));
            back = RuneTags.without(all, kept.runes());
            if (!back.isEmpty()) setLoadout(stack, kept);
        } else if (p != null) {
            ArmorRunes kept = RuneTags.readArmor(stack.getTag()).fitting(p);
            back = RuneTags.without(all, kept.runes());
            if (!back.isEmpty()) setArmor(stack, kept);
        } else {
            return List.of();
        }
        for (RuneId r : back) give(player, RuneItem.stack(r, 1));
        return back;
    }

    /** The runes read for this weapon at an XP level, with the server's limits. */
    @Nullable
    public static Reading reading(ItemStack stack, int level) {
        HostSpec spec = spec(stack);
        if (spec == null) return null;
        return Reader.read(loadout(stack), spec, level, RuneConfig.limits());
    }

    /** The runes read for this weapon in this player's hands (their XP level wakes the second phase). */
    @Nullable
    public static Reading reading(ItemStack stack, Player p) { return reading(stack, p.experienceLevel); }

    /** Every rune it keeps, as items (a broken weapon's, a grindstone's). */
    public static List<ItemStack> runeItems(ItemStack stack) {
        List<ItemStack> out = new ArrayList<>();
        for (RuneId r : RuneTags.storedRunes(stack.getTag())) {
            ItemStack item = RuneItem.stack(r, 1);
            if (!item.isEmpty()) out.add(item);
        }
        return out;
    }

    /** Takes the runes off (they have been handed out). */
    public static void clearRunes(ItemStack stack) {
        stack.removeTagKey(LOADOUT_TAG);
        stack.removeTagKey(RuneTags.OLD_ROW);
    }

    /** Gives every rune on a breaking weapon back to its owner (into the inventory, or dropped at their feet). */
    public static void dropRunes(ItemStack stack, Player player) {
        if (player.level().isClientSide) return;
        for (ItemStack r : runeItems(stack)) give(player, r);
        clearRunes(stack);
    }

    /**
     * A weapon that still has its row from before 0.20.3: its first active rune, first modifier and first passive go in
     * its sockets and the rest come back to the player. Server only. True if it had a row.
     */
    public static boolean convertOldRow(ItemStack stack, Player player) {
        if (player.level().isClientSide || !isGear(stack)) return false;
        RuneTags.Converted c = RuneTags.convertGear(stack.getTag());
        if (c == null) return false;
        setLoadout(stack, c.kept());
        for (RuneId r : c.extras()) give(player, RuneItem.stack(r, 1));
        return true;
    }

    /** Into the inventory, or dropped at the player's feet if there is no room (or they are dead). */
    public static void give(Player p, ItemStack s) {
        if (s.isEmpty()) return;
        if (p.isDeadOrDying()) p.drop(s, false);
        else if (!p.getInventory().add(s)) p.drop(s, false);
    }

    /**
     * What a grindstone makes of one runed weapon: the same weapon with no runes and, as a grindstone does, without its
     * enchantments (curses stay) and with its repair cost reset. Its wear stays, and so do its energy and its wait, so a
     * round trip through a grindstone never refills it.
     */
    public static ItemStack ground(ItemStack stack) {
        ItemStack out = stack.copyWithCount(1);
        clearRunes(out);
        // its energy and its wait stay (taking the runes off doesn't refill it)
        CastState s = RuneTags.readState(out.getTag());
        if (s != null) {
            s.cursor = 0;
            out.addTagElement(CAST_TAG, RuneTags.writeState(s, gearId(out)));
        }
        out.removeTagKey("Enchantments");
        out.removeTagKey("StoredEnchantments");
        java.util.Map<net.minecraft.world.item.enchantment.Enchantment, Integer> curses = new java.util.LinkedHashMap<>();
        net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantments(stack).forEach((en, lvl) -> {
            if (en.isCurse()) curses.put(en, lvl);
        });
        net.minecraft.world.item.enchantment.EnchantmentHelper.setEnchantments(curses, out);
        out.setRepairCost(0);
        for (int i = 0; i < curses.size(); i++) out.setRepairCost(net.minecraft.world.inventory.AnvilMenu.calculateIncreasedRepairCost(out.getBaseRepairCost()));
        return out;
    }

    // ------------------------------------------------------------------------------------------------ casting state

    /** The gear's casting state, or a fresh one (starting energy, nothing cast). */
    public static CastState state(ItemStack stack, HostSpec spec, long now) {
        CastState s = RuneTags.readState(stack.getTag());
        return s == null ? CastState.fresh(spec, now) : s;
    }

    /** Whether something has happened to the gear yet (a cast, a hit, a mote; not just being given an id). */
    public static boolean hasState(ItemStack stack) { return RuneTags.readState(stack.getTag()) != null; }

    /** Writes the casting state, only if it changed (every write is sent to the client). Keeps the gear id. */
    public static void setState(ItemStack stack, CastState s) {
        CompoundTag c = RuneTags.writeState(s, gearId(stack));
        CompoundTag tag = stack.getTag();
        if (tag != null && c.equals(tag.get(CAST_TAG))) return;
        stack.addTagElement(CAST_TAG, c);
    }

    /** The gear's id (0 if it has none yet). */
    public static int gearId(ItemStack stack) { return RuneTags.gearId(stack.getTag()); }

    /** The gear's id, giving it one first if it has none (server only: the stack is written). */
    public static int ensureGearId(ItemStack stack, net.minecraft.util.RandomSource random) {
        int id = gearId(stack);
        if (id != 0) return id;
        do id = random.nextInt(); while (id == 0);
        stack.addTagElement(CAST_TAG, RuneTags.withGearId(stack.getTag(), id));
        return id;
    }

    /**
     * The weapon with this id that the player carries: in a hand or anywhere in the inventory (at the enchanting table
     * it never leaves the hand). Null if they no longer have it.
     */
    @Nullable
    public static ItemStack findGear(Player player, int id) {
        if (id == 0) return null;
        net.minecraft.world.entity.player.Inventory inv = player.getInventory();
        if (isGear(inv.getSelected()) && gearId(inv.getSelected()) == id) return inv.getSelected();
        ItemStack off = inv.offhand.get(0);
        if (isGear(off) && gearId(off) == id) return off;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (isGear(s) && gearId(s) == id) return s;
        }
        return null;
    }

    /** Whether two stacks differ only in their casting state (no re-equip bob for that). */
    public static boolean sameButCastState(ItemStack a, ItemStack b) {
        if (a.getItem() != b.getItem() || a.getCount() != b.getCount()) return false;
        CompoundTag ta = a.getTag(), tb = b.getTag();
        if (ta == null || tb == null) return ta == tb;
        if (!ta.contains(CAST_TAG) && !tb.contains(CAST_TAG)) return ta.equals(tb);
        CompoundTag ca = ta.copy(), cb = tb.copy();
        ca.remove(CAST_TAG);
        cb.remove(CAST_TAG);
        return ca.equals(cb);
    }
}
