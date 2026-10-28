package com.explorationremix.enchant;

import com.explorationremix.config.ERConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** The visible half of the enchanting removal: dead tables, clean mob gear, honest tooltips. */
public final class EnchantingEvents {
    private EnchantingEvents() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock e) {
        if (!ERConfig.INSTANCE.disableEnchantingTable.get()) return;
        if (!e.getLevel().getBlockState(e.getPos()).is(Blocks.ENCHANTING_TABLE)) return;
        e.setCanceled(true);
        if (e.getEntity() instanceof ServerPlayer p) {
            p.displayClientMessage(Component.translatable("run.explorationremix.no_enchant").withStyle(ChatFormatting.GRAY), true);
        }
    }

    /** Mobs that spawn with enchanted gear (zombies, skeletons, piglins) lose the enchantments before they appear. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent e) {
        if (e.loadedFromDisk() || e.getLevel().isClientSide() || !ERConfig.INSTANCE.stripLootEnchantments.get()) return;
        if (!(e.getEntity() instanceof Mob mob)) return;
        for (ItemStack s : mob.getAllSlots()) Enchantments.strip(s);
    }

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent e) {
        if (!Enchantments.nullify() || !Enchantments.carriesDead(e.getItemStack())) return;
        e.getToolTip().add(Component.translatable("rune.explorationremix.tooltip.vanilla_dead").withStyle(ChatFormatting.DARK_GRAY));
    }
}
