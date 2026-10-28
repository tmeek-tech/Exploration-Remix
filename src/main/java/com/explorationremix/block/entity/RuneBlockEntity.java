package com.explorationremix.block.entity;

import com.explorationremix.registry.ERBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * State of one rune block: its tier (deeper and more hidden means rarer runes), and which players woke it during
 * which run. The rune interaction itself lives in {@link com.explorationremix.rune.RuneBlockInteraction}.
 */
public class RuneBlockEntity extends BlockEntity {
    private int tier;
    private String kind = "refuge";
    private String layer = "";
    private final Map<UUID, Long> wokenBy = new HashMap<>();
    /** Offers a player has woken but not yet picked from, so closing the screen loses nothing. */
    private final Map<UUID, List<ResourceLocation>> offers = new HashMap<>();

    public RuneBlockEntity(BlockPos pos, BlockState state) {
        super(ERBlockEntities.RUNE_BLOCK.get(), pos, state);
    }

    public void setup(int tier, String kind, String layer) {
        this.tier = tier;
        this.kind = kind;
        this.layer = layer;
        setChanged();
    }

    public int tier() { return tier; }
    public String kind() { return kind; }
    public String layer() { return layer; }

    /** Returns the run id this player last woke the block in, or -1. */
    public long wokenIn(UUID player) { return wokenBy.getOrDefault(player, -1L); }

    public void markWoken(UUID player, long runId) {
        wokenBy.put(player, runId);
        setChanged();
    }

    public void setLit(boolean lit) {
        if (level != null && getBlockState().hasProperty(com.explorationremix.block.RuneBlock.LIT) && getBlockState().getValue(com.explorationremix.block.RuneBlock.LIT) != lit)
            level.setBlock(worldPosition, getBlockState().setValue(com.explorationremix.block.RuneBlock.LIT, lit), 3);
    }

    public List<ResourceLocation> offersFor(UUID player) { return offers.getOrDefault(player, List.of()); }

    public void setOffers(UUID player, List<ResourceLocation> list) {
        if (list.isEmpty()) offers.remove(player); else offers.put(player, new ArrayList<>(list));
        setChanged();
    }

    public InteractionResult interact(Player player, InteractionHand hand) {
        return com.explorationremix.rune.RuneBlockInteraction.interact(this, player, hand);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("Tier", tier);
        tag.putString("Kind", kind);
        tag.putString("Layer", layer);
        CompoundTag woken = new CompoundTag();
        wokenBy.forEach((id, run) -> woken.putLong(id.toString(), run));
        tag.put("WokenBy", woken);
        CompoundTag off = new CompoundTag();
        offers.forEach((id, list) -> {
            ListTag l = new ListTag();
            for (ResourceLocation r : list) l.add(StringTag.valueOf(r.toString()));
            off.put(id.toString(), l);
        });
        tag.put("Offers", off);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        tier = tag.getInt("Tier");
        kind = tag.getString("Kind");
        layer = tag.getString("Layer");
        wokenBy.clear();
        CompoundTag woken = tag.getCompound("WokenBy");
        for (String key : woken.getAllKeys()) {
            try { wokenBy.put(UUID.fromString(key), woken.getLong(key)); } catch (IllegalArgumentException ignored) {}
        }
        offers.clear();
        CompoundTag off = tag.getCompound("Offers");
        for (String key : off.getAllKeys()) {
            try {
                ListTag l = off.getList(key, Tag.TAG_STRING);
                List<ResourceLocation> list = new ArrayList<>();
                for (int i = 0; i < l.size(); i++) { ResourceLocation r = ResourceLocation.tryParse(l.getString(i)); if (r != null) list.add(r); }
                if (!list.isEmpty()) offers.put(UUID.fromString(key), list);
            } catch (IllegalArgumentException ignored) {}
        }
    }
}
