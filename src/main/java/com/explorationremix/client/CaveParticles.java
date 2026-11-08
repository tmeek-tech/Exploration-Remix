package com.explorationremix.client;

import com.explorationremix.block.GlowBlock;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

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
        private final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();

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
        private boolean rock(double x, double y, double z) {
            BlockState s = level.getBlockState(at.set(x, y, z));
            return !GlowBlock.open(s) && s.getFluidState().isEmpty();
        }

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
     */
    public static final class Spatter extends TextureSheetParticle implements Sized {
        /** Once landed it keeps this share of its size and of its green and blue each tick. */
        static final float COOL_SIZE = 0.93F, COOL_COLOUR = 0.9F;

        Spatter(ClientLevel level, double x, double y, double z, double dx, double dy, double dz, SpriteSet sprites) {
            super(level, x, y, z);
            this.xd = dx;
            this.yd = dy;
            this.zd = dz;
            this.gravity = (float) Fountain.SPATTER_GRAVITY;
            this.friction = (float) Fountain.SPATTER_FRICTION;
            this.hasPhysics = true;
            this.lifetime = 30 + random.nextInt(31);
            this.quadSize = 0.07F + 0.09F * random.nextFloat();
            this.setColor(1.0F, 0.75F + 0.25F * random.nextFloat(), 0.55F + 0.3F * random.nextFloat());
            this.pickSprite(sprites);
        }

        /** Drawn {@code k} times its size (a far fountain's spatter keeps more of its size); its box for landing stays the same. */
        @Override
        public void grow(float k) { this.quadSize *= k; }

        @Override
        public void tick() {
            super.tick();
            // the game's particle stops dead for good once something stops it up or down, and only one stopped by the
            // ground is onGround: a gob thrown into the rock overhead stays stuck there, so it cools there too
            boolean stuck = x == xo && y == yo && z == zo;
            if (removed || !onGround && !stuck) return;
            // cooling where it landed: smaller and redder, never fainter
            this.quadSize *= COOL_SIZE;
            this.gCol *= COOL_COLOUR;
            this.bCol *= COOL_COLOUR * COOL_COLOUR;
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
     * A puff of an eruption's dark ash (0.9.0 pack look fixes): the plume, a thick charcoal column from the vent's mouth to
     * the vault, spreading under it, and the warning's smoke billowing from the mouth. Never the Caldera's own beige
     * volcanic smoke: near black (darker than the basalt behind it), nearly opaque, more than half as big again, swelling
     * quickly, and thrown out of the mouth ({@link SmokePuff#launch}) so it climbs fast before it slows to the smoke's own
     * rise; its foot glows orange where the fountain lights it. Moves as a {@link SmokePuff} (spreading under the rock it
     * meets) and counts in the client's smoke ({@link SmokeBudget}).
     *
     * <p>The spawner passes the slow rise after the thrust as the x speed and the thrust as the y speed.
     */
    public static final class EruptionAsh extends TextureSheetParticle implements Sized {
        /** Its size against a volcanic smoke puff's, and its opacity at its thickest. */
        static final float SIZE = 1.6F, PEAK = 0.96F;
        /** Its foot glows over this many blocks above the mouth, at most this much of the fire's colour. */
        static final double HOT = 14, GLOW = 0.75;
        private final SmokePuff puff;
        private final float spin, r0, g0, b0;
        private final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        /** Drawn this many times larger, for a far viewer. */
        private float scale = 1F;

        EruptionAsh(ClientLevel level, double x, double y, double z, double reach, double thrust, SpriteSet sprites) {
            super(level, x, y, z);
            if (!(reach > 0)) reach = SmokePuff.REACH_MIN;
            this.puff = new SmokePuff(x, y, z, y, reach, random::nextDouble).launch(Math.max(0, thrust));
            this.lifetime = puff.life;
            this.hasPhysics = false;
            this.gravity = 0;
            this.friction = 1;
            this.xd = this.yd = this.zd = 0;
            this.quadSize = size();
            float g = 0.035F + 0.035F * random.nextFloat();
            this.r0 = g * 1.08F;
            this.g0 = g;
            this.b0 = g * 0.94F;
            colour();
            this.alpha = 0;
            this.roll = this.oRoll = random.nextFloat() * Mth.TWO_PI;
            this.spin = (0.002F + 0.003F * random.nextFloat()) * (random.nextBoolean() ? 1 : -1);
            this.pickSprite(sprites);
            SmokeBudget.add(level.getGameTime() + lifetime);
        }

        /** Charcoal, turning to the fire's orange low over the mouth. */
        private void colour() {
            double k = GLOW * Math.pow(Mth.clamp(1 - (puff.y - puff.base) / HOT, 0, 1), 1.5);
            this.rCol = (float) (r0 + (0.95 - r0) * k);
            this.gCol = (float) (g0 + (0.42 - g0) * k);
            this.bCol = (float) (b0 + (0.12 - b0) * k);
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

        private boolean rock(double x, double y, double z) {
            BlockState s = level.getBlockState(at.set(x, y, z));
            return !GlowBlock.open(s) && s.getFluidState().isEmpty();
        }

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
                return new EruptionAsh(level, x, y, z, dx, dy, sprites);
            }
        }
    }

    /**
     * A billow of dust (0.9.0 pack look fixes): the dark cloud of ash and rock dust a lava bomb's blast throws up, and the
     * pale dust where a block of the ceiling crashes down. It is thrown out with the spawn's speed, slows within half a
     * second, then hangs and rises slowly, swelling to twice its size, and thins away over four to ten seconds, so a
     * still taken a few seconds after the impact still shows it. It takes the game's collision (a few dozen at a time),
     * and never draws darker than its light floor.
     */
    public static final class Dust extends TextureSheetParticle implements Sized {
        private final float peak;
        private final int lightFloor;
        private float size0;

        Dust(ClientLevel level, double x, double y, double z, double dx, double dy, double dz, SpriteSet sprites, float grey, float peak, int lightFloor) {
            super(level, x, y, z);
            this.xd = dx;
            this.yd = dy;
            this.zd = dz;
            this.gravity = -0.03F;
            this.friction = 0.9F;
            this.hasPhysics = true;
            this.lifetime = 90 + random.nextInt(110);
            this.quadSize = this.size0 = 0.7F + 0.5F * random.nextFloat();
            float g = grey * (0.85F + 0.3F * random.nextFloat());
            this.setColor(g * 1.02F, g, g * 0.97F);
            this.peak = peak;
            this.lightFloor = lightFloor;
            this.alpha = 0;
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
            this.quadSize = size0 * (1 + 1.1F * (1 - (1 - u) * (1 - u)));
            this.alpha = peak * Math.min(1, age / 3F) * Math.min(1, (1 - u) / 0.5F);
        }

        @Override
        public int getLightColor(float partialTick) {
            int own = super.getLightColor(partialTick);
            return LightTexture.pack(Math.max(LightTexture.block(own), lightFloor), LightTexture.sky(own));
        }

        @Override
        public boolean shouldCull() { return false; }

        @Override
        public ParticleRenderType getRenderType() { return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT; }

        /** A bomb's blast: charcoal ash and burnt rock, thick. */
        public record BlastProvider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double dx, double dy, double dz) {
                Dust d = new Dust(level, x, y, z, dx, dy, dz, sprites, 0.13F, 0.92F, 7);
                d.grow(1.3F);
                return d;
            }
        }

        /** A ceiling block's crash: pale grey rock dust, lit so it shows in the dark halls. */
        public record RockProvider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
            @Override
            public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double dx, double dy, double dz) {
                return new Dust(level, x, y, z, dx, dy, dz, sprites, 0.62F, 0.8F, 10);
            }
        }
    }

    /**
     * A chunk of broken rock (0.9.0 pack look fixes): a piece of a block's own texture, a quarter to half a block across
     * (the game's crumbs are a tenth), thrown out where a ceiling block crashes down or a lava bomb blasts the
     * ground. It flies with the speed it is given under the game's gravity and collision, comes to rest on what it lands
     * on and lies there eight to fifteen seconds before it crumbles away, so the floor round a landing reads as rubble for
     * a while. Paler than the game's crumbs (fresh broken faces) and never darker than block light 7. Made by the
     * client's own effects only ({@code ParticleEngine.add}), so it needs no particle type.
     */
    public static final class Rubble extends TerrainParticle {
        /** It crumbles away over this many ticks once it has lain its time. */
        static final int CRUMBLE = 20;
        private final int lie;
        private final float size0;
        private int lain;

        public Rubble(ClientLevel level, double x, double y, double z, double dx, double dy, double dz, BlockState state) {
            super(level, x, y, z, dx, dy, dz, state, BlockPos.containing(x, y, z));
            // the game's constructor scatters the speed it is given: this one flies as thrown
            this.xd = dx;
            this.yd = dy;
            this.zd = dz;
            this.gravity = 1.0F;
            this.friction = 0.98F;
            this.hasPhysics = true;
            this.quadSize = this.size0 = 0.12F + 0.14F * random.nextFloat();
            float g = 0.82F + 0.18F * random.nextFloat();
            this.rCol *= g / 0.6F;
            this.gCol *= g / 0.6F;
            this.bCol *= g / 0.6F;
            this.lie = 160 + random.nextInt(140);
            this.lifetime = 600;
        }

        @Override
        public void tick() {
            super.tick();
            if (removed || !onGround) return;
            if (++lain > lie) {
                this.quadSize = size0 * Math.max(0, 1 - (lain - lie) / (float) CRUMBLE);
                if (lain > lie + CRUMBLE) remove();
            }
        }

        @Override
        public int getLightColor(float partialTick) {
            int own = super.getLightColor(partialTick);
            return LightTexture.pack(Math.max(LightTexture.block(own), 7), LightTexture.sky(own));
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

    /** A spark from the lava that drifts up, flickers and goes out. Glows in the dark. */
    public static final class Ember extends TextureSheetParticle {
        Ember(ClientLevel level, double x, double y, double z, SpriteSet sprites) {
            super(level, x, y, z);
            this.lifetime = 40 + random.nextInt(60);
            this.gravity = -0.004F;
            this.friction = 0.97F;
            this.xd = (random.nextDouble() - 0.5) * 0.03;
            this.yd = 0.03 + random.nextDouble() * 0.05;
            this.zd = (random.nextDouble() - 0.5) * 0.03;
            this.quadSize = 0.04F + random.nextFloat() * 0.05F;
            this.setColor(1.0F, 0.55F + random.nextFloat() * 0.25F, 0.15F);
            this.pickSprite(sprites);
        }

        @Override
        public void tick() {
            super.tick();
            if (removed) return;
            this.xd += (random.nextDouble() - 0.5) * 0.006;
            this.zd += (random.nextDouble() - 0.5) * 0.006;
            float t = (float) age / lifetime;
            this.alpha = (1 - t * t) * (0.75F + 0.25F * random.nextFloat());
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
