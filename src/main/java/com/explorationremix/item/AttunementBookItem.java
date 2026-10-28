package com.explorationremix.item;

import com.explorationremix.run.RunManager;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * This mod's own book: it holds no rune. Used on a tool at a rune block it attunes the tool to a family of runes,
 * for good. Books come from remains deep in hunting grounds and crumble when the run that found them ends.
 */
public class AttunementBookItem extends Item {
    public AttunementBookItem(Properties p) { super(p); }

    public static ItemStack of(Item item, String attunement, long run) {
        ItemStack s = new ItemStack(item);
        s.getOrCreateTag().putString("Attunement", attunement);
        s.getOrCreateTag().putLong("Run", run);
        return s;
    }

    public static String attunement(ItemStack s) {
        CompoundTag t = s.getTag();
        return t == null ? "" : t.getString("Attunement");
    }

    public static long run(ItemStack s) {
        CompoundTag t = s.getTag();
        return t == null ? 0L : t.getLong("Run");
    }

    @Override
    public Component getName(ItemStack stack) {
        String a = attunement(stack);
        if (a.isEmpty()) return super.getName(stack);
        return Component.translatable("attunement.explorationremix." + a).append(" ").append(super.getName(stack));
    }

    @Override
    public boolean isFoil(ItemStack stack) { return true; }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        String a = attunement(stack);
        if (!a.isEmpty()) tooltip.add(Component.translatable("item.explorationremix.attunement_book.desc", Component.translatable("attunement.explorationremix." + a)).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.explorationremix.attunement_book.crumbles").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (level.isClientSide || !(entity instanceof ServerPlayer p) || p.tickCount % 40 != 0) return;
        long run = run(stack);
        if (run != 0L && RunManager.currentRun(p) != run) {
            stack.shrink(stack.getCount());
            p.displayClientMessage(Component.translatable("item.explorationremix.attunement_book.crumbles").withStyle(ChatFormatting.DARK_GRAY), true);
        }
    }
}
