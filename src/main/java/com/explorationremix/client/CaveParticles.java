package com.explorationremix.client;

import com.explorationremix.block.GlowBlock;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;

/** The cave particles' looks and motion. */
public final class CaveParticles {
    private CaveParticles() {}

    /**
     * A puff of volcanic smoke off the lava of the Galleries and the Wasteland, built like {@link Steam} but long-lived:
     * it rises 40 to 100 blocks over 30 to 60 seconds (the plumes through the openings about 170), slowing as it cools,
     * swells from two blocks or so across to seven to nine, thickens and thins away, and spreads under the rock it meets
     * rather than stopping dead ({@link SmokePuff} has the motion). It takes the lava's light from below
     * ({@link SmokePuff#lightFromBelow}), so it is never a black blot in mid-air.
     *
     * <p>The spawner passes the rise it should make as the x speed and the height over the lava it starts at as the y
     * speed (0, 0, 0 makes a plume of a random rise starting at the lava).
     */
    public static final class VolcanicSmoke extends TextureSheetParticle {
        private final SmokePuff puff;
        private final float spin;
        private final RockCache rock = new RockCache();

        VolcanicSmoke(ClientLevel level, double x, double y, double z, double reach, double above, SpriteSet sprites) {
            super(level, x, y, z);
            if (!(reach > 0)) reach = SmokePuff.REACH_MIN + (SmokePuff.REACH_MAX - SmokePuff.REACH_MIN) * random.nextDouble();
            this.puff = new SmokePuff(x, y, z, y - Math.max(0, above), reach, random::nextDouble);
            this.lifetime = puff.life;
            this.hasPhysics = false;   // the puff meets the rock itself (Particle's collision stops a puff dead under a vault)
            this.gravity = 0;
            this.friction = 1;
            this.xd = this.yd = this.zd = 0;
            this.quadSize = puff.size();
            float g = 0.5F + 0.12F * random.nextFloat();
            this.setColor(g * 1.03F, g, g * 0.94F);   // a warm grey; the lava's light below does the rest
            this.alpha = 0;
            this.roll = this.oRoll = random.nextFloat() * Mth.TWO_PI;
            this.spin = (0.0015F + 0.0025F * random.nextFloat()) * (random.nextBoolean() ? 1 : -1);
            this.pickSprite(sprites);
            SmokeBudget.add(level.getGameTime() + lifetime);
        }

        @Override
        public void tick() {
            this.xo = x;
            this.yo = y;
            this.zo = z;
            this.oRoll = roll;
            if (age++ >= lifetime) {
                remove();
                return;
            }
            puff.tick(this::rock, random::nextDouble);
            this.setPos(puff.x, puff.y, puff.z);
            this.roll += spin;
            this.quadSize = puff.size();
            this.alpha = puff.alpha();
        }

        /** Rock for the smoke: any block but air, the glow and fluids (a puff rises past a lava fall). */
        private boolean rock(double x, double y, double z) { return rock.at(level, x, y, z, age); }

        @Override
        public int getLightColor(float partialTick) {
            int own = super.getLightColor(partialTick);
            int below = SmokePuff.lightFromBelow(y - puff.base);
            return LightTexture.pack(Math.max(LightTexture.block(own), below), LightTexture.sky(own));
        }

        /** Never culled by its point-sized box: the quad is up to nine blocks across and would pop at the screen's edge. */
        @Override
        public boolean shouldCull() { return false; }

        @Override
        public ParticleRenderType getRenderType() { return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT; }

        public record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double dx, double dy, double dz) {
                return new VolcanicSmoke(level, x, y, z, dx, dy, sprites);
            }
        }
    }

    /**
     * A smoke puff's test for rock (0.9.0 eruption effects, round 2: the hottest of the mod's particle code in the last
     * profile, three block reads a tick for each of up to 1800 puffs): the last four cells asked and their answers, kept
     * for {@link #KEEP} ticks. A puff rises a tenth of a block a tick at most, so most of its reads are of the cells it
     * asked the tick before; a block that changes under it (a crater, the reset) is seen within two seconds.
     */
    static final class RockCache {
        static final int KEEP = 40;
        private final long[] cells = {Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
        private final boolean[] rock = new boolean[4];
        private final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        private int next, since;

        /** Rock at (x, y, z) at the particle's {@code age}: any block but air, the glow and fluids. */
        boolean at(ClientLevel level, double x, double y, double z, int age) {
            if (age - since >= KEEP) {
                Arrays.fill(cells, Long.MIN_VALUE);
                since = age;
            }
            int bx = Mth.floor(x), by = Mth.floor(y), bz = Mth.floor(z);
            long cell = BlockPos.asLong(bx, by, bz);
            for (int i = 0; i < 4; i++) if (cells[i] == cell) return rock[i];
            BlockState s = level.getBlockState(at.set(bx, by, bz));
            boolean hit = !GlowBlock.open(s) && s.getFluidState().isEmpty();
            cells[next] = cell;
            rock[next] = hit;
            next = (next + 1) & 3;
            return hit;
        }
    }

    /**
     * A small grey pebble of grit falling (0.9.0): the dust a quake shakes out of the rock, the trickle from a ceiling
     * block about to come down and the patter where it will land, a build's crumbs as it creaks. It takes the spawn's
     * velocity, falls as a falling block does (gravity 1, 2 % drag a tick), stops on what it lands on and is gone a second
     * after, or after up to 6 seconds in the air. Opaque, off the smoke's translucent sheet; lit by the world, but never
     * darker than block light 6, so it shows in the dark halls.
     */
    public static final class Grit extends TextureSheetParticle {
        /** The least block light it is drawn with. */
        static final int LIGHT_FLOOR = 6;
        private int grounded;

        Grit(ClientLevel level, double x, double y, double z, double dx, double dy, double dz, SpriteSet sprites) {
            super(level, x, y, z);
            this.xd = dx;
            this.yd = dy;
            this.zd = dz;
            this.gravity = 1.0F;
            this.friction = 0.98F;
            this.hasPhysics = true;
            this.lifetime = 60 + random.nextInt(61);
            this.quadSize = 0.06F + 0.05F * random.nextFloat();
            float g = 0.85F + 0.15F * random.nextFloat();
            this.setColor(g, g * 0.98F, g * 0.95F);
            this.roll = this.oRoll = random.nextFloat() * Mth.TWO_PI;
            this.pickSprite(sprites);
        }

        @Override
        public void tick() {
            super.tick();
            if (!removed && onGround && ++grounded > 20) remove();
        }

        @Override
        public int getLightColor(float partialTick) {
            int own = super.getLightColor(partialTick);
            return LightTexture.pack(Math.max(LightTexture.block(own), LIGHT_FLOOR), LightTexture.sky(own));
        }

        @Override
        public ParticleRenderType getRenderType() { return ParticleRenderType.PARTICLE_SHEET_OPAQUE; }

        public record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double dx, double dy, double dz) {
                return new Grit(level, x, y, z, dx, dy, dz, sprites);
            }
        }
    }

    /**
     * A gob of glowing lava (0.9.0): thrown up by an eruption's fountain, hopping in a vent's crater while it warns, off a
     * lava bomb in flight, and splashed up where something falls into the lava. It takes the spawn's velocity, flies
     * under {@link Fountain#SPATTER_GRAVITY} of the particles' gravity with {@link Fountain#SPATTER_FRICTION} drag, lands
     * on what it meets and stops there, shrinking and darkening as it cools, and is gone after 30 to 60 ticks. It never
     * fades its alpha and is always full-bright, so the shader and the cave fog do not drop it at a distance; drawn on
     * the opaque sheet (the vanilla lava's sprite), away from the long-lived smoke on the translucent one. Unlike the
     * game's own lava particle it leaves no smoke behind it each tick.
     *
     * <p>(eruption effects, round 2: the second hottest of the mod's particle code) It no longer takes the game's
     * collision, a box test every tick for each of hundreds: as the lava gob, it looks at the one block it moves into,
     * sinks into a fluid (a splash's spatter falls back into the lava and is gone), and stops on top of the block it
     * fell onto or under the one it flew into, where it cools.
     */
    public static final class Spatter extends TextureSheetParticle implements Sized {
        /** Once landed it keeps this share of its size and of its green and blue each tick. */
        static final float COOL_SIZE = 0.93F, COOL_COLOUR = 0.9F;
        private final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        private boolean stopped;

        Spatter(ClientLevel level, double x, double y, double z, double dx, double dy, double dz, SpriteSet sprites) {
            super(level, x, y, z);
            this.xd = dx;
            this.yd = dy;
            this.zd = dz;
            this.gravity = (float) Fountain.SPATTER_GRAVITY;
            this.friction = (float) Fountain.SPATTER_FRICTION;
            this.hasPhysics = false;
            this.lifetime = 30 + random.nextInt(31);
            this.quadSize = 0.07F + 0.09F * random.nextFloat();
            this.setColor(1.0F, 0.75F + 0.25F * random.nextFloat(), 0.55F + 0.3F * random.nextFloat());
            this.pickSprite(sprites);
        }

        /** Drawn {@code k} times its size (a far fountain's spatter keeps more of its size). */
        @Override
        public void grow(float k) { this.quadSize *= k; }

        @Override
        public void tick() {
            this.xo = x;
            this.yo = y;
            this.zo = z;
            if (age++ >= lifetime) {
                remove();
                return;
            }
            if (stopped) {
                // cooling where it landed or stuck: smaller and redder, never fainter
                this.quadSize *= COOL_SIZE;
                this.gCol *= COOL_COLOUR;
                this.bCol *= COOL_COLOUR * COOL_COLOUR;
                return;
            }
            this.yd -= 0.04 * gravity;
            double nx = x + xd, ny = y + yd, nz = z + zd;
            BlockState s = level.getBlockState(at.set(nx, ny, nz));
            if (!s.getFluidState().isEmpty()) {
                remove();
                return;
            }
            if (s.blocksMotion()) {
                stopped = true;
                int from = Mth.floor(y), into = Mth.floor(ny);
                if (into < from) setPos(x, into + 1 + 0.5 * quadSize, z);
                else if (into > from) setPos(x, into - 0.5 * quadSize, z);
                this.xd = this.yd = this.zd = 0;
                return;
            }
            setPos(nx, ny, nz);
            this.xd *= friction;
            this.yd *= friction;
            this.zd *= friction;
        }

        @Override
        public int getLightColor(float partialTick) { return 0xF000F0; }

        @Override
        public ParticleRenderType getRenderType() { return ParticleRenderType.PARTICLE_SHEET_OPAQUE; }

        public record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double dx, double dy, double dz) {
                return new Spatter(level, x, y, z, dx, dy, dz, sprites);
            }
        }
    }

    /** A particle an eruption's or a bomb's spawner may draw larger than it was made: a far one keeps more of its size. */
    interface Sized {
        void grow(float k);
    }

    /**
     * A clot of glowing lava half a block to a block across (0.9.0 pack look fixes): the body of an eruption's fountain,
     * thrown up off the crater floor in a dense jet that climbs 10 to 25 blocks and rains back down round the stack, the
     * pops of a vent about to erupt, a bomb's burst and its trail seen from afar. Much bigger than the spatter and drawn
     * with its own lumpy sprites (yellow-white heart, orange, a dark red rim), full-bright and never faded, so it reads
     * as lava through haze. It flies under the spatter's physics ({@link Fountain#SPATTER_GRAVITY}, drag
     * {@link Fountain#SPATTER_FRICTION}) and lives until it has fallen {@link Fountain#GOB_DROP} blocks below where it
     * left ({@link Fountain#flightTicks}), cooling from yellow to orange as it flies. It does not take the game's
     * collision (a box test every tick, for thousands of gobs): it looks at the one block it moves into, sinks into a
     * fluid, and stops on rock, on top of what it fell onto or under what it flew into, where it shrinks and reddens for
     * a moment.
     */
    public static final class LavaGob extends TextureSheetParticle implements Sized {
        /** Once stopped it keeps this share of its size and of its green and blue each tick, and is gone after {@link #LINGER} ticks. */
        static final float COOL_SIZE = 0.92F, COOL_COLOUR = 0.88F;
        static final int LINGER = 16;
        /** It shrinks away over its last this many ticks if its life ends in the air. */
        static final int FADE = 8;
        private final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        private float size0;
        private boolean stopped;
        private int stoppedFor;

        LavaGob(ClientLevel level, double x, double y, double z, double dx, double dy, double dz, SpriteSet sprites) {
            super(level, x, y, z);
            this.xd = dx;
            this.yd = dy;
            this.zd = dz;
            this.gravity = (float) Fountain.SPATTER_GRAVITY;
            this.friction = (float) Fountain.SPATTER_FRICTION;
            this.hasPhysics = false;
            this.lifetime = Fountain.flightTicks(dy, Fountain.GOB_DROP) + random.nextInt(8);
            this.quadSize = this.size0 = 0.22F + 0.26F * random.nextFloat();
            this.setColor(1.0F, 0.9F + 0.1F * random.nextFloat(), 0.7F + 0.3F * random.nextFloat());
            this.roll = this.oRoll = random.nextFloat() * Mth.TWO_PI;
            this.pickSprite(sprites);
        }

        @Override
        public void grow(float k) {
            this.quadSize *= k;
            this.size0 *= k;
        }

        /** Lives {@code ticks} at most (a bomb's trail: a short streak). */
        void life(int ticks) { this.lifetime = Math.min(lifetime, ticks); }

        @Override
        public void tick() {
            this.xo = x;
            this.yo = y;
            this.zo = z;
            this.oRoll = roll;
            if (age++ >= lifetime) {
                remove();
                return;
            }
            if (stopped) {
                // cooling where it struck: smaller and redder, never fainter
                this.quadSize *= COOL_SIZE;
                this.gCol *= COOL_COLOUR;
                this.bCol *= COOL_COLOUR * COOL_COLOUR;
                if (++stoppedFor > LINGER) remove();
                return;
            }
            this.yd -= 0.04 * gravity;
            double nx = x + xd, ny = y + yd, nz = z + zd;
            BlockState s = level.getBlockState(at.set(nx, ny, nz));
            if (!s.getFluidState().isEmpty()) {
                // into the lava (or water): gone under
                remove();
                return;
            }
            if (s.blocksMotion()) {
                stopped = true;
                int from = Mth.floor(y), into = Mth.floor(ny);
                // on top of the block it fell onto, under the one it flew up into; against a wall it stays where it was
                if (into < from) setPos(x, into + 1 + 0.5 * quadSize, z);
                else if (into > from) setPos(x, into - 0.5 * quadSize, z);
                this.xd = this.yd = this.zd = 0;
                return;
            }
            setPos(nx, ny, nz);
            this.xd *= friction;
            this.yd *= friction;
            this.zd *= friction;
            // cooling as it flies: from yellow-white to deep orange over its life
            float u = (float) age / lifetime;
            this.gCol = Math.max(0.5F, this.gCol - 0.006F);
            this.bCol = Math.max(0.2F, this.bCol - 0.012F);
            int left = lifetime - age;
            if (left < FADE) this.quadSize = size0 * left / FADE;
            else if (u < 0.1F) this.quadSize = size0 * (0.6F + 4 * u);
        }

        @Override
        public int getLightColor(float partialTick) { return 0xF000F0; }

        @Override
        public ParticleRenderType getRenderType() { return ParticleRenderType.PARTICLE_SHEET_OPAQUE; }

        public record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double dx, double dy, double dz) {
                return new LavaGob(level, x, y, z, dx, dy, dz, sprites);
            }
        }
    }

    /**
     * A ball of fire (0.9.0 pack look fixes): the flash of flame and lava at a vent's mouth as its burst starts, the fire
     * roaring at the mouth while the fountain throws, a lava pop's flare, a bomb's burst. One to two and a half blocks
     * across, full-bright and see-through, it swells to nearly twice that, reddens and is gone within a second, drifting
     * the way it was thrown and slowing.
     */
    public static final class Fireball extends TextureSheetParticle implements Sized {
        private float size0;

        Fireball(ClientLevel level, double x, double y, double z, double dx, double dy, double dz, SpriteSet sprites) {
            super(level, x, y, z);
            this.xd = dx;
            this.yd = dy;
            this.zd = dz;
            this.gravity = 0;
            this.friction = 0.9F;
            this.hasPhysics = false;
            this.lifetime = 10 + random.nextInt(12);
            this.quadSize = this.size0 = 0.6F + 0.6F * random.nextFloat();
            this.alpha = 0.95F;
            this.roll = this.oRoll = random.nextFloat() * Mth.TWO_PI;
            this.pickSprite(sprites);
        }

        @Override
        public void grow(float k) {
            this.quadSize *= k;
            this.size0 *= k;
        }

        @Override
        public void tick() {
            super.tick();
            if (removed) return;
            float u = (float) age / lifetime;
            this.quadSize = size0 * (1 + 0.9F * (float) Math.sqrt(u));
            this.alpha = 0.95F * (1 - u * u);
            this.gCol = 1 - 0.5F * u;
            this.bCol = 1 - 0.8F * u;
        }

        @Override
        public int getLightColor(float partialTick) { return 0xF000F0; }

        /** Never culled by its point-sized box: the quad is up to four blocks across. */
        @Override
        public boolean shouldCull() { return false; }

        @Override
        public ParticleRenderType getRenderType() { return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT; }

        public record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double dx, double dy, double dz) {
                return new Fireball(level, x, y, z, dx, dy, dz, sprites);
            }
        }
    }

    /**
     * A puff of an eruption's ash (0.9.0 pack look fixes): the plume, a thick column from the vent's mouth to the vault,
     * spreading under it, and the warning's smoke billowing from the mouth. Never the Caldera's own pale beige volcanic
     * smoke: a charcoal brown, nearly opaque, more than half as big again, swelling quickly, and thrown out of the mouth
     * ({@link SmokePuff#launch}) so it climbs fast before it slows to the smoke's own rise. Moves as a {@link SmokePuff}
     * (spreading under the rock it meets) and counts in the client's smoke ({@link SmokeBudget}).
     *
     * <p>(eruption effects, round 2) A near-black column showed only against lit rock: from the shore and across the
     * Caldera it was a black gap in a dark vault. Now its sprite's lobes are light at their edges and dark at their
     * hearts ({@code ash_puff}), tinted a dark grey brown at birth that lightens as it ages ({@link #LIFT}), so the
     * column's core is dark and its tops and the edges it spreads to are paler; its foot takes the fountain's orange
     * glow from below, fading to charcoal over {@link #HOT} blocks, as strongly as the spawner says the vent glows (the
     * fountain's strength while it throws, the crater's lava while it warns, dying with the plume's tail). It is turned
     * a quarter at a time, never freely, so its pixels stay square to the view.
     *
     * <p>The spawner passes the slow rise after the thrust as the x speed, the thrust as the y speed and the vent's glow
     * (0 to 1) as the z speed.
     */
    public static final class EruptionAsh extends TextureSheetParticle implements Sized {
        /** Its size against a volcanic smoke puff's, and its opacity at its thickest. */
        static final float SIZE = 1.6F, PEAK = 0.92F;
        /** Its foot glows over this many blocks above the mouth, at most this much of the fire's colour (times the vent's glow). */
        static final double HOT = 22, GLOW = 0.85;
        /** The fire's colour its foot turns to. */
        static final float FIRE_R = 1.0F, FIRE_G = 0.5F, FIRE_B = 0.17F;
        /** Its grey at birth (the column's dark core), and how much lighter it grows by its end (the tops and edges it spreads to). */
        static final float GREY = 0.34F, GREY_SPREAD = 0.08F, LIFT = 0.6F;
        private final SmokePuff puff;
        private final float r0, g0, b0, glow;
        private final RockCache rock = new RockCache();
        /** Drawn this many times larger, for a far viewer. */
        private float scale = 1F;

        EruptionAsh(ClientLevel level, double x, double y, double z, double reach, double thrust, double glow, SpriteSet sprites) {
            super(level, x, y, z);
            if (!(reach > 0)) reach = SmokePuff.REACH_MIN;
            this.puff = new SmokePuff(x, y, z, y, reach, random::nextDouble).launch(Math.max(0, thrust));
            this.lifetime = puff.life;
            this.hasPhysics = false;
            this.gravity = 0;
            this.friction = 1;
            this.xd = this.yd = this.zd = 0;
            this.quadSize = size();
            // a dark grey brown, lighter as it spreads (the sprite's light edges and dark hearts make the billows)
            float g = GREY + GREY_SPREAD * random.nextFloat();
            this.r0 = g * 1.08F;
            this.g0 = g * 0.95F;
            this.b0 = g * 0.84F;
            this.glow = (float) Mth.clamp(glow, 0, 1);
            colour();
            this.alpha = 0;
            // turned a quarter at a time, so its pixels stay square to the view (turned freely, they showed as diamonds)
            this.roll = this.oRoll = random.nextInt(4) * Mth.HALF_PI;
            this.pickSprite(sprites);
            SmokeBudget.add(level.getGameTime() + lifetime);
        }

        /**
         * Dark grey brown, lightening as it ages (the old puffs are the column's tops and the edges it spreads to), turning
         * to the fire's orange low over the mouth as strongly as the vent glows.
         */
        private void colour() {
            double lift = 1 + LIFT * Math.min(1.0, puff.age / (double) puff.life);
            double k = glow * GLOW * Math.pow(Mth.clamp(1 - (puff.y - puff.base) / HOT, 0, 1), 0.9);
            this.rCol = (float) (r0 * lift + (FIRE_R - r0 * lift) * k);
            this.gCol = (float) (g0 * lift + (FIRE_G - g0 * lift) * k);
            this.bCol = (float) (b0 * lift + (FIRE_B - b0 * lift) * k);
        }

        @Override
        public void tick() {
            this.xo = x;
            this.yo = y;
            this.zo = z;
            this.oRoll = roll;
            if (age++ >= lifetime) {
                remove();
                return;
            }
            puff.tick(this::rock, random::nextDouble);
            this.setPos(puff.x, puff.y, puff.z);
            this.quadSize = size();
            this.alpha = puff.alpha() / puff.peak * PEAK;
            colour();
        }

        /** Its half-width: from its puff's birth size to its full size, most of the way within the first fifth of its life (a billow). */
        private float size() {
            double u = Math.min(1.0, puff.age / (double) puff.life);
            return (float) (SIZE * scale * (puff.size0 + (puff.size1 - puff.size0) * (1 - Math.pow(1 - u, 5))));
        }

        @Override
        public void grow(float k) {
            this.scale *= k;
            this.quadSize = size();
        }

        private boolean rock(double x, double y, double z) { return rock.at(level, x, y, z, age); }

        @Override
        public int getLightColor(float partialTick) {
            int own = super.getLightColor(partialTick);
            int below = SmokePuff.lightFromBelow(y - puff.base);
            return LightTexture.pack(Math.max(LightTexture.block(own), below), LightTexture.sky(own));
        }

        @Override
        public boolean shouldCull() { return false; }

        @Override
        public ParticleRenderType getRenderType() { return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT; }

        public record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double dx, double dy, double dz) {
                return new EruptionAsh(level, x, y, z, dx, dy, dz, sprites);
            }
        }
    }

    /**
     * A puff of a cloud of dust (0.9.0 pack look fixes): the dark cloud of ash and rock dust a lava bomb's blast throws up
     * ({@link Billow#BLAST_ASH}), and the pale dust where a block of the ceiling crashes down ({@link Billow#ROCK_DUST}).
     * It is thrown out with the spawn's speed and slowed by the drag within a second, then hangs, rising or settling
     * slowly, swells, and thins away (the kind's numbers, {@link Billow}). Drawn with a soft, mottled sprite
     * ({@code soft_puff}, eruption effects round 2: the smoke's hard-stepped puff, blown up, showed square facets), and
     * many small puffs make one cloud. It takes the game's collision (a few dozen at a time), and never draws darker
     * than its kind's light floor.
     */
    public static final class Dust extends TextureSheetParticle implements Sized {
        private final Billow.Kind kind;
        private float size0;

        Dust(ClientLevel level, double x, double y, double z, double dx, double dy, double dz, SpriteSet sprites, Billow.Kind kind) {
            super(level, x, y, z);
            this.kind = kind;
            this.xd = dx;
            this.yd = dy;
            this.zd = dz;
            this.gravity = kind.gravity();
            this.friction = (float) kind.drag();
            this.hasPhysics = true;
            this.lifetime = kind.life0() + random.nextInt(kind.life1() - kind.life0() + 1);
            this.quadSize = this.size0 = kind.size0() + (kind.size1() - kind.size0()) * random.nextFloat();
            float g = kind.grey() * (0.88F + 0.24F * random.nextFloat());
            this.setColor(g * 1.02F, g, g * 0.96F);
            this.alpha = 0;
            // a quarter turn at a time: its pixels square to the view
            this.roll = this.oRoll = random.nextInt(4) * Mth.HALF_PI;
            this.pickSprite(sprites);
        }

        @Override
        public void grow(float k) {
            this.quadSize *= k;
            this.size0 *= k;
        }

        @Override
        public void tick() {
            super.tick();
            if (removed) return;
            this.quadSize = Billow.size(kind, size0, (double) age / lifetime);
            this.alpha = Billow.alpha(kind, age, lifetime);
        }

        @Override
        public int getLightColor(float partialTick) {
            int own = super.getLightColor(partialTick);
            return LightTexture.pack(Math.max(LightTexture.block(own), kind.light()), LightTexture.sky(own));
        }

        @Override
        public boolean shouldCull() { return false; }

        @Override
        public ParticleRenderType getRenderType() { return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT; }

        /** A bomb's blast: charcoal ash and burnt rock. */
        public record BlastProvider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double dx, double dy, double dz) {
                return new Dust(level, x, y, z, dx, dy, dz, sprites, Billow.BLAST_ASH);
            }
        }

        /** A ceiling block's crash: pale grey rock dust, lit so it shows in the dark halls. */
        public record RockProvider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double dx, double dy, double dz) {
                return new Dust(level, x, y, z, dx, dy, dz, sprites, Billow.ROCK_DUST);
            }
        }
    }

    /**
     * A flash (0.9.0 eruption effects, round 2): one big full-bright billboard of fire's light. The blaze of a lava
     * bomb's blast, in place of the game's white explosion puffs, and of an eruption's first ticks, larger than the
     * fireballs at the mouth, each gone within two or three ticks; and the glow building at a vent's mouth while it
     * warns, deep orange and steady (a new one every few ticks, each living a little longer than that).
     *
     * <p>The spawner passes its half-width as the x speed, its life in ticks as the y speed, and, for a vent's glow,
     * its strength (above 0, up to 1) as the z speed; 0 there makes a blast's flash.
     */
    public static final class Flash extends TextureSheetParticle implements Sized {
        private final float size0, strength;
        /** Drawn this many times larger, for a far viewer. */
        private float scale = 1F;

        Flash(ClientLevel level, double x, double y, double z, double half, double life, double glow, SpriteSet sprites) {
            super(level, x, y, z);
            this.hasPhysics = false;
            this.gravity = 0;
            this.xd = this.yd = this.zd = 0;
            this.lifetime = Math.max(1, (int) Math.round(life));
            this.quadSize = this.size0 = (float) (half > 0 ? half : 1.5);
            this.strength = (float) Mth.clamp(glow, 0, 1);
            if (strength > 0) this.setColor(1.0F, 0.42F + 0.2F * strength, 0.12F + 0.1F * strength);
            else this.setColor(1.0F, 0.97F, 0.88F);
            this.alpha = alphaAt(0);
            this.roll = this.oRoll = random.nextFloat() * Mth.TWO_PI;
            this.pickSprite(sprites);
        }

        /** A blast's flash: bright at once, thinning over its life; a glow: up and down again over its life, never full. */
        private float alphaAt(int age) {
            float u = (float) age / lifetime;
            if (strength > 0) return (0.35F + 0.6F * strength) * Math.min(1, 2.5F * Math.min(u + 0.2F, 1 - u));
            return 1 - 0.6F * u * u;
        }

        @Override
        public void grow(float k) {
            this.scale *= k;
            this.quadSize = size0 * scale;
        }

        @Override
        public void tick() {
            this.xo = x;
            this.yo = y;
            this.zo = z;
            if (age++ >= lifetime) {
                remove();
                return;
            }
            this.alpha = alphaAt(age);
            // a blast's flash swells a little and reddens as it goes
            if (strength == 0) {
                float u = (float) age / lifetime;
                this.quadSize = size0 * scale * (1 + 0.15F * u);
                this.bCol = 0.88F - 0.5F * u;
                this.gCol = 0.97F - 0.3F * u;
            }
        }

        @Override
        public int getLightColor(float partialTick) { return 0xF000F0; }

        @Override
        public boolean shouldCull() { return false; }

        @Override
        public ParticleRenderType getRenderType() { return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT; }

        public record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double dx, double dy, double dz) {
                return new Flash(level, x, y, z, dx, dy, dz, sprites);
            }
        }
    }

    /**
     * A chunk of broken rock (0.9.0 pack look fixes; eruption effects round 2): 0.35 to 0.6 blocks across, thrown
     * out where a ceiling block crashes down or a lava bomb blasts the ground. The block's own texture shrunk to a patch
     * read as a flat tile and vanished on the same rock, so a chunk has its own sprite ({@code rubble}: an angular
     * outline, a pale lit facet, a shadowed face, a dark rim) tinted with the colour of the struck block's texture,
     * lightened as fresh broken stone ({@link #tint}). It flies under the game's gravity with its own one-block test (no
     * box test each tick), bounces once off the floor it strikes, comes to rest there and lies {@link #LIE_MIN} to
     * {@link #LIE_MAX} ticks before it crumbles away over {@link #CRUMBLE}, so the floor round a landing or a crater
     * reads as rubble well after its dust has cleared. Sinks into a fluid; never darker than block light
     * {@link #LIGHT_FLOOR}.
     */
    public static final class Rubble extends TextureSheetParticle {
        static final int LIE_MIN = 260, LIE_MAX = 380, CRUMBLE = 20, LIGHT_FLOOR = 9;
        /** Its half-width (the chunk in its sprite is three quarters of it): a chunk 0.35 to 0.6 blocks across. */
        static final float SIZE_MIN = 0.24F, SIZE_MAX = 0.4F;
        /** It bounces once if it strikes the floor faster than this, blocks a tick, keeping this share of its speed up and this share across. */
        static final double BOUNCE_FROM = 0.12, BOUNCE_UP = 0.35, BOUNCE_ACROSS = 0.55;
        /** The struck rock's colours, from its texture, by sprite (a reload makes new sprites). */
        private static final Map<TextureAtlasSprite, float[]> COLOURS = new WeakHashMap<>();
        private static final float[] GREY = {0.55F, 0.55F, 0.57F};
        private final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        private final int lie;
        private final float size0, spin;
        private boolean bounced, resting;
        private int lain;

        Rubble(ClientLevel level, double x, double y, double z, double dx, double dy, double dz, SpriteSet sprites) {
            super(level, x, y, z);
            this.xd = dx;
            this.yd = dy;
            this.zd = dz;
            this.gravity = 1.0F;
            this.friction = 0.98F;
            this.hasPhysics = false;
            this.quadSize = this.size0 = SIZE_MIN + (SIZE_MAX - SIZE_MIN) * random.nextFloat();
            this.lie = LIE_MIN + random.nextInt(LIE_MAX - LIE_MIN + 1);
            this.lifetime = LIE_MAX + CRUMBLE + 200;
            this.roll = this.oRoll = random.nextFloat() * Mth.TWO_PI;
            this.spin = (0.15F + 0.25F * random.nextFloat()) * (random.nextBoolean() ? 1 : -1);
            this.setColor(GREY[0], GREY[1], GREY[2]);
            this.pickSprite(sprites);
        }

        /** Tints it as the rock {@code state}: its texture's mean colour, lightened as a fresh broken face. */
        void tint(BlockState state) {
            float[] c = colourOf(state);
            this.setColor(fresh(c[0]), fresh(c[1]), fresh(c[2]));
        }

        private static float fresh(float c) { return Math.min(1F, 0.18F + 1.35F * c); }

        /** The mean colour of {@code state}'s particle texture (its opaque pixels, the first frame), or a stone grey. */
        static float[] colourOf(BlockState state) {
            TextureAtlasSprite sprite = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper().getParticleIcon(state);
            return COLOURS.computeIfAbsent(sprite, Rubble::mean);
        }

        private static float[] mean(TextureAtlasSprite sprite) {
            try {
                NativeImage img = sprite.contents().getOriginalImage();
                int w = Math.min(sprite.contents().width(), img.getWidth()), h = Math.min(sprite.contents().height(), img.getHeight());
                long r = 0, g = 0, b = 0, n = 0;
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        // ABGR, as the image holds it
                        int c = img.getPixelRGBA(x, y);
                        if ((c >>> 24) < 128) continue;
                        r += c & 255;
                        g += c >> 8 & 255;
                        b += c >> 16 & 255;
                        n++;
                    }
                }
                return n == 0 ? GREY : new float[]{r / (255F * n), g / (255F * n), b / (255F * n)};
            } catch (RuntimeException e) {
                return GREY;
            }
        }

        @Override
        public void tick() {
            this.xo = x;
            this.yo = y;
            this.zo = z;
            this.oRoll = roll;
            if (age++ >= lifetime) {
                remove();
                return;
            }
            if (resting) {
                if (++lain > lie) {
                    this.quadSize = size0 * Math.max(0, 1 - (lain - lie) / (float) CRUMBLE);
                    if (lain > lie + CRUMBLE) remove();
                }
                return;
            }
            this.yd -= 0.04 * gravity;
            // the chunk's foot, which meets the floor
            double foot = 0.7 * quadSize;
            double nx = x + xd, ny = y + yd, nz = z + zd;
            BlockState s = level.getBlockState(at.set(nx, ny - foot, nz));
            if (!s.getFluidState().isEmpty()) {
                remove();
                return;
            }
            if (s.blocksMotion()) {
                int from = Mth.floor(y - foot), into = Mth.floor(ny - foot);
                if (into < from) {
                    // onto the block under it: once off it if it struck hard, then at rest on it
                    setPos(nx, into + 1 + foot, nz);
                    if (!bounced && yd < -BOUNCE_FROM) {
                        bounced = true;
                        this.yd = -yd * BOUNCE_UP;
                        this.xd *= BOUNCE_ACROSS;
                        this.zd *= BOUNCE_ACROSS;
                    } else {
                        resting = true;
                        this.xd = this.yd = this.zd = 0;
                    }
                } else if (into == from && level.getBlockState(at.set(x, y - foot, z)).blocksMotion()) {
                    // thrown out from inside the rock it broke from: up onto it
                    setPos(x, from + 1 + foot, z);
                } else {
                    // a wall or the rock overhead: it loses its way across, or up, and falls on
                    this.xd *= -0.2;
                    this.zd *= -0.2;
                    if (into > from) this.yd = 0;
                }
                return;
            }
            setPos(nx, ny, nz);
            this.xd *= friction;
            this.yd *= friction;
            this.zd *= friction;
            this.roll += spin;
        }

        @Override
        public int getLightColor(float partialTick) {
            int own = super.getLightColor(partialTick);
            return LightTexture.pack(Math.max(LightTexture.block(own), LIGHT_FLOOR), LightTexture.sky(own));
        }

        @Override
        public ParticleRenderType getRenderType() { return ParticleRenderType.PARTICLE_SHEET_OPAQUE; }

        /** A chunk of stone grey; the client's effects tint it as the rock they break ({@link #tint}). */
        public record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double dx, double dy, double dz) {
                return new Rubble(level, x, y, z, dx, dy, dz, sprites);
            }
        }
    }

    /** A soft puff of steam that rises off a hot pool, swells and fades. */
    public static final class Steam extends TextureSheetParticle {
        private final SpriteSet sprites;

        Steam(ClientLevel level, double x, double y, double z, SpriteSet sprites) {
            super(level, x, y, z);
            this.sprites = sprites;
            this.lifetime = 50 + random.nextInt(50);
            this.gravity = -0.012F;
            this.friction = 0.96F;
            this.xd = (random.nextDouble() - 0.5) * 0.012;
            this.yd = 0.025 + random.nextDouble() * 0.02;
            this.zd = (random.nextDouble() - 0.5) * 0.012;
            this.quadSize = 0.35F + random.nextFloat() * 0.35F;
            float g = 0.9F + random.nextFloat() * 0.08F;
            this.setColor(g, g, g);
            this.alpha = 0;
            this.setSpriteFromAge(sprites);
        }

        @Override
        public void tick() {
            super.tick();
            if (removed) return;
            float t = (float) age / lifetime;
            this.alpha = 0.32F * Mth.clamp(t * 5, 0, 1) * (1 - t);
            this.quadSize *= 1.012F;
            this.setSpriteFromAge(sprites);
        }

        @Override
        public ParticleRenderType getRenderType() { return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT; }

        public record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double dx, double dy, double dz) {
                return new Steam(level, x, y, z, sprites);
            }
        }
    }

    /**
     * A spark from the lava that drifts up, flickers and goes out. Glows in the dark. (eruption effects, round 2: the
     * hottest of the mod's particle ticks in a fountain) It wanders on a slow wobble of its own rather than four random
     * draws a tick, flickers by its age, and stops dead where it meets rock with a one-block test instead of the game's
     * box test.
     */
    public static final class Ember extends TextureSheetParticle {
        private final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        private final float wobble, flicker;

        Ember(ClientLevel level, double x, double y, double z, SpriteSet sprites) {
            super(level, x, y, z);
            this.lifetime = 40 + random.nextInt(60);
            this.gravity = -0.004F;
            this.friction = 0.97F;
            this.hasPhysics = false;
            this.xd = (random.nextDouble() - 0.5) * 0.03;
            this.yd = 0.03 + random.nextDouble() * 0.05;
            this.zd = (random.nextDouble() - 0.5) * 0.03;
            this.quadSize = 0.04F + random.nextFloat() * 0.05F;
            this.setColor(1.0F, 0.55F + random.nextFloat() * 0.25F, 0.15F);
            this.wobble = random.nextFloat() * Mth.TWO_PI;
            this.flicker = random.nextFloat() * 64;
            this.pickSprite(sprites);
        }

        @Override
        public void tick() {
            this.xo = x;
            this.yo = y;
            this.zo = z;
            if (age++ >= lifetime) {
                remove();
                return;
            }
            this.yd -= 0.04 * gravity;
            float w = age * 0.4F + wobble;
            this.xd += 0.0017 * Mth.sin(w);
            this.zd += 0.0017 * Mth.cos(1.3F * w);
            double nx = x + xd, ny = y + yd, nz = z + zd;
            if (level.getBlockState(at.set(nx, ny, nz)).blocksMotion()) {
                this.xd = this.yd = this.zd = 0;
            } else {
                setPos(nx, ny, nz);
            }
            this.xd *= friction;
            this.yd *= friction;
            this.zd *= friction;
            float t = (float) age / lifetime;
            this.alpha = (1 - t * t) * (0.75F + 0.25F * Mth.frac(flicker + age * 0.618F));
            this.gCol = Math.max(0.2F, this.gCol - 0.006F);
        }

        @Override
        public int getLightColor(float partialTick) { return 0xF000F0; }

        @Override
        public ParticleRenderType getRenderType() { return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT; }

        public record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double dx, double dy, double dz) {
                return new Ember(level, x, y, z, sprites);
            }
        }
    }

    /** A mote of pollen hanging in a shaft of daylight, drifting slowly and glinting. */
    public static final class Pollen extends TextureSheetParticle {
        Pollen(ClientLevel level, double x, double y, double z, SpriteSet sprites) {
            super(level, x, y, z);
            this.lifetime = 120 + random.nextInt(120);
            this.gravity = 0.0015F;
            this.friction = 0.98F;
            this.xd = (random.nextDouble() - 0.5) * 0.01;
            this.yd = -0.002;
            this.zd = (random.nextDouble() - 0.5) * 0.01;
            this.quadSize = 0.025F + random.nextFloat() * 0.025F;
            this.setColor(1.0F, 0.9F, 0.55F);
            this.alpha = 0;
            this.pickSprite(sprites);
        }

        @Override
        public void tick() {
            super.tick();
            if (removed) return;
            this.xd += (random.nextDouble() - 0.5) * 0.002;
            this.zd += (random.nextDouble() - 0.5) * 0.002;
            float t = (float) age / lifetime;
            this.alpha = 0.85F * Mth.clamp(t * 6, 0, 1) * Mth.clamp((1 - t) * 4, 0, 1);
        }

        @Override
        public int getLightColor(float partialTick) { return 0xF000F0; }

        @Override
        public ParticleRenderType getRenderType() { return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT; }

        public record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double dx, double dy, double dz) {
                return new Pollen(level, x, y, z, sprites);
            }
        }
    }
}
