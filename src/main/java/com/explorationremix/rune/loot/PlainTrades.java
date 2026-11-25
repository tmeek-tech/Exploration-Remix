package com.explorationremix.rune.loot;

import com.explorationremix.ExplorationRemix;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraftforge.event.village.VillagerTradesEvent;
import net.minecraftforge.event.village.WandererTradesEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Villagers' and wandering traders' trades, made plain (Plain.offer): a librarian's book trades sell runes, and the
 * smiths', fletcher's and fisherman's enchanted gear comes plain for half the price. Wrapped last, so trades other mods
 * add are made plain too. A villager's trades already offered stay as they were.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID)
public final class PlainTrades {
    private PlainTrades() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void villager(VillagerTradesEvent e) {
        for (Int2ObjectMap.Entry<List<VillagerTrades.ItemListing>> level : e.getTrades().int2ObjectEntrySet()) {
            int at = level.getIntKey();
            level.getValue().replaceAll(l -> l instanceof Listing ? l : new Listing(l, at));
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void wanderer(WandererTradesEvent e) {
        e.getGenericTrades().replaceAll(l -> l instanceof Listing ? l : new Listing(l, 1));
        e.getRareTrades().replaceAll(l -> l instanceof Listing ? l : new Listing(l, 3));
    }

    /** A trade as it was, made plain when it is offered (level: the villager level it is offered at, 1 to 5). */
    record Listing(VillagerTrades.ItemListing inner, int level) implements VillagerTrades.ItemListing {
        @Nullable
        @Override
        public MerchantOffer getOffer(Entity trader, RandomSource rand) {
            MerchantOffer o = inner.getOffer(trader, rand);
            return o == null ? null : Plain.offer(o, level, rand);
        }
    }
}
