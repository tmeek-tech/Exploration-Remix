package com.explorationremix.compat;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.network.ComboFinisherPacket;
import com.explorationremix.network.ERNetwork;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Better Combat integration, through reflection so the mod has no compile-time dependency. On the client, the last
 * swing of a combo tells the server to treat the next hit as a combo finisher.
 */
public final class BetterCombatCompat {
    private static Boolean loaded;
    private BetterCombatCompat() {}

    public static boolean loaded() {
        if (loaded == null) loaded = ModList.get().isLoaded("bettercombat");
        return loaded;
    }

    /** Client only. Registers an ATTACK_HIT listener with Better Combat's client event publisher. */
    public static void registerClient() {
        if (!loaded()) return;
        try {
            Class<?> events = Class.forName("net.bettercombat.api.client.BetterCombatClientEvents");
            Object publisher = events.getField("ATTACK_HIT").get(null);
            Class<?> listener = Class.forName("net.bettercombat.api.client.BetterCombatClientEvents$PlayerAttackHit");
            InvocationHandler handler = (proxy, method, args) -> {
                if (method.getName().equals("onPlayerAttackStart") && args != null && args.length >= 2 && args[1] != null) {
                    Object hand = args[1];
                    Object combo = hand.getClass().getMethod("combo").invoke(hand);
                    int current = (int) combo.getClass().getMethod("current").invoke(combo);
                    int total = (int) combo.getClass().getMethod("total").invoke(combo);
                    if (total > 1 && current == total) ERNetwork.CHANNEL.sendToServer(new ComboFinisherPacket());
                    return null;
                }
                if (method.getName().equals("toString")) return "ExplorationRemix finisher listener";
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("equals")) return proxy == args[0];
                return null;
            };
            Object proxy = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener}, handler);
            Method register = publisher.getClass().getMethod("register", Object.class);
            register.invoke(publisher, proxy);
            ExplorationRemix.LOGGER.info("Better Combat found: combo finishers feed the On finisher rune");
        } catch (ReflectiveOperationException | RuntimeException e) {
            ExplorationRemix.LOGGER.warn("Better Combat is present but its client API could not be hooked: {}", e.toString());
        }
    }
}
