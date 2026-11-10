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
 * A rune: an item you hold, keep, trade and slot into gear. One item per rune, carrying nothing but what it is, so a
 * rune taken out of gear is the same item again. Runes never burn, never despawn and can't be destroyed by blasts or
 * cactus, so nothing is lost by tinkering.
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

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tips, TooltipFlag flag) {
        // what it does and what it changes, in the rune list's words (lang: rune.explorationremix.<id>.desc)
        tips.add(Component.translatable("rune.explorationremix.kind." + rune.kind.name().toLowerCase(java.util.Locale.ROOT))
                .withStyle(net.minecraft.ChatFormatting.DARK_AQUA));
        tips.add(Component.translatable("rune.explorationremix." + rune.id + ".desc").withStyle(net.minecraft.ChatFormatting.GRAY));
        if (rune.cost > 0) {
            // an action costs its own energy; a modifier or element adds its cost to every action it changes
            String key = rune.isAction() ? "rune.explorationremix.cost" : "rune.explorationremix.cost_each";
            tips.add(Component.translatable(key, rune.cost).withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        }
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
