package com.explorationremix.rune.menu;

import com.explorationremix.block.entity.RuneBlockEntity;
import com.explorationremix.config.ERConfig;
import com.explorationremix.network.ERNetwork;
import com.explorationremix.network.RuneEditPacket;
import com.explorationremix.network.RuneMenuStatePacket;
import com.explorationremix.registry.ERMenus;
import com.explorationremix.rune.AttunementBook;
import com.explorationremix.rune.Rune;
import com.explorationremix.rune.RuneRegistry;
import com.explorationremix.rune.RuneTool;
import com.explorationremix.run.RunManager;
import com.explorationremix.run.RunState;
import com.explorationremix.run.RunXp;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * The rune block editor. The tool being edited stays in the player's hand; the menu shows the inventory so the tool
 * syncs normally and so attunement books can be used from it. Rune order on the tool is the sentence order.
 */
public class RuneBlockMenu extends AbstractContainerMenu {
    public final BlockPos pos;
    public final InteractionHand hand;
    public final int toolSlot;
    // client-visible state, refreshed by RuneMenuStatePacket
    public List<ResourceLocation> offers = new ArrayList<>();
    public int wakeCost;
    public int xp;
    public boolean wokenThisRun;
    public boolean inRun;
    public int tier;

    public RuneBlockMenu(int id, Inventory inv, FriendlyByteBuf buf) {
        this(id, inv, buf.readBlockPos(), buf.readEnum(InteractionHand.class));
        applyState(RuneMenuStatePacket.read(buf));
    }

    public RuneBlockMenu(int id, Inventory inv, BlockPos pos, InteractionHand hand) {
        super(ERMenus.RUNE_BLOCK.get(), id);
        this.pos = pos;
        this.hand = hand;
        this.toolSlot = hand == InteractionHand.MAIN_HAND ? inv.selected : Inventory.SLOT_OFFHAND;
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++) addSlot(lockedIfTool(inv, col + row * 9 + 9, 8 + col * 18, 140 + row * 18));
        for (int col = 0; col < 9; col++) addSlot(lockedIfTool(inv, col, 8 + col * 18, 198));
    }

    private Slot lockedIfTool(Inventory inv, int index, int x, int y) {
        return new Slot(inv, index, x, y) {
            @Override public boolean mayPickup(Player player) { return index != toolSlot; }
            @Override public boolean mayPlace(ItemStack stack) { return index != toolSlot; }
        };
    }

    public ItemStack tool(Player player) { return player.getInventory().getItem(toolSlot); }

    public void applyState(RuneMenuStatePacket s) {
        offers = new ArrayList<>(s.offers()); wakeCost = s.wakeCost(); xp = s.xp(); wokenThisRun = s.wokenThisRun(); inRun = s.inRun(); tier = s.tier();
    }

    public static RuneMenuStatePacket stateFor(ServerPlayer p, RuneBlockEntity be) {
        RunState s = RunManager.state(p);
        boolean inRun = s.active;
        boolean woken = inRun && be.wokenIn(p.getUUID()) == s.runId;
        List<ResourceLocation> offers = woken ? be.offersFor(p.getUUID()) : List.of();
        return new RuneMenuStatePacket(offers, wakeCost(be), RunXp.points(p), woken, inRun, be.tier());
    }

    public static int wakeCost(RuneBlockEntity be) {
        return ERConfig.INSTANCE.runeBlockBaseCost.get() + ERConfig.INSTANCE.runeBlockCostPerLayer.get() * Math.max(0, be.tier() - 1);
    }

    private RuneBlockEntity blockEntity(ServerPlayer p) {
        BlockEntity be = p.level().getBlockEntity(pos);
        return be instanceof RuneBlockEntity r ? r : null;
    }

    private void sync(ServerPlayer p, RuneBlockEntity be) {
        ERNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), stateFor(p, be));
        p.getInventory().setChanged();
        broadcastChanges();
    }

    public void handle(ServerPlayer p, RuneEditPacket packet) {
        RuneBlockEntity be = blockEntity(p);
        if (be == null) return;
        RunState s = RunManager.state(p);
        ItemStack tool = tool(p);
        if (!RuneTool.canHoldRunes(tool)) return;
        switch (packet.action()) {
            case WAKE -> wake(p, be, s, tool);
            case PICK_OFFER -> pick(p, be, s, tool, packet.a());
            case MOVE -> move(p, s, tool, packet.a(), packet.b());
            case REMOVE -> remove(p, s, tool, packet.a());
            case ATTUNE -> attune(p, tool, packet.a());
        }
        sync(p, be);
    }

    private void wake(ServerPlayer p, RuneBlockEntity be, RunState s, ItemStack tool) {
        if (!s.active) { p.displayClientMessage(Component.translatable("rune.explorationremix.block.no_run").withStyle(ChatFormatting.GRAY), true); return; }
        if (be.wokenIn(p.getUUID()) == s.runId) return;
        int cost = wakeCost(be);
        if (!RunXp.spend(p, cost, s)) {
            p.displayClientMessage(Component.translatable("rune.explorationremix.block.not_enough", cost).withStyle(ChatFormatting.RED), true);
            return;
        }
        List<ResourceLocation> exclude = new ArrayList<>(RuneTool.runes(tool));
        List<ResourceLocation> offers = new ArrayList<>();
        String attunement = RuneTool.attunement(tool);
        for (int i = 0; i < 3; i++) {
            Rune r = RuneRegistry.roll(p.getRandom(), be.tier(), attunement, exclude);
            if (r == null) break;
            offers.add(r.id());
            exclude.add(r.id());
        }
        be.markWoken(p.getUUID(), s.runId);
        be.setOffers(p.getUUID(), offers);
        be.setLit(true);
        s.runeBlocksWoken++;
        p.level().playSound(null, pos, SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.BLOCKS, 0.8F, 0.7F);
    }

    private void pick(ServerPlayer p, RuneBlockEntity be, RunState s, ItemStack tool, int index) {
        List<ResourceLocation> offers = be.offersFor(p.getUUID());
        if (!s.active || index < 0 || index >= offers.size()) return;
        ResourceLocation id = offers.get(index);
        if (RuneRegistry.get(id) == null) return;
        if (!RuneTool.addRune(tool, id, s.runId)) {
            p.displayClientMessage(Component.translatable("rune.explorationremix.block.full").withStyle(ChatFormatting.RED), true);
            return;
        }
        be.setOffers(p.getUUID(), List.of());
        s.runesTaken.add(id.toString());
        p.level().playSound(null, pos, SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS, 0.8F, 1.2F);
    }

    private void move(ServerPlayer p, RunState s, ItemStack tool, int from, int to) {
        if (!s.active || RuneTool.runStamp(tool) != s.runId) return;
        List<ResourceLocation> runes = RuneTool.runes(tool);
        if (from < 0 || from >= runes.size() || to < 0 || to >= runes.size()) return;
        ResourceLocation r = runes.remove(from);
        runes.add(to, r);
        RuneTool.setRunes(tool, runes, s.runId);
    }

    private void remove(ServerPlayer p, RunState s, ItemStack tool, int index) {
        List<ResourceLocation> runes = RuneTool.runes(tool);
        if (index < 0 || index >= runes.size()) return;
        boolean live = s.active && RuneTool.runStamp(tool) == s.runId;
        runes.remove(index);
        RuneTool.setRunes(tool, runes, live ? s.runId : RuneTool.runStamp(tool));
        p.level().playSound(null, pos, SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 0.5F, 1.4F);
    }

    private void attune(ServerPlayer p, ItemStack tool, int invSlot) {
        if (invSlot < 0 || invSlot >= p.getInventory().getContainerSize()) return;
        ItemStack book = p.getInventory().getItem(invSlot);
        String att = AttunementBook.attunementOf(book);
        if (att.isEmpty()) return;
        RuneTool.setAttunement(tool, att);
        book.shrink(1);
        p.level().playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 1.0F, 0.8F);
        p.displayClientMessage(Component.translatable("rune.explorationremix.attuned", Component.translatable("attunement.explorationremix." + att)).withStyle(ChatFormatting.AQUA), true);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }

    @Override
    public boolean stillValid(Player player) {
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0 && RuneTool.canHoldRunes(tool(player));
    }
}
