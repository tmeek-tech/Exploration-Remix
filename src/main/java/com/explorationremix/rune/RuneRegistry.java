package com.explorationremix.rune;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.registry.ERBlocks;
import com.explorationremix.rune.effect.RuneBolt;
import com.explorationremix.rune.effect.RuneField;
import com.explorationremix.rune.effect.RuneMote;
import com.explorationremix.rune.engine.RuneId;
import com.explorationremix.rune.item.RuneItem;
import com.explorationremix.rune.menu.RuneEditorMenu;
import com.explorationremix.rune.world.TempBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Map;

/**
 * Everything the runes add to the game: one item per rune, the blocks runes make for a while (Barrier glass, Frost's
 * ice, Glow's light), the flying and lingering runes and Siphon's motes, the rune screen, and the creative tab. Kept
 * apart from the rest of the mod's registries so the rune code stays in its own packages.
 */
public final class RuneRegistry {
    private static final String MOD = ExplorationRemix.MOD_ID;
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MOD);
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MOD);
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MOD);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, MOD);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD);

    /** Rune damage: credited to the caster, never blocked by a shield (data/explorationremix/damage_type/rune.json). */
    public static final ResourceKey<DamageType> RUNE_DAMAGE = ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(MOD, "rune"));

    private RuneRegistry() {}

    // ------------------------------------------------------------------------------------------------ items

    private static final Map<RuneId, RegistryObject<Item>> RUNES = new EnumMap<>(RuneId.class);

    static {
        // explorationremix:rune_<id>; they never burn (RuneItem keeps them from blasts, cactus and the void too)
        for (RuneId r : RuneId.runes()) {
            RUNES.put(r, ITEMS.register("rune_" + r.id, () -> new RuneItem(r, new Item.Properties().stacksTo(16).fireResistant())));
        }
    }

    /** The rune's item, or null before the registries are filled (and for {@link RuneId#ARROW}). */
    @Nullable
    public static Item item(RuneId rune) {
        RegistryObject<Item> o = RUNES.get(rune);
        return o != null && o.isPresent() ? o.get() : null;
    }

    // ------------------------------------------------------------------------------------------------ blocks

    /** Barrier glass: 8 s, breakable, drops nothing; creatures can't spawn on it. */
    public static final RegistryObject<Block> BARRIER = BLOCKS.register("rune_barrier", () -> new TempBlock(TempBlock.Kind.BARRIER,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_BLUE).strength(0.3F).sound(SoundType.AMETHYST).noOcclusion()
                    .noLootTable().lightLevel(s -> 6).isValidSpawn((s, l, p, t) -> false).isRedstoneConductor((s, l, p) -> false)
                    .isSuffocating((s, l, p) -> false).isViewBlocking((s, l, p) -> false).pushReaction(PushReaction.BLOCK)));

    /** Frost's ice on still water: about 10 s, walkable and slippery; back to water when it melts or breaks. */
    public static final RegistryObject<Block> ICE = BLOCKS.register("rune_ice", () -> new TempBlock(TempBlock.Kind.ICE,
            BlockBehaviour.Properties.of().mapColor(MapColor.ICE).friction(0.98F).strength(0.5F).sound(SoundType.GLASS).noOcclusion()
                    .noLootTable().isValidSpawn((s, l, p, t) -> false).isRedstoneConductor((s, l, p) -> false)
                    .pushReaction(PushReaction.BLOCK)));

    /** Glow's light: a minute, no collision, anything placed there replaces it. */
    public static final RegistryObject<Block> LIGHT = BLOCKS.register("rune_light", () -> new TempBlock(TempBlock.Kind.LIGHT,
            BlockBehaviour.Properties.of().replaceable().noCollission().instabreak().noLootTable().lightLevel(s -> 14)
                    .sound(SoundType.AMETHYST_CLUSTER).pushReaction(PushReaction.DESTROY)));

    // ------------------------------------------------------------------------------------------------ entities

    /** A flying rune (Spark, Shard, Orb, Glow). Never saved; sent every tick so it flies smoothly. */
    public static final RegistryObject<EntityType<RuneBolt>> BOLT = ENTITIES.register("rune_bolt", () ->
            EntityType.Builder.<RuneBolt>of(RuneBolt::new, MobCategory.MISC).sized(0.25F, 0.25F).clientTrackingRange(8).updateInterval(1)
                    .fireImmune().noSave().noSummon().build(MOD + ":rune_bolt"));

    /** A Field: a lingering patch on the ground. Never saved. */
    public static final RegistryObject<EntityType<RuneField>> FIELD = ENTITIES.register("rune_field", () ->
            EntityType.Builder.<RuneField>of(RuneField::new, MobCategory.MISC).sized(0.5F, 0.25F).clientTrackingRange(8).updateInterval(5)
                    .fireImmune().noSave().noSummon().build(MOD + ":rune_field"));

    /** One of Siphon's white motes on its way back to the player who earned it. Never saved. */
    public static final RegistryObject<EntityType<RuneMote>> MOTE = ENTITIES.register("rune_mote", () ->
            EntityType.Builder.<RuneMote>of(RuneMote::new, MobCategory.MISC).sized(0.25F, 0.25F).clientTrackingRange(6).updateInterval(1)
                    .fireImmune().noSave().noSummon().build(MOD + ":rune_mote"));

    // ------------------------------------------------------------------------------------------------ the rune screen

    public static final RegistryObject<MenuType<RuneEditorMenu>> EDITOR = MENUS.register("rune_editor",
            () -> IForgeMenuType.create(RuneEditorMenu::client));

    // ------------------------------------------------------------------------------------------------ creative tab

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("runes", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.explorationremix.runes"))
            .icon(() -> {
                Item spark = item(RuneId.SPARK);
                return spark == null ? ItemStack.EMPTY : new ItemStack(spark);
            })
            .withTabsBefore(ERBlocks.TAB.getId())
            .displayItems((params, out) -> {
                for (RuneId r : RuneId.runes()) {
                    Item i = item(r);
                    if (i != null) out.accept(i);
                }
            })
            .build());

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        BLOCKS.register(modBus);
        ENTITIES.register(modBus);
        MENUS.register(modBus);
        TABS.register(modBus);
    }
}
