package com.explorationremix.world.quake;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The ground answering a climb (0.9.0, spec 4.3.7, {@code quakes.climbTremors}): a block placed more than
 * {@code supportDepth} over rock brings a local tremor round it 1.5 to 3 seconds later, which topples it and lowers the
 * climber on their tower. Pure (no game types, seeded), so it is tested offline; {@link Quakes} feeds it the placements
 * its one {@code EntityPlaceEvent} listener sees and starts the answers that fall due.
 *
 * <ul>
 *     <li>A placement that falls by the rule, with no big quake running, schedules an answer for its placer (a player, a
 *     deployer's fake player, any entity; the placed position stands in for a placer when there is none),
 *     {@link #MIN_DELAY} to {@link #MAX_DELAY} ticks later, but never before the placer's cooldown ends
 *     ({@link #COOLDOWN} ticks after the find of its last answer): an answer is put off by the cooldown, never lost.</li>
 *     <li>Nothing new is scheduled for a placer that already has one pending (the find reads the new block when it
 *     runs) or where a lowering job already takes the placed block's column down; at most {@link #MAX_PENDING} are
 *     pending in the server, and a placer past that waits for the next tremor's pass.</li>
 * </ul>
 * Each answer lasts {@link #MIN_TICKS} to {@link #MAX_TICKS} ticks at a strength of 0.25 to 0.4.
 */
public final class ClimbAnswers {
    /** How soon after the placement an answer comes, in ticks (1.5 to 3 seconds). */
    public static final int MIN_DELAY = 30, MAX_DELAY = 60;
    /** No answer for a placer comes sooner than this many ticks after the find of its last one. */
    public static final int COOLDOWN = 100;
    /** Most answers pending in the server. */
    public static final int MAX_PENDING = 8;
    /** How long an answer lasts, in ticks. */
    public static final int MIN_TICKS = 30, MAX_TICKS = 50;

    /** An answer that falls due at {@code due}: round the position {@code pos} (packed as {@code BlockPos.asLong}). */
    public record Answer(UUID placer, long pos, long due, int duration, float strength, int seed) {}

    private final Random random;
    private final Map<UUID, Answer> pending = new LinkedHashMap<>();
    /** The tick each placer's last answer found its blocks at, kept while its cooldown runs. */
    private final Map<UUID, Long> lastFind = new LinkedHashMap<>();

    public ClimbAnswers(long seed) { this.random = new Random(seed); }

    /**
     * A block was placed at {@code pos} by {@code placer} at tick {@code now}: {@code falls} whether it falls by the rule
     * (more than {@code supportDepth} over rock), {@code bigRunning} whether a big quake runs (it takes the block
     * anyway), {@code jobOnColumn} whether a lowering job already takes the block's column down. Returns the answer
     * scheduled, or null when none is.
     */
    @Nullable
    public Answer placed(UUID placer, long pos, long now, boolean falls, boolean bigRunning, boolean jobOnColumn) {
        if (!falls || bigRunning || jobOnColumn) return null;
        if (pending.containsKey(placer) || pending.size() >= MAX_PENDING) return null;
        long due = now + MIN_DELAY + random.nextInt(MAX_DELAY - MIN_DELAY + 1);
        Long last = lastFind.get(placer);
        if (last != null) due = Math.max(due, last + COOLDOWN);
        int duration = MIN_TICKS + random.nextInt(MAX_TICKS - MIN_TICKS + 1);
        float strength = 0.25F + random.nextFloat() * 0.15F;
        Answer a = new Answer(placer, pos, due, duration, strength, random.nextInt());
        pending.put(placer, a);
        return a;
    }

    /** The answers due at tick {@code now}, taken off the pending list, in the order they were scheduled. */
    public List<Answer> due(long now) {
        List<Answer> out = new ArrayList<>();
        for (Iterator<Answer> it = pending.values().iterator(); it.hasNext(); ) {
            Answer a = it.next();
            if (a.due() > now) continue;
            out.add(a);
            it.remove();
        }
        // forget cooldowns that have run out, so the map holds only the last few placers
        lastFind.values().removeIf(t -> t + COOLDOWN <= now);
        return out;
    }

    /** The answer for {@code placer} found its blocks at tick {@code now}: its cooldown runs from here. */
    public void found(UUID placer, long now) { lastFind.put(placer, now); }

    /** How many answers are pending. */
    public int pending() { return pending.size(); }

    /** True when {@code placer} has an answer pending. */
    public boolean pendingFor(UUID placer) { return pending.containsKey(placer); }

    /** Forgets every pending answer and cooldown. */
    public void clear() {
        pending.clear();
        lastFind.clear();
    }
}
