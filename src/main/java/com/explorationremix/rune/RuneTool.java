package com.explorationremix.rune;

import com.explorationremix.config.ERConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.TridentItem;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Runes live on tools, as NBT. Each rune is stamped with the run it was taken in; runes from any other run are dead.
 * Attunement is permanent and sits outside the stamp.
 */
public final class RuneTool {
    public static final String TAG = "ERRunes";
    private RuneTool() {}

    public static boolean canHoldRunes(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return stack.getItem() instanceof TieredItem || stack.getItem() instanceof ProjectileWeaponItem || stack.getItem() instanceof TridentItem
                || stack.getItem() instanceof ShieldItem || stack.is(com.explorationremix.registry.ERTags.RUNE_HOLDERS);
    }

    private static CompoundTag tag(ItemStack stack, boolean create) {
        CompoundTag root = create ? stack.getOrCreateTag() : stack.getTag();
        if (root == null) return null;
        if (!root.contains(TAG) && create) root.put(TAG, new CompoundTag());
        return root.getCompound(TAG).isEmpty() && !create ? (root.contains(TAG) ? root.getCompound(TAG) : null) : root.getCompound(TAG);
    }

    /** The run id the runes on this tool belong to, or 0. */
    public static long runStamp(ItemStack stack) {
        CompoundTag t = tag(stack, false);
        return t == null ? 0L : t.getLong("Run");
    }

    public static UUID toolId(ItemStack stack) {
        CompoundTag t = tag(stack, true);
        if (!t.hasUUID("Id")) t.putUUID("Id", UUID.randomUUID());
        return t.getUUID("Id");
    }

    public static List<ResourceLocation> runes(ItemStack stack) {
        CompoundTag t = tag(stack, false);
        List<ResourceLocation> out = new ArrayList<>();
        if (t == null) return out;
        ListTag list = t.getList("Runes", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            ResourceLocation id = ResourceLocation.tryParse(list.getString(i));
            if (id != null) out.add(id);
        }
        return out;
    }

    public static void setRunes(ItemStack stack, List<ResourceLocation> runes, long runId) {
        CompoundTag t = tag(stack, true);
        ListTag list = new ListTag();
        for (ResourceLocation id : runes) list.add(StringTag.valueOf(id.toString()));
        t.put("Runes", list);
        t.putLong("Run", runId);
        toolId(stack);
    }

    /** Runes that are alive in the given run (dead ones are still listed but inert). */
    public static List<Rune> liveRunes(ItemStack stack, long runId) {
        if (runId == 0L || runStamp(stack) != runId) return List.of();
        List<Rune> out = new ArrayList<>();
        for (ResourceLocation id : runes(stack)) {
            Rune r = RuneRegistry.get(id);
            if (r != null) out.add(r);
        }
        return out;
    }

    public static boolean isDead(ItemStack stack, long runId) { return !runes(stack).isEmpty() && runStamp(stack) != runId; }

    public static int slots(ItemStack stack) {
        CompoundTag t = tag(stack, false);
        int base = ERConfig.INSTANCE.startingSlots.get();
        if (t == null || !t.contains("Slots")) return base;
        return Math.max(base, t.getInt("Slots"));
    }

    public static void setSlots(ItemStack stack, int slots) { tag(stack, true).putInt("Slots", slots); }

    public static String attunement(ItemStack stack) {
        CompoundTag t = tag(stack, false);
        return t == null ? "" : t.getString("Attune");
    }

    public static void setAttunement(ItemStack stack, String attunement) { tag(stack, true).putString("Attune", attunement); }

    /** Adds a rune at the end if there is room. */
    public static boolean addRune(ItemStack stack, ResourceLocation id, long runId) {
        List<ResourceLocation> runes = runStamp(stack) == runId ? runes(stack) : new ArrayList<>();
        if (runes.size() >= slots(stack)) return false;
        runes.add(id);
        setRunes(stack, runes, runId);
        return true;
    }

    public static int triggerCount(List<Rune> runes) {
        int n = 0;
        for (Rune r : runes) if (r.kind() == RuneKind.TRIGGER) n++;
        return n;
    }
}
