package com.explorationremix.rune.menu;

import com.explorationremix.rune.Combat;
import com.explorationremix.rune.RunDepth;
import com.explorationremix.rune.RuneGear;
import com.explorationremix.rune.RuneRegistry;
import com.explorationremix.rune.engine.CastState;
import com.explorationremix.rune.engine.HostSpec;
import com.explorationremix.rune.engine.Reading;
import com.explorationremix.rune.engine.Row;
import com.explorationremix.rune.engine.RuneId;
import com.explorationremix.rune.item.RuneItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkHooks;

/**
 * The rune screen: one piece of gear in the gear slot, its row of rune slots, and the player's inventory. Opened with
 * the rune key (R) anywhere, once nothing has hurt the player for a few seconds. The gear slot is the hand itself (the
 * main hand, or the off hand when only it holds gear): the gear never leaves the player's inventory, so it is saved
 * with them, travels with them and can't be lost whatever happens while the screen is open. Its hotbar slot is left
 * out of the screen's hotbar, so it shows in one place.
 *
 * <p>The rune slots are a view of the gear's row, never a store of their own: placing a rune writes it into the gear
 * and uses the item up, taking one out clears it from the gear and hands back the same item. So a rune is always in
 * exactly one place, whatever is clicked, and taking the gear out of the gear slot takes its runes with it. The server
 * checks every move (the slot rules below run there); being hurt closes the screen. The breaks between slots are menu
 * buttons: button {@code i} toggles the break after slot {@code i}.
 */
public class RuneEditorMenu extends AbstractContainerMenu {
    // ---- the layout, which the screen draws
    public static final int WIDTH = 176, HEIGHT = 222;
    public static final int GEAR_X = 8, GEAR_Y = 18;
    /** Rune slot {@code i} sits at {@link #runeX}(i), {@link #ROW_Y}. */
    public static final int ROW_X = 19, ROW_Y = 44, ROW_STEP = 24;
    public static final int INV_X = 8, INV_Y = 140, HOTBAR_Y = 198;
    /** The most slots any gear has (a netherite sword). */
    public static final int MAX_SLOTS = 6;
    public static final int GEAR_SLOT = 0, RUNE_SLOTS = 1, INV_SLOTS = RUNE_SLOTS + MAX_SLOTS, INV_END = INV_SLOTS + 36;

    private final Player player;
    private final RowView row = new RowView();
    /** Ticks until runes can be moved (0: now); the server keeps it, the client is told. */
    private final DataSlot lock = DataSlot.standalone();
    /** The inventory slot the gear slot shows: a hotbar slot, or the off hand. */
    private final int handSlot;

    public static int runeX(int i) { return ROW_X + i * ROW_STEP; }

    /** The client's copy: the server says which hand it shows, and what is in it. */
    public static RuneEditorMenu client(int id, Inventory inv, FriendlyByteBuf buf) { return new RuneEditorMenu(id, inv, buf.readVarInt()); }

    /** Opens the rune screen for a player, with the gear in hand in the gear slot. Refused in combat. */
    public static void open(ServerPlayer p) {
        if (!p.isAlive() || p.isSpectator() || p.isSleeping()) return;
        int wait = Combat.ticksUntilFree(p);
        if (wait > 0) {
            p.displayClientMessage(Component.translatable("message.explorationremix.runes.in_combat", (wait + 19) / 20), true);
            return;
        }
        Inventory inv = p.getInventory();
        int hand = !RuneGear.isGear(inv.getSelected()) && RuneGear.isGear(inv.offhand.get(0)) ? Inventory.SLOT_OFFHAND : inv.selected;
        NetworkHooks.openScreen(p, new SimpleMenuProvider((id, pinv, pl) -> new RuneEditorMenu(id, pinv, hand),
                Component.translatable("container.explorationremix.rune_editor")), buf -> buf.writeVarInt(hand));
    }

    public RuneEditorMenu(int id, Inventory inv, int handSlot) {
        super(RuneRegistry.EDITOR.get(), id);
        this.player = inv.player;
        this.handSlot = handSlot == Inventory.SLOT_OFFHAND || Inventory.isHotbarSlot(handSlot) ? handSlot : inv.selected;
        addSlot(new GearSlot(inv, this.handSlot, GEAR_X, GEAR_Y));
        for (int i = 0; i < MAX_SLOTS; i++) addSlot(new RuneSlot(row, i, runeX(i), ROW_Y));
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) addSlot(new Slot(inv, c + r * 9 + 9, INV_X + c * 18, INV_Y + r * 18));
        }
        // the hand's own hotbar place is the gear slot above; here it is left out, backed by nothing, so no two slots
        // ever hold the same stack (a shift-click would merge it into itself)
        for (int c = 0; c < 9; c++) {
            addSlot(c == this.handSlot ? new LeftOutSlot(new SimpleContainer(1), 0, INV_X + c * 18, HOTBAR_Y) : new Slot(inv, c, INV_X + c * 18, HOTBAR_Y));
        }
        addDataSlot(lock);
        // runes stored past the gear's last slot (only edited data puts them there) go back to the player at once
        returnOverflow(gear());
    }

    // ------------------------------------------------------------------------------------------------ what the screen reads

    /** The gear in the gear slot (empty if none). */
    public ItemStack gear() { return slots.get(GEAR_SLOT).getItem(); }

    /** Slots the gear has (0 if there is no gear). */
    public int slotCount() {
        HostSpec spec = RuneGear.spec(gear());
        return spec == null ? 0 : Math.min(MAX_SLOTS, spec.slotCount());
    }

    /** The gear's row (empty if there is no gear). */
    public Row row() { return RuneGear.row(gear()); }

    /** Ticks until runes can be moved (0: now). */
    public int lockTicks() { return player.level().isClientSide ? lock.get() : Combat.ticksUntilFree(player); }

    public boolean locked() { return lockTicks() > 0; }

    // ------------------------------------------------------------------------------------------------ server rules

    @Override
    public void broadcastChanges() {
        if (!player.level().isClientSide) lock.set(Math.min(Short.MAX_VALUE, Combat.ticksUntilFree(player)));
        super.broadcastChanges();
    }

    /** Button {@code i}: toggles the break after slot {@code i}. */
    @Override
    public boolean clickMenuButton(Player p, int id) {
        int n = slotCount();
        if (id < 0 || id >= n - 1 || locked()) return false;
        Row r = row();
        write(r.withBreak(id, !r.breakAfter(id)));
        return true;
    }

    @Override
    public boolean stillValid(Player p) { return p.isAlive(); }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) { return slot.container != row && super.canTakeItemForPickAll(stack, slot); }

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack before = stack.copy();
        if (index == GEAR_SLOT) {
            if (!moveItemStackTo(stack, INV_SLOTS, INV_END, true)) return ItemStack.EMPTY;
            slot.setByPlayer(stack.isEmpty() ? ItemStack.EMPTY : stack);
            return before;
        }
        if (index < INV_SLOTS) {
            // a rune back to the inventory; the slot is cleared only once it fits
            if (!slot.mayPickup(p) || !moveItemStackTo(stack, INV_SLOTS, INV_END, true)) return ItemStack.EMPTY;
            slot.setByPlayer(ItemStack.EMPTY);
            return ItemStack.EMPTY;
        }
        if (RuneGear.isGear(stack) && !slots.get(GEAR_SLOT).hasItem()) {
            if (!moveItemStackTo(stack, GEAR_SLOT, GEAR_SLOT + 1, false)) return ItemStack.EMPTY;
        } else if (RuneItem.runeOf(stack) != null) {
            // one rune into the first open slot of the row (one per click)
            if (!insertRune(stack)) return ItemStack.EMPTY;
            if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
            else slot.setChanged();
            return ItemStack.EMPTY;
        } else if (index < INV_SLOTS + 27) {
            if (!moveItemStackTo(stack, INV_SLOTS + 27, INV_END, false)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(stack, INV_SLOTS, INV_SLOTS + 27, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        if (stack.getCount() == before.getCount()) return ItemStack.EMPTY;
        slot.onTake(p, stack);
        return before;
    }

    private boolean insertRune(ItemStack stack) {
        for (int i = 0; i < MAX_SLOTS; i++) {
            Slot s = slots.get(RUNE_SLOTS + i);
            if (!s.hasItem() && s.mayPlace(stack)) {
                s.setByPlayer(stack.split(1));
                return true;
            }
        }
        return false;
    }

    /**
     * Whatever is on the cursor goes back into the inventory (the gear never left it). The player is only "not alive"
     * while being moved to another world, so being dead is what counts, as it does for a logout (the screen is closed
     * before they are saved: RuneEvents).
     */
    @Override
    public void removed(Player p) {
        if (!p.level().isClientSide) {
            ItemStack carried = getCarried();
            if (!carried.isEmpty()) {
                setCarried(ItemStack.EMPTY);
                giveBack(p, carried);
            }
        }
        super.removed(p);
    }

    /** Into the inventory (or dropped at the player's feet if there is no room, or they are dead). */
    private static void giveBack(Player p, ItemStack s) {
        if (s.isEmpty()) return;
        if (p.isDeadOrDying()) p.drop(s, false);
        else p.getInventory().placeItemBackInInventory(s);
    }

    /**
     * Writes a row into the gear, which then starts again from its first cast. A row left part-way through waits out
     * the new row's recovery from its last cast, as finishing it would have, so an edit can't repeat a first cast early.
     */
    private void write(Row r) {
        ItemStack g = gear();
        HostSpec spec = RuneGear.spec(g);
        if (spec == null) return;
        RuneGear.setRow(g, r);
        if (!player.level().isClientSide && RuneGear.hasState(g)) {
            long now = player.level().getGameTime();
            CastState s = RuneGear.state(g, spec, now);
            if (s.cursor != 0) {
                Reading after = RuneGear.reading(g, RunDepth.get(player));
                if (after != null) s.readyAt = Math.max(s.readyAt, s.lastCast + after.recovery());
            }
            s.cursor = 0;
            RuneGear.setState(g, s);
        }
        slots.get(GEAR_SLOT).setChanged();
    }

    /** Runes stored past the gear's last slot (only edited data puts them there) go back to the player. */
    private void returnOverflow(ItemStack g) {
        if (player.level().isClientSide || !RuneGear.isGear(g) || RuneGear.overflow(g).isEmpty()) return;
        for (RuneId r : RuneGear.overflow(g)) giveBack(player, RuneItem.stack(r, 1));
        RuneGear.setRow(g, RuneGear.row(g));
    }

    // ------------------------------------------------------------------------------------------------ slots

    /** The hand the screen edits: the player's own inventory slot. */
    private final class GearSlot extends Slot {
        GearSlot(Container c, int i, int x, int y) { super(c, i, x, y); }

        @Override
        public boolean mayPlace(ItemStack s) { return RuneGear.isGear(s); }

        @Override
        public int getMaxStackSize() { return 1; }

        @Override
        public void set(ItemStack s) {
            super.set(s);
            returnOverflow(s);
        }
    }

    /** The hand's own hotbar slot, left out of the screen's hotbar (it is the gear slot): not drawn, not clickable. */
    private static final class LeftOutSlot extends Slot {
        LeftOutSlot(Container c, int i, int x, int y) { super(c, i, x, y); }

        @Override
        public boolean mayPlace(ItemStack s) { return false; }

        @Override
        public boolean mayPickup(Player p) { return false; }

        @Override
        public boolean isActive() { return false; }
    }

    private final class RuneSlot extends Slot {
        private final int index;

        RuneSlot(Container c, int i, int x, int y) {
            super(c, i, x, y);
            this.index = i;
        }

        @Override
        public boolean mayPlace(ItemStack s) { return index < slotCount() && !locked() && RuneItem.runeOf(s) != null; }

        @Override
        public boolean mayPickup(Player p) { return index < slotCount() && !locked() && hasItem(); }

        @Override
        public int getMaxStackSize() { return 1; }

        @Override
        public int getMaxStackSize(ItemStack s) { return 1; }

        /** Slots past the gear's last are not shown and can't be clicked. */
        @Override
        public boolean isActive() { return index < slotCount(); }
    }

    /** The gear's row as a container of rune items: a view, never a store. */
    private final class RowView implements Container {
        private RuneId runeAt(int i) {
            Row r = row();
            return i >= 0 && i < r.size() ? r.get(i) : null;
        }

        @Override
        public int getContainerSize() { return MAX_SLOTS; }

        @Override
        public boolean isEmpty() { return !RuneGear.hasRunes(gear()); }

        @Override
        public ItemStack getItem(int i) {
            RuneId r = runeAt(i);
            return r == null ? ItemStack.EMPTY : RuneItem.stack(r, 1);
        }

        @Override
        public ItemStack removeItem(int i, int n) {
            RuneId r = runeAt(i);
            if (n <= 0 || r == null) return ItemStack.EMPTY;
            write(row().with(i, null));
            return RuneItem.stack(r, 1);
        }

        @Override
        public ItemStack removeItemNoUpdate(int i) { return removeItem(i, 1); }

        @Override
        public void setItem(int i, ItemStack stack) {
            boolean server = !player.level().isClientSide;
            RuneId r = stack.isEmpty() ? null : RuneItem.runeOf(stack);
            if (i < 0 || i >= slotCount() || (!stack.isEmpty() && r == null)) {
                // nowhere for it: never lost, never kept here
                if (server && !stack.isEmpty()) giveBack(player, stack);
                return;
            }
            write(row().with(i, r));
            if (server && stack.getCount() > 1) giveBack(player, stack.copyWithCount(stack.getCount() - 1));
        }

        @Override
        public int getMaxStackSize() { return 1; }

        @Override
        public void setChanged() {}

        @Override
        public boolean stillValid(Player p) { return true; }

        @Override
        public void clearContent() {}

        @Override
        public boolean canPlaceItem(int i, ItemStack s) { return i < slotCount() && RuneItem.runeOf(s) != null; }
    }
}
