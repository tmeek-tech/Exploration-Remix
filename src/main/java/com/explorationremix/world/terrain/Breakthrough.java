package com.explorationremix.world.terrain;

/**
 * Where the Wasteland and the Basalt Galleries open into each other (0.5.1, Taran: "No band, opens into them"): a 2D
 * noise, {@link #beta} (0 to 1 over a column), that both layers read, and the weight of the Wasteland's opening dip
 * before its guard ({@link #openingSite}). The Galleries find the openings' middles from that site function and open the
 * ones they keep a ramp for, a disc each, round which the Wasteland's lowest sheet goes down to the crater's floor; the
 * Wasteland's own dip is the same site times its guard, which the Galleries' cap keeps shut (so the dip never falls and
 * every opening is a crater's). Where beta is low the {@link Join}'s seal keeps the two apart.
 *
 * <p>An immutable function of the seed, built before both fields from the shared {@link WastelandSheets}, so that neither
 * field has to ask the other where they meet: Terrain builds the sheets, this, the tubes, the Galleries (which see this as
 * their {@link GalleriesField.Join}), the Wasteland and the {@link Join}.
 */
public final class Breakthrough implements GalleriesField.Join {
    /** The noise: 2 octaves at this scale (blocks), mapped linearly from BETA_LO (0) to BETA_HI (1) and clamped. */
    static final double BETA_SCALE = 75, BETA_LO = 0.08, BETA_HI = 0.38;
    /** How many rows over the Galleries' top their space may reach under an opening. */
    static final int REACH_UP = 6;

    private final Perlin betaN;
    private final int bandTop;
    /** The Wasteland's sheets, whose lowest one's junction rooms are the openings. */
    private final WastelandSheets sheets;

    /** The breakthrough of world seed {@code seed} over the layout {@code s} (its Galleries' top row) and the Wasteland's sheets. */
    public Breakthrough(long seed, Shape s, WastelandSheets sheets) {
        this.betaN = new Perlin(Hashing.mix(seed ^ 0x1F83D9ABFB41BD6BL, 1, 0, 0));
        this.bandTop = s.top(Terrain.GALLERIES) + REACH_UP;
        this.sheets = sheets;
    }

    /** The breakthrough noise over column (x, z), 0 to 1. */
    @Override
    public double beta(double x, double z) {
        double n = betaN.fbm2(x / BETA_SCALE, z / BETA_SCALE, 2);
        double b = (n - BETA_LO) / (BETA_HI - BETA_LO);
        return b < 0 ? 0 : (b > 1 ? 1 : b);
    }

    /** The weight of the Wasteland's opening dip at column (x, z) before its guard ({@link WastelandSheets#openingSite}). */
    @Override
    public double openingSite(double x, double z) { return sheets.openingSite(beta(x, z), x, z); }

    /**
     * A bound on the highest open row of the Galleries' space under an opening, their top + 6 (-683 by default), inside
     * the join's rows. Their space stops at T - 4 (a disc's at T - 5); a crater's open space over it is the Wasteland's.
     */
    @Override
    public int bandTop() { return bandTop; }
}
