package com.explorationremix.client.rune.table;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.client.rune.GlowSprite;
import com.explorationremix.client.rune.fx.GlowParticle;
import com.explorationremix.rune.engine.RuneId;
import com.explorationremix.rune.engine.Socket;
import com.explorationremix.rune.menu.RuneTableMenu;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderHighlightEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The enchanting table's rune view, drawn in the world itself (Taran, 2026-11-22: "a beautiful in-world interface on
 * the enchanting table"). The view eases from the player's eyes to over the table and looks down on its open book; the
 * room dims, and the weapon floats over the book, slowly turning, its three sockets hanging in a shallow arc below it:
 * the active rune (light blue), the modifier (lavender) and the passive (silver-blue), each a ring of light with its
 * rune at the middle and a thread of light up to the weapon. Picking a socket makes the runes the player carries for it
 * rise out of it into an arc over the weapon; picking one of those sets it. Round the active socket a second, finer
 * ring fills with the player's XP level toward the rune's second phase, and shines whole once it wakes. Motes drift up
 * from the book the whole time. Leaving gives the view back as smoothly as it was taken.
 *
 * <p>Where things hang is worked out on the screen first, in GUI pixels ({@link #plan}), and then set into the world
 * where the settled view shows them there: so the layout fits every window and GUI scale, clear of the words at the top
 * and the panel at the foot, and still moves with the world as the view eases in and out. Every light sits in a soft
 * shade of its own, so it keeps its colour over busy shelves and the table's red.
 *
 * <p>The screen (RuneTableScreen) is the clicks, the words and the panel; this is the camera and everything in the
 * world. What is drawn each frame is laid out here ({@link #targets}), so the screen tests the mouse against the very
 * places drawn. No warm colours and no cyan: light blue, lavender, silver-blue and white.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class RuneTableView {
    // ---- the palette
    static final int ACTIVE = 0x8EC2FF, MODIFIER = 0xB7A8FF, PASSIVE = 0xD6E6FF, CORE = 0xEEF4FF, DEEP = 0x3F63B8, PHASE = 0xC9DEFF;
    /** The shade the lights sit in: a blue so deep it reads as black. */
    private static final int SHADE = 0x03060E;

    /** Ticks the view takes to settle over the table, and to give the view back. */
    private static final float EASE_IN = 16.0F, EASE_OUT = 11.0F;
    /** The field of view once settled (degrees): a little narrower than most play, so the table fills it. */
    private static final double FOV = 56.0, TAN_HALF_FOV = Math.tan(Math.toRadians(FOV / 2.0));
    /** How far from the table's middle the view settles, how high above its floor, and the point it looks at: just over the book. */
    private static final double BACK = 1.9, HEIGHT = 2.05, LOOK_AT = 0.95;
    /** How far toward the eye the sockets and the risen runes hang, in front of the weapon. */
    private static final double SOCKET_Z = 0.3, RISEN_Z = 0.15;
    /**
     * GUI pixels: the room the header keeps at the top, the least room the panel keeps at the foot (four lines and its
     * margins), and the room the two lines of words under a socket take below its ring.
     */
    static final float TOP_ROOM = 34.0F, FOOT_ROOM = 60.0F, NAMES = 23.0F;

    @Nullable
    private static BlockPos table;
    private static boolean open;
    private static float ease, easeO;
    private static int ticks;
    /** Where the view settles, set once as it opens: the eye, the point it looks at, and the plane the layout lies in. */
    private static Vec3 eye = Vec3.ZERO, focus = Vec3.ZERO, right = new Vec3(1, 0, 0), up = new Vec3(0, 1, 0), toward = new Vec3(0, 0, 1);
    private static double dist = 2.0;
    private static float yaw, pitch;

    // ---- what the screen asks for
    /** The socket whose runes are risen (null: none), and the tick they started to rise. */
    @Nullable
    static Socket chosen;
    static int chosenAt;
    /** What the mouse is over (null: nothing). */
    @Nullable
    static Target hovered;
    /** A socket just set or emptied, and when: it flashes. */
    @Nullable
    static Socket flashed;
    static int flashedAt = -100;

    // ---- the last frame drawn, for the screen's tests and words
    private static final Matrix4f PROJ = new Matrix4f(), VIEW = new Matrix4f();
    private static Vec3 cam = Vec3.ZERO;
    private static boolean drawn, pending;
    private static float lastTime;
    static final List<Target> targets = new ArrayList<>();

    // ---- the layout, in GUI pixels, for the screen as it was last drawn
    private static float gw = 480.0F, gh = 270.0F;
    private static float weaponX, weaponY, weaponPx, socketPx, risenPx, arcRX, arcRY;
    private static final float[] socketX = new float[3], socketY = new float[3];
    /** The room the screen's panel needs at the foot, and the glide to a new need (from, to, when it began). */
    private static float footFrom = FOOT_ROOM, footTo = FOOT_ROOM, footAt = -100.0F;
    private static boolean footFresh = true;

    private RuneTableView() {}

    /** Something the view shows that the mouse can pick: a socket, a rune risen for one, or "take it out". */
    static final class Target {
        enum Kind { SOCKET, RUNE, TAKE_OUT }

        final Kind kind;
        final Socket socket;
        @Nullable
        final RuneId rune;
        final int count;
        final Vec3 at;
        /** Its ring's radius in the world. */
        final float radius;
        /** Where it was on the screen in the last frame drawn (GUI pixels), and how far round it the mouse counts. */
        float sx, sy, sr;
        boolean onScreen;

        Target(Kind kind, Socket socket, @Nullable RuneId rune, int count, Vec3 at, float radius) {
            this.kind = kind;
            this.socket = socket;
            this.rune = rune;
            this.count = count;
            this.at = at;
            this.radius = radius;
        }

        boolean same(@Nullable Target o) { return o != null && o.kind == kind && o.socket == socket && o.rune == rune; }
    }

    // ------------------------------------------------------------------------------------------------ opening and closing

    /** The table's view opens (its screen did). */
    static void open(BlockPos at) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (table == null || !table.equals(at)) {
            table = at.immutable();
            ease = easeO = 0.0F;
            settle(mc);
        }
        open = true;
        chosen = null;
        hovered = null;
        targets.clear();
        footFresh = true;
    }

    /** The screen closed: the view eases back to the player's eyes, the sockets fading as it goes. */
    static void close() {
        open = false;
        chosen = null;
        hovered = null;
    }

    /** Whether the view has the camera at all now (opening, open or giving it back). */
    public static boolean active() { return table != null && (open || ease > 0.0F); }

    /** How far the view has settled over the table at this partial tick: 0 the player's own, 1 over the table. */
    static float shown(float partialTick) {
        if (table == null) return 0.0F;
        float t = Mth.clamp(Mth.lerp(partialTick, easeO, ease), 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    /**
     * Works out where the view settles: on the side of the table the player stands, above their eyes, looking down at
     * the book; pulled in toward the table if a wall or ceiling is in the way.
     */
    private static void settle(Minecraft mc) {
        Vec3 c = Vec3.atBottomCenterOf(table);
        Vec3 side = new Vec3(mc.player.getX() - c.x, 0, mc.player.getZ() - c.z);
        if (side.lengthSqr() < 0.04) side = new Vec3(-mc.player.getLookAngle().x, 0, -mc.player.getLookAngle().z);
        side = side.lengthSqr() < 1.0E-6 ? new Vec3(0, 0, 1) : side.normalize();
        focus = c.add(0, LOOK_AT, 0);
        Vec3 want = c.add(side.scale(BACK)).add(0, HEIGHT, 0);
        BlockHitResult hit = mc.level.clip(new ClipContext(focus, want, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() != HitResult.Type.MISS) {
            double d = Math.max(0.9, hit.getLocation().distanceTo(focus) - 0.25);
            want = focus.add(want.subtract(focus).normalize().scale(d));
        }
        eye = want;
        dist = eye.distanceTo(focus);
        Vec3 look = focus.subtract(eye).normalize();
        yaw = (float) (Mth.atan2(-look.x, look.z) * Mth.RAD_TO_DEG);
        pitch = (float) (-Math.asin(Mth.clamp(look.y, -1.0, 1.0)) * Mth.RAD_TO_DEG);
        toward = look.scale(-1);
        right = look.cross(new Vec3(0, 1, 0)).normalize();
        up = right.cross(look).normalize();
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END || table == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            table = null;
            return;
        }
        ticks++;
        easeO = ease;
        ease = open ? Math.min(1.0F, ease + 1.0F / EASE_IN) : Math.max(0.0F, ease - 1.0F / EASE_OUT);
        if (!open && ease <= 0.0F && easeO <= 0.0F) {
            table = null;
            targets.clear();
            return;
        }
        if (open && ease > 0.4F) motes(mc);
    }

    /** A new world, or none: the view lets go at once. */
    @SubscribeEvent
    public static void unload(LevelEvent.Unload e) {
        if (e.getLevel().isClientSide()) {
            table = null;
            open = false;
            targets.clear();
        }
    }

    // ------------------------------------------------------------------------------------------------ the camera

    /** Implemented by the camera (CameraMixin): puts it here, facing so, as seen from its own eyes. */
    public interface Placeable {
        void explorationremix$place(Vec3 at, float yaw, float pitch);
    }

    /** At the end of the camera's own setup each frame: eased from where the game put it to the table's view. */
    public static void camera(Camera c, float partialTick) {
        float t = shown(partialTick);
        if (t <= 0.0F || !(c instanceof Placeable p)) return;
        Vec3 from = c.getPosition();
        Vec3 at = from.add(eye.subtract(from).scale(t));
        float y = c.getYRot() + Mth.wrapDegrees(yaw - c.getYRot()) * t;
        float x = Mth.lerp(t, c.getXRot(), pitch);
        p.explorationremix$place(at, y, x);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void fov(ViewportEvent.ComputeFov e) {
        if (!e.usedConfiguredFov()) return;
        float t = shown((float) e.getPartialTick());
        if (t > 0.0F) e.setFOV(Mth.lerp(t, e.getFOV(), FOV));
    }

    /** The hand, the block outline and the HUD stay out of the view while it has the camera. */
    @SubscribeEvent
    public static void hand(RenderHandEvent e) {
        if (active()) e.setCanceled(true);
    }

    @SubscribeEvent
    public static void outline(RenderHighlightEvent.Block e) {
        if (active()) e.setCanceled(true);
    }

    @SubscribeEvent
    public static void hud(RenderGuiOverlayEvent.Pre e) {
        if (active() && e.getOverlay() != VanillaGuiOverlay.CHAT_PANEL.type()) e.setCanceled(true);
    }

    // ------------------------------------------------------------------------------------------------ the layout

    /** World units per GUI pixel {@code z} toward the eye from the point the settled view looks at. */
    private static double perPx(double z) { return Math.max(0.05, dist - z) * TAN_HALF_FOV / (gh / 2.0); }

    /** The point {@code z} toward the eye from the point the view looks at, that the settled view shows at (x, y) GUI pixels. */
    private static Vec3 place(double x, double y, double z) {
        double k = perPx(z);
        return focus.add(toward.scale(z)).add(right.scale((x - gw / 2.0) * k)).add(up.scale((gh / 2.0 - y) * k));
    }

    /**
     * Works out the layout for the screen as it is now (GUI pixels): the sockets in a shallow smile, as low as the words
     * under them and the panel allow; the weapon over them, above the book; and the arc the runes rise into, round the
     * top of the weapon and clear of the header. Sizes follow the screen's height within bounds the words can live with.
     */
    private static void plan(float time) {
        Window w = Minecraft.getInstance().getWindow();
        gw = Math.max(1, w.getGuiScaledWidth());
        gh = Math.max(1, w.getGuiScaledHeight());
        float cx = gw / 2.0F;
        socketPx = Mth.clamp(gh * 0.05F, 12.0F, 20.0F);
        risenPx = socketPx * 0.85F;
        float dip = socketPx * 0.55F;
        float low = Math.min(gh * 0.64F, gh - foot(time) - NAMES - socketPx * 1.25F);
        float dx = Math.min(Mth.clamp(gh * 0.36F, 100.0F, 170.0F), gw * 0.34F);
        for (Socket s : Socket.values()) {
            int i = s.ordinal() - 1;
            socketX[s.ordinal()] = cx + i * dx;
            socketY[s.ordinal()] = i == 0 ? low : low - dip;
        }
        weaponPx = Mth.clamp(gh * 0.21F, 44.0F, 120.0F);
        weaponX = cx;
        weaponY = Math.min(gh * 0.42F, low - dip - socketPx - 8.0F - weaponPx / 2.0F);
        // close round the weapon, so the runes plainly rise to it; wide, so many fit down its sides
        float inner = weaponPx / 2.0F + risenPx + 6.0F;
        arcRY = Math.max(inner, Math.min(weaponY - TOP_ROOM - 2.0F - risenPx, inner + gh * 0.035F));
        arcRX = Math.max(inner + 4.0F, Math.min(arcRY * 2.0F, gw * 0.42F));
    }

    /** The screen's panel needs this much room at the foot (GUI pixels): the layout glides to make it, or takes it at once on opening. */
    static void footNeed(float need) {
        need = Math.max(FOOT_ROOM, need);
        if (footFresh) {
            footFresh = false;
            footFrom = footTo = need;
            footAt = -100.0F;
            return;
        }
        if (Math.abs(need - footTo) < 0.5F) return;
        footFrom = foot(lastTime);
        footTo = need;
        footAt = lastTime;
    }

    private static float foot(float time) {
        float t = Mth.clamp((time - footAt) / 6.0F, 0.0F, 1.0F);
        return Mth.lerp(t * t * (3.0F - 2.0F * t), footFrom, footTo);
    }

    static Vec3 weaponAt(float time) { return place(weaponX, weaponY + gh * 0.008 * Math.sin(time * 0.06), 0.0); }

    static Vec3 socketAt(Socket s) { return place(socketX[s.ordinal()], socketY[s.ordinal()], SOCKET_Z); }

    /** Where the screen says there is nothing to raise: over the weapon (GUI pixels), or null. */
    @Nullable
    static float[] hintAt() { return toScreen(place(weaponX, weaponY - weaponPx * 0.5F - 16.0F, RISEN_Z)); }

    private static final int ARC_STEPS = 72;
    private static final float[] ARC_LENGTH = new float[ARC_STEPS + 1];

    /** Measures the top half of the arc's ellipse from its left end to its right, so the risen runes space evenly along it. */
    private static float measureArc() {
        float px = -arcRX, py = 0.0F;
        ARC_LENGTH[0] = 0.0F;
        for (int i = 1; i <= ARC_STEPS; i++) {
            double a = Math.PI * (1.0 - i / (double) ARC_STEPS);
            float x = (float) (Math.cos(a) * arcRX), y = (float) (Math.sin(a) * arcRY);
            ARC_LENGTH[i] = ARC_LENGTH[i - 1] + (float) Math.hypot(x - px, y - py);
            px = x;
            py = y;
        }
        return ARC_LENGTH[ARC_STEPS];
    }

    /** The point {@code s} along the arc from its left end (GUI pixels). */
    private static float[] alongArc(float s) {
        int i = 1;
        while (i < ARC_STEPS && ARC_LENGTH[i] < s) i++;
        float f = Mth.clamp((s - ARC_LENGTH[i - 1]) / Math.max(1.0E-4F, ARC_LENGTH[i] - ARC_LENGTH[i - 1]), 0.0F, 1.0F);
        double a = Math.PI * (1.0 - (i - 1 + f) / ARC_STEPS);
        return new float[]{weaponX + (float) (Math.cos(a) * arcRX), weaponY - (float) (Math.sin(a) * arcRY)};
    }

    /** Lays out what the mouse can pick this frame: the three sockets, and the runes risen for the chosen one. */
    private static void layout(RuneTableMenu menu, float time) {
        Target was = hovered;
        targets.clear();
        for (Socket s : Socket.values()) {
            targets.add(new Target(Target.Kind.SOCKET, s, menu.loadout().get(s), 1, socketAt(s), (float) (socketPx * perPx(SOCKET_Z))));
        }
        if (chosen != null) {
            List<Target> risen = new ArrayList<>();
            RuneId in = menu.loadout().get(chosen);
            for (Map.Entry<RuneId, Integer> c : menu.carried(chosen).entrySet()) {
                if (c.getKey() != in) risen.add(new Target(Target.Kind.RUNE, chosen, c.getKey(), c.getValue(), Vec3.ZERO, 0.0F));
            }
            if (in != null) risen.add(new Target(Target.Kind.TAKE_OUT, chosen, in, 0, Vec3.ZERO, 0.0F));
            int n = risen.size();
            float total = measureArc();
            // spaced evenly from the top of the arc down its sides; packed closer, and smaller, if there are many
            float spacing = n > 1 ? Math.min(risenPx * 2.8F, total / (n - 1)) : 0.0F;
            float fit = n > 1 ? Mth.clamp(spacing / (risenPx * 2.3F), 0.6F, 1.0F) : 1.0F;
            float fx = socketX[chosen.ordinal()], fy = socketY[chosen.ordinal()];
            for (int k = 0; k < n; k++) {
                Target r = risen.get(k);
                // each rises out of the socket in turn, up and then over to its place, and settles
                float t = Mth.clamp((time - chosenAt - k * 1.2F) / 7.0F, 0.0F, 1.0F);
                float e = 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
                float[] to = alongArc(total / 2.0F + (k - (n - 1) / 2.0F) * spacing);
                float mx = fx * 0.7F + to[0] * 0.3F, my = to[1];
                float x = (1 - e) * (1 - e) * fx + 2 * e * (1 - e) * mx + e * e * to[0];
                float y = (1 - e) * (1 - e) * fy + 2 * e * (1 - e) * my + e * e * to[1];
                double z = Mth.lerp(e, SOCKET_Z, RISEN_Z);
                float px = risenPx * fit * (0.4F + 0.6F * e);
                targets.add(new Target(r.kind, r.socket, r.rune, r.count, place(x, y, z), (float) (px * perPx(z))));
            }
        }
        hovered = null;
        if (was != null) for (Target t : targets) if (t.same(was)) hovered = t;
    }

    // ------------------------------------------------------------------------------------------------ drawing

    /** A frame's world starts: the view is owed a drawing. */
    public static void beginLevel() { pending = true; }

    /** A frame's world is done: if the view wasn't drawn once the world was (another mod moved that), it is now. */
    public static void endLevel(PoseStack viewPose, float partialTick) {
        if (pending) afterWorld(viewPose, partialTick);
    }

    /**
     * Once the world is drawn (GameRendererMixin), over it: the shade, the light, then the weapon and the runes. The
     * depth the world left is cleared first, so nothing of the table's surroundings cuts into them.
     */
    public static void afterWorld(PoseStack viewPose, float partialTick) {
        pending = false;
        float t = shown(partialTick);
        Minecraft mc = Minecraft.getInstance();
        drawn = false;
        if (t <= 0.01F || mc.level == null || mc.player == null) return;
        RuneTableMenu menu = mc.player.containerMenu instanceof RuneTableMenu m && open ? m : null;
        Camera camera = mc.gameRenderer.getMainCamera();
        cam = camera.getPosition();
        PROJ.set(RenderSystem.getProjectionMatrix());
        VIEW.set(viewPose.last().pose());
        float time = ticks + partialTick;
        lastTime = time;
        if (menu != null) {
            plan(time);
            layout(menu, time);
        } else {
            // leaving: the sockets stay where they were, fading with the rest, and nothing is risen
            targets.removeIf(tg -> tg.kind != Target.Kind.SOCKET);
            hovered = null;
        }
        drawn = true;
        // the runes come in once the view is most of the way over the table, and go first when it leaves
        float shown = Mth.clamp((t - 0.45F) / 0.55F, 0.0F, 1.0F);
        if (shown <= 0.0F) return;

        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        PoseStack pose = new PoseStack();
        pose.last().pose().set(VIEW);
        pose.last().normal().set(viewPose.last().normal());
        shade(pose, camera, time, shown);
        light(pose, camera, time, shown);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        Lighting.setupLevel(pose.last().pose());
        weapon(mc, pose, camera, buffers, time, shown);
        for (Target tg : targets) tablet(pose, camera, buffers, tg, time, shown);
        buffers.endBatch();
        project();
    }

    /** The weapon over the book, turning a little this way and that, bobbing. */
    private static void weapon(Minecraft mc, PoseStack pose, Camera camera, MultiBufferSource buffers, float time, float shown) {
        ItemStack w = mc.player.getMainHandItem();
        if (w.isEmpty()) return;
        Vec3 p = weaponAt(time).subtract(cam);
        pose.pushPose();
        pose.translate(p.x, p.y, p.z);
        pose.mulPose(camera.rotation());
        pose.mulPose(Axis.YP.rotationDegrees((float) (28.0 * Math.sin(time * 0.035))));
        // the item spans one block: scaled to the layout's size for it
        float s = (float) (weaponPx * perPx(0.0)) * (0.6F + 0.4F * shown);
        pose.scale(s, s, s);
        mc.getItemRenderer().renderStatic(w, ItemDisplayContext.FIXED, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, pose, buffers, mc.level, 0);
        pose.popPose();
    }

    private static final ResourceLocation[] RUNE_TEXTURES = new ResourceLocation[RuneId.values().length];

    private static ResourceLocation runeTexture(RuneId r) {
        ResourceLocation t = RUNE_TEXTURES[r.ordinal()];
        if (t == null) RUNE_TEXTURES[r.ordinal()] = t = new ResourceLocation(ExplorationRemix.MOD_ID, "textures/item/rune_" + r.id + ".png");
        return t;
    }

    /** A rune's tablet, facing the eye, full bright: in a socket, risen over the weapon, or (dimmed) to take out. */
    private static void tablet(PoseStack pose, Camera camera, MultiBufferSource buffers, Target tg, float time, float shown) {
        if (tg.rune == null) return;
        float half = tg.radius * 0.7F * shown;
        if (tg.same(hovered)) half *= 1.14F;
        Vec3 p = tg.at.subtract(cam);
        pose.pushPose();
        pose.translate(p.x, p.y, p.z);
        int rgb = tg.kind == Target.Kind.TAKE_OUT ? mix(0, 0xFFFFFF, 0.45F) : 0xFFFFFF;
        GlowSprite.facing(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(runeTexture(tg.rune))), camera.rotation(), half,
                (float) (3.0 * Math.sin(time * 0.05 + tg.rune.ordinal())), 0.0F, rgb, 1.0F);
        pose.popPose();
    }

    /**
     * The shade the lights sit in, laid down first: the room dims a little, most toward the edges, and darkest right
     * under each light, so a ring keeps its colour over coloured shelves or the table's red.
     */
    private static void shade(PoseStack pose, Camera camera, float time, float shown) {
        Glow g = new Glow(pose.last().pose(), camera);
        double k = perPx(0.0);
        Vec3 middle = place(gw / 2.0, (weaponY + socketY[Socket.MODIFIER.ordinal()]) / 2.0, 0.0);
        g.vignette(middle, gw * 0.62 * k, gh * 0.75 * k, SHADE, 0.16F * shown, 0.42F * shown);
        g.soft(weaponAt(time), (float) (weaponPx * 0.8 * k), SHADE, 0.45F * shown, 0.4F);
        for (Target tg : targets) g.soft(tg.at, tg.radius * 2.1F, SHADE, (tg.kind == Target.Kind.SOCKET ? 0.62F : 0.55F) * shown, 0.5F);
        g.draw(false);
    }

    /** Every ring, halo, thread and arc of light, added onto the picture in one go. */
    private static void light(PoseStack pose, Camera camera, float time, float shown) {
        Glow g = new Glow(pose.last().pose(), camera);
        Vec3 w = weaponAt(time);
        float wr = (float) (weaponPx * perPx(0.0));
        float pulse = 0.5F + 0.5F * Mth.sin(time * 0.12F);
        // the weapon's halo, and a wide faint one round everything
        g.disc(w, wr * 0.8F, ACTIVE, 0.28F * shown);
        g.disc(w, wr * 0.38F, CORE, 0.22F * shown);
        g.disc(place(gw / 2.0, (weaponY + socketY[Socket.MODIFIER.ordinal()]) / 2.0, 0.0), (float) (gh * 0.55 * perPx(0.0)), DEEP, 0.12F * shown);
        int level = Minecraft.getInstance().player.experienceLevel;
        for (Target tg : targets) {
            boolean hover = tg.same(hovered);
            int colour = colour(tg.socket);
            float r = tg.radius;
            if (tg.kind == Target.Kind.SOCKET) {
                Vec3 c = tg.at;
                boolean filled = tg.rune != null, isChosen = chosen == tg.socket;
                float flash = flashed == tg.socket ? Mth.clamp(1.0F - (time - flashedAt) / 14.0F, 0.0F, 1.0F) : 0.0F;
                float a = (filled ? 0.85F : 0.45F) * shown;
                if (hover) a = Math.min(1.0F, a + 0.25F);
                if (isChosen) a = Math.min(1.0F, a + 0.15F * pulse + 0.1F);
                g.disc(c, r * (filled ? 1.5F : 1.1F), colour, (filled ? 0.32F : 0.14F) * shown + 0.3F * flash);
                g.ring(c, r * (hover ? 1.08F : 1.0F), r * 0.2F, colour, a);
                g.ring(c, r * 0.82F, r * 0.08F, CORE, a * 0.45F);
                if (isChosen) g.ring(c, r * (1.25F + 0.06F * pulse), r * 0.12F, colour, 0.55F * shown);
                if (flash > 0.0F) g.ring(c, r * (1.0F + 1.6F * (1.0F - flash)), r * 0.2F, CORE, flash);
                // a thread of light from a filled socket up to the weapon, a mote running up it
                if (filled) {
                    Vec3 from = c.add(up.scale(r * 1.05)), to = w.add(c.subtract(w).normalize().scale(wr * 0.3));
                    g.line(from, to, r * 0.08F, colour, 0.35F * shown);
                    float k = (time * 0.025F + tg.socket.ordinal() * 0.33F) % 1.0F;
                    g.disc(from.add(to.subtract(from).scale(k)), r * 0.23F, CORE, 0.8F * shown * Mth.sin(k * Mth.PI));
                }
                // round the active socket: the XP toward its rune's second phase, whole and shining once awake
                if (tg.socket == Socket.ACTIVE && tg.rune != null) {
                    float fill = Mth.clamp(level / (float) tg.rune.phaseLevel, 0.0F, 1.0F);
                    float rr = r * 1.42F;
                    g.arc(c, rr, r * 0.08F, 0.0F, 1.0F, DEEP, 0.4F * shown);
                    if (fill >= 1.0F) {
                        g.arc(c, rr, r * 0.17F, 0.0F, 1.0F, PHASE, (0.65F + 0.3F * pulse) * shown);
                        g.disc(c, r * 2.1F, PHASE, (0.1F + 0.06F * pulse) * shown);
                    } else {
                        g.arc(c, rr, r * 0.15F, 0.0F, fill, PHASE, 0.85F * shown);
                    }
                }
            } else {
                float a = (tg.kind == Target.Kind.TAKE_OUT ? 0.5F : 0.8F) * shown;
                if (hover) a = 1.0F;
                g.disc(tg.at, r * (hover ? 1.9F : 1.45F), colour, (hover ? 0.4F : 0.2F) * shown);
                g.ring(tg.at, r * (hover ? 1.1F : 1.0F), r * 0.19F, colour, a);
                if (tg.kind == Target.Kind.TAKE_OUT) {
                    // a stroke across it: this one comes out
                    Vec3 d = g.r.add(g.u).normalize().scale(r * 0.75);
                    g.line(tg.at.subtract(d), tg.at.add(d), r * 0.13F, CORE, a * 0.8F);
                }
            }
        }
        g.draw(true);
    }

    static int colour(Socket s) {
        return switch (s) {
            case ACTIVE -> ACTIVE;
            case MODIFIER -> MODIFIER;
            case PASSIVE -> PASSIVE;
        };
    }

    static int mix(int a, int b, float t) {
        t = Mth.clamp(t, 0.0F, 1.0F);
        int r = Math.round((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * t);
        int g = Math.round((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * t);
        int bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
        return r << 16 | g << 8 | bl;
    }

    /** Motes of light drifting up from the book to the weapon, now and then. */
    private static void motes(Minecraft mc) {
        if (ticks % 3 != 0) return;
        var r = mc.level.random;
        Vec3 book = Vec3.atBottomCenterOf(table).add(0, 0.95, 0);
        Vec3 o = new Vec3((r.nextDouble() - 0.5) * 0.5, 0, (r.nextDouble() - 0.5) * 0.5);
        int c = switch (r.nextInt(3)) {
            case 0 -> ACTIVE;
            case 1 -> MODIFIER;
            default -> PASSIVE;
        };
        GlowParticle.dot(book.add(o)).vel(new Vec3(-o.x * 0.02, 0.012 + r.nextDouble() * 0.01, -o.z * 0.02)).colour(mix(c, 0xFFFFFF, 0.4F)).fadeTo(DEEP)
                .size(0.025F, 0.0F).alpha(0.8F).life(40 + r.nextInt(20)).drag(0.99F).spawn();
    }

    /** A burst of light at a socket just set: the screen calls it when the server's change arrives. */
    static void burst(Socket s) {
        flashed = s;
        flashedAt = ticks;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || table == null) return;
        var r = mc.level.random;
        Vec3 c = socketAt(s);
        int col = colour(s);
        for (int i = 0; i < 14; i++) {
            Vec3 v = new Vec3(r.nextDouble() - 0.5, r.nextDouble() - 0.3, r.nextDouble() - 0.5).normalize().scale(0.03 + r.nextDouble() * 0.03);
            GlowParticle.dot(c).vel(v).colour(mix(col, 0xFFFFFF, 0.6F)).fadeTo(col).size(0.022F, 0.0F).life(14 + r.nextInt(8)).drag(0.88F).spawn();
        }
    }

    /** Where each target was on the screen this frame (GUI pixels), for the mouse. */
    private static void project() {
        Minecraft mc = Minecraft.getInstance();
        float w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        for (Target tg : targets) {
            Vector4f c = clip(tg.at);
            if (c.w <= 0.05F) {
                tg.onScreen = false;
                continue;
            }
            tg.sx = (c.x / c.w * 0.5F + 0.5F) * w;
            tg.sy = (1.0F - (c.y / c.w * 0.5F + 0.5F)) * h;
            // the radius on screen: the same distance off to the side, projected
            Vector4f e = clip(tg.at.add(right.scale(tg.radius * 1.25)));
            tg.sr = e.w <= 0.05F ? 8.0F : Math.max(6.0F, Math.abs((e.x / e.w * 0.5F + 0.5F) * w - tg.sx));
            tg.onScreen = true;
        }
    }

    private static Vector4f clip(Vec3 p) {
        Vector4f v = new Vector4f((float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z), 1.0F);
        VIEW.transform(v);
        PROJ.transform(v);
        return v;
    }

    /** A point of the world on the screen (GUI pixels), or null if it is behind the eye or nothing was drawn. */
    @Nullable
    static float[] toScreen(Vec3 p) {
        if (!drawn) return null;
        Minecraft mc = Minecraft.getInstance();
        Vector4f c = clip(p);
        if (c.w <= 0.05F) return null;
        return new float[]{(c.x / c.w * 0.5F + 0.5F) * mc.getWindow().getGuiScaledWidth(),
                (1.0F - (c.y / c.w * 0.5F + 0.5F)) * mc.getWindow().getGuiScaledHeight()};
    }

    static boolean drawn() { return drawn; }

    static int ticks() { return ticks; }

    // ------------------------------------------------------------------------------------------------ light

    /**
     * Soft light and shade in the plane facing the eye ({@code r} right, {@code u} up): discs that fade out from their
     * middle, rings and arcs that fade to either side of their line, threads, and the shade's soft-edged discs. Points
     * are the world's; the pose turns them as the eye sees them. Drawn added onto the picture (light) or over it (shade).
     */
    private static final class Glow {
        private static final int SEGMENTS = 48;
        private final Matrix4f m;
        final Vec3 r, u;
        private final BufferBuilder b;

        Glow(Matrix4f m, Camera camera) {
            this.m = m;
            Vector3f left = camera.getLeftVector(), above = camera.getUpVector();
            this.r = new Vec3(-left.x(), -left.y(), -left.z());
            this.u = new Vec3(above.x(), above.y(), above.z());
            this.b = Tesselator.getInstance().getBuilder();
            b.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        }

        private void v(Vec3 p, int rgb, float a) {
            b.vertex(m, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z))
                    .color(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, Mth.clamp(Math.round(a * 255.0F), 0, 255)).endVertex();
        }

        private Vec3 round(Vec3 c, double rx, double ry, double ang) { return c.add(r.scale(Math.cos(ang) * rx)).add(u.scale(Math.sin(ang) * ry)); }

        private Vec3 round(Vec3 c, double rad, double ang) { return round(c, rad, rad, ang); }

        /** A disc of light, brightest at its middle, nothing at its rim. */
        void disc(Vec3 c, float rad, int rgb, float a) {
            if (a <= 0.004F || rad <= 0.0F) return;
            for (int i = 0; i < SEGMENTS; i++) {
                double a0 = Math.PI * 2 * i / SEGMENTS, a1 = Math.PI * 2 * (i + 1) / SEGMENTS;
                v(c, rgb, a);
                v(round(c, rad, a0), rgb, 0.0F);
                v(round(c, rad, a1), rgb, 0.0F);
            }
        }

        /** A disc even out to {@code solid} of its radius, then fading to nothing at its rim. */
        void soft(Vec3 c, float rad, int rgb, float a, float solid) {
            if (a <= 0.004F || rad <= 0.0F) return;
            double in = rad * solid;
            for (int i = 0; i < SEGMENTS; i++) {
                double a0 = Math.PI * 2 * i / SEGMENTS, a1 = Math.PI * 2 * (i + 1) / SEGMENTS;
                Vec3 i0 = round(c, in, a0), i1 = round(c, in, a1);
                v(c, rgb, a);
                v(i0, rgb, a);
                v(i1, rgb, a);
                quad(i0, round(c, rad, a0), round(c, rad, a1), i1, rgb, a, 0.0F, 0.0F, a);
            }
        }

        /** Shade over the whole view: {@code in} at the ellipse's middle, deepening to {@code out} at its rim and beyond. */
        void vignette(Vec3 c, double rx, double ry, int rgb, float in, float out) {
            for (int i = 0; i < SEGMENTS; i++) {
                double a0 = Math.PI * 2 * i / SEGMENTS, a1 = Math.PI * 2 * (i + 1) / SEGMENTS;
                Vec3 p0 = round(c, rx, ry, a0), p1 = round(c, rx, ry, a1);
                v(c, rgb, in);
                v(p0, rgb, out);
                v(p1, rgb, out);
                quad(p0, round(c, rx * 8, ry * 8, a0), round(c, rx * 8, ry * 8, a1), p1, rgb, out, out, out, out);
            }
        }

        /** A ring of light {@code w} wide on its line, fading to nothing either side. */
        void ring(Vec3 c, float rad, float w, int rgb, float a) { arc(c, rad, w, 0.0F, 1.0F, rgb, a); }

        /** Part of a ring: from {@code from} to {@code to} of the way round (0 at the top, going clockwise as seen). */
        void arc(Vec3 c, float rad, float w, float from, float to, int rgb, float a) {
            if (a <= 0.004F || to <= from) return;
            int n = Math.max(2, Math.round(SEGMENTS * (to - from)));
            for (int i = 0; i < n; i++) {
                double t0 = from + (to - from) * i / n, t1 = from + (to - from) * (i + 1) / n;
                double a0 = Math.PI / 2 - t0 * Math.PI * 2, a1 = Math.PI / 2 - t1 * Math.PI * 2;
                Vec3 in0 = round(c, rad - w, a0), mid0 = round(c, rad, a0), out0 = round(c, rad + w, a0);
                Vec3 in1 = round(c, rad - w, a1), mid1 = round(c, rad, a1), out1 = round(c, rad + w, a1);
                quad(in0, in1, mid1, mid0, rgb, 0.0F, 0.0F, a, a);
                quad(mid0, mid1, out1, out0, rgb, a, a, 0.0F, 0.0F);
            }
        }

        /** A thread of light from {@code p} to {@code q}, {@code w} wide, fading to either side. */
        void line(Vec3 p, Vec3 q, float w, int rgb, float a) {
            if (a <= 0.004F) return;
            Vec3 d = q.subtract(p);
            // across the thread, in the plane facing the eye
            Vec3 across = new Vec3(-d.dot(u), d.dot(r), 0);
            Vec3 side = r.scale(across.x).add(u.scale(across.y));
            if (side.lengthSqr() < 1.0E-9) return;
            side = side.normalize().scale(w);
            quad(p.subtract(side), q.subtract(side), q, p, rgb, 0.0F, 0.0F, a, a);
            quad(p, q, q.add(side), p.add(side), rgb, a, a, 0.0F, 0.0F);
        }

        /** Corners p0..p3 in order, with alphas a0..a3 (p0 a0, p1 a1, p2 a2, p3 a3). */
        private void quad(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, int rgb, float a0, float a1, float a2, float a3) {
            v(p0, rgb, a0);
            v(p1, rgb, a1);
            v(p2, rgb, a2);
            v(p0, rgb, a0);
            v(p2, rgb, a2);
            v(p3, rgb, a3);
        }

        /** Light is added onto the picture; shade is laid over it. */
        void draw(boolean light) {
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            RenderSystem.enableBlend();
            if (light) RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
            else RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            try {
                BufferUploader.drawWithShader(b.end());
            } finally {
                RenderSystem.enableCull();
                RenderSystem.depthMask(true);
                RenderSystem.enableDepthTest();
                RenderSystem.defaultBlendFunc();
                RenderSystem.disableBlend();
            }
        }
    }
}
