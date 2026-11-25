package com.explorationremix.rune.armor;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.rune.RuneGear;
import com.explorationremix.rune.cast.TempBlocks;
import com.explorationremix.rune.effect.RuneFx;
import com.explorationremix.rune.engine.ArmorSet;
import com.explorationremix.rune.engine.Explain;
import com.explorationremix.rune.engine.Piece;
import com.explorationremix.rune.engine.RuneId;
import com.explorationremix.rune.engine.RuneKind;
import com.explorationremix.rune.net.RuneNet;
import com.explorationremix.rune.world.TempBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.FrostWalkerEnchantment;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingExperienceDropEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What worn runes do, on the server (Forge bus). The player's own game does what moves them (a glide, a jump in the
 * air, a pull up onto a ledge: ArmorClient) and says so; everything else is here:
 * <ul>
 *   <li>helmet: Lantern's light where their head is, Sense telling their game which creatures hunt them, Far Sight
 *   marking what they hold in view as they peer;</li>
 *   <li>chestplate: a glide's fall forgiven, Second Wind's heal as a hit would leave them low, Steadfast's footing;</li>
 *   <li>leggings: Stride's step (and its way through cobwebs: WadeMixin) and Swim's stroke (Climb's pull is the
 *   player's game's, but its fall is forgiven here);</li>
 *   <li>boots: a Leap's fall forgiven, Feather's soft landing, Frost Walk's ice;</li>
 *   <li>collect sets awake on three pieces: Mend (ExperienceOrbMendMixin), Vigor's hearts, Fleet's pace, Thirst (read by
 *   CastService), Ward against creatures' hits, Wisdom's XP.</li>
 * </ul>
 * Effect runes work the same at every XP level (Taran, 2026-11-25): armor grows by its collect sets, and a set waking,
 * filling or falling asleep is told on the action bar.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID)
public final class ArmorEffects {
    // ------------------------------------------------------------------------------------------------ the numbers

    /** Lantern: how long a light stays lit once its player has gone from it. */
    static final int LANTERN_LIFE = 40;
    /** Sense: how far it feels creatures hunting you; how often it looks; the most it tells of. */
    public static final double SENSE_REACH = 24.0;
    static final int SENSE_EVERY = 10, SENSE_MOST = 64;
    /** Far Sight: how far off a creature held in view may be, and how long it glows. */
    public static final double MARK_REACH = 128.0;
    public static final int MARK_TICKS = 200;
    /** Second Wind: under what health a hit wakes it, how much it heals, and how long it rests. */
    public static final float WIND_BELOW = 6.0F, WIND_HEAL = 8.0F;
    public static final int WIND_REST = 2400;
    /** Steadfast: how much knockback it takes off, and while sneaking (all of it). */
    public static final double STEADFAST = 0.5, ROOTED = 1.0;
    /** Stride: how much higher you step (a full block, with the game's own 0.6). */
    public static final double STRIDE = 0.5;
    /** Swim: how much faster you swim. */
    public static final double SWIM = 0.6;
    /** Feather: what falls still hurt. */
    public static final float FEATHER = 0.5F;
    /** Frost Walk: the Frost Walker level it freezes water as (its reach is 2 more: 3 blocks). */
    static final int FROST = 1;
    /** Glide: the most a tick's drop that is a glide's, not a fall's (a glide sinks 0.12 a tick at most). */
    static final double GLIDE_DROP = 0.3;
    /** Leap: jumps in the air before landing. */
    public static final int LEAPS = 1;
    /** Climb: the most pulls in the air before landing (a pull is from a jump at a ledge, so one is all it takes). */
    static final int PULLS = 2;
    /** Vigor's health (two hearts, four), Fleet's pace, Ward's cut and Wisdom's XP, each awake and as a full set. */
    public static final double VIGOR = 4.0, VIGOR_FULL = 8.0, FLEET = 0.10, FLEET_FULL = 0.20;
    public static final float WARD = 0.12F, WARD_FULL = 0.25F, WISDOM = 1.5F, WISDOM_FULL = 2.0F;
    /** Mend: durability one point of XP mends, awake and as a full set. */
    public static final int MEND = 2, MEND_FULL = 4;

    /** The armor's light blue, and Second Wind's paler one. */
    public static final int ARMOR_BLUE = 0x8EC2FF, WIND_BLUE = 0xCFE3FF;

    private static final UUID STEADFAST_ID = UUID.fromString("5e0f6a3c-1d2b-4c8e-9f47-a1b2c3d4e5f6");
    private static final UUID STRIDE_ID = UUID.fromString("6a1b7c4d-2e3f-4d9a-8b51-b2c3d4e5f607");
    private static final UUID SWIM_ID = UUID.fromString("7b2c8d5e-3f4a-4eab-9c62-c3d4e5f60718");
    private static final UUID VIGOR_ID = UUID.fromString("8c3d9e6f-4a5b-4fbc-ad73-d4e5f6071829");
    private static final UUID FLEET_ID = UUID.fromString("9d4eaf70-5b6c-40cd-be84-e5f60718293a");

    /** Kept on the player through a save, so health over the usual twenty comes back with them: Vigor's hearts. */
    private static final String HEALTH_TAG = "explorationremix_armor_health";

    // ------------------------------------------------------------------------------------------------ each player's state

    private static final class Worn {
        @Nullable
        GlobalPos lantern;
        /** When Second Wind is back (game time), and whether they have been told it is. */
        long windBack;
        boolean windTold = true;
        int leaps, pulls;
        double lastY = Double.NaN;
        @Nullable
        BlockPos frostAt;
        long markAt = Long.MIN_VALUE;
        int[] sensed = new int[0];
        long sensedAt;
        /** What they wore last tick (null before their first), to tell them what woke or slept. */
        @Nullable
        ArmorSet last;
    }

    private static final Map<UUID, Worn> WORN = new HashMap<>();

    private ArmorEffects() {}

    private static Worn worn(ServerPlayer p) { return WORN.computeIfAbsent(p.getUUID(), u -> new Worn()); }

    // ------------------------------------------------------------------------------------------------ each tick

    @SubscribeEvent
    public static void playerTick(TickEvent.PlayerTickEvent e) {
        if (e.phase == TickEvent.Phase.END && e.player instanceof ServerPlayer p && !(p instanceof FakePlayer)) tick(p);
    }

    static void tick(ServerPlayer p) {
        ArmorSet set = WornRunes.of(p);
        Worn w = WORN.get(p.getUUID());
        if (w == null) {
            if (set.isEmpty()) return;
            w = worn(p);
        }
        long now = p.level().getGameTime();
        notice(p, w, set);
        attributes(p, set);
        if (p.tickCount % 20 == 0) keepHealth(p, set);
        lantern(p, w, set, now);
        if (set.has(RuneId.SENSE)) {
            if (p.tickCount % SENSE_EVERY == 0) sense(p, w, set, now);
        } else if (w.sensed.length > 0) {
            w.sensed = new int[0];
            RuneNet.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new RuneNet.SenseSync(w.sensed));
        }
        // a glide's slow sink is no fall: what it dropped is forgiven tick by tick (a fall picks up past GLIDE_DROP)
        double y = p.getY();
        if (set.has(RuneId.GLIDE) && !Double.isNaN(w.lastY) && !p.onGround() && !p.isFallFlying() && y < w.lastY && w.lastY - y < GLIDE_DROP) {
            p.resetFallDistance();
        }
        w.lastY = y;
        if (p.onGround() || p.isInWater() || p.onClimbable()) {
            w.leaps = 0;
            w.pulls = 0;
        }
        if (set.has(RuneId.FROST_WALK) && p.onGround() && !p.isSpectator()) {
            BlockPos at = p.blockPosition();
            if (!at.equals(w.frostAt)) {
                w.frostAt = at;
                FrostWalkerEnchantment.onEntityMoved(p, p.level(), at, FROST);
            }
        }
        if (!w.windTold && now >= w.windBack) {
            w.windTold = true;
            if (set.has(RuneId.SECOND_WIND)) {
                p.displayClientMessage(Component.translatable("message.explorationremix.armor.wind_back").withStyle(s -> s.withColor(WIND_BLUE)), true);
                p.playNotifySound(SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.6F, 1.5F);
            }
        }
    }

    /**
     * A collect set woke (a third piece carries it), filled (a fourth) or fell asleep: a word on the action bar and a
     * chime. Nothing for what they wore as they came in.
     */
    private static void notice(ServerPlayer p, Worn w, ArmorSet set) {
        ArmorSet was = w.last;
        w.last = set;
        if (was == null || was == set) return;
        for (RuneId c : RuneId.ofKind(RuneKind.COLLECT)) {
            int before = was.tier(c), after = set.tier(c);
            if (after == before) continue;
            Component msg = after == 2 ? Component.translatable("message.explorationremix.armor.full", c.title, c.full)
                    : after == 1 && before == 0 ? Component.translatable("message.explorationremix.armor.wakes", c.title, c.three)
                    : after == 1 ? Component.translatable("message.explorationremix.armor.not_full", c.title, c.three)
                    : Component.translatable("message.explorationremix.armor.sleeps", c.title, set.count(c));
            boolean up = after > before;
            p.displayClientMessage(msg.copy().withStyle(s -> s.withColor(up ? 0xBFD9FF : 0x8C9BB3)), true);
            p.playNotifySound(SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, up ? 0.9F : 0.5F, up ? 1.2F + 0.25F * after : 0.6F);
            if (after == 2) {
                p.playNotifySound(SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 0.4F, 1.8F);
                RuneFx.phase(p.serverLevel(), p.position(), ARMOR_BLUE, true, p.getId());
            }
            return;
        }
    }

    // ------------------------------------------------------------------------------------------------ worn numbers

    /** Steadfast, Stride, Swim, Vigor and Fleet as the game's own attributes, kept in step with what they wear. */
    private static void attributes(ServerPlayer p, ArmorSet set) {
        double steadfast = !set.has(RuneId.STEADFAST) ? 0.0 : p.isShiftKeyDown() ? ROOTED : STEADFAST;
        modifier(p, Attributes.KNOCKBACK_RESISTANCE, STEADFAST_ID, "Steadfast rune", steadfast, AttributeModifier.Operation.ADDITION);
        modifier(p, ForgeMod.STEP_HEIGHT_ADDITION.get(), STRIDE_ID, "Stride rune", set.has(RuneId.STRIDE) ? STRIDE : 0.0, AttributeModifier.Operation.ADDITION);
        modifier(p, ForgeMod.SWIM_SPEED.get(), SWIM_ID, "Swim rune", set.has(RuneId.SWIM) ? SWIM : 0.0, AttributeModifier.Operation.ADDITION);
        int vigor = set.tier(RuneId.VIGOR), fleet = set.tier(RuneId.FLEET);
        modifier(p, Attributes.MAX_HEALTH, VIGOR_ID, "Vigor runes", vigor == 2 ? VIGOR_FULL : vigor == 1 ? VIGOR : 0.0, AttributeModifier.Operation.ADDITION);
        if (p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth());
        modifier(p, Attributes.MOVEMENT_SPEED, FLEET_ID, "Fleet runes", fleet == 2 ? FLEET_FULL : fleet == 1 ? FLEET : 0.0, AttributeModifier.Operation.MULTIPLY_BASE);
    }

    /** One rune's modifier: added, changed or taken off ({@code amount} 0). Transient, so nothing of it is ever saved. */
    private static void modifier(Player p, Attribute a, UUID id, String name, double amount, AttributeModifier.Operation op) {
        AttributeInstance inst = p.getAttribute(a);
        if (inst == null) return;
        AttributeModifier m = inst.getModifier(id);
        if (m != null && m.getAmount() == amount && m.getOperation() == op) return;
        if (m != null) inst.removeModifier(id);
        if (amount != 0.0) inst.addTransientModifier(new AttributeModifier(id, name, amount, op));
    }

    /** Health over the usual most (Vigor's) is noted, as the game caps health at what it was loaded with. */
    private static void keepHealth(ServerPlayer p, ArmorSet set) {
        CompoundTag data = p.getPersistentData();
        if (set.tier(RuneId.VIGOR) > 0 && p.getHealth() > 20.0F) data.putFloat(HEALTH_TAG, p.getHealth());
        else data.remove(HEALTH_TAG);
    }

    /** Back in the world: Vigor's hearts are on again at once, and the health they had in them with them. */
    @SubscribeEvent
    public static void loggedIn(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || p instanceof FakePlayer) return;
        attributes(p, WornRunes.of(p));
        CompoundTag data = p.getPersistentData();
        if (data.contains(HEALTH_TAG)) {
            float h = data.getFloat(HEALTH_TAG);
            if (h > p.getHealth()) p.setHealth(h);
        }
    }

    // ------------------------------------------------------------------------------------------------ the helmet

    /** Lantern on the helmet: a soft light where their head is (their feet, if the head is in something). */
    private static void lantern(ServerPlayer p, Worn w, ArmorSet set, long now) {
        boolean on = !p.isSpectator() && p.isAlive() && set.has(RuneId.LANTERN);
        ServerLevel l = p.serverLevel();
        BlockPos at = null;
        if (on) {
            BlockPos head = BlockPos.containing(p.getEyePosition()), feet = p.blockPosition();
            at = fits(l, head) ? head : fits(l, feet) ? feet : null;
        }
        w.lantern = light(p, w.lantern, at, now);
    }

    private static boolean fits(ServerLevel l, BlockPos pos) {
        return TempBlocks.fits(l, pos, TempBlock.Kind.LANTERN)
                || (l.getBlockState(pos).getBlock() instanceof TempBlock tb && tb.kind == TempBlock.Kind.LANTERN);
    }

    /**
     * One light kept for its player: moved to {@code at} (the next one goes up before this one goes out, so it never
     * blinks), kept lit while it stands, or put out ({@code at} null). Put out from outside (something placed there,
     * water over it), it is lit again when there is room.
     */
    @Nullable
    private static GlobalPos light(ServerPlayer p, @Nullable GlobalPos was, @Nullable BlockPos at, long now) {
        ServerLevel l = p.serverLevel();
        GlobalPos here = at == null ? null : GlobalPos.of(l.dimension(), at);
        if (was != null && !was.equals(here)) {
            GlobalPos next = here != null && TempBlocks.place(l, at, TempBlock.Kind.LANTERN, p) ? here : null;
            TempBlocks.end(p.server, was, TempBlock.Kind.LANTERN);
            return next;
        }
        if (here == null) return null;
        if (was == null) return TempBlocks.place(l, at, TempBlock.Kind.LANTERN, p) ? here : null;
        if (!TempBlocks.standing(here.dimension(), at)) return null;
        TempBlocks.extend(here, now + LANTERN_LIFE);
        return was;
    }

    /** Sense: which creatures near hunt them (their target is the player), for their game to show glowing. */
    private static void sense(ServerPlayer p, Worn w, ArmorSet set, long now) {
        double reach = SENSE_REACH;
        List<Mob> hunting = p.serverLevel().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(reach),
                m -> m.isAlive() && m.distanceToSqr(p) <= reach * reach && hunts(m, p));
        int[] ids = hunting.stream().mapToInt(Entity::getId).sorted().limit(SENSE_MOST).toArray();
        // told when it changes, and again now and then so their game knows it still stands
        if (Arrays.equals(ids, w.sensed) && (ids.length == 0 || now - w.sensedAt < 20)) return;
        w.sensed = ids;
        w.sensedAt = now;
        RuneNet.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new RuneNet.SenseSync(ids));
    }

    /** Whether {@code m} is after {@code p}: its target, or its brain's (piglins, wardens and their like). */
    public static boolean hunts(Mob m, Player p) {
        if (m.getTarget() == p) return true;
        Brain<?> b = m.getBrain();
        return b.hasMemoryValue(MemoryModuleType.ATTACK_TARGET) && b.getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) == p;
    }

    /** Far Sight: the player's game says they have held creature {@code id} in view as they peer; it glows for ten seconds. */
    public static void marked(ServerPlayer p, int id) {
        ArmorSet set = WornRunes.of(p);
        if (!set.has(RuneId.FAR_SIGHT)) return;
        Worn w = worn(p);
        long now = p.level().getGameTime();
        if (now - w.markAt < 10) return;
        Entity e = p.level().getEntity(id);
        if (!(e instanceof LivingEntity le) || le instanceof Player || !le.isAlive() || le.distanceToSqr(p) > MARK_REACH * MARK_REACH) return;
        if (!p.hasLineOfSight(le)) return;
        w.markAt = now;
        le.addEffect(new MobEffectInstance(MobEffects.GLOWING, MARK_TICKS, 0, false, false, true), p);
        p.playNotifySound(SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.7F, 1.8F);
    }

    // ------------------------------------------------------------------------------------------------ moving: what the player's game did

    /** The player's game jumped in the air (Leap): the fall they had is forgiven, once before they land. */
    public static void leapt(ServerPlayer p) {
        ArmorSet set = WornRunes.of(p);
        if (!set.has(RuneId.LEAP)) return;
        Worn w = worn(p);
        if (w.leaps >= LEAPS) return;
        w.leaps++;
        p.resetFallDistance();
        RuneFx.armorFrom(p, p.position(), ARMOR_BLUE, RuneFx.ARMOR_LEAP);
        p.level().playSound(p, p.getX(), p.getY(), p.getZ(), SoundEvents.PHANTOM_FLAP, SoundSource.PLAYERS, 0.4F, 1.7F);
    }

    /** The player's game pulled them up onto a ledge (Climb): the fall they had before they caught it is forgiven. */
    public static void climbed(ServerPlayer p) {
        ArmorSet set = WornRunes.of(p);
        if (!set.has(RuneId.CLIMB)) return;
        Worn w = worn(p);
        if (w.pulls >= PULLS) return;
        w.pulls++;
        p.resetFallDistance();
        RuneFx.armorFrom(p, p.position(), ARMOR_BLUE, RuneFx.ARMOR_CLIMB);
        p.level().playSound(p, p.getX(), p.getY(), p.getZ(), SoundEvents.ARMOR_EQUIP_LEATHER, SoundSource.PLAYERS, 0.7F, 1.1F);
    }

    // ------------------------------------------------------------------------------------------------ hits and falls

    /**
     * Second Wind, as a hit would leave them under three hearts: four hearts come off the hit, and what the hit didn't
     * need of them heals. Then it rests two minutes. Never for what nothing withstands (the void, a kill command).
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void damaged(LivingDamageEvent e) {
        if (e.isCanceled() || !(e.getEntity() instanceof ServerPlayer p) || p instanceof FakePlayer || e.getAmount() <= 0.0F) return;
        if (e.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return;
        ArmorSet set = WornRunes.of(p);
        if (!set.has(RuneId.SECOND_WIND)) return;
        Worn w = worn(p);
        long now = p.level().getGameTime();
        float amount = e.getAmount();
        if (now < w.windBack || p.getHealth() - amount >= WIND_BELOW) return;
        float off = Math.min(amount, WIND_HEAL);
        e.setAmount(amount - off);
        if (WIND_HEAL - off > 0.0F) p.heal(WIND_HEAL - off);
        w.windBack = now + WIND_REST;
        w.windTold = false;
        RuneFx.armor(p.serverLevel(), p.position(), WIND_BLUE, RuneFx.ARMOR_WIND, p.getId());
        RuneFx.play(p.serverLevel(), p.position().add(0, 1, 0), SoundEvents.AMETHYST_BLOCK_RESONATE, 1.0F, 1.5F);
        RuneFx.play(p.serverLevel(), p.position().add(0, 1, 0), SoundEvents.BEACON_POWER_SELECT, 0.5F, 1.9F);
        p.displayClientMessage(Component.translatable("message.explorationremix.armor.wind").withStyle(s -> s.withColor(WIND_BLUE)), true);
    }

    /** Ward: creatures' hits (not players') land lighter on a player whose armor carries it on three pieces or more. */
    @SubscribeEvent
    public static void hurt(LivingHurtEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || p instanceof FakePlayer) return;
        Entity from = e.getSource().getEntity();
        if (!(from instanceof LivingEntity) || from instanceof Player) return;
        int tier = WornRunes.of(p).tier(RuneId.WARD);
        if (tier > 0) e.setAmount(e.getAmount() * (1.0F - (tier == 2 ? WARD_FULL : WARD)));
    }

    /** Feather: falls hurt half as much. The landing itself is as ever. */
    @SubscribeEvent
    public static void fall(LivingFallEvent e) {
        if (!(e.getEntity() instanceof Player p) || p.level().isClientSide()) return;
        if (WornRunes.of(p).has(RuneId.FEATHER)) e.setDamageMultiplier(e.getDamageMultiplier() * FEATHER);
    }

    // ------------------------------------------------------------------------------------------------ collect sets

    /** How much more energy a hit knocks loose with Thirst on the armor: 1 asleep, more awake and as a full set. */
    public static float thirst(Player p) {
        int tier = WornRunes.of(p).tier(RuneId.THIRST);
        return tier == 2 ? Explain.THIRST_FULL_GAIN : tier == 1 ? Explain.THIRST_GAIN : 1.0F;
    }

    /**
     * Mend: {@code xp} points picked up mend worn armor, a random damaged piece at a time ({@link #MEND} durability a
     * point); a full set mends what they hold too, twice as fast. The XP left over, for the game's own mending and then
     * the player. Server side.
     */
    public static int mend(Player p, int xp) {
        if (xp <= 0 || p.level().isClientSide()) return xp;
        int tier = WornRunes.of(p).tier(RuneId.MEND);
        if (tier == 0) return xp;
        int rate = tier == 2 ? MEND_FULL : MEND;
        RandomSource r = p.getRandom();
        while (xp > 0) {
            List<ItemStack> worn = new ArrayList<>();
            for (Piece pc : Piece.values()) worn.add(p.getItemBySlot(RuneGear.slot(pc)));
            if (tier == 2) {
                worn.add(p.getItemInHand(InteractionHand.MAIN_HAND));
                worn.add(p.getItemInHand(InteractionHand.OFF_HAND));
            }
            worn.removeIf(s -> s.isEmpty() || !s.isDamageableItem() || !s.isDamaged());
            if (worn.isEmpty()) break;
            ItemStack s = worn.get(r.nextInt(worn.size()));
            int mended = Math.min(xp * rate, s.getDamageValue());
            s.setDamageValue(s.getDamageValue() - mended);
            xp -= Math.max(1, (mended + rate - 1) / rate);
        }
        return Math.max(0, xp);
    }

    /** Wisdom: what a creature drops for its killer comes as half as much again (a full set: twice). */
    @SubscribeEvent
    public static void xpDrop(LivingExperienceDropEvent e) {
        Player p = e.getAttackingPlayer();
        if (p == null || p.level().isClientSide() || e.getDroppedExperience() <= 0) return;
        float k = wisdom(p);
        if (k > 1.0F) e.setDroppedExperience(more(e.getDroppedExperience(), k, p.getRandom()));
    }

    /** Wisdom: what a block gives for mining it. */
    @SubscribeEvent
    public static void broke(BlockEvent.BreakEvent e) {
        Player p = e.getPlayer();
        if (p == null || p.level().isClientSide() || e.getExpToDrop() <= 0) return;
        float k = wisdom(p);
        if (k > 1.0F) e.setExpToDrop(more(e.getExpToDrop(), k, p.getRandom()));
    }

    private static float wisdom(Player p) {
        int tier = WornRunes.of(p).tier(RuneId.WISDOM);
        return tier == 2 ? WISDOM_FULL : tier == 1 ? WISDOM : 1.0F;
    }

    /** {@code xp} times {@code k}, the part of a point left over rounded up or down at random (so a 1 gives 1 or 2). */
    static int more(int xp, float k, RandomSource r) {
        float f = xp * k;
        int whole = (int) f;
        return whole + (r.nextFloat() < f - whole ? 1 : 0);
    }

    // ------------------------------------------------------------------------------------------------ leaving

    /** The player left: their lights go out, and what was kept for them goes. */
    public static void forget(ServerPlayer p) {
        Worn w = WORN.remove(p.getUUID());
        if (w == null) return;
        if (w.lantern != null) TempBlocks.end(p.server, w.lantern, TempBlock.Kind.LANTERN);
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent e) { WORN.clear(); }
}
