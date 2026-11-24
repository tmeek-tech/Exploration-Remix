package com.explorationremix.rune.menu;

import com.explorationremix.rune.Combat;
import com.explorationremix.rune.RuneGear;
import com.explorationremix.rune.RuneRegistry;
import com.explorationremix.rune.engine.CastState;
import com.explorationremix.rune.engine.HostSpec;
import com.explorationremix.rune.engine.Loadout;
import com.explorationremix.rune.engine.Reading;
import com.explorationremix.rune.engine.RuneId;
import com.explorationremix.rune.engine.Socket;
import com.explorationremix.rune.item.RuneItem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.EnchantmentTableBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkHooks;

import java.util.EnumMap;
import java.util.Map;

/**
 * An enchanting table opened with a weapon in the main hand: the weapon's three sockets (an active rune, a modifier, a
 * passive), set from the runes the player carries. The client draws it in the world, over the table
 * (RuneTableScreen); this is the server's side, which decides every change.
 *
 * <p>The weapon never leaves the hand, and a rune is always in exactly one place: setting one takes that rune item
 * from the inventory and hands back the one it replaces, taking one out hands it back. The player's inventory is in
 * the menu, unseen, only so their game is kept up to date with what they carry while it is open. Changes are menu
 * buttons ({@link #button}); while in combat (hurt in the last few seconds) runes can't be changed, and being hurt
 * closes it. Button {@link #ENCHANT} swaps to the table's own enchanting.
 */
public class RuneTableMenu extends AbstractContainerMenu {
    /** Socket {@code s} set to rune {@code r}: {@code s * STRIDE + r.ordinal() + 1}; taken out: {@code s * STRIDE}. */
    public static final int STRIDE = 32;
    /** The table's own enchanting, for the weapon or anything else. */
    public static final int ENCHANT = 120;
    /** How far the player may stand from the table (as for any container). */
    private static final double REACH_SQR = 64.0;

    private final Player player;
    private final BlockPos table;
    /** Ticks until runes can be changed (0: now); the server keeps it, the client is told. */
    private final DataSlot lock = DataSlot.standalone();

    public static int setButton(Socket s, RuneId r) { return s.ordinal() * STRIDE + r.ordinal() + 1; }

    public static int clearButton(Socket s) { return s.ordinal() * STRIDE; }

    /** The client's copy: the server says which table it is. */
    public static RuneTableMenu client(int id, Inventory inv, FriendlyByteBuf buf) { return new RuneTableMenu(id, inv, buf.readBlockPos()); }

    /** Whether this block opens the rune sockets for a weapon (an enchanting table, a mod's included). */
    public static boolean isTable(BlockState state) { return state.getBlock() instanceof EnchantmentTableBlock; }

    /** Opens the sockets of the weapon in the player's main hand, at the table at {@code pos}. */
    public static void open(ServerPlayer p, BlockPos pos) {
        if (!p.isAlive() || p.isSpectator() || p.isSleeping() || !RuneGear.isGear(p.getMainHandItem())) return;
        BlockPos at = pos.immutable();
        NetworkHooks.openScreen(p, new SimpleMenuProvider((id, inv, pl) -> new RuneTableMenu(id, inv, at),
                Component.translatable("container.explorationremix.rune_table")), buf -> buf.writeBlockPos(at));
        p.level().playSound(null, at, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 0.6F, 1.3F);
    }

    public RuneTableMenu(int id, Inventory inv, BlockPos table) {
        super(RuneRegistry.TABLE.get(), id);
        this.player = inv.player;
        this.table = table;
        // the inventory, unseen: its slots keep the player's game told what they carry (runes come and go from it)
        for (int i = 0; i < 36; i++) addSlot(new Unseen(inv, i));
        addSlot(new Unseen(inv, Inventory.SLOT_OFFHAND));
        addDataSlot(lock);
    }

    // ------------------------------------------------------------------------------------------------ what the view reads

    public BlockPos table() { return table; }

    /** The weapon whose sockets these are: the one in the main hand (empty if there is none). */
    public ItemStack weapon() { return player.getMainHandItem(); }

    public Loadout loadout() { return RuneGear.loadout(weapon()); }

    /** Ticks until runes can be changed (0: now). */
    public int lockTicks() { return player.level().isClientSide ? lock.get() : Combat.ticksUntilFree(player); }

    public boolean locked() { return lockTicks() > 0; }

    /** How many of each rune the player carries (main inventory and off hand), for the socket {@code s}'s kind. */
    public Map<RuneId, Integer> carried(Socket s) {
        Map<RuneId, Integer> out = new EnumMap<>(RuneId.class);
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            RuneId r = RuneItem.runeOf(inv.getItem(i));
            if (r != null && r.kind == s.kind) out.merge(r, inv.getItem(i).getCount(), Integer::sum);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ server rules

    @Override
    public void broadcastChanges() {
        if (!player.level().isClientSide) lock.set(Math.min(Short.MAX_VALUE, Combat.ticksUntilFree(player)));
        super.broadcastChanges();
    }

    @Override
    public boolean clickMenuButton(Player p, int id) {
        if (p.level().isClientSide || !(p instanceof ServerPlayer sp)) return false;
        if (id == ENCHANT) {
            enchant(sp);
            return false;
        }
        if (id < 0 || id >= Socket.values().length * STRIDE) return false;
        Socket s = Socket.byIndex(id / STRIDE);
        int r = id % STRIDE;
        if (s == null || locked() || !RuneGear.isGear(weapon())) return false;
        return r == 0 ? takeOut(sp, s) : set(sp, s, RuneId.byOrdinal(r - 1));
    }

    /** Sets {@code r} into the socket {@code s}: one is taken from what they carry, the one there before handed back. */
    private boolean set(ServerPlayer p, Socket s, RuneId r) {
        if (r == null || r.kind != s.kind) return false;
        Loadout before = loadout();
        RuneId was = before.get(s);
        if (was == r) return false;
        if (!takeOne(p.getInventory(), r)) return false;
        write(before.with(s, r));
        if (was != null) give(p, RuneItem.stack(was, 1));
        sound(SoundEvents.AMETHYST_BLOCK_RESONATE, 0.9F, pitch(s));
        sound(SoundEvents.ENCHANTMENT_TABLE_USE, 0.35F, 1.5F);
        return true;
    }

    /** Takes the rune out of the socket {@code s}, back to the player. */
    private boolean takeOut(ServerPlayer p, Socket s) {
        Loadout before = loadout();
        RuneId was = before.get(s);
        if (was == null) return false;
        write(before.with(s, null));
        give(p, RuneItem.stack(was, 1));
        sound(SoundEvents.AMETHYST_CLUSTER_BREAK, 0.6F, 1.4F);
        return true;
    }

    private static float pitch(Socket s) {
        return switch (s) {
            case ACTIVE -> 1.0F;
            case MODIFIER -> 1.26F;
            case PASSIVE -> 1.5F;
        };
    }

    private void sound(net.minecraft.sounds.SoundEvent e, float volume, float pitch) {
        player.level().playSound(null, table, e, SoundSource.BLOCKS, volume, pitch);
    }

    /** One of this rune from the inventory: from a stack in the main inventory first, the off hand last. */
    private static boolean takeOne(Inventory inv, RuneId r) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (RuneItem.runeOf(st) == r && !st.isEmpty()) {
                st.shrink(1);
                if (st.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                inv.setChanged();
                return true;
            }
        }
        return false;
    }

    private static void give(Player p, ItemStack s) {
        if (s.isEmpty()) return;
        if (p.isDeadOrDying()) p.drop(s, false);
        else p.getInventory().placeItemBackInInventory(s);
    }

    /**
     * Writes the sockets. A change waits out the longer of the recovery before and after it from the last cast, so
     * swapping runes never skips a wait.
     */
    private void write(Loadout l) {
        ItemStack g = weapon();
        HostSpec spec = RuneGear.spec(g);
        if (spec == null) return;
        Reading before = RuneGear.reading(g, player);
        RuneGear.setLoadout(g, l);
        if (RuneGear.hasState(g)) {
            long now = player.level().getGameTime();
            CastState s = RuneGear.state(g, spec, now);
            Reading after = RuneGear.reading(g, player);
            int recovery = Math.max(before == null ? 0 : before.recovery(), after == null ? 0 : after.recovery());
            s.readyAt = Math.max(s.readyAt, s.lastCast + recovery);
            s.cursor = 0;
            RuneGear.setState(g, s);
        }
        player.getInventory().setChanged();
    }

    /** The table's own enchanting, for whatever they hold. */
    private void enchant(ServerPlayer p) {
        BlockState state = p.level().getBlockState(table);
        if (!isTable(state)) return;
        net.minecraft.world.MenuProvider provider = state.getMenuProvider(p.level(), table);
        if (provider != null) p.openMenu(provider);
    }

    @Override
    public boolean stillValid(Player p) {
        return p.isAlive() && RuneGear.isGear(p.getMainHandItem()) && isTable(p.level().getBlockState(table))
                && p.distanceToSqr(table.getX() + 0.5, table.getY() + 0.5, table.getZ() + 0.5) <= REACH_SQR;
    }

    /** Nothing moves in or out of the unseen inventory here. */
    @Override
    public ItemStack quickMoveStack(Player p, int index) { return ItemStack.EMPTY; }

    /** One of the player's own inventory slots, kept in step with their game but never shown or clicked. */
    private static final class Unseen extends Slot {
        Unseen(Inventory inv, int i) { super(inv, i, -10000, -10000); }

        @Override
        public boolean mayPlace(ItemStack s) { return false; }

        @Override
        public boolean mayPickup(Player p) { return false; }

        @Override
        public boolean isActive() { return false; }
    }
}
