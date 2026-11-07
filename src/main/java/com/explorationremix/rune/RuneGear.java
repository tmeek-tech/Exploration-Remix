package com.explorationremix.rune;

import com.explorationremix.rune.engine.CastState;
import com.explorationremix.rune.engine.Host;
import com.explorationremix.rune.engine.HostSpec;
import com.explorationremix.rune.engine.Reader;
import com.explorationremix.rune.engine.Reading;
import com.explorationremix.rune.engine.Row;
import com.explorationremix.rune.engine.RuneId;
import com.explorationremix.rune.item.RuneItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;
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
 * Where a piece of gear keeps its runes, and what kind of gear it is. Two tags on the stack:
 * <ul>
 *   <li>{@value #ROW_TAG}: {@code {v: 1, s: ["spark", "", "burst"], b: <break mask>}}, written only when the row is
 *   edited;</li>
 *   <li>{@value #CAST_TAG}: {@code {e: energy, t: tick, g: regenerating since (-1), c: cursor, r: ready at, l: last
 *   cast, i: gear id}}, written only when something happens to the gear (a cast, a hit, a mote, taken in hand or put
 *   away); the energy in between is worked out from the ticks, so the stack is not rewritten every tick. The gear id
 *   is how a Siphon mote finds the gear that earned it.</li>
 * </ul>
 * A rune in a row is only its id: rune items carry nothing else, so taking a rune out makes the same item again.
 */
public final class RuneGear {
    public static final String ROW_TAG = "explorationremix:runes";
    public static final String CAST_TAG = "explorationremix:cast";

    private RuneGear() {}

    /** The kind of gear this is, or null if it carries no runes. */
    @Nullable
    public static Host host(ItemStack stack) {
        if (stack.isEmpty()) return null;
        if (stack.getItem() instanceof SwordItem) return Host.SWORD;
        if (stack.getItem() instanceof CrossbowItem) return Host.CROSSBOW;
        if (stack.getItem() instanceof BowItem) return Host.BOW;
        return null;
    }

    public static boolean isGear(ItemStack stack) { return host(stack) != null; }

    /** This piece of gear's numbers: energy, regeneration, power and bonus slots by its kind and material. */
    @Nullable
    public static HostSpec spec(ItemStack stack) {
        Host h = host(stack);
        if (h == null) return null;
        return switch (h) {
            case SWORD -> sword((SwordItem) stack.getItem());
            case BOW, CROSSBOW -> new HostSpec(h, 60, 0.2F, 60, 1.0F, 0);
        };
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
        int bonus = t.getLevel() >= 3 ? 1 : 0;
        return new HostSpec(Host.SWORD, max, 0, Math.min(30, max), power, bonus);
    }

    // ------------------------------------------------------------------------------------------------ the row

    /** The gear's row, as long as the gear has slots. Runes stored past its end (edited data) are not read. */
    public static Row row(ItemStack stack) {
        HostSpec spec = spec(stack);
        int n = spec == null ? 0 : spec.slotCount();
        RuneId[] slots = new RuneId[n];
        int breaks = 0;
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(ROW_TAG, Tag.TAG_COMPOUND)) {
            CompoundTag row = tag.getCompound(ROW_TAG);
            ListTag list = row.getList("s", Tag.TAG_STRING);
            for (int i = 0; i < Math.min(n, list.size()); i++) slots[i] = RuneId.byId(list.getString(i));
            breaks = row.getInt("b");
        }
        return new Row(slots, breaks);
    }

    /** Runes stored past the gear's last slot (only edited data can put them there). */
    public static List<RuneId> overflow(ItemStack stack) {
        List<RuneId> out = new ArrayList<>();
        HostSpec spec = spec(stack);
        CompoundTag tag = stack.getTag();
        if (spec == null || tag == null || !tag.contains(ROW_TAG, Tag.TAG_COMPOUND)) return out;
        ListTag list = tag.getCompound(ROW_TAG).getList("s", Tag.TAG_STRING);
        for (int i = spec.slotCount(); i < list.size(); i++) {
            RuneId r = RuneId.byId(list.getString(i));
            if (r != null) out.add(r);
        }
        return out;
    }

    /** Writes the row (an empty row with no breaks removes the tag). */
    public static void setRow(ItemStack stack, Row row) {
        if (row.isEmpty() && row.breaks() == 0) {
            stack.removeTagKey(ROW_TAG);
            return;
        }
        CompoundTag t = new CompoundTag();
        t.putInt("v", 1);
        ListTag list = new ListTag();
        for (int i = 0; i < row.size(); i++) list.add(StringTag.valueOf(row.get(i) == null ? "" : row.get(i).id));
        t.put("s", list);
        t.putInt("b", row.breaks());
        stack.addTagElement(ROW_TAG, t);
    }

    public static boolean hasRunes(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(ROW_TAG, Tag.TAG_COMPOUND)) return false;
        ListTag list = tag.getCompound(ROW_TAG).getList("s", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) if (RuneId.byId(list.getString(i)) != null) return true;
        return false;
    }

    /** The row read for this gear at a run depth, with the server's limits. */
    @Nullable
    public static Reading reading(ItemStack stack, int depth) {
        HostSpec spec = spec(stack);
        if (spec == null) return null;
        return Reader.read(row(stack), spec, depth, RuneConfig.limits());
    }

    /** Every rune in the row and past it, as items (a broken tool's, a grindstone's). */
    public static List<ItemStack> runeItems(ItemStack stack) {
        List<ItemStack> out = new ArrayList<>();
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(ROW_TAG, Tag.TAG_COMPOUND)) return out;
        ListTag list = tag.getCompound(ROW_TAG).getList("s", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            RuneId r = RuneId.byId(list.getString(i));
            if (r != null) out.add(RuneItem.stack(r, 1));
        }
        return out;
    }

    /** Gives every rune on a breaking tool back to its owner (into the inventory, or dropped at their feet). */
    public static void dropRunes(ItemStack stack, Player player) {
        if (player.level().isClientSide) return;
        for (ItemStack r : runeItems(stack)) {
            if (!player.getInventory().add(r)) player.drop(r, false);
        }
        stack.removeTagKey(ROW_TAG);
    }

    // ------------------------------------------------------------------------------------------------ casting state

    /** The gear's casting state, or a fresh one (starting energy, nothing cast). */
    public static CastState state(ItemStack stack, HostSpec spec, long now) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(CAST_TAG, Tag.TAG_COMPOUND)) return CastState.fresh(spec, now);
        CompoundTag c = tag.getCompound(CAST_TAG);
        return new CastState(c.getFloat("e"), c.getLong("t"), c.contains("g") ? c.getLong("g") : -1, c.getInt("c"), c.getLong("r"),
                c.contains("l") ? c.getLong("l") : Long.MIN_VALUE / 4);
    }

    public static boolean hasState(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(CAST_TAG, Tag.TAG_COMPOUND);
    }

    /** Writes the casting state, only if it changed (every write is sent to the client). Keeps the gear id. */
    public static void setState(ItemStack stack, CastState s) {
        CompoundTag c = new CompoundTag();
        int id = gearId(stack);
        if (id != 0) c.putInt("i", id);
        c.putFloat("e", s.energy);
        c.putLong("t", s.energyTick);
        c.putLong("g", s.regenSince);
        c.putInt("c", s.cursor);
        c.putLong("r", s.readyAt);
        c.putLong("l", s.lastCast);
        CompoundTag tag = stack.getTag();
        if (tag != null && c.equals(tag.get(CAST_TAG))) return;
        stack.addTagElement(CAST_TAG, c);
    }

    /** The gear's id (0 if it has none yet). */
    public static int gearId(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(CAST_TAG, Tag.TAG_COMPOUND) ? tag.getCompound(CAST_TAG).getInt("i") : 0;
    }

    /** The gear's id, giving it one first if it has none (server only: the stack is written). */
    public static int ensureGearId(ItemStack stack, net.minecraft.util.RandomSource random) {
        int id = gearId(stack);
        if (id != 0) return id;
        do id = random.nextInt(); while (id == 0);
        CompoundTag c = stack.getOrCreateTag().getCompound(CAST_TAG);
        c.putInt("i", id);
        stack.addTagElement(CAST_TAG, c);
        return id;
    }

    /**
     * The piece of gear with this id that the player carries: in a hand, anywhere in the inventory, or in the rune
     * screen's gear slot. Null if they no longer have it.
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
        if (player.containerMenu instanceof com.explorationremix.rune.menu.RuneEditorMenu m && isGear(m.gear()) && gearId(m.gear()) == id) {
            return m.gear();
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
