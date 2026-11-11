package com.explorationremix.rune;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.mixin.accessor.ProjectileAccessor;
import com.explorationremix.rune.cast.ArrowRides;
import com.explorationremix.rune.cast.CastService;
import com.explorationremix.rune.cast.Grapples;
import com.explorationremix.rune.cast.Statuses;
import com.explorationremix.rune.engine.Host;
import com.explorationremix.rune.menu.RuneEditorMenu;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.common.ForgeHooks;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.AnvilUpdateEvent;
import net.minecraftforge.event.GrindstoneEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;

/**
 * Where the runes meet the game (Forge bus). The client only ever asks; the server decides every cast, every hit and
 * every change to a piece of gear.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID)
public final class RuneEvents {
    private RuneEvents() {}

    // ------------------------------------------------------------------------------------------------ casting

    /**
     * A charged runed crossbow fired with right click is marked so the shot it fires now carries its cast. (A sword casts
     * on the cast key, not right click: CastService.castKey.)
     */
    @SubscribeEvent
    public static void rightClickItem(PlayerInteractEvent.RightClickItem e) {
        if (!(e.getEntity() instanceof ServerPlayer sp) || sp instanceof FakePlayer) return;
        ItemStack s = e.getItemStack();
        if (RuneGear.host(s) == Host.CROSSBOW && CrossbowItem.isCharged(s) && RuneGear.hasRunes(s)) CastService.crossbowAboutToFire(sp, e.getHand());
    }

    /**
     * A bow's or crossbow's shot came into the world. An arrow the runes shaped that comes back (with its chunk, or
     * through a portal or a teleport to another world) comes back plain, and a copy Fan made doesn't come back.
     */
    @SubscribeEvent
    public static void entityJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || !(e.getEntity() instanceof AbstractArrow arrow)) return;
        // a marked arrow is never a new shot (a shot is marked only once it has joined, a Fan copy only once it has been
        // added): it came back with its chunk, or a portal or a teleport to another world copied it, mark and all
        if (e.loadedFromDisk() || ArrowRides.marked(arrow)) {
            if (ArrowRides.returningDropsCopy(arrow)) e.setCanceled(true);
            return;
        }
        // nor is one that has flown already (a portal's or a teleport's copy of an arrow the runes let go of, or never
        // had): a new shot joins before its first tick, and the copy keeps the game's own note that it has flown
        if (((ProjectileAccessor) arrow).er$hasBeenShot()) return;
        CastService.arrowLoosed(arrow);
    }

    @SubscribeEvent
    public static void impact(ProjectileImpactEvent e) {
        if (!e.getProjectile().level().isClientSide()) ArrowRides.impact(e);
    }

    // ------------------------------------------------------------------------------------------------ hits fill gear

    /** Damage landed: a runed sword's swing, or an arrow runed gear loosed, knocks energy loose into that gear. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void damage(LivingDamageEvent e) {
        if (e.isCanceled() || e.getEntity().level().isClientSide()) return;
        DamageSource src = e.getSource();
        if (!(src.getEntity() instanceof ServerPlayer p)) return;
        if (src.is(DamageTypes.PLAYER_ATTACK) && src.getDirectEntity() == p) {
            CastService.meleeDamage(p, e.getEntity(), e.getAmount());
        } else if (src.getDirectEntity() instanceof AbstractArrow arrow && !src.is(RuneRegistry.RUNE_DAMAGE)) {
            CastService.arrowDamage(p, arrow, e.getEntity(), e.getAmount());
        }
    }

    // ------------------------------------------------------------------------------------------------ combat, and the rune screen

    /** Hurt: in combat for a few seconds, and the rune screen closes. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void hurt(LivingHurtEvent e) {
        if (e.isCanceled() || !(e.getEntity() instanceof ServerPlayer p) || e.getAmount() <= 0.0F) return;
        Combat.hurt(p);
        if (p.containerMenu instanceof RuneEditorMenu) p.closeContainer();
    }

    /** A held creature (stunned, rooted) can't strike. */
    /** A player landing while Reel pulls them takes only the fall they had before it caught them (a pull adds none). */
    @SubscribeEvent
    public static void fall(LivingFallEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) e.setDistance(Grapples.landing(p, e.getDistance()));
    }

    @SubscribeEvent
    public static void attackBy(LivingAttackEvent e) {
        // the client's own copy of a hit is no matter (and the held list is the server's)
        if (e.getEntity().level().isClientSide()) return;
        if (e.getSource().getEntity() instanceof LivingEntity att && e.getSource().getDirectEntity() == att && Statuses.held(att)) e.setCanceled(true);
    }

    /** A held creature stands frozen in place: its own tick doesn't run (bosses and players are never held). */
    @SubscribeEvent
    public static void livingTick(LivingEvent.LivingTickEvent e) {
        LivingEntity le = e.getEntity();
        if (le.level().isClientSide() || !Statuses.any(le)) return;
        if (Statuses.held(le) && !le.isDeadOrDying() && !le.isRemoved()) {
            e.setCanceled(true);
            Statuses.heldTick(le);
        } else {
            Statuses.wetTick(le);
        }
    }

    // ------------------------------------------------------------------------------------------------ players

    @SubscribeEvent
    public static void playerTick(TickEvent.PlayerTickEvent e) {
        if (e.phase == TickEvent.Phase.END && e.player instanceof ServerPlayer p) CastService.tick(p);
    }

    @SubscribeEvent
    public static void serverTick(TickEvent.ServerTickEvent e) {
        if (e.phase == TickEvent.Phase.END) CastService.serverTick(e.getServer());
    }

    @SubscribeEvent
    public static void loggedIn(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) RunDepth.sync(p);
    }

    @SubscribeEvent
    public static void respawn(PlayerEvent.PlayerRespawnEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) RunDepth.sync(p);
    }

    /** Back from the End is a new player without a death: the run depth, and an operator's pin, come along. */
    @SubscribeEvent
    public static void clone(PlayerEvent.Clone e) {
        if (!e.isWasDeath()) RunDepth.copy(e.getOriginal(), e.getEntity());
    }

    @SubscribeEvent
    public static void changedDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) RunDepth.sync(p);
    }

    /** Leaving: the rune screen closes first (its gear goes back before the player is saved), then the rest is let go. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void loggedOut(PlayerEvent.PlayerLoggedOutEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        if (p.containerMenu instanceof RuneEditorMenu) p.closeContainer();
        CastService.forget(p);
        // the combat lock is kept: it runs on the level's clock, so leaving and rejoining doesn't skip it
    }

    /** The server is stopping: rune screens close first, so what is on a cursor is back in the inventory to be saved. */
    @SubscribeEvent
    public static void stopping(ServerStoppingEvent e) {
        for (ServerPlayer p : e.getServer().getPlayerList().getPlayers()) {
            if (p.containerMenu instanceof RuneEditorMenu) p.closeContainer();
        }
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent e) {
        CastService.clear();
        Combat.clear();
    }

    // ------------------------------------------------------------------------------------------------ keeping runes safe

    /**
     * An anvil never eats a runed piece of gear as the second input (its runes would be lost). Refusing leaves the last
     * result standing in the output, so it is cleared with its price: otherwise it could be taken again for nothing.
     */
    @SubscribeEvent
    public static void anvil(AnvilUpdateEvent e) {
        if (!RuneGear.hasRunes(e.getRight())) return;
        e.setCanceled(true);
        if (e.getPlayer() != null && e.getPlayer().containerMenu instanceof AnvilMenu m) {
            m.getSlot(m.getResultSlot()).set(ItemStack.EMPTY);
            m.setMaximumCost(0);
        }
    }

    /**
     * A grindstone takes the runes off one runed piece of gear: it comes out plain (and, as ever, without its
     * enchantments), and the runes come back as items when it is taken out. It won't merge two pieces when either has
     * runes (the merged one would come out without them).
     */
    @SubscribeEvent
    public static void grindstone(GrindstoneEvent.OnPlaceItem e) {
        ItemStack top = e.getTopItem(), bottom = e.getBottomItem();
        if (!top.isEmpty() && !bottom.isEmpty()) {
            if (RuneGear.hasRunes(top) || RuneGear.hasRunes(bottom)) e.setCanceled(true);
            return;
        }
        ItemStack one = top.isEmpty() ? bottom : top;
        if (one.getCount() == 1 && RuneGear.hasRunes(one)) e.setOutput(RuneGear.ground(one));
    }

    /** The plain gear is taken out of the grindstone: its runes go to the player using it (or drop at their feet). */
    @SubscribeEvent
    public static void grindstoneTake(GrindstoneEvent.OnTakeItem e) {
        ItemStack top = e.getTopItem(), bottom = e.getBottomItem();
        if (!top.isEmpty() && !bottom.isEmpty()) return;
        ItemStack one = top.isEmpty() ? bottom : top;
        if (!RuneGear.hasRunes(one)) return;
        ServerPlayer user = grindstoneUser(one);
        if (user == null) {
            ExplorationRemix.LOGGER.warn("A grindstone took the runes off {} with no player using it; the runes stay on it", one);
            return;
        }
        RuneGear.dropRunes(one.copy(), user);
    }

    /** The player whose open grindstone holds this exact stack. */
    @Nullable
    private static ServerPlayer grindstoneUser(ItemStack stack) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.containerMenu instanceof GrindstoneMenu m && (m.getSlot(0).getItem() == stack || m.getSlot(1).getItem() == stack)) return p;
        }
        return null;
    }

    /**
     * Crafting with runed gear in the grid (a repair): its runes come back to the player unless the result kept them,
     * or the recipe hands the gear back with its runes still on (a pack's recipe that only wears a tool in the grid).
     */
    @SubscribeEvent
    public static void crafted(PlayerEvent.ItemCraftedEvent e) {
        Player p = e.getEntity();
        if (p.level().isClientSide() || RuneGear.hasRunes(e.getCrafting())) return;
        Container grid = e.getInventory();
        boolean any = false;
        for (int i = 0; i < grid.getContainerSize() && !any; i++) any = RuneGear.hasRunes(grid.getItem(i));
        if (!any) return;
        NonNullList<ItemStack> left = null;
        if (grid instanceof CraftingContainer cc) {
            // what the grid will hold once this craft is taken, worked out as the result slot does
            ForgeHooks.setCraftingPlayer(p);
            left = p.level().getRecipeManager().getRemainingItemsFor(RecipeType.CRAFTING, cc, p.level());
            ForgeHooks.setCraftingPlayer(null);
        }
        for (int i = 0; i < grid.getContainerSize(); i++) {
            ItemStack s = grid.getItem(i);
            if (!RuneGear.hasRunes(s)) continue;
            // handed back: a new stack that still has the runes (the same stack is used up with the rest)
            boolean handedBack = left != null ? i < left.size() && left.get(i) != s && RuneGear.hasRunes(left.get(i)) : s.hasCraftingRemainingItem();
            if (handedBack) continue;
            for (ItemStack r : RuneGear.runeItems(s)) {
                if (!p.getInventory().add(r)) p.drop(r, false);
            }
        }
    }
}
