package com.explorationremix.rune.menu;

import com.explorationremix.rune.Combat;
import com.explorationremix.rune.RuneGear;
import com.explorationremix.rune.RuneRegistry;
import com.explorationremix.rune.RuneTags;
import com.explorationremix.rune.engine.ArmorRunes;
import com.explorationremix.rune.engine.CastState;
import com.explorationremix.rune.engine.HostSpec;
import com.explorationremix.rune.engine.Loadout;
import com.explorationremix.rune.engine.Piece;
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

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * An enchanting table: the rune sockets of what is in the main hand (a weapon's active rune, modifier and passive, a
 * tool's one tool rune, or a piece of armor's effect rune and two collect runes) and of each piece of armor worn, set
 * from the runes the player carries. Since 0.22.6 the table enchants no more: it is where runes are set, whatever is in
 * hand. Since 0.23.0 a piece of armor held in the hand is set there too, worn or not (Taran, 2026-11-26: "make it so
 * that you can right click armor held in your hand onto the enchanting table"). The client draws it in the world, over
 * the table (RuneTableScreen); this is the server's side, which decides every change.
 *
 * <p>Gear never leaves the hand or the body, and a rune is always in exactly one place: setting one takes that rune
 * item from the inventory and hands back the one it replaces, taking one out hands it back. The player's inventory and
 * armor are in the menu, unseen, only so their game is kept up to date with what they carry and wear while it is open.
 * Changes are RuneNet.TableEdit messages ({@link #edit}: a menu button's one byte can't say which piece and which
 * rune); while in combat (hurt in the last few seconds) runes can't be changed, and being hurt closes it.
 */
public class RuneTableMenu extends AbstractContainerMenu {
    /** Gear 0 is the weapon, tool or piece of armor in the main hand; 1 to 4 the worn helmet, chestplate, leggings and boots. */
    public static final int HAND = 0, GEAR = 1 + Piece.values().length;
    /** How far the player may stand from the table (as for any container). */
    private static final double REACH_SQR = 64.0;

    private final Player player;
    private final BlockPos table;
    /** Ticks until runes can be changed (0: now); the server keeps it, the client is told. */
    private final DataSlot lock = DataSlot.standalone();

    /** The client's copy: the server says which table it is. */
    public static RuneTableMenu client(int id, Inventory inv, FriendlyByteBuf buf) { return new RuneTableMenu(id, inv, buf.readBlockPos()); }

    /** Whether this block is where runes are set (an enchanting table, a mod's included). */
    public static boolean isTable(BlockState state) { return state.getBlock() instanceof EnchantmentTableBlock; }

    /** Opens the rune sockets of what the player holds and wears, at the table at {@code pos}. */
    public static void open(ServerPlayer p, BlockPos pos) {
        if (!p.isAlive() || p.isSpectator() || p.isSleeping()) return;
        BlockPos at = pos.immutable();
        NetworkHooks.openScreen(p, new SimpleMenuProvider((id, inv, pl) -> new RuneTableMenu(id, inv, at),
                Component.translatable("container.explorationremix.rune_table")), buf -> buf.writeBlockPos(at));
        p.level().playSound(null, at, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 0.6F, 1.3F);
    }

    public RuneTableMenu(int id, Inventory inv, BlockPos table) {
        super(RuneRegistry.TABLE.get(), id);
        this.player = inv.player;
        this.table = table;
        // the inventory, unseen: its slots keep the player's game told what they carry (runes come and go from it) and
        // wear (its armor's runes change)
        for (int i = 0; i < 36; i++) addSlot(new Unseen(inv, i));
        addSlot(new Unseen(inv, Inventory.SLOT_OFFHAND));
        for (int i = 0; i < 4; i++) addSlot(new Unseen(inv, 36 + i));
        addDataSlot(lock);
    }

    // ------------------------------------------------------------------------------------------------ what the view reads

    public BlockPos table() { return table; }

    public Player player() { return player; }

    /** The weapon in the main hand (empty if what is there is none). */
    public ItemStack weapon() {
        ItemStack s = player.getMainHandItem();
        return RuneGear.isGear(s) ? s : ItemStack.EMPTY;
    }

    /**
     * What is in the main hand if it takes runes: a weapon, a tool or a piece of armor (empty if none of those, or if it
     * is a stack of several, as another mod's armor might be: a rune set in one would be copied when the stack splits).
     */
    public ItemStack hand() {
        ItemStack s = player.getMainHandItem();
        return RuneGear.takesRunes(s) && s.getCount() == 1 ? s : ItemStack.EMPTY;
    }

    /** Whether the main hand holds a tool (its one socket a tool rune's). */
    public boolean tool() { return RuneGear.isTool(player.getMainHandItem()); }

    /** The piece of armor held in the main hand (not worn), or null if what is there isn't armor that takes runes. */
    @Nullable
    public Piece heldPiece() { return RuneGear.piece(hand()); }

    /** The piece of armor gear {@code g} is: a worn piece's own, or the one held in the hand; null for a weapon or a tool. */
    @Nullable
    public Piece armorPiece(int g) { return g == HAND ? heldPiece() : piece(g); }

    /** The armor worn as {@code p} (empty if none, or if what is worn there isn't armor that takes runes). */
    public ItemStack armor(Piece p) {
        ItemStack s = player.getItemBySlot(RuneGear.slot(p));
        return RuneGear.piece(s) == p ? s : ItemStack.EMPTY;
    }

    /** Gear {@code g}: what is in the hand ({@link #HAND}) or a worn piece (1 helmet to 4 boots); empty if there is none. */
    public ItemStack gear(int g) {
        if (g == HAND) return hand();
        return g > 0 && g < GEAR ? armor(Piece.values()[g - 1]) : ItemStack.EMPTY;
    }

    /** The worn piece gear {@code g} is, or null for the hand (whatever it holds: {@link #armorPiece} says if that is armor). */
    @Nullable
    public static Piece piece(int g) { return g > 0 && g < GEAR ? Piece.values()[g - 1] : null; }

    /** The gear number of a piece of armor. */
    public static int gearOf(Piece p) { return 1 + p.ordinal(); }

    /** Gear {@code g}'s sockets, left to right: a weapon's three, a tool's one, a piece of armor's three (worn or held). */
    public Socket[] sockets(int g) { return armorPiece(g) != null ? Socket.ARMOR : tool() ? Socket.TOOLS : Socket.WEAPON; }

    /** Whether gear {@code g} has socket {@code s}. */
    public boolean has(int g, Socket s) {
        for (Socket t : sockets(g)) if (t == s) return true;
        return false;
    }

    public Loadout loadout() { return RuneGear.loadout(weapon()); }

    /** The rune in socket {@code s} of gear {@code g} (null: empty, or no such gear or socket). */
    public RuneId rune(int g, Socket s) {
        ItemStack st = gear(g);
        if (st.isEmpty() || !has(g, s)) return null;
        // the socket says what the gear is: has() matched it to the gear's own
        if (s.armor()) return RuneGear.armor(st).get(s);
        return s == Socket.TOOL ? RuneGear.tool(st) : RuneGear.loadout(st).get(s);
    }

    /** Ticks until runes can be changed (0: now). */
    public int lockTicks() { return player.level().isClientSide ? lock.get() : Combat.ticksUntilFree(player); }

    public boolean locked() { return lockTicks() > 0; }

    /**
     * How many of each rune the player carries (main inventory and off hand) that could go in socket {@code s} of gear
     * {@code g}: of its kind, and fitting that gear.
     */
    public Map<RuneId, Integer> carried(int g, Socket s) {
        Map<RuneId, Integer> out = new EnumMap<>(RuneId.class);
        int fits = RuneGear.fits(gear(g));
        if (fits == 0) return out;
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            RuneId r = RuneItem.runeOf(st);
            // the armor's own slots are part of the inventory; a rune is never worn, but the count is of carried runes
            if (r != null && r.kind == s.kind && r.fits(fits)) out.merge(r, st.getCount(), Integer::sum);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ server rules

    @Override
    public void broadcastChanges() {
        if (!player.level().isClientSide) lock.set(Math.min(Short.MAX_VALUE, Combat.ticksUntilFree(player)));
        super.broadcastChanges();
    }

    /** Nothing is a button any more: changes come as RuneNet.TableEdit ({@link #edit}). */
    @Override
    public boolean clickMenuButton(Player p, int id) { return false; }

    /**
     * Sets rune {@code runeOrdinal} (of RuneId; below 0 takes out what is there) into socket {@code socketIndex} (of
     * Socket) of gear {@code g}. Refused in combat, for a socket the gear hasn't, or a rune that doesn't go there.
     */
    public void edit(ServerPlayer p, int g, int socketIndex, int runeOrdinal) {
        Socket s = Socket.byIndex(socketIndex);
        if (s == null || g < 0 || g >= GEAR || locked() || !stillValid(p)) return;
        ItemStack gear = gear(g);
        if (gear.isEmpty() || !has(g, s)) return;
        RuneId r = runeOrdinal < 0 ? null : RuneId.byOrdinal(runeOrdinal);
        if (runeOrdinal >= 0 && r == null) return;
        // runes that don't fit this gear (from before 0.22.6) go back first, so a change never loses them
        RuneGear.handBack(gear, p);
        if (r == null) takeOut(p, gear, s);
        else set(p, gear, s, r);
    }

    /**
     * Sets {@code r} into the socket {@code s}: one is taken from what they carry, and what it displaces is handed back
     * (the one there before; on armor, the same collect rune moving from the piece's other collect socket).
     */
    private boolean set(ServerPlayer p, ItemStack gear, Socket s, RuneId r) {
        if (r.kind != s.kind || !r.fits(RuneGear.fits(gear))) return false;
        List<RuneId> before, after;
        Loadout l = null;
        ArmorRunes a = null;
        if (s == Socket.TOOL) {
            RuneId was = RuneGear.tool(gear);
            if (was == r) return false;
            before = was == null ? List.of() : List.of(was);
            after = List.of(r);
        } else if (s.kind.weapon()) {
            Loadout was = RuneGear.loadout(gear);
            if (was.get(s) == r) return false;
            l = was.with(s, r);
            before = was.runes();
            after = l.runes();
        } else {
            ArmorRunes was = RuneGear.armor(gear);
            if (was.get(s) == r) return false;
            a = was.with(s, r);
            before = was.runes();
            after = a.runes();
        }
        // exactly the one rune comes in
        if (!RuneTags.without(after, before).equals(List.of(r))) return false;
        if (!takeOne(p.getInventory(), r)) return false;
        if (s == Socket.TOOL) RuneGear.setTool(gear, r);
        else if (l != null) writeWeapon(l);
        else RuneGear.setArmor(gear, a);
        for (RuneId back : RuneTags.without(before, after)) give(p, RuneItem.stack(back, 1));
        p.getInventory().setChanged();
        sound(SoundEvents.AMETHYST_BLOCK_RESONATE, 0.9F, pitch(s));
        sound(SoundEvents.ENCHANTMENT_TABLE_USE, 0.35F, 1.5F);
        return true;
    }

    /** Takes the rune out of the socket {@code s}, back to the player. */
    private boolean takeOut(ServerPlayer p, ItemStack gear, Socket s) {
        RuneId was;
        if (s == Socket.TOOL) {
            was = RuneGear.tool(gear);
            if (was == null) return false;
            RuneGear.setTool(gear, null);
        } else if (s.kind.weapon()) {
            Loadout l = RuneGear.loadout(gear);
            was = l.get(s);
            if (was == null) return false;
            writeWeapon(l.with(s, null));
        } else {
            ArmorRunes a = RuneGear.armor(gear);
            was = a.get(s);
            if (was == null) return false;
            RuneGear.setArmor(gear, a.with(s, null));
        }
        give(p, RuneItem.stack(was, 1));
        p.getInventory().setChanged();
        sound(SoundEvents.AMETHYST_CLUSTER_BREAK, 0.6F, 1.4F);
        return true;
    }

    private static float pitch(Socket s) {
        return switch (s) {
            case ACTIVE -> 1.0F;
            case MODIFIER -> 1.26F;
            case PASSIVE -> 1.5F;
            case EFFECT -> 1.12F;
            case COLLECT_1, COLLECT_2 -> 1.4F;
            case TOOL -> 1.19F;
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
     * Writes the weapon's sockets. A change waits out the longer of the recovery before and after it from the last
     * cast, so swapping runes never skips a wait.
     */
    private void writeWeapon(Loadout l) {
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
    }

    @Override
    public boolean stillValid(Player p) {
        return p.isAlive() && isTable(p.level().getBlockState(table))
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
