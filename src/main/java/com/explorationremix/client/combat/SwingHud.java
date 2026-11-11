package com.explorationremix.client.combat;

import com.explorationremix.combat.swing.SwingKind;
import com.explorationremix.combat.swing.SwingMachine;
import com.explorationremix.combat.swing.WeaponProfile;
import com.explorationremix.combat.swing.Weapons;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraftforge.client.gui.overlay.ForgeGui;

/**
 * The swings' marks under the crosshair, where vanilla's attack indicator was: one small pip per light swing of the
 * chain, lit as each lands; then the cooldown's bar draining away. After the chain's last light swing the bar is pale
 * blue with a bright point at its left end (a strong attack can still be made: hold the button); after a strong attack
 * it is grey (nothing can start until it runs out), and a press in it makes it blink. Shown only while a chain or a
 * cooldown is under way, in first person.
 */
final class SwingHud {
    private static final int PIP = 3, PIP_GAP = 2, BAR_W = 16, BAR_H = 2;
    private static final int LIT = 0xFFE4F1FF, UNLIT = 0x70E4F1FF, SHADOW = 0x90000000;
    private static final int OPEN_BAR = 0xFFB4D6FF, OPEN_BACK = 0x60324A66, OPEN_POINT = 0xFFFFFFFF;
    private static final int SHUT_BAR = 0xFF9AA2AC, SHUT_BACK = 0x60202428;
    /** How long a refused press makes the bar blink (ticks). */
    private static final float BLINK = 8.0F;

    private SwingHud() {}

    static void render(ForgeGui gui, GuiGraphics g, float partialTick, int width, int height) {
        Minecraft mc = gui.getMinecraft();
        if (mc.options.hideGui || mc.screen != null || !mc.options.getCameraType().isFirstPerson()) return;
        LocalPlayer p = mc.player;
        if (p == null || p.isSpectator()) return;
        WeaponProfile w = Weapons.profile(p.getMainHandItem());
        if (w == null) return;
        SwingMachine m = SwingClient.machine();
        float now = SwingClient.now(partialTick);
        float sinceRefused = now - SwingClient.refusedAt;
        boolean chainOn = m.chain() > 0 || (m.busy() && m.kind() == SwingKind.LIGHT && m.phase() != SwingMachine.Phase.IDLE && m.combo() > 0);
        if (m.cooldown() <= 0 && !chainOn && sinceRefused > BLINK) return;

        int cx = width / 2, y = height / 2 - 7 + 16;
        if (m.cooldown() > 0) {
            float left = Mth.clamp((m.cooldown() - partialTick) / Math.max(1.0F, m.cooldownTotal()), 0.0F, 1.0F);
            boolean open = !m.cooldownFromStrong();
            int x0 = cx - BAR_W / 2;
            int len = Math.round(BAR_W * left);
            boolean blink = sinceRefused <= BLINK && ((int) (sinceRefused / 2.0F)) % 2 == 0;
            g.fill(x0 - 1, y - 1, x0 + BAR_W + 1, y + BAR_H + 1, SHADOW);
            g.fill(x0, y, x0 + BAR_W, y + BAR_H, open ? OPEN_BACK : SHUT_BACK);
            g.fill(x0, y, x0 + len, y + BAR_H, blink ? 0xFFFFFFFF : open ? OPEN_BAR : SHUT_BAR);
            if (open) {
                // the point: a strong attack is still to be had
                g.fill(x0 - 3, y - 1, x0 - 1, y + BAR_H + 1, OPEN_POINT);
            }
            return;
        }
        // the chain's pips: one per light swing, lit as each lands
        int n = w.chain();
        int total = n * PIP + (n - 1) * PIP_GAP;
        int x = cx - total / 2;
        int landed = Math.min(n, m.chain());
        for (int i = 0; i < n; i++) {
            int px = x + i * (PIP + PIP_GAP);
            g.fill(px - 1, y - 1, px + PIP + 1, y + PIP + 1, SHADOW);
            g.fill(px, y, px + PIP, y + PIP, i < landed ? LIT : UNLIT);
        }
    }
}
