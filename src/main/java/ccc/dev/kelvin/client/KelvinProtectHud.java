package ccc.dev.kelvin.client;

import ccc.dev.kelvin.entity.KelvinEntity;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * While a nearby Kelvin has protect mode on for the local player, draws a persistent green line above the hotbar.
 */
@OnlyIn(Dist.CLIENT)
public final class KelvinProtectHud {
    private static final int COLOR_GREEN = 0xFF55FF55;
    private static final double SCAN_RADIUS = 72.0D;

    private KelvinProtectHud() {}

    public static void renderAboveHotbar(GuiGraphics guiGraphics) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        if (!isProtectedByNearbyKelvin(player)) {
            return;
        }
        Component line = Component.literal("Kelvin is protecting you.");
        int sw = mc.font.width(line);
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        int x = (screenW - sw) / 2;
        int y = screenH - 68;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        guiGraphics.drawString(mc.font, line, x, y, COLOR_GREEN, false);
        RenderSystem.disableBlend();
    }

    private static boolean isProtectedByNearbyKelvin(Player player) {
        for (KelvinEntity kelvin : player.level().getEntitiesOfClass(KelvinEntity.class, player.getBoundingBox().inflate(SCAN_RADIUS))) {
            if (!kelvin.isAlive() || kelvin.isRemoved() || kelvin.isDowned()) {
                continue;
            }
            if (!kelvin.isProtectOwner()) {
                continue;
            }
            if (kelvin.getProtectPatronId().filter(id -> id.equals(player.getUUID())).isPresent()) {
                return true;
            }
        }
        return false;
    }
}
