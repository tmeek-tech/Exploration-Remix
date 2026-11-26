package com.explorationremix.rune.stone;

import com.explorationremix.registry.ERSounds;
import com.explorationremix.rune.RuneRegistry;
import com.explorationremix.rune.effect.RuneFx;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A rune freed from a broken rune stone (0.23.1; Taran's card pick: "the runes rise out of it and fly to you"): it rises
 * out of the stone turning slowly, hangs there a moment, glowing, then streaks to the player who broke it, through
 * anything in the way, and goes into their pack, with a clear note and the rune's name over the hotbar. A second rune
 * rises beside the first and comes a beat later, a note higher.
 *
 * <p>It never loses its rune: a player gone, dead, in another world or more than {@link #REACH} blocks off, or a flight
 * that runs past {@link #LIFE} ticks, leaves it lying where it is as an item; a full pack drops what does not fit at the
 * player's feet. It is saved with its chunk, so a world closed mid-flight finds it there, and with nobody near it falls
 * as an item then.
 */
public class FreedRune extends Entity {
    private static final EntityDataAccessor<ItemStack> DATA_ITEM = SynchedEntityData.defineId(FreedRune.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<Byte> DATA_INDEX = SynchedEntityData.defineId(FreedRune.class, EntityDataSerializers.BYTE);

    /** Ticks it rises out of the stone, slowing. */
    public static final int RISE = 12;
    /** Ticks it hangs, glowing, before it flies (the second rune waits a few more). */
    public static final int HANG = 8, SECOND_WAIT = 5;
    /** It flies to its player from this far; any further and it is left as an item. */
    public static final double REACH = 48.0;
    /** A flight longer than this leaves it as an item. */
    public static final int LIFE = 240;

    /** Each stone's runes caught so far (by the stone's key), for the names shown over the hotbar. Server side only. */
    private static final Map<Long, Caught> CAUGHT = new HashMap<>();

    private record Caught(long tick, List<Component> names) {}

    @Nullable
    private UUID owner;
    /** The stone it came out of (its position packed), shared by the stone's runes. */
    private long stone;
    private int age;

    public FreedRune(EntityType<? extends FreedRune> type, Level level) {
        super(type, level);
        noPhysics = true;
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(DATA_ITEM, ItemStack.EMPTY);
        entityData.define(DATA_INDEX, (byte) 0);
    }

    /** The rune it carries. */
    public ItemStack item() { return entityData.get(DATA_ITEM); }

    /** 0 for a stone's first rune, 1 for its second. */
    public int index() { return entityData.get(DATA_INDEX); }

    /** Ticks since it came out of its stone (the client counts its own). */
    public int age() { return age; }

    /**
     * Frees {@code stack} from the stone at {@code at} for {@code owner}: rune {@code index} of {@code of}, the second
     * drifting out to the other side of the first, square to the way the player looks at the stone.
     */
    public static void spawn(ServerLevel level, Vec3 at, ServerPlayer owner, ItemStack stack, int index, int of, long stone) {
        FreedRune r = RuneRegistry.FREED_RUNE.get().create(level);
        if (r == null) {
            drop(level, at, stack);
            return;
        }
        r.owner = owner.getUUID();
        r.stone = stone;
        r.entityData.set(DATA_ITEM, stack.copy());
        r.entityData.set(DATA_INDEX, (byte) index);
        Vec3 look = at.subtract(owner.getEyePosition());
        Vec3 side = new Vec3(-look.z, 0, look.x);
        side = side.lengthSqr() < 1.0E-6 ? new Vec3(1, 0, 0) : side.normalize();
        double spread = of > 1 ? (index == 0 ? -0.055 : 0.055) : 0.0;
        r.setPos(at.x, at.y - 0.15, at.z);
        r.setDeltaMovement(side.x * spread, 0.2 - 0.03 * index, side.z * spread);
        r.setYRot(level.random.nextFloat() * 360.0F);
        if (!level.addFreshEntity(r)) drop(level, at, stack);
    }

    @Override
    public void tick() {
        super.tick();
        Vec3 v = getDeltaMovement();
        if (level().isClientSide) {
            age++;
            setPos(getX() + v.x, getY() + v.y, getZ() + v.z);
            RuneFx.client.freedTick(this);
            return;
        }
        age++;
        ItemStack stack = item();
        if (stack.isEmpty()) {
            discard();
            return;
        }
        ServerPlayer p = player();
        int wait = RISE + HANG + SECOND_WAIT * index();
        if (p == null || distanceToSqr(p) > REACH * REACH || age > LIFE) {
            // nobody to fly to: it settles where it is, a rune on the ground
            drop((ServerLevel) level(), position(), stack);
            discard();
            return;
        }
        if (age < RISE) {
            // rising out of the stone, slowing to a hang
            v = v.scale(0.84);
        } else if (age < wait) {
            // hanging: a slow bob
            v = new Vec3(0, Math.sin((age - RISE) * 0.45) * 0.008, 0);
        } else {
            Vec3 target = p.position().add(0, p.getBbHeight() * 0.6, 0);
            Vec3 to = target.subtract(position());
            double d = to.length();
            if (d < 0.45 + p.getBbWidth() * 0.5) {
                collect(p, stack);
                return;
            }
            // a gentle start that gathers speed, as a mote does, then straight in
            double speed = Math.min(1.25, Math.max(0.18, v.length() + 0.09));
            v = v.lerp(to.scale(speed / d), 0.38);
            if (v.length() > d) v = to;
        }
        setDeltaMovement(v);
        setPos(getX() + v.x, getY() + v.y, getZ() + v.z);
    }

    @Nullable
    private ServerPlayer player() {
        if (owner == null) return null;
        Player p = level().getPlayerByUUID(owner);
        return p instanceof ServerPlayer sp && sp.isAlive() && !sp.isSpectator() ? sp : null;
    }

    /** Into the pack, or at the player's feet what does not fit; heard and named. */
    private void collect(ServerPlayer p, ItemStack stack) {
        ItemStack left = stack.copy();
        int count = left.getCount();
        p.getInventory().add(left);
        if (!left.isEmpty()) drop((ServerLevel) level(), p.position(), left);
        p.awardStat(Stats.ITEM_PICKED_UP.get(stack.getItem()), count);
        Vec3 chest = p.position().add(0, p.getBbHeight() * 0.6, 0);
        level().playSound(null, p.getX(), p.getY(), p.getZ(), ERSounds.RUNE_STONE_CATCH.get(), SoundSource.PLAYERS, 0.7F,
                index() == 0 ? 1.0F : 1.335F);
        RuneFx.stoneCatch((ServerLevel) level(), chest, index(), p.getId());
        say(p, stack);
        RuneStones.FREED.incrementAndGet();
        entityData.set(DATA_ITEM, ItemStack.EMPTY);
        discard();
    }

    /** The runes this stone has given so far, over the hotbar: "Spark Rune" then "Spark Rune  ·  Glow Rune". */
    private void say(ServerPlayer p, ItemStack stack) {
        long now = level().getGameTime();
        for (Iterator<Map.Entry<Long, Caught>> it = CAUGHT.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue().tick() > 200) it.remove();
        }
        Caught c = CAUGHT.computeIfAbsent(stone ^ p.getUUID().getLeastSignificantBits(), k -> new Caught(now, new ArrayList<>()));
        c.names().add(stack.getHoverName());
        MutableComponent line = Component.empty();
        for (int i = 0; i < c.names().size(); i++) {
            if (i > 0) line.append(Component.literal("  ·  ").withStyle(ChatFormatting.DARK_GRAY));
            line.append(c.names().get(i).copy().withStyle(st -> st.withColor(0xBFD9FF)));
        }
        p.displayClientMessage(line, true);
    }

    static void drop(ServerLevel level, Vec3 at, ItemStack stack) {
        if (stack.isEmpty()) return;
        ItemEntity e = new ItemEntity(level, at.x, at.y, at.z, stack.copy());
        e.setDeltaMovement(0, 0.1, 0);
        e.setPickUpDelay(10);
        level.addFreshEntity(e);
    }

    public static void clearAll() { CAUGHT.clear(); }

    /** Its age goes with it to each client, so one that starts watching it mid-flight sees it at the right stage. */
    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() { return new ClientboundAddEntityPacket(this, age); }

    @Override
    public void recreateFromPacket(ClientboundAddEntityPacket packet) {
        super.recreateFromPacket(packet);
        age = packet.getData();
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double d) { return d < 96 * 96; }

    @Override
    public boolean isPickable() { return false; }

    @Override
    public boolean isAttackable() { return false; }

    /** A portal never takes it: it belongs to the world its stone stood in. */
    @Override
    public boolean canChangeDimensions() { return false; }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        entityData.set(DATA_ITEM, ItemStack.of(tag.getCompound("Item")));
        entityData.set(DATA_INDEX, tag.getByte("Index"));
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        stone = tag.getLong("Stone");
        age = tag.getInt("Age");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.put("Item", item().save(new CompoundTag()));
        tag.putByte("Index", (byte) index());
        if (owner != null) tag.putUUID("Owner", owner);
        tag.putLong("Stone", stone);
        tag.putInt("Age", age);
    }
}
