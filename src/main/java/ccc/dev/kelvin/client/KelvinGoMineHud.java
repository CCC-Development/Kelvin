package ccc.dev.kelvin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class KelvinGoMineHud {
    private static boolean active;
    private static int collectedTotal;
    @Nullable
    private static BlockPos highlightChest;
    @Nullable
    private static MutableComponent targetLabel;

    private KelvinGoMineHud() {}

    public static void applyFromServer(boolean act, int total, @Nullable BlockPos chest, @Nullable Block block) {
        active = act;
        collectedTotal = total;
        highlightChest = chest;
        if (block != null) {
            targetLabel = Component.translatable(block.getDescriptionId());
        } else {
            targetLabel = null;
        }
        if (!active) {
            highlightChest = null;
            targetLabel = null;
        }
    }

    @Nullable
    public static BlockPos getHighlightChest() {
        return active ? highlightChest : null;
    }

    public static void renderAboveHotbar(GuiGraphics g) {
        if (!active || targetLabel == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        MutableComponent line =
                Component.translatable("gui.ccc_kelvin.go_mine_hud", targetLabel, collectedTotal);
        int w = mc.font.width(line);
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int x = (sw - w) / 2;
        int y = sh - 59;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.drawString(mc.font, line, x, y, 0xFFEEDD22, false);
        RenderSystem.disableBlend();
    }
}
