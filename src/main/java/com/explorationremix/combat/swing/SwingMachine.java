package com.explorationremix.combat.swing;

/**
 * One player's swings, tick by tick, with no game in it (the player's own game runs it; tests run it too). A press
 * starts a light swing's wind-up; held on past {@link WeaponProfile#holdAt} it becomes a strong attack, which fires on
 * its own once wound up and then lunges (held without a break: a quick second click is a tap, not a hold). Held in
 * mid-air instead (a sword, Taran 2026-11-25), it becomes a heavy jump attack: the blade rises overhead and waits, and
 * lands the moment the player touches down (never sooner than {@link WeaponProfile#jumpMin} after the press); in water,
 * on a ladder, or still falling after {@link #JUMP_MAX_AIR} ticks, it is dropped. A press while a swing is under way
 * waits for it to finish. Light swings in a row make a chain: slash, backhand, chop; after the chain's last one comes
 * the cooldown, in which a press can only start a strong (or jump) attack (held, or it is let go), and a heavy attack
 * starts the heavy cooldown, in which nothing can start (a press is refused, unless it comes in the cooldown's last few
 * ticks: then it waits). Idle for a moment, the chain starts again from its first swing.
 */
public final class SwingMachine {
    /** What the machine tells the game. */
    public interface Out {
        void start(int seq, SwingKind kind, int combo);

        /** The light wind-up under way became a heavy one: a strong attack's, or a jump attack's. */
        void upgrade(int seq, SwingKind kind);

        void land(int seq, SwingKind kind, int combo);

        /** Each tick of a strong attack's lunge, from 0; {@code speed} is what the player is set moving at. */
        void lunge(int seq, int tick, double speed);

        void cancel(int seq);

        /** A press that could start nothing (the cooldown after a heavy attack). */
        void refused();
    }

    public enum Phase { IDLE, WINDUP, LUNGE, RECOVER }

    /** Where the player is, each tick: on their feet, in the air on their own (jumped, or off a ledge), or neither. */
    public enum Footing { GROUND, AIR, OTHER }

    /** A press this near the end of a cooldown waits for it instead of being refused. */
    static final int BUFFER_END = 4;
    /** A jump attack still in the air this long after its press is dropped (a fall that long is no jump). */
    public static final int JUMP_MAX_AIR = 50;

    private WeaponProfile w;
    private Phase phase = Phase.IDLE;
    private SwingKind kind = SwingKind.LIGHT;
    private int combo, t, seq, chain, idle, cooldown, cooldownTotal;
    private boolean buffered, fromCooldown, cooldownFromStrong;
    /** The button was let go since the press of the wind-up under way. */
    private boolean let;

    public SwingMachine(WeaponProfile w) { this.w = w; }

    public WeaponProfile profile() { return w; }

    /** Another weapon in hand: whatever was under way is dropped (the caller cancels it), the cooldown goes on. */
    public void profile(WeaponProfile p) { this.w = p; }

    // ------------------------------------------------------------------------------------------------ input

    /** The attack button was pressed this tick. */
    public void press(Out out) {
        if (cooldownFromStrong && cooldown > BUFFER_END) {
            // nothing can start in a heavy attack's cooldown, even once its lunge is over
            out.refused();
            return;
        }
        if (phase != Phase.IDLE || (cooldown > 0 && cooldownFromStrong)) {
            buffered = true;
            return;
        }
        begin(out, false);
    }

    private void begin(Out out, boolean fromBuffer) {
        if (cooldown > 0) {
            if (cooldownFromStrong) {
                if (!fromBuffer) out.refused();
                return;
            }
            kind = SwingKind.STRONG;
            fromCooldown = true;
        } else {
            kind = SwingKind.LIGHT;
            fromCooldown = false;
        }
        combo = kind == SwingKind.LIGHT ? Math.min(chain, w.chain() - 1) : 0;
        phase = Phase.WINDUP;
        t = 0;
        let = false;
        seq++;
        out.start(seq, kind, combo);
    }

    /** Drops what is under way (another item in hand, a screen open, the player using an item): no hit, no cooldown. */
    public void cancel(Out out) {
        if (phase == Phase.WINDUP) out.cancel(seq);
        if (phase != Phase.IDLE) phase = Phase.IDLE;
        buffered = false;
        idle = 0;
    }

    /** A press waiting for the swing under way is dropped (the guard went up). */
    public void unbuffer() { buffered = false; }

    /** Back to nothing at all (a new world, death). */
    public void reset() {
        phase = Phase.IDLE;
        buffered = false;
        chain = idle = cooldown = cooldownTotal = 0;
        cooldownFromStrong = false;
    }

    // ------------------------------------------------------------------------------------------------ time

    /** One tick on the ground (tests of the swings that don't jump). */
    public void tick(boolean held, Out out) { tick(held, Footing.GROUND, out); }

    /**
     * One tick, after this tick's presses: {@code held} is whether the attack button is down now, {@code footing} where
     * the player is now.
     */
    public void tick(boolean held, Footing footing, Out out) {
        if (cooldown > 0 && --cooldown == 0) cooldownFromStrong = false;
        switch (phase) {
            case IDLE -> {
                if (++idle > w.chainGap()) chain = 0;
                if (buffered && (cooldown == 0 || !cooldownFromStrong)) {
                    buffered = false;
                    begin(out, true);
                }
            }
            case WINDUP -> windup(held, footing, out);
            case LUNGE -> {
                if (t < w.lungeTicks()) out.lunge(seq, t, w.lunge()[t]);
                if (++t >= w.lungeTicks()) {
                    phase = Phase.RECOVER;
                    t = 0;
                }
            }
            case RECOVER -> {
                if (++t >= w.recover(kind)) {
                    phase = Phase.IDLE;
                    idle = 0;
                    if (buffered) {
                        buffered = false;
                        begin(out, true);
                    }
                }
            }
        }
    }

    private void windup(boolean held, Footing footing, Out out) {
        t++;
        if (!held) let = true;
        if (kind == SwingKind.JUMP) {
            // overhead, waiting to come down: it lands as the player touches down
            if (footing == Footing.OTHER || (footing == Footing.AIR && t > JUMP_MAX_AIR)) {
                drop(out);
                return;
            }
            if (footing == Footing.AIR || t < w.jumpMin()) return;
            landNow(out);
            return;
        }
        if (fromCooldown && let && t <= w.holdAt()) {
            // a strong attack out of the cooldown is only made by holding; let go early, it is dropped, unless the
            // cooldown ran out meanwhile: then it goes on as the light swing it would now have been
            if (cooldown == 0 && t < w.lightWindup()) {
                kind = SwingKind.LIGHT;
                fromCooldown = false;
                combo = 0;
                chain = 0;
                out.start(seq, kind, combo);
            } else {
                drop(out);
                return;
            }
        }
        if (!let && t == w.holdAt()) {
            if (footing == Footing.AIR && w.jumps()) {
                // held in mid-air: a jump attack (out of the chain's cooldown too)
                kind = SwingKind.JUMP;
                combo = 0;
                out.upgrade(seq, kind);
                return;
            }
            if (kind == SwingKind.LIGHT) {
                kind = SwingKind.STRONG;
                combo = 0;
                out.upgrade(seq, kind);
            }
        }
        if (t < w.windup(kind)) return;
        landNow(out);
    }

    private void landNow(Out out) {
        out.land(seq, kind, combo);
        switch (kind) {
            case LIGHT -> {
                if (++chain >= w.chain()) startCooldown(SwingKind.LIGHT);
                phase = Phase.RECOVER;
            }
            case STRONG -> {
                startCooldown(kind);
                phase = Phase.LUNGE;
            }
            case JUMP -> {
                startCooldown(kind);
                phase = Phase.RECOVER;
            }
        }
        t = 0;
    }

    private void drop(Out out) {
        out.cancel(seq);
        phase = Phase.IDLE;
        idle = 0;
    }

    private void startCooldown(SwingKind k) {
        chain = 0;
        cooldown = cooldownTotal = w.cooldown(k);
        cooldownFromStrong = k.heavy();
    }

    // ------------------------------------------------------------------------------------------------ for drawing

    public Phase phase() { return phase; }

    public SwingKind kind() { return kind; }

    public int combo() { return combo; }

    /** Ticks into the phase. */
    public int ticks() { return t; }

    public int seq() { return seq; }

    /** Light swings landed in the chain so far. */
    public int chain() { return chain; }

    public int cooldown() { return cooldown; }

    public int cooldownTotal() { return cooldownTotal; }

    /** The cooldown is a heavy attack's (nothing can start in it). */
    public boolean cooldownFromStrong() { return cooldownFromStrong; }

    /** The wind-up under way is a strong attack made out of the cooldown (it needs the button held). */
    public boolean fromCooldown() { return fromCooldown; }

    public boolean busy() { return phase != Phase.IDLE; }
}
