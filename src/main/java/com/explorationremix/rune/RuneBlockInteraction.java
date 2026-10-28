package com.explorationremix.rune;

import com.explorationremix.block.entity.RuneBlockEntity;
import com.explorationremix.rune.menu.RuneBlockMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkHooks;

/** Right-click a rune block with a tool: opens the editor. Waking (for new runes) happens inside the editor. */
public final class RuneBlockInteraction {
    private RuneBlockInteraction() {}

    public static InteractionResult interact(RuneBlockEntity rune, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!RuneTool.canHoldRunes(stack)) {
            if (!player.level().isClientSide)
                player.displayClientMessage(Component.translatable("rune.explorationremix.block.hold_tool").withStyle(ChatFormatting.GRAY), true);
            return InteractionResult.sidedSuccess(player.level().isClientSide);
        }
        if (player.level().isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        NetworkHooks.openScreen(sp, new SimpleMenuProvider((id, inv, p) -> new RuneBlockMenu(id, inv, rune.getBlockPos(), hand),
                Component.translatable("container.explorationremix.rune_block")), buf -> {
            buf.writeBlockPos(rune.getBlockPos());
            buf.writeEnum(hand);
            RuneBlockMenu.stateFor(sp, rune).write(buf);
        });
        return InteractionResult.CONSUME;
    }
}
