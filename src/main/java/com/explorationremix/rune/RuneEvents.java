package com.explorationremix.rune;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.rune.cast.ArrowRides;
import com.explorationremix.rune.cast.CastService;
import com.explorationremix.rune.cast.Statuses;
import com.explorationremix.rune.engine.Host;
import com.explorationremix.rune.menu.RuneEditorMenu;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.AnvilUpdateEvent;
import net.minecraftforge.event.GrindstoneEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
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
    /** How long a held sword "uses": it never runs out; the player lets go. */
    private static final int HOLD_TICKS = 72000;

    private RuneEvents() {}

    // ------------------------------------------------------------------------------------------------ casting

    /**
     * Right-click with a runed sword (both sides, so the client's hand acts at once): the sword is held up to cast, and
     * the server casts at once and again each time it's ready while the button is held. A charged runed crossbow is
     * marked so the shot it fires now carries its cast.
     */
    @SubscribeEvent
    public static void rightClickItem(PlayerInteractEvent.RightClickItem e) {
        Player p = e.getEntity();
        if (p instanceof FakePlayer) return;
        ItemStack s = e.getItemStack();
        Host h = RuneGear.host(s);
        if (h == Host.CROSSBOW && p instanceof ServerPlayer sp && CrossbowItem.isCharged(s) && RuneGear.hasRunes(s)) {
            CastService.crossbowAboutToFire(sp, e.getHand());
            return;
        }
        if (h != Host.SWORD || !CastService.swordTakesRightClick(p, e.getHand())) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.CONSUME);
        if (!p.isUsingItem()) p.startUsingItem(e.getHand());
        if (p instanceof ServerPlayer sp) CastService.castSword(sp, e.getHand());
    }

    /** A runed sword held up to cast "uses" for as long as the button is down. */
    @SubscribeEvent
    public static void useStart(LivingEntityUseItemEvent.Start e) {
        if (e.getEntity() instanceof Player && RuneGear.host(e.getItem()) == Host.SWORD && RuneGear.hasRunes(e.getItem())) e.setDuration(HOLD_TICKS);
    }

    /** Each tick a runed sword is held up, it casts whenever it's ready. */
    @SubscribeEvent
    public static void useTick(LivingEntityUseItemEvent.Tick e) {
        if (e.getEntity() instanceof ServerPlayer p && RuneGear.host(e.getItem()) == Host.SWORD) {
            CastService.castSword(p, p.getUsedItemHand());
            e.setDuration(HOLD_TICKS);
        }
    }

    /** A shield in the off hand: a tap (let go before it ever blocks) casts the runed sword in the main hand. */
    @SubscribeEvent
    public static void useStop(LivingEntityUseItemEvent.Stop e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || p.getUsedItemHand() != InteractionHand.OFF_HAND) return;
        ItemStack shield = e.getItem();
        if (!shield.canPerformAction(ToolActions.SHIELD_BLOCK)) return;
        int used = shield.getUseDuration() - e.getDuration();
        if (used <= RuneConfig.get(RuneConfig.INSTANCE.tapTicks)) CastService.castSword(p, InteractionHand.MAIN_HAND);
    }

    /** A bow's or crossbow's shot came into the world. */
    @SubscribeEvent
    public static void entityJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || e.loadedFromDisk() || !(e.getEntity() instanceof AbstractArrow arrow)) return;
        CastService.arrowLoosed(arrow);
    }

    @SubscribeEvent
    public static void impact(ProjectileImpactEvent e) {
        if (!e.getProjectile().level().isClientSide()) ArrowRides.impact(e);
    }

    // ------------------------------------------------------------------------------------------------ the sword's charge

    @SubscribeEvent
    public static void attack(AttackEntityEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) CastService.swing(p, e.getTarget());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void damage(LivingDamageEvent e) {
        if (e.isCanceled() || e.getEntity().level().isClientSide()) return;
        if (e.getSource().is(DamageTypes.PLAYER_ATTACK) && e.getSource().getEntity() instanceof ServerPlayer p && e.getSource().getDirectEntity() == p) {
            CastService.meleeDamage(p, e.getEntity(), e.getAmount());
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
    @SubscribeEvent
    public static void attackBy(LivingAttackEvent e) {
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
        Combat.forget(p);
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent e) {
        CastService.clear();
        Combat.clear();
    }

    // ------------------------------------------------------------------------------------------------ keeping runes safe

    /** An anvil never eats a runed piece of gear as the second input (its runes would be lost). */
    @SubscribeEvent
    public static void anvil(AnvilUpdateEvent e) {
        if (RuneGear.hasRunes(e.getRight())) e.setCanceled(true);
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

    /** Crafting with runed gear in the grid (a repair): its runes come back to the player unless the result kept them. */
    @SubscribeEvent
    public static void crafted(PlayerEvent.ItemCraftedEvent e) {
        if (e.getEntity().level().isClientSide() || RuneGear.hasRunes(e.getCrafting())) return;
        for (int i = 0; i < e.getInventory().getContainerSize(); i++) {
            ItemStack s = e.getInventory().getItem(i);
            // gear the recipe hands back (a tool used in the grid) keeps its own runes
            if (RuneGear.hasRunes(s) && !s.hasCraftingRemainingItem()) {
                for (ItemStack r : RuneGear.runeItems(s)) {
                    if (!e.getEntity().getInventory().add(r)) e.getEntity().drop(r, false);
                }
            }
        }
    }
}
