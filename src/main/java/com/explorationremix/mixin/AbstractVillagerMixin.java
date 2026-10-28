package com.explorationremix.mixin;

import com.explorationremix.config.ERConfig;
import com.explorationremix.enchant.Enchantments;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Villagers and wandering traders never sell enchanted books, enchanted gear or bottles o' enchanting. */
@Mixin(AbstractVillager.class)
public abstract class AbstractVillagerMixin {
    @Inject(method = "addOffersFromItemListings", at = @At("TAIL"))
    private void er$stripTrades(MerchantOffers offers, VillagerTrades.ItemListing[] listings, int max, CallbackInfo ci) {
        if (!ERConfig.INSTANCE.stripTradeEnchantments.get()) return;
        for (int i = offers.size() - 1; i >= 0; i--) {
            MerchantOffer o = offers.get(i);
            ItemStack result = o.getResult();
            if (result.is(Items.ENCHANTED_BOOK) || result.is(Items.EXPERIENCE_BOTTLE)) {
                offers.remove(i);
                continue;
            }
            if (Enchantments.carriesDead(result)) {
                ItemStack clean = result.copy();
                Enchantments.strip(clean);
                offers.set(i, new MerchantOffer(o.getBaseCostA(), o.getCostB(), clean, o.getUses(), o.getMaxUses(),
                        o.getXp(), o.getPriceMultiplier(), o.getDemand()));
            }
        }
    }
}
