package com.explorationremix.creature.body;

import org.joml.Vector3d;

/**
 * When the ember urchin chases someone, and when it gives up (0.24.3, Taran: "Can you make it chase the player at the
 * player's walking speed and keep this as its meandering speed?"). The rules alone, in plain numbers: the entity says
 * who it sees and where they are; {@link EmberWalk#chase} finds its way to them.
 *
 * <ul>
 *     <li>It sets off after a player it sees within {@link #SEE} (the cards' "When it sees you 16 blocks off"), and
 *     after one who hurts it from anywhere within {@link #FAR} (the cards' "Yes, it comes for whoever hurts it").</li>
 *     <li>First it rouses: it flares and hisses and stays {@link #ROUSE} ticks, then sets off (the cards' "Flares and
 *     hisses, then sets off").</li>
 *     <li>Out of its sight it makes for where it last saw them. Out of its sight {@link #LOST} ticks, or {@link #FAR}
 *     off, it gives up (the cards' "Lost 5 s, or you're 32 blocks off") and meanders on from where it is.</li>
 *     <li>With no way to them (in the Ash Hollows, which it keeps out of: the cards' "No, the Hollows stay safe") it
 *     waits at the nearest place it can reach, and gives up after {@link #NO_WAY} ticks.</li>
 *     <li>Given up, it notices no one for {@link #REST} ticks ({@link #REST_NO_WAY} if it found no way to them, so that
 *     it does not set off again and again after someone it cannot reach, below a drop, say).</li>
 * </ul>
 */
public final class EmberChase {
    /** It sets off after a player it sees this near. */
    public static final double SEE = 16.0;
    /** It gives up on one this far off (and is set after one who hurts it only from within this). */
    public static final double FAR = 32.0;
    /** Ticks: its warning before it sets off; out of its sight this long, it gives up; with no way to them this long. */
    public static final int ROUSE = 10, LOST = 100, NO_WAY = 40;
    /** Given up, it notices no one this long (ticks); this long if it found no way to them. */
    public static final int REST = 40, REST_NO_WAY = 200;

    public enum Phase { NONE, ROUSE, CHASE }

    public Phase phase = Phase.NONE;
    /** Whom it chases (the entity's id; -1 none). */
    public int target = -1;
    /** Where it last saw them (their feet), and makes for while they are out of its sight. */
    public final Vector3d seenAt = new Vector3d();
    /** Whether it sees them now. */
    public boolean seesNow;
    private int rouse, lost, noWay, rest;
    /** For checks: chases begun, and why the last was given up. */
    public int chases;
    public String gaveUp = "";

    /** Whether it may notice someone now (not chasing, not resting after giving up). */
    public boolean free() { return phase == Phase.NONE && rest <= 0; }

    public boolean chasing() { return phase != Phase.NONE; }

    /** It is set after {@code id}, seen (or hurt by) at {@code at}: it rouses first. */
    public void start(int id, Vector3d at) {
        boolean again = phase != Phase.NONE && target == id;
        target = id;
        seenAt.set(at);
        seesNow = true;
        lost = 0;
        noWay = 0;
        if (again) return;
        phase = Phase.ROUSE;
        rouse = ROUSE;
        chases++;
    }

    /**
     * A tick of the chase. {@code there}: they are still about to be chased (alive, playing, in its level, not in
     * Peaceful); {@code sees} whether it sees them now ({@code null} if it did not look this tick: as it last looked),
     * {@code at} where they are, {@code distance} how far from it; {@code way} whether it can still get nearer them (its
     * way to them reaches them, or at least takes it on). Whether it still chases.
     */
    public boolean tick(boolean there, Boolean sees, Vector3d at, double distance, boolean way) {
        if (rest > 0) rest--;
        if (phase == Phase.NONE) return false;
        if (!there) return stop("gone");
        if (distance > FAR) return stop("far");
        if (sees != null) seesNow = sees;
        if (seesNow) {
            seenAt.set(at);
            lost = 0;
        } else if (++lost >= LOST) return stop("lost");
        if (phase == Phase.ROUSE) {
            if (--rouse <= 0) phase = Phase.CHASE;
            return true;
        }
        noWay = way ? 0 : noWay + 1;
        if (noWay >= NO_WAY) return stop("no way");
        return true;
    }

    /** Gives up (for {@code why}): it meanders on from here, and notices no one a while. */
    public boolean stop(String why) {
        phase = Phase.NONE;
        target = -1;
        gaveUp = why;
        rest = why.equals("no way") ? REST_NO_WAY : REST;
        return false;
    }

    /** For /remix ember: what it is doing, in words. */
    public String describe() {
        return switch (phase) {
            case NONE -> rest > 0 ? "gave up (" + gaveUp + ")" : "";
            case ROUSE -> "rousing";
            case CHASE -> seesNow ? "chasing" : "chasing, lost " + lost + " ticks";
        };
    }
}
