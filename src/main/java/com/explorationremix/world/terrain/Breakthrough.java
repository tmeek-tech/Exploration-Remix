package com.explorationremix.world.terrain;

/**
 * Where the Wasteland and the Basalt Galleries open into each other (0.5.1, Taran: "No band, opens into them"): a 2D
 * noise, {@link #beta} (0 to 1 over a column), that both layers read. Where it is high the Wasteland's lowest tunnels
 * dip through their floor ({@link WastelandField}) and the Galleries raise their ceiling into the Wasteland's rows
 * ({@link #breakthrough}); where it is low the {@link Join}'s seal keeps the two apart.
 *
 * <p>An immutable function of the seed, built before both fields, so that neither field has to ask the other where they
 * meet: Terrain builds this first, then the tubes, the Galleries (which see it as their {@link GalleriesField.Join}),
 * the Wasteland and the {@link Join}.
 */
public final class Breakthrough implements GalleriesField.Join {
    /** The noise: 2 octaves at this scale (blocks), mapped linearly from BETA_LO (0) to BETA_HI (1) and clamped. */
    static final double BETA_SCALE = 75, BETA_LO = 0.08, BETA_HI = 0.38;
    /** The Galleries' ceiling starts to rise at this beta and reaches {@link #bandTop} OPEN_RAMP further on. */
    static final double OPEN_LO = 0.35, OPEN_RAMP = 0.25;
    /** How many rows over the Galleries' top their space may reach where the breakthrough is full. */
    static final int REACH_UP = 6;

    private final Perlin betaN;
    private final int bandTop;

    /** The breakthrough of world seed {@code seed} over the layout {@code s} (its Galleries' top row). */
    public Breakthrough(long seed, Shape s) {
        this(seed, s.top(Terrain.GALLERIES));
    }

    /** The breakthrough over a Galleries layer whose top row is {@code galleriesTop}. */
    Breakthrough(long seed, int galleriesTop) {
        this.betaN = new Perlin(Hashing.mix(seed ^ 0x1F83D9ABFB41BD6BL, 1, 0, 0));
        this.bandTop = galleriesTop + REACH_UP;
    }

    /** The breakthrough noise over column (x, z), 0 to 1. */
    public double beta(double x, double z) {
        double n = betaN.fbm2(x / BETA_SCALE, z / BETA_SCALE, 2);
        double b = (n - BETA_LO) / (BETA_HI - BETA_LO);
        return b < 0 ? 0 : (b > 1 ? 1 : b);
    }

    /** How far the Galleries' ceiling rises into the join over (x, z): 0 under beta 0.35, 1 from beta 0.6. */
    @Override
    public double breakthrough(double x, double z) { return Terrain.smooth((beta(x, z) - OPEN_LO) / OPEN_RAMP); }

    /** The highest open row of the Galleries where the breakthrough is full: their top + 6 (-683 by default). */
    @Override
    public int bandTop() { return bandTop; }
}
