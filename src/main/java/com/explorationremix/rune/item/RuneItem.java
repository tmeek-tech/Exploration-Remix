package com.explorationremix.rune.item;

import com.explorationremix.rune.RuneRegistry;
import com.explorationremix.rune.engine.RuneId;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.damagesource.DamageSource;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A rune: an item you hold, keep, trade and set into a weapon's socket at an enchanting table. One item per rune,
 * carrying nothing but what it is, so a rune taken out of a weapon is the same item again. Runes never burn, never
 * despawn and can't be destroyed by blasts or cactus, so nothing is lost by tinkering.
 */
public class RuneItem extends Item {
    public final RuneId rune;

    public RuneItem(RuneId rune, Properties props) {
        super(props);
        this.rune = rune;
    }

    public static ItemStack stack(RuneId rune, int count) {
        Item item = RuneRegistry.item(rune);
        return item == null ? ItemStack.EMPTY : new ItemStack(item, count);
    }

    @Nullable
    public static RuneId runeOf(ItemStack stack) { return stack.getItem() instanceof RuneItem r ? r.rune : null; }

    @Override
    public Rarity getRarity(ItemStack stack) {
        return rune.layer >= 2 ? Rarity.RARE : rune.layer == 1 ? Rarity.UNCOMMON : Rarity.COMMON;
    }

    /** The sockets' colours, which the enchanting table and the tooltips share: light blue, lavender and silver-blue. */
    public static int kindColour(com.explorationremix.rune.engine.RuneKind k) {
        return switch (k) {
            case ACTION -> 0x8EC2FF;
            case MODIFIER -> 0xB7A8FF;
            case PASSIVE -> 0xD6E6FF;
        };
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tips, TooltipFlag flag) {
        // what it does, in the rune list's words (lang: rune.explorationremix.<id>.desc), and which socket it goes in
        int kind = kindColour(rune.kind);
        tips.add(Component.translatable("rune.explorationremix.kind." + rune.kind.word).withStyle(s -> s.withColor(kind)));
        tips.add(Component.translatable("rune.explorationremix." + rune.id + ".desc").withStyle(net.minecraft.ChatFormatting.GRAY));
        if (rune.isAction()) {
            tips.add(Component.translatable("rune.explorationremix.cost", rune.cost).withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
            tips.add(Component.translatable("rune.explorationremix.phase_two", rune.phaseLevel, rune.phaseTwo).withStyle(s -> s.withColor(0xA9B8E8)));
        } else if (rune.cost > 0) {
            tips.add(Component.translatable("rune.explorationremix.cost_each", rune.cost).withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        }
        tips.add(Component.translatable("rune.explorationremix.set_at_table").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
    }

    // ---- nothing is lost by tinkering

    @Override
    public int getEntityLifespan(ItemStack stack, Level level) { return 20 * 60 * 60; }

    @Override
    public boolean canBeHurtBy(DamageSource source) { return false; }

    @Override
    public boolean onEntityItemUpdate(ItemStack stack, ItemEntity entity) {
        // dropped into the void, a rune floats back up rather than falling out of the world
        if (entity.getY() < entity.level().getMinBuildHeight() + 1 && entity.getDeltaMovement().y < 0) {
            entity.setDeltaMovement(entity.getDeltaMovement().multiply(0.5, -0.4, 0.5).add(0, 0.6, 0));
        }
        return false;
    }
}
