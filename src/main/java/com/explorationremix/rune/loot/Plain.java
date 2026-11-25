package com.explorationremix.rune.loot;

import com.explorationremix.rune.engine.RuneId;
import com.explorationremix.rune.item.RuneItem;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.ArrayList;
import java.util.List;

/**
 * Runes in place of enchanting (0.22.6). The enchanting table sets runes and nothing else, so what the world hands out
 * comes without enchantments too: new chest loot, fishing, bartering, trades and the gear mobs spawn with come plain, and
 * an enchanted book found or sold becomes a rune. Gear that was enchanted before keeps its enchantments.
 * <ul>
 *   <li>A book found in the world (a chest, a catch, a barter) becomes a rune of any layer: one first found in the Upper
 *   Caves most often, one of the Deep Gardens least.</li>
 *   <li>A librarian's book trade sells a rune instead: a novice's or apprentice's one of the Upper Caves, a journeyman's
 *   or expert's one of the Blue Reaches. The Deep Gardens' runes are only ever found.</li>
 *   <li>A trade that sold enchanted gear sells it plain, for half the emeralds.</li>
 *   <li>Collect runes (armor's sets) are never in a book or a trade: each layer's creatures drop its two kinds.</li>
 * </ul>
 * The pick itself is pure ({@link #pick(int, int, int)}), so tests can count it.
 */
public final class Plain {
    private Plain() {}

    /** How much more often a rune of each layer comes from a book than one of the next: 5 : 3 : 2. */
    static final int[] LAYER_WEIGHT = {5, 3, 2};
    /** A rune trade's uses between restocks: a few, where a book sold twelve times. */
    static final int RUNE_TRADE_USES = 3;

    /** The runes a book or a trade can be, of layers min to max: every rune but the collect runes. */
    public static List<RuneId> bookRunes(int min, int max) {
        List<RuneId> out = new ArrayList<>();
        for (RuneId r : RuneId.runes()) if (!r.isCollect() && r.layer >= min && r.layer <= max) out.add(r);
        return out;
    }

    /** The sum of the weights of the runes of layers min to max, for {@link #pick}. */
    public static int total(int min, int max) {
        int t = 0;
        for (RuneId r : bookRunes(min, max)) t += LAYER_WEIGHT[r.layer];
        return t;
    }

    /** The rune a roll from 0 to {@link #total}(min, max) - 1 lands on. */
    public static RuneId pick(int roll, int min, int max) {
        List<RuneId> runes = bookRunes(min, max);
        for (RuneId r : runes) {
            roll -= LAYER_WEIGHT[r.layer];
            if (roll < 0) return r;
        }
        return runes.get(runes.size() - 1);
    }

    public static RuneId pick(RandomSource rand, int min, int max) { return pick(rand.nextInt(total(min, max)), min, max); }

    /** The two kinds of collect rune a layer's creatures drop: Mend and Vigor in the Upper Caves, Fleet and Thirst in the Blue Reaches, Ward and Wisdom in the Deep Gardens. */
    public static List<RuneId> collects(int layer) {
        List<RuneId> out = new ArrayList<>();
        for (RuneId r : RuneId.runes()) if (r.isCollect() && r.layer == layer) out.add(r);
        return out;
    }

    /** A rune trade's price in emeralds: 6 to 10 for one of the Upper Caves, 12 to 19 for the Blue Reaches', 18 to 28 deeper. */
    static int price(int layer, RandomSource rand) { return Math.min(64, 6 + 6 * layer + rand.nextInt(5 + 3 * layer)); }

    /** What the world hands out instead of this stack: an enchanted book becomes a rune, enchanted gear comes plain. */
    public static ItemStack of(ItemStack stack, RandomSource rand) {
        if (stack.is(Items.ENCHANTED_BOOK)) {
            ItemStack rune = RuneItem.stack(pick(rand, 0, 2), stack.getCount());
            return rune.isEmpty() ? new ItemStack(Items.BOOK, stack.getCount()) : rune;
        }
        return plain(stack);
    }

    /** The stack without its enchantments (a copy), or the stack itself when it has none. */
    public static ItemStack plain(ItemStack stack) {
        if (!stack.isEnchanted()) return stack;
        ItemStack c = stack.copy();
        c.removeTagKey(ItemStack.TAG_ENCH);
        c.removeTagKey("RepairCost");
        return c;
    }

    /**
     * A trade, made plain: a book sold becomes a rune (of the Upper Caves at levels 1 and 2, of the Blue Reaches from 3),
     * sold for emeralds alone; enchanted gear sold comes plain, for half the emeralds. Any other trade is left as it is.
     */
    public static MerchantOffer offer(MerchantOffer o, int level, RandomSource rand) {
        ItemStack result = o.getResult();
        if (result.is(Items.ENCHANTED_BOOK)) {
            int layer = level >= 3 ? 1 : 0;
            RuneId r = pick(rand, layer, layer);
            ItemStack rune = RuneItem.stack(r, 1);
            if (rune.isEmpty()) return o;
            return new MerchantOffer(new ItemStack(Items.EMERALD, price(r.layer, rand)), ItemStack.EMPTY, rune,
                    RUNE_TRADE_USES, o.getXp(), o.getPriceMultiplier());
        }
        if (result.isEnchanted()) {
            ItemStack costA = o.getBaseCostA().copy();
            if (costA.is(Items.EMERALD)) costA.setCount(Math.max(1, costA.getCount() / 2));
            return new MerchantOffer(costA, o.getCostB().copy(), plain(result), o.getMaxUses(), o.getXp(), o.getPriceMultiplier());
        }
        return o;
    }
}
