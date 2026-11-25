package com.explorationremix.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A choir key (0.21.9): the thing an ancient city's reliquary gives up. Three of them, set in the sockets round a
 * Hollow Choir's arena, call the Choir. The tooltip says so, since nothing else in the deep tells you.
 */
public class ChoirKeyItem extends Item {
    public ChoirKeyItem(Properties properties) { super(properties); }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("item.explorationremix.choir_key.tip").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("item.explorationremix.choir_key.tip2").withStyle(ChatFormatting.DARK_GRAY));
    }
}
