package com.explorationremix.client.foes;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.combat.foes.FoeMove;
import com.explorationremix.combat.foes.FoeNet;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.Iterator;

/**
 * What this game is told of the fights (0.23.8): which move each vanilla hostile near it is in, and since when (posed
 * by {@link FoePoses}); and whether the player's own health is coming back, which ripples their hearts as Regeneration
 * does (the health bar is drawn as if they had it, for that one draw).
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, value = Dist.CLIENT)
public final class FoeClient implements FoeNet.ClientSide {
    static final FoeClient INSTANCE = new FoeClient();

    /** A mob's move as last told: from {@code start} (this game's tick) for {@code ticks}; or held at {@code held} ticks in. */
    record State(FoeMove move, FoeMove.Phase phase, long start, int ticks, float held) {}

    private static final Int2ObjectMap<State> STATES = new Int2ObjectOpenHashMap<>();
    private static boolean healing;
    /** The stand-in Regeneration put in for the health bar's draw, taken out after it. */
    @Nullable private static MobEffectInstance ripple;
    private static int pruneIn;

    private FoeClient() {}

    @Override
    public void move(FoeNet.Move m) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        FoeMove.Phase p = m.foePhase();
        if (p == FoeMove.Phase.IDLE || m.foeMove() == FoeMove.NONE) STATES.remove(m.entity());
        else STATES.put(m.entity(), new State(m.foeMove(), p, mc.level.getGameTime(), Math.max(1, m.ticks()), m.held() < 0 ? -1.0F : m.held() / 10.0F));
    }

    @Override
    public void healing(boolean on) { healing = on; }

    @Nullable
    static State state(int entity) { return STATES.isEmpty() ? null : STATES.get(entity); }

    /** This game's time now, between ticks. */
    static float now(float partialTicks) {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? 0.0F : (float) (mc.level.getGameTime() % 1_000_000L) + partialTicks;
    }

    /** Ticks since {@code s} began, on this game's clock. */
    static float elapsed(State s, float partialTicks) {
        if (s.held() >= 0.0F) return s.held();
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? 0.0F : (float) (mc.level.getGameTime() - s.start()) + partialTicks;
    }

    // ------------------------------------------------------------------------------------------------ ticks

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            STATES.clear();
            healing = false;
            return;
        }
        if (--pruneIn > 0 || STATES.isEmpty()) return;
        pruneIn = 40;
        long now = mc.level.getGameTime();
        for (Iterator<Int2ObjectMap.Entry<State>> it = STATES.int2ObjectEntrySet().iterator(); it.hasNext(); ) {
            Int2ObjectMap.Entry<State> s = it.next();
            Entity en = mc.level.getEntity(s.getIntKey());
            boolean stale = s.getValue().held() < 0.0F && now - s.getValue().start() > s.getValue().ticks() + 60L;
            if (en == null || en.isRemoved() || stale) it.remove();
        }
    }

    @SubscribeEvent
    public static void loggedOut(ClientPlayerNetworkEvent.LoggingOut e) {
        STATES.clear();
        healing = false;
    }

    // ------------------------------------------------------------------------------------------------ the hearts' ripple

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void healthBar(RenderGuiOverlayEvent.Pre e) {
        if (!healing || !e.getOverlay().id().equals(VanillaGuiOverlay.PLAYER_HEALTH.id())) return;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || p.hasEffect(MobEffects.REGENERATION)) return;
        ripple = new MobEffectInstance(MobEffects.REGENERATION, 40, 0, true, false, false);
        p.getActiveEffectsMap().put(MobEffects.REGENERATION, ripple);
    }

    @SubscribeEvent
    public static void healthBarDrawn(RenderGuiOverlayEvent.Post e) {
        if (e.getOverlay().id().equals(VanillaGuiOverlay.PLAYER_HEALTH.id())) takeRipple();
    }

    /** Whatever happened to the health bar's draw, the stand-in is never left in. */
    @SubscribeEvent
    public static void guiDrawn(RenderGuiEvent.Post e) { takeRipple(); }

    private static void takeRipple() {
        if (ripple == null) return;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null && p.getActiveEffectsMap().get(MobEffects.REGENERATION) == ripple) p.getActiveEffectsMap().remove(MobEffects.REGENERATION);
        ripple = null;
    }
}
