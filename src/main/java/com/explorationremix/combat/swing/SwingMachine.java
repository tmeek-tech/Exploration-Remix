package com.explorationremix.combat.swing;

/**
 * One player's swings, tick by tick, with no game in it (the player's own game runs it; tests run it too). A press
 * starts a light swing's wind-up; held on past {@link WeaponProfile#holdAt} it becomes a strong attack, which fires on
 * its own once wound up and then lunges (held without a break: a quick second click is a tap, not a hold). A press while a
 * swing is under way waits for it to finish. Light swings in a row make a chain: slash, backhand, chop; after the
 * chain's last one comes the cooldown, in which a press can only start a strong attack (held, or it is let go), and a
 * strong attack starts the cooldown again, in which nothing can start (a press is refused, unless it comes in the
 * cooldown's last few ticks: then it waits). Idle for a moment, the chain starts again from its first swing.
 */
public final class SwingMachine {
    /** What the machine tells the game. */
    public interface Out {
        void start(int seq, SwingKind kind, int combo);

        /** The light wind-up under way became a strong attack's. */
        void upgrade(int seq);

        void land(int seq, SwingKind kind, int combo);

        /** Each tick of a strong attack's lunge, from 0; {@code speed} is what the player is set moving at. */
        void lunge(int seq, int tick, double speed);

        void cancel(int seq);

        /** A press that could start nothing (the cooldown after a strong attack). */
        void refused();
    }

    public enum Phase { IDLE, WINDUP, LUNGE, RECOVER }

    /** A press this near the end of a cooldown waits for it instead of being refused. */
    static final int BUFFER_END = 4;

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
            // nothing can start in a strong attack's cooldown, even once its lunge is over
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

    /** Back to nothing at all (a new world, death). */
    public void reset() {
        phase = Phase.IDLE;
        buffered = false;
        chain = idle = cooldown = cooldownTotal = 0;
        cooldownFromStrong = false;
    }

    // ------------------------------------------------------------------------------------------------ time

    /** One tick, after this tick's presses: {@code held} is whether the attack button is down now. */
    public void tick(boolean held, Out out) {
        if (cooldown > 0 && --cooldown == 0) cooldownFromStrong = false;
        switch (phase) {
            case IDLE -> {
                if (++idle > w.chainGap()) chain = 0;
                if (buffered && (cooldown == 0 || !cooldownFromStrong)) {
                    buffered = false;
                    begin(out, true);
                }
            }
            case WINDUP -> windup(held, out);
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

    private void windup(boolean held, Out out) {
        t++;
        if (!held) let = true;
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
                out.cancel(seq);
                phase = Phase.IDLE;
                idle = 0;
                return;
            }
        }
        if (kind == SwingKind.LIGHT && !let && t == w.holdAt()) {
            kind = SwingKind.STRONG;
            combo = 0;
            out.upgrade(seq);
        }
        if (t < w.windup(kind)) return;
        out.land(seq, kind, combo);
        if (kind == SwingKind.LIGHT) {
            if (++chain >= w.chain()) startCooldown(false);
            phase = Phase.RECOVER;
        } else {
            startCooldown(true);
            phase = Phase.LUNGE;
        }
        t = 0;
    }

    private void startCooldown(boolean strong) {
        chain = 0;
        cooldown = cooldownTotal = w.cooldown();
        cooldownFromStrong = strong;
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

    /** The cooldown is a strong attack's (nothing can start in it). */
    public boolean cooldownFromStrong() { return cooldownFromStrong; }

    /** The wind-up under way is a strong attack made out of the cooldown (it needs the button held). */
    public boolean fromCooldown() { return fromCooldown; }

    public boolean busy() { return phase != Phase.IDLE; }
}
