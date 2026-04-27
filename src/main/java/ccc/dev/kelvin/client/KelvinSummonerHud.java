package ccc.dev.kelvin.client;

import ccc.dev.kelvin.KelvinSummonerWorldData;
import com.mojang.blaze3d.systems.RenderSystem;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Orange hotbar-area hint when the Kelvin Summoner reports a downed or dead Kelvin's coordinates. */
@OnlyIn(Dist.CLIENT)
public final class KelvinSummonerHud {
    private static final long DISPLAY_MS = 6000L;
    private static final long FADE_MS = 1500L;
    /** ARGB base (orange); alpha applied in render. */
    private static final int BASE_ORANGE = 0xFFAA00;

    private static @Nullable Component line;
    private static long hideAtMs;

    private KelvinSummonerHud() {}

    public static void onRevivalHintFromServer(BlockPos pos, ResourceLocation dimension) {
        String dim = KelvinSummonerWorldData.describeDimension(dimension);
        line = Component.literal(
                "Kelvin is in " + dim + " at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                        + " — he needs to be revived.");
        hideAtMs = Util.getMillis() + DISPLAY_MS;
    }

    public static void renderAboveHotbar(GuiGraphics guiGraphics) {
        if (line == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        long now = Util.getMillis();
        if (now >= hideAtMs) {
            line = null;
            return;
        }
        long fadeStart = hideAtMs - FADE_MS;
        float alpha = now < fadeStart ? 1.0F : (hideAtMs - now) / (float) FADE_MS;
        alpha = Mth.clamp(alpha, 0.0F, 1.0F);

        int sw = mc.font.width(line);
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int x = (screenW - sw) / 2;
        int y = screenH - 59;
        int a = (int) (alpha * 255.0F) << 24;
        int color = a | (BASE_ORANGE & 0xFFFFFF);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        guiGraphics.drawString(mc.font, line, x, y, color, false);
        RenderSystem.disableBlend();
    }
}
