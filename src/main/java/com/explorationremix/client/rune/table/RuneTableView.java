package com.explorationremix.client.rune.table;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.client.rune.GlowSprite;
import com.explorationremix.client.rune.fx.GlowParticle;
import com.explorationremix.rune.RuneGear;
import com.explorationremix.rune.armor.WornRunes;
import com.explorationremix.rune.engine.ArmorSet;
import com.explorationremix.rune.engine.Piece;
import com.explorationremix.rune.engine.RuneId;
import com.explorationremix.rune.engine.RuneKind;
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
 * room dims, and the gear being set (what is in hand, a weapon, a tool or a piece of armor; or a worn piece of armor:
 * picked in the screen's column, {@link #picked}) floats over the book before a rune circle, slowly turning, its sockets hanging in a shallow arc below
 * it: a weapon's active rune (light blue), modifier (lavender) and passive (silver-blue), a piece of armor's effect rune
 * (periwinkle) and two collect runes (pale lilac), or a tool's one rune (pale sky blue). Each is a silver ring set with
 * gems of its colour, its rune's tablet in the middle and a thread of light up to the gear. Picking a socket makes the
 * runes the player carries for it rise out of it into an arc over the gear; picking one of those sets it. Up the active
 * socket's outer side a gauge of pips fills with the player's XP level toward the rune's second phase, and glints once
 * it wakes; over a collect socket four pips count the worn pieces carrying its rune (a piece held in the hand counted as if it were
 * worn in place of the one worn there), and glint once three do. Light runs
 * slowly round the circle, stars twinkle about it, and motes drift up from the book the whole time. Leaving gives the
 * view back as smoothly as it was taken.
 *
 * <p>The rings, the circle, the pips and the stars are pixel art (Taran, 2026-11-23: "add some magical themed pixel
 * art to the interface"), from textures/gui/rune_table_world.png (tools/rune_table_art.py), drawn sharp: a socket's
 * ring and its rune's tablet share one texel size, so the two read as one piece.
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
    static final int EFFECT = 0x9FB8FF, COLLECT = 0xE0D4FF, TOOL = 0xBFE0FF;
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
    /** GUI pixels: the room the header keeps at the top, and the room the two lines of words under a socket take below its ring. */
    static final float TOP_ROOM = 30.0F, NAMES = 23.0F;
    /** GUI pixels: the room kept at the left for the column of gear (RuneTableScreen), and at the right for the sets' tally. */
    static final float SIDE_LEFT = 40.0F, SIDE_RIGHT = 84.0F;
    /** How far toward the eye the rune circle hangs: just behind the weapon. */
    private static final double SEAL_Z = -0.05;

    // ---- the panel at the foot (RuneTableScreen), whose room the layout keeps whatever it says
    /** GUI pixels: the panel's margins round its words, a line, how far its title's plate stands over its top edge, and the gap under it. */
    static final int PANEL_PAD_X = 13, PANEL_PAD_TOP = 12, PANEL_PAD_BOTTOM = 10, PANEL_LINE = 10, PANEL_RAISED = 6, PANEL_MARGIN = 4;

    // ---- the pixel art: where each piece lies on the sheet (texels)
    private static final ResourceLocation ART = new ResourceLocation(ExplorationRemix.MOD_ID, "textures/gui/rune_table_world.png");
    private static final float SHEET = 256.0F;
    /** The rune circle, 128 texels across from the sheet's corner, its middle at texel 63.5. */
    private static final int SEAL_SIZE = 128;
    private static final float SEAL_MID = 63.5F;
    /**
     * The socket rings, 32 x 32 side by side: the active's, the modifier's and the passive's from (128, 0); the effect
     * socket's, the collect sockets' and the tool's from (128, 96).
     */
    private static final int RING_U = 128, RING_SIZE = 32, RING_V_GEAR = 96;
    /** The gauge's pips, 5 x 5 on the top row: dark, lit, and the glint that runs up them once the phase wakes. */
    private static final int PIP_DARK_U = 224, PIP_LIT_U = 230, PIP_GLINT_U = 236, PIP_SIZE = 5;
    /** The star's four frames (9 x 9, 10 apart), the stroke over a rune to take out (16 x 16), the kind icons (9 x 9, 10 apart). */
    private static final int STAR_U = 128, STAR_V = 40, SLASH_U = 128, SLASH_V = 56, ICON_U = 128, ICON_V = 80;
    /** A socket's texel, in its radius: its ring (32 texels) spans 2.56 radii, its rune's tablet (16) 1.28. */
    private static final float TEXEL = 0.08F, RING_HALF = 16 * TEXEL, TABLET_HALF = 8 * TEXEL;
    /** The pips in the gauge up the active socket's outer side, how far out from its middle they sit (in its radius), and the angle between them. */
    private static final int PIPS = 6;
    private static final float PIP_OUT = 1.45F, PIP_STEP = 20.0F;
    /** Over a collect socket: one pip for each piece of armor (lit for each worn piece carrying its rune), this far apart. */
    private static final float SET_PIP_STEP = 24.0F;
    /** Where the stars twinkle about the circle, in its radii from its middle (right, down). */
    private static final float[][] STAR_SPOTS = {{-1.12F, -0.42F}, {1.08F, -0.55F}, {-0.62F, -1.02F}, {0.7F, -0.98F}, {-1.22F, 0.28F}, {1.2F, 0.18F}};

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
    /** The gear being set (RuneTableMenu's numbers: the hand, then the four pieces), and the tick it was picked. */
    static int picked = RuneTableMenu.HAND;
    static int pickedAt = -100;
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
    static final List<Target> targets = new ArrayList<>();

    // ---- the layout, in GUI pixels, for the screen as it was last drawn
    private static float gw = 480.0F, gh = 270.0F;
    private static float weaponX, weaponY, weaponPx, sealPx, socketPx, risenPx, arcRX, arcRY, socketsY;
    /** Where each socket of the gear shown hangs, by its place among its gear's sockets (Socket.pos). */
    private static final float[] socketX = new float[3], socketY = new float[3];
    /** The gear drawn over the book, and its sockets (kept as the view leaves, so they fade where they were). */
    private static ItemStack shownGear = ItemStack.EMPTY;
    private static Socket[] shownSockets = Socket.WEAPON;
    /** The sets its collect sockets count by ({@link #counted}), kept likewise. */
    private static ArmorSet shownSets = ArmorSet.NONE;

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

    /** The table's view opens (its screen did), showing gear {@code first}. */
    static void open(BlockPos at, int first) {
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
        picked = first;
        targets.clear();
    }

    /**
     * The sets as they would be with the gear shown worn: a piece of armor held in the hand, when it is shown, counted in
     * place of the one worn there, as putting it on would swap them; with anything else shown, {@code worn} itself.
     */
    static ArmorSet counted(RuneTableMenu menu, ArmorSet worn) {
        Piece held = picked == RuneTableMenu.HAND ? menu.heldPiece() : null;
        return held == null ? worn : worn.with(held, RuneGear.armor(menu.hand()));
    }

    /** The screen picked other gear to set: its sockets come in where the last one's were. */
    static void pick(int g) {
        if (g == picked) return;
        picked = g;
        pickedAt = ticks;
        chosen = null;
        hovered = null;
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

    /**
     * The hand, the block outline and the HUD stay out of the view while it has the camera; the chat too, since 0.20.5,
     * as the panel at the foot now spans the corner it would show in (it comes back as the view gives the camera back).
     */
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
        if (active()) e.setCanceled(true);
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
     * under them and the panel allow and clear of the columns at either side; the gear over them, above the book; and
     * the arc the runes rise into, round the top of the gear and clear of the header. Sizes follow the screen's height
     * within bounds the words can live with.
     */
    private static void plan(int sockets) {
        Window w = Minecraft.getInstance().getWindow();
        gw = Math.max(1, w.getGuiScaledWidth());
        gh = Math.max(1, w.getGuiScaledHeight());
        float cx = gw / 2.0F;
        socketPx = Mth.clamp(gh * 0.05F, 12.0F, 20.0F);
        risenPx = socketPx * 0.85F;
        float dip = socketPx * 0.55F;
        float low = Math.min(gh * 0.64F, gh - foot() - NAMES - socketPx * 1.25F);
        float dx = Math.min(Mth.clamp(gh * 0.36F, 100.0F, 170.0F), gw * 0.34F);
        // clear of the tally at the right (the wider side), the outer sockets' names included
        dx = Math.max(socketPx * 3.2F, Math.min(dx, cx - SIDE_RIGHT - socketPx * 1.3F - 24.0F));
        socketsY = low;
        for (int i = 0; i < sockets; i++) {
            float off = i - (sockets - 1) / 2.0F;
            socketX[i] = cx + off * dx;
            socketY[i] = off == 0.0F ? low : low - dip;
        }
        weaponPx = Mth.clamp(gh * 0.21F, 44.0F, 120.0F);
        weaponX = cx;
        weaponY = Math.min(gh * 0.42F, low - dip - socketPx - 8.0F - weaponPx / 2.0F);
        sealPx = weaponPx * 0.98F;
        // close round the weapon, so the runes plainly rise to it; wide, so many fit down its sides
        float inner = weaponPx / 2.0F + risenPx + 6.0F;
        arcRY = Math.max(inner, Math.min(weaponY - TOP_ROOM - 2.0F - risenPx, inner + gh * 0.035F));
        arcRX = Math.max(inner + 4.0F, Math.min(arcRY * 2.0F, Math.min(gw * 0.42F, cx - SIDE_RIGHT - risenPx)));
    }

    /**
     * The lines of words the panel at the foot keeps room for on a screen this high (GUI pixels). Its room never
     * changes with what it says, so nothing on the table moves as the mouse goes from one thing to the next; a longer
     * say grows the panel up over the sockets' names for as long as it lasts.
     */
    static int panelLines(float height) { return height < 255.0F ? 4 : 5; }

    /** The panel's height with so many lines of words (GUI pixels). */
    static int panelHeight(int lines) { return PANEL_PAD_TOP + lines * PANEL_LINE - 1 + PANEL_PAD_BOTTOM; }

    /** The room the panel keeps at the foot: itself, its title's plate standing over its top edge, and a margin above and below. */
    private static float foot() { return panelHeight(panelLines(gh)) + PANEL_RAISED + PANEL_MARGIN + 3.0F; }

    static Vec3 weaponAt(float time) { return place(weaponX, weaponY + gh * 0.008 * Math.sin(time * 0.06), 0.0); }

    static Vec3 socketAt(Socket s) { return place(socketX[s.pos], socketY[s.pos], SOCKET_Z); }

    /** The rune circle's middle: behind the weapon, still while the weapon bobs. */
    private static Vec3 sealAt() { return place(weaponX, weaponY, SEAL_Z); }

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

    /** Lays out what the mouse can pick this frame: the gear's sockets (none without gear), and the runes risen for the chosen one. */
    private static void layout(RuneTableMenu menu, float time) {
        Target was = hovered;
        targets.clear();
        if (shownGear.isEmpty()) chosen = null;
        else {
            for (Socket s : shownSockets) {
                targets.add(new Target(Target.Kind.SOCKET, s, menu.rune(picked, s), 1, socketAt(s), (float) (socketPx * perPx(SOCKET_Z))));
            }
        }
        if (chosen != null && !menu.has(picked, chosen)) chosen = null;
        if (chosen != null) {
            List<Target> risen = new ArrayList<>();
            RuneId in = menu.rune(picked, chosen);
            for (Map.Entry<RuneId, Integer> c : menu.carried(picked, chosen).entrySet()) {
                if (c.getKey() != in) risen.add(new Target(Target.Kind.RUNE, chosen, c.getKey(), c.getValue(), Vec3.ZERO, 0.0F));
            }
            if (in != null) risen.add(new Target(Target.Kind.TAKE_OUT, chosen, in, 0, Vec3.ZERO, 0.0F));
            int n = risen.size();
            float total = measureArc();
            // spaced evenly from the top of the arc down its sides; packed closer, and smaller, if there are many
            float spacing = n > 1 ? Math.min(risenPx * 2.8F, total / (n - 1)) : 0.0F;
            float fit = n > 1 ? Mth.clamp(spacing / (risenPx * 2.3F), 0.6F, 1.0F) : 1.0F;
            float fx = socketX[chosen.pos], fy = socketY[chosen.pos];
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
        if (menu != null) {
            shownGear = menu.gear(picked);
            shownSockets = menu.sockets(picked);
            shownSets = counted(menu, WornRunes.of(mc.player));
            plan(shownSockets.length);
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
        // shade, then light, the circle and the frames; the weapon and the tablets; then what lies over them
        shade(pose, camera, time, shown);
        light(pose, camera, time, shown);
        seal(pose, camera, time, shown);
        frames(pose, camera, time, shown);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        Lighting.setupLevel(pose.last().pose());
        weapon(mc, pose, camera, buffers, time, shown);
        for (Target tg : targets) tablet(pose, camera, buffers, tg, time, shown);
        buffers.endBatch();
        sparkle(pose, camera, time, shown);
        project();
    }

    /** The gear being set, over the book, turning a little this way and that, bobbing; it grows in again when picked. */
    private static void weapon(Minecraft mc, PoseStack pose, Camera camera, MultiBufferSource buffers, float time, float shown) {
        ItemStack w = shownGear;
        if (w.isEmpty()) return;
        Vec3 p = weaponAt(time).subtract(cam);
        pose.pushPose();
        pose.translate(p.x, p.y, p.z);
        pose.mulPose(camera.rotation());
        pose.mulPose(Axis.YP.rotationDegrees((float) (28.0 * Math.sin(time * 0.035))));
        // the item spans one block: scaled to the layout's size for it
        float picked = Mth.clamp((time - pickedAt) / 6.0F, 0.0F, 1.0F);
        float s = (float) (weaponPx * perPx(0.0)) * (0.6F + 0.4F * shown) * (0.7F + 0.3F * (1.0F - (1.0F - picked) * (1.0F - picked)));
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

    /** A rune's tablet, facing the eye, full bright, in its ring's slot: in a socket, risen over the weapon, or (dimmed) to take out. */
    private static void tablet(PoseStack pose, Camera camera, MultiBufferSource buffers, Target tg, float time, float shown) {
        if (tg.rune == null) return;
        float half = (float) (tg.radius * TABLET_HALF * grow(tg, shown) * lift(tg));
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
        Vec3 middle = place(gw / 2.0, (weaponY + socketsY) / 2.0, 0.0);
        g.vignette(middle, gw * 0.62 * k, gh * 0.75 * k, SHADE, 0.16F * shown, 0.42F * shown);
        g.soft(sealAt(), (float) (sealPx * 1.1 * perPx(SEAL_Z)), SHADE, 0.3F * shown, 0.55F);
        g.soft(weaponAt(time), (float) (weaponPx * 0.8 * k), SHADE, 0.45F * shown, 0.4F);
        for (Target tg : targets) g.soft(tg.at, tg.radius * 2.1F, SHADE, (tg.kind == Target.Kind.SOCKET ? 0.62F : 0.55F) * shown, 0.5F);
        g.draw(false);
    }

    /** Every halo, thread and soft ring of light, added onto the picture in one go (the frames are drawn over it). */
    private static void light(PoseStack pose, Camera camera, float time, float shown) {
        Glow g = new Glow(pose.last().pose(), camera);
        Vec3 w = weaponAt(time);
        float wr = (float) (weaponPx * perPx(0.0));
        float pulse = 0.5F + 0.5F * Mth.sin(time * 0.12F);
        // the weapon's halo, and a wide faint one round everything
        g.disc(w, wr * 0.8F, ACTIVE, 0.28F * shown);
        g.disc(w, wr * 0.38F, CORE, 0.22F * shown);
        g.disc(place(gw / 2.0, (weaponY + socketsY) / 2.0, 0.0), (float) (gh * 0.55 * perPx(0.0)), DEEP, 0.12F * shown);
        int level = Minecraft.getInstance().player.experienceLevel;
        ArmorSet set = shownSets;
        for (Target tg : targets) {
            boolean hover = tg.same(hovered);
            int colour = colour(tg.socket);
            float r = tg.radius;
            if (tg.kind == Target.Kind.SOCKET) {
                Vec3 c = tg.at;
                boolean filled = tg.rune != null, isChosen = chosen == tg.socket;
                float flash = flashed == tg.socket ? Mth.clamp(1.0F - (time - flashedAt) / 14.0F, 0.0F, 1.0F) : 0.0F;
                g.disc(c, r * (filled ? 1.6F : 1.25F), colour, (filled ? 0.32F : 0.16F) * shown + (hover ? 0.12F : 0.0F) + 0.3F * flash);
                if (isChosen) g.ring(c, r * (1.34F + 0.05F * pulse), r * 0.1F, colour, (0.45F + 0.2F * pulse) * shown);
                if (flash > 0.0F) g.ring(c, r * (1.1F + 1.6F * (1.0F - flash)), r * 0.2F, CORE, flash);
                // a thread of light from a filled socket's ring up to the weapon, a mote running up it
                if (filled) {
                    Vec3 dir = w.subtract(c).normalize();
                    Vec3 from = c.add(dir.scale(r * 1.22)), to = w.subtract(dir.scale(wr * 0.3));
                    g.line(from, to, r * 0.08F, colour, 0.35F * shown);
                    float k = (time * 0.025F + tg.socket.pos * 0.33F) % 1.0F;
                    g.disc(from.add(to.subtract(from).scale(k)), r * 0.23F, CORE, 0.8F * shown * Mth.sin(k * Mth.PI));
                }
                // the active rune's second phase awake: a wide glow round its socket
                if (tg.socket == Socket.ACTIVE && tg.rune != null && level >= tg.rune.phaseLevel) {
                    g.disc(c, r * 2.2F, PHASE, (0.1F + 0.06F * pulse) * shown);
                }
                // a collect rune awake on three pieces: the same glow, brighter for a full set
                if (tg.socket.kind == RuneKind.COLLECT && tg.rune != null && set.awake(tg.rune)) {
                    g.disc(c, r * 2.2F, COLLECT, ((set.full(tg.rune) ? 0.16F : 0.1F) + 0.06F * pulse) * shown);
                }
            } else {
                boolean out = tg.kind == Target.Kind.TAKE_OUT;
                g.disc(tg.at, r * (hover ? 2.0F : 1.6F), colour, (hover ? 0.4F : out ? 0.1F : 0.2F) * shown);
            }
        }
        g.draw(true);
    }

    /** How far a socket's ring and tablet have grown in as the view settles: from a little over half their size. Risen runes grow as they rise. */
    private static float grow(Target tg, float shown) { return tg.kind == Target.Kind.SOCKET ? 0.55F + 0.45F * shown : 1.0F; }

    /** A little larger under the mouse. */
    private static float lift(Target tg) { return tg.same(hovered) ? (tg.kind == Target.Kind.SOCKET ? 1.06F : 1.08F) : 1.0F; }

    /** How bright the latest flash is now (a socket just set or emptied): 1 as it happens, fading over {@code ticks}. */
    private static float flash(float time, float ticks) { return Mth.clamp(1.0F - (time - flashedAt) / ticks, 0.0F, 1.0F); }

    /**
     * The rune circle behind the weapon, added onto the picture: light runs slowly round it, it brightens while a socket
     * is chosen and flares as a rune goes in or out; and stars twinkle about it.
     */
    private static void seal(PoseStack pose, Camera camera, float time, float shown) {
        Sprites s = new Sprites(pose.last().pose(), camera);
        double k = perPx(SEAL_Z);
        double rad = sealPx * k * (0.85 + 0.15 * shown);
        float base = (0.34F + (chosen != null ? 0.08F : 0.0F) + 0.25F * flash(time, 20.0F)) * shown;
        // round clockwise as seen, once in about sixteen seconds
        double sweep = -time * 0.02;
        float run = 0.5F * shown;
        s.seal(sealAt(), rad, base, a -> base + run * Math.pow(Math.max(0.0, Math.cos(a - sweep)), 10));
        double texel = sealPx * 2.0 / SEAL_SIZE * k;
        for (int i = 0; i < STAR_SPOTS.length; i++) {
            float tw = Mth.sin(time * (0.05F + 0.011F * i) + i * 2.1F);
            if (tw <= 0.0F) continue;
            int frame = Math.min(3, (int) (tw * 4.0F));
            int col = i % 3 == 0 ? ACTIVE : i % 3 == 1 ? MODIFIER : CORE;
            Vec3 at = place(weaponX + STAR_SPOTS[i][0] * sealPx, weaponY + STAR_SPOTS[i][1] * sealPx, SEAL_Z);
            s.piece(at, 4.5 * texel, STAR_U + 10 * frame, STAR_V, 9, 9, col, (0.35F + 0.65F * tw) * shown);
        }
        s.draw(true);
    }

    /**
     * The frames, laid over the picture: each socket's ring (an empty one showing faintly what goes in it), the rings of
     * the risen runes, and up the active socket's outer side the gauge of pips toward its rune's second phase.
     */
    private static void frames(PoseStack pose, Camera camera, float time, float shown) {
        Sprites s = new Sprites(pose.last().pose(), camera);
        int level = Minecraft.getInstance().player.experienceLevel;
        ArmorSet set = shownSets;
        for (Target tg : targets) {
            double r = tg.radius * grow(tg, shown) * lift(tg);
            int ru = ringU(tg.socket), rv = ringV(tg.socket);
            if (tg.kind == Target.Kind.SOCKET) {
                s.piece(tg.at, r * RING_HALF, ru, rv, RING_SIZE, RING_SIZE, 0xFFFFFF, shown);
                if (tg.rune == null) {
                    s.piece(tg.at, r * TEXEL * 4.5, ICON_U + 10 * iconIndex(tg.socket), ICON_V, 9, 9, 0xFFFFFF, 0.45F * shown);
                } else if (tg.socket == Socket.ACTIVE) {
                    float fill = gauge(tg.rune, level);
                    for (int p = 0; p < PIPS; p++) {
                        s.piece(pipAt(s, tg, shown, p), r * TEXEL * 2.5, p + 1 <= fill ? PIP_LIT_U : PIP_DARK_U, 0, PIP_SIZE, PIP_SIZE, 0xFFFFFF, shown);
                    }
                } else if (tg.socket.kind == RuneKind.COLLECT) {
                    int count = set.count(tg.rune);
                    for (int p = 0; p < ArmorSet.FULL; p++) {
                        s.piece(setPipAt(s, tg, shown, p), r * TEXEL * 2.5, p < count ? PIP_LIT_U : PIP_DARK_U, 0, PIP_SIZE, PIP_SIZE, 0xFFFFFF, shown);
                    }
                }
            } else {
                boolean out = tg.kind == Target.Kind.TAKE_OUT;
                s.piece(tg.at, r * RING_HALF, ru, rv, RING_SIZE, RING_SIZE, out ? 0x9AA3B5 : 0xFFFFFF, (out ? 0.8F : 1.0F) * shown);
            }
        }
        s.draw(false);
    }

    /**
     * Over the weapon and the tablets: the stroke across a rune to take out; then, added on, light on the ring the mouse
     * is over and on the chosen socket's, the gauge's next pip warming as the XP toward it grows, and once the phase is
     * awake a glint running up the gauge now and then.
     */
    private static void sparkle(PoseStack pose, Camera camera, float time, float shown) {
        Sprites over = new Sprites(pose.last().pose(), camera);
        for (Target tg : targets) {
            if (tg.kind == Target.Kind.TAKE_OUT) over.piece(tg.at, tg.radius * lift(tg) * 0.72, SLASH_U, SLASH_V, 16, 16, 0xFFFFFF, shown);
        }
        over.draw(false);
        Sprites add = new Sprites(pose.last().pose(), camera);
        float pulse = 0.5F + 0.5F * Mth.sin(time * 0.12F);
        int level = Minecraft.getInstance().player.experienceLevel;
        ArmorSet set = shownSets;
        for (Target tg : targets) {
            double r = tg.radius * grow(tg, shown) * lift(tg);
            float light = tg.same(hovered) ? 0.32F : 0.0F;
            if (tg.kind == Target.Kind.SOCKET && chosen == tg.socket) light = Math.max(light, 0.1F + 0.14F * pulse);
            add.piece(tg.at, r * RING_HALF, ringU(tg.socket), ringV(tg.socket), RING_SIZE, RING_SIZE, 0xFFFFFF, light * shown);
            if (tg.kind != Target.Kind.SOCKET || tg.rune == null) continue;
            if (tg.socket.kind == RuneKind.COLLECT) {
                // awake on three pieces: a glint runs over its pips now and then
                if (!set.awake(tg.rune)) continue;
                float at = (time * 0.12F + tg.socket.pos * 3.0F) % 8.0F;
                for (int p = 0; p < ArmorSet.FULL; p++) {
                    float glint = 1.0F - Math.abs(at - p);
                    if (glint > 0.0F && p < set.count(tg.rune)) {
                        add.piece(setPipAt(add, tg, shown, p), r * TEXEL * 3.0, PIP_GLINT_U, 0, PIP_SIZE, PIP_SIZE, 0xFFFFFF, glint * 0.9F * shown);
                    }
                }
                continue;
            }
            if (tg.socket != Socket.ACTIVE) continue;
            float fill = gauge(tg.rune, level);
            double half = r * TEXEL * 2.5;
            if (fill < PIPS) {
                int next = (int) fill;
                add.piece(pipAt(add, tg, shown, next), half, PIP_LIT_U, 0, PIP_SIZE, PIP_SIZE, 0xFFFFFF, (fill - next) * (0.3F + 0.3F * pulse) * shown);
            } else {
                float at = (time * 0.12F) % 10.0F;
                for (int p = 0; p < PIPS; p++) {
                    float glint = 1.0F - Math.abs(at - p);
                    if (glint > 0.0F) add.piece(pipAt(add, tg, shown, p), half * 1.2, PIP_GLINT_U, 0, PIP_SIZE, PIP_SIZE, 0xFFFFFF, glint * 0.9F * shown);
                }
            }
        }
        add.draw(true);
    }

    /** How far the active rune's gauge is filled, in pips (0 to {@link #PIPS}): the player's XP level toward its second phase's. */
    private static float gauge(RuneId rune, int level) { return Mth.clamp(level / (float) rune.phaseLevel, 0.0F, 1.0F) * PIPS; }

    /** Where the gauge's pip {@code p} sits round the socket: up its outer (left) side, filling from below. */
    private static Vec3 pipAt(Sprites s, Target tg, float shown, int p) {
        double th = Math.toRadians(-140.0 + p * PIP_STEP);
        double rad = tg.radius * grow(tg, shown) * lift(tg) * PIP_OUT;
        return tg.at.add(s.r.scale(Math.sin(th) * rad)).add(s.u.scale(Math.cos(th) * rad));
    }

    /** Where a collect socket's pip {@code p} sits: in an arc over the top of its ring, one for each piece of armor. */
    private static Vec3 setPipAt(Sprites s, Target tg, float shown, int p) {
        double th = Math.toRadians((p - (ArmorSet.FULL - 1) / 2.0) * SET_PIP_STEP);
        double rad = tg.radius * grow(tg, shown) * lift(tg) * PIP_OUT;
        return tg.at.add(s.r.scale(Math.sin(th) * rad)).add(s.u.scale(Math.cos(th) * rad));
    }

    static int colour(Socket s) {
        return switch (s) {
            case ACTIVE -> ACTIVE;
            case MODIFIER -> MODIFIER;
            case PASSIVE -> PASSIVE;
            case EFFECT -> EFFECT;
            case COLLECT_1, COLLECT_2 -> COLLECT;
            case TOOL -> TOOL;
        };
    }

    /** A socket's ring on the sheet: the weapon's three from (128, 0), then the effect, collect and tool rings from (128, 96). */
    private static int ringU(Socket s) {
        int i = switch (s) {
            case ACTIVE, EFFECT -> 0;
            case MODIFIER, COLLECT_1, COLLECT_2 -> 1;
            case PASSIVE, TOOL -> 2;
        };
        return RING_U + RING_SIZE * i;
    }

    private static int ringV(Socket s) { return s.kind.weapon() ? 0 : RING_V_GEAR; }

    /** A socket's kind icon, on either sheet: the weapon's three kinds 0 to 2, effect 4, collect 5, tool 6 (3 is the second phase's mark). */
    static int iconIndex(Socket s) {
        return switch (s) {
            case ACTIVE -> 0;
            case MODIFIER -> 1;
            case PASSIVE -> 2;
            case EFFECT -> 4;
            case COLLECT_1, COLLECT_2 -> 5;
            case TOOL -> 6;
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

    // ------------------------------------------------------------------------------------------------ pixel art

    /**
     * Pieces of the pixel-art sheet in the plane facing the eye, as Glow lays its light: over the picture, or added onto
     * it. The sheet is drawn without blur, so its texels stay square and sharp.
     */
    private static final class Sprites {
        private static final int SEGMENTS = 48;
        private final Matrix4f m;
        final Vec3 r, u;
        private final BufferBuilder b;

        Sprites(Matrix4f m, Camera camera) {
            this.m = m;
            Vector3f left = camera.getLeftVector(), above = camera.getUpVector();
            this.r = new Vec3(-left.x(), -left.y(), -left.z());
            this.u = new Vec3(above.x(), above.y(), above.z());
            this.b = Tesselator.getInstance().getBuilder();
            b.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
        }

        private void v(Vec3 p, float tu, float tv, int rgb, float a) {
            b.vertex(m, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z)).uv(tu / SHEET, tv / SHEET)
                    .color(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, Mth.clamp(Math.round(a * 255.0F), 0, 255)).endVertex();
        }

        /** The piece at texel (x, y), w x h, centred on {@code c} and {@code half} wide either side of it in the world, its texels square. */
        void piece(Vec3 c, double half, int x, int y, int w, int h, int rgb, float a) {
            if (a <= 0.004F || half <= 0.0) return;
            Vec3 across = r.scale(half), down = u.scale(-half * h / w);
            Vec3 tl = c.subtract(across).subtract(down), tr = c.add(across).subtract(down);
            Vec3 br = c.add(across).add(down), bl = c.subtract(across).add(down);
            v(tl, x, y, rgb, a);
            v(bl, x, y + h, rgb, a);
            v(br, x + w, y + h, rgb, a);
            v(tl, x, y, rgb, a);
            v(br, x + w, y + h, rgb, a);
            v(tr, x + w, y, rgb, a);
        }

        /**
         * The rune circle, as a fan from its middle so its light can vary round it: {@code mid} at the middle, and at the
         * rim what {@code rim} gives for the angle there (radians, anticlockwise from the right, as seen).
         */
        void seal(Vec3 c, double rad, float mid, java.util.function.DoubleUnaryOperator rim) {
            if (rad <= 0.0) return;
            float k = SEAL_SIZE / 2.0F;
            for (int i = 0; i < SEGMENTS; i++) {
                double a0 = Math.PI * 2 * i / SEGMENTS, a1 = Math.PI * 2 * (i + 1) / SEGMENTS;
                float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
                v(c, SEAL_MID, SEAL_MID, 0xFFFFFF, mid);
                v(c.add(r.scale(c0 * rad)).add(u.scale(s0 * rad)), SEAL_MID + c0 * k, SEAL_MID - s0 * k, 0xFFFFFF, (float) rim.applyAsDouble(a0));
                v(c.add(r.scale(c1 * rad)).add(u.scale(s1 * rad)), SEAL_MID + c1 * k, SEAL_MID - s1 * k, 0xFFFFFF, (float) rim.applyAsDouble(a1));
            }
        }

        /** Added onto the picture, or laid over it. */
        void draw(boolean add) {
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            RenderSystem.setShaderTexture(0, ART);
            RenderSystem.enableBlend();
            if (add) RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
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
