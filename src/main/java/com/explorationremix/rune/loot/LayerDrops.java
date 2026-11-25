package com.explorationremix.rune.loot;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.rune.RunDepth;
import com.explorationremix.rune.engine.RuneId;
import com.explorationremix.rune.item.RuneItem;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * Each layer's creatures drop its two kinds of collect rune often (Taran, 25 Nov 2026): Mend and Vigor in the Upper
 * Caves, Fleet and Thirst in the Blue Reaches, Ward and Wisdom in the Deep Gardens (and the Galleries below them). Any
 * hostile creature a player kills underground has a chance at one, by the layer it dies in; the mod's own creatures
 * drop them more often, from their loot tables (tools/gen_data.py). Armor's sets wake on three pieces carrying one kind,
 * so the runes for them have to come often.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID)
public final class LayerDrops {
    private LayerDrops() {}

    /** A hostile creature's chance at a collect rune when a player kills it underground, and how much each Looting level adds. */
    static final float CHANCE = 0.10F, PER_LOOTING = 0.03F;

    @SubscribeEvent
    public static void drops(LivingDropsEvent e) {
        LivingEntity dead = e.getEntity();
        if (!(dead instanceof Enemy) || !(dead.level() instanceof ServerLevel l)) return;
        if (!(e.getSource().getEntity() instanceof ServerPlayer p) || p instanceof FakePlayer) return;
        ResourceLocation type = ForgeRegistries.ENTITY_TYPES.getKey(dead.getType());
        if (type != null && type.getNamespace().equals(ExplorationRemix.MOD_ID)) return;
        BlockPos at = dead.blockPosition();
        int layer = RunDepth.depthAt(l, dead.getY());
        if (layer < 0 || at.getY() >= l.getSeaLevel() || l.canSeeSky(at)) return;
        if (l.random.nextFloat() >= CHANCE + PER_LOOTING * e.getLootingLevel()) return;
        List<RuneId> kinds = Plain.collects(Math.min(2, layer));
        if (kinds.isEmpty()) return;
        ItemStack rune = RuneItem.stack(kinds.get(l.random.nextInt(kinds.size())), 1);
        if (rune.isEmpty()) return;
        ItemEntity item = new ItemEntity(l, dead.getX(), dead.getY() + 0.25, dead.getZ(), rune);
        item.setDefaultPickUpDelay();
        e.getDrops().add(item);
    }
}
