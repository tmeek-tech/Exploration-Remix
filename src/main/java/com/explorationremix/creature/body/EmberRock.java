package com.explorationremix.creature.body;

/**
 * The rock round an ember urchin's body (0.23.5), read again only when it is needed: its region of blocks moves on a
 * block only every twenty ticks or so at its slow walk, and reading it is most of what a tick of its body costs, so
 * the patch is kept while the region stays where it was, and read afresh every {@link #FRESH} ticks all the same
 * (for blocks broken or set since).
 */
public final class EmberRock {
    /** Read afresh at least this often (ticks), the region moved or not. */
    static final int FRESH = 20;

    public final TerrainPatch patch = new TerrainPatch();
    private final int[] bounds = new int[6], last = new int[6];
    private int age = Integer.MAX_VALUE / 2;
    /** For checks: how many times it was read. */
    public int reads;

    /** The rock for {@code body}'s next tick, moved by {@code in}, read from {@code t} if it must be. */
    public TerrainPatch around(EmberBody body, EmberBody.Drive in, Terrain t) {
        body.bounds(in, bounds);
        boolean same = true;
        for (int i = 0; i < 6; i++) if (bounds[i] != last[i]) { same = false; break; }
        if (!same || ++age >= FRESH) {
            patch.build(t, bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
            System.arraycopy(bounds, 0, last, 0, 6);
            age = 0;
            reads++;
        }
        return patch;
    }

    /** Read afresh at the next tick (blocks changed, or the body was laid out somewhere new). */
    public void stale() { age = Integer.MAX_VALUE / 2; }
}
