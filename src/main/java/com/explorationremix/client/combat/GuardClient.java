package com.explorationremix.client.combat;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.client.combat.SwingAnim.Hand;
import com.explorationremix.client.rune.fx.RuneCamera;
import com.explorationremix.combat.swing.SwingNet;
import com.explorationremix.combat.swing.Weapons;
import com.explorationremix.registry.ERSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;

/**
 * A sword's guard on the player's own game (Taran 2026-11-25). The use button raises it when neither hand's own use took
 * the click (MinecraftAttackMixin, at the end of startUseItem: a shield in the other hand, food, a block or a torch to
 * place all work as they always did) and the sword isn't mid-swing; it is held up while the button is, as vanilla holds a
 * shield up: the attack button does nothing meanwhile and the player walks slowly. What it turns is the server's to judge ({@code combat.guard.Guard}).
 * Here: the hand's pose in first person (raised, knocked back by a blow, flicked out by a parry, let down), and the
 * guards of the players in sight, for their pose seen from outside.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class GuardClient {
    /** The player's own guard, on the swings' clock: up now; when it went up and came down, and where the hand was then. */
    private static boolean up;
    private static float upAt = -100.0F, downAt = -100.0F;
    private static Hand upFrom = Hand.REST, downFrom = Hand.REST;
    /** The last blow taken on the player's own blade, and whether they parried it. */
    private static float struckAt = -100.0F;
    private static boolean struckParry;
    /** Other players: since when each one's guard has been up, and their last blow taken (when, and 1 for a parry). */
    private static final Map<Integer, Float> RAISED = new HashMap<>();
    private static final Map<Integer, float[]> STRUCK = new HashMap<>();
    /** How long after a blow the blade still shows it (ticks). */
    private static final float STRUCK_SHOWN = 12.0F;

    private GuardClient() {}

    /** Whether {@code p} holds a sword up in guard now (their use of the sword in the main hand). */
    public static boolean guarding(Player p) {
        return p.isUsingItem() && p.getUsedItemHand() == InteractionHand.MAIN_HAND && Weapons.guards(p.getUseItem());
    }

    /** Minecraft.startUseItem, at its end (nothing took the click): a sword in hand goes up in guard. */
    public static void tryRaise() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null || mc.screen != null || mc.gameMode.isDestroying() || p.isHandsBusy() || p.isUsingItem()
                || p.isSpectator() || !p.isAlive()) return;
        if (!Weapons.guards(p.getMainHandItem())) return;
        // never mid-swing: the swing finishes first (its cooldown is no matter)
        if (SwingClient.machine().busy()) return;
        p.startUsingItem(InteractionHand.MAIN_HAND);
        if (!guarding(p)) return;
        if (mc.getConnection() != null) SwingNet.CHANNEL.sendToServer(new SwingNet.Guard());
        raised(SwingClient.now(mc.getFrameTime()));
        SwingClient.play(p, ERSounds.GUARD_RAISE.get(), 0.5F, 1.0F);
    }

    private static void raised(float now) {
        if (up) return;
        Minecraft mc = Minecraft.getInstance();
        up = true;
        // from where the hand is: the swing before still easing back, the guard on its way down, or at rest
        SwingClient.Swing s = mc.player == null ? null : SwingClient.swing(mc.player.getId());
        upFrom = lowering(now) ? handAt(now) : s != null && !s.over(now) ? SwingRender.handAt(s, now) : Hand.REST;
        upAt = now;
    }

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        float now = SwingClient.now(0.0F);
        if (p == null || mc.level == null) {
            reset();
            return;
        }
        boolean g = guarding(p);
        if (g && !up) {
            raised(now);
        } else if (!g && up) {
            downFrom = handAt(now);
            up = false;
            downAt = now;
        }
        // the players in sight: whose guard is up, and since when
        for (Player other : mc.level.players()) {
            if (other == p) continue;
            if (guarding(other)) RAISED.putIfAbsent(other.getId(), now);
            else RAISED.remove(other.getId());
        }
        RAISED.keySet().removeIf(id -> mc.level.getEntity(id) == null);
        STRUCK.values().removeIf(v -> now - v[0] > STRUCK_SHOWN);
    }

    /** A blow was taken on {@code player}'s blade ({@code parry}: turned back), as the server shows it to everyone near. */
    public static void struck(int player, boolean parry, float strength) {
        Minecraft mc = Minecraft.getInstance();
        float now = SwingClient.now(mc.getFrameTime());
        if (mc.player != null && player == mc.player.getId()) {
            struckAt = now;
            struckParry = parry;
            // a parry jars the view less than a blow driven home on the blade
            if (parry) RuneCamera.kick(-0.6F, 0.0F);
            else RuneCamera.kick(0.5F + 0.9F * Mth.clamp(strength, 0.0F, 1.0F), (mc.player.getRandom().nextFloat() - 0.5F) * 0.6F);
        } else {
            STRUCK.put(player, new float[]{now, parry ? 1.0F : 0.0F});
        }
    }

    /** Back to nothing (a new world). */
    static void reset() {
        up = false;
        upAt = downAt = struckAt = -100.0F;
        RAISED.clear();
        STRUCK.clear();
    }

    // ------------------------------------------------------------------------------------------------ for drawing

    /** Whether the player's own hand is drawn in guard now: up, or on its way down. */
    static boolean shown(LocalPlayer p, float now) { return up || lowering(now); }

    /** Whether the player's own guard is on its way down now. */
    static boolean lowering(float now) { return !up && now - downAt < SwingAnim.GUARD_DOWN; }

    /** A swing starts from where the hand is now: the guard's way down ends there. */
    static Hand takeHand(float now) {
        Hand h = handAt(now);
        downAt = -100.0F;
        return h;
    }

    /** The player's own hand in guard at {@code now} (raised, struck, or let down). */
    static Hand handAt(float now) {
        if (up) {
            float since = now - struckAt;
            return SwingAnim.guard(upFrom, (now - upAt) / SwingAnim.GUARD_UP, since <= STRUCK_SHOWN ? since : -1.0F, struckParry, now);
        }
        float k = Mth.clamp((now - downAt) / SwingAnim.GUARD_DOWN, 0.0F, 1.0F);
        return downFrom.lerp(Hand.REST, SwingAnim.smooth(k));
    }

    /** How far {@code p}'s guard is raised (0 to 1), seen from outside. */
    static float raised(Player p, float now) {
        Minecraft mc = Minecraft.getInstance();
        Float since = p == mc.player ? (up ? upAt : null) : RAISED.get(p.getId());
        if (since == null) return 1.0F;
        return SwingAnim.smooth(Mth.clamp((now - since) / SwingAnim.GUARD_UP, 0.0F, 1.0F));
    }

    /** How far a blow just taken throws {@code p}'s guard out, seen from outside: up to 1 for a parry, less for a block. */
    static float flick(Player p, float now) {
        Minecraft mc = Minecraft.getInstance();
        float at;
        boolean parry;
        if (p == mc.player) {
            at = struckAt;
            parry = struckParry;
        } else {
            float[] v = STRUCK.get(p.getId());
            if (v == null) return 0.0F;
            at = v[0];
            parry = v[1] > 0.5F;
        }
        float t = now - at;
        if (t < 0.0F || t > STRUCK_SHOWN) return 0.0F;
        float out = parry ? SwingAnim.FLICK_OUT : 1.0F, back = parry ? SwingAnim.FLICK_BACK : SwingAnim.JOLT - 1.0F;
        float k = t < out ? SwingAnim.easeOut(t / out) : 1.0F - SwingAnim.smooth(Mth.clamp((t - out) / back, 0.0F, 1.0F));
        return parry ? k : -0.4F * k;
    }
}
