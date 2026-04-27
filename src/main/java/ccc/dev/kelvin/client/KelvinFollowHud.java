package ccc.dev.kelvin.client;

import ccc.dev.kelvin.entity.KelvinEntity;
import com.mojang.blaze3d.systems.RenderSystem;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * While Kelvin is following the local player, shows a persistent green line above the hotbar plus orange bold
 * distance. A timed fade applies only to the “stopped following” notice.
 */
@OnlyIn(Dist.CLIENT)
public final class KelvinFollowHud {
    private static final long STOP_DISPLAY_MS = 5000L;
    private static final long STOP_FADE_MS = 1500L;
    private static final int COLOR_GREEN = 0xFF55FF55;

    private static boolean followingActive;
    private static int followingKelvinEntityId = -1;
    private static @Nullable Component stopLine;
    private static long stopHideAtMs;

    private KelvinFollowHud() {}

    /** Called from network handler on game thread (client only). */
    public static void onStatusFromServer(byte status, int kelvinEntityId) {
        if (status == ccc.dev.kelvin.network.KelvinFollowStatusPacket.STATUS_FOLLOWING) {
            followingActive = true;
            followingKelvinEntityId = kelvinEntityId;
            stopLine = null;
        } else {
            followingActive = false;
            followingKelvinEntityId = -1;
            stopLine = Component.literal("Kelvin has stopped following you.");
            stopHideAtMs = Util.getMillis() + STOP_DISPLAY_MS;
        }
    }

    public static void renderAboveHotbar(GuiGraphics guiGraphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        if (followingActive) {
            renderFollowingLine(guiGraphics, mc);
            return;
        }

        if (stopLine == null) {
            return;
        }
        long now = Util.getMillis();
        if (now >= stopHideAtMs) {
            stopLine = null;
            return;
        }
        long fadeStart = stopHideAtMs - STOP_FADE_MS;
        float alpha = now < fadeStart ? 1.0F : (stopHideAtMs - now) / (float) STOP_FADE_MS;
        alpha = Mth.clamp(alpha, 0.0F, 1.0F);

        int sw = mc.font.width(stopLine);
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int x = (screenW - sw) / 2;
        int y = screenH - 46;
        int a = (int) (alpha * 255.0F) << 24;
        int color = a | 0xFF5555;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        guiGraphics.drawString(mc.font, stopLine, x, y, color, false);
        RenderSystem.disableBlend();
    }

    private static void renderFollowingLine(GuiGraphics guiGraphics, Minecraft mc) {
        String base = "Kelvin is following you.";
        String distText = resolveFollowDistanceText(mc);
        MutableComponent distPart =
                Component.literal(" [Distance: " + distText + "m]")
                        .withStyle(
                                Style.EMPTY.withBold(true).withColor(TextColor.fromRgb(0xFF9800)));

        int w1 = mc.font.width(base);
        int w2 = mc.font.width(distPart);
        int total = w1 + w2;
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int x = (screenW - total) / 2;
        int y = screenH - 46;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        guiGraphics.drawString(mc.font, base, x, y, COLOR_GREEN, false);
        guiGraphics.drawString(mc.font, distPart, x + w1, y, 0xFFFFFFFF, false);
        RenderSystem.disableBlend();
    }

    private static String resolveFollowDistanceText(Minecraft mc) {
        Entity e = mc.level.getEntity(followingKelvinEntityId);
        if (!(e instanceof KelvinEntity kelvin) || !kelvin.isAlive() || kelvin.isRemoved()) {
            return "\u2014";
        }
        return Integer.toString(Mth.floor(mc.player.distanceTo(kelvin)));
    }
}

