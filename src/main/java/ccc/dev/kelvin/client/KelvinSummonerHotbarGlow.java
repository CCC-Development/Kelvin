package ccc.dev.kelvin.client;

import ccc.dev.kelvin.ModItems;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.Util;
import net.minecraft.util.Mth;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * When the Kelvin summoner is the selected hotbar stack, tints the selected slot interior (same
 * grid as {@code Gui#renderHotbar}) — no outer border.
 */
@OnlyIn(Dist.CLIENT)
public final class KelvinSummonerHotbarGlow {
    private static final int HOTBAR_HALF_SPAN = 91;
    private static final int HOTBAR_TOP_OFFSET = 22;
    private static final int SLOT_STEP = 20;
    /** Slot cell size; matches vanilla hotbar slot grid. */
    private static final int SLOT_W = 20;
    private static final int SLOT_H = 22;
    /** Inset so the tint stays inside the slot frame (thinner than full highlight sprite). */
    private static final int INSET = 2;
    /** User-specified tint: #689926 */
    private static final int TINT_RGB = 0x689926;
    private static final int ALPHA_MIN = 52;
    private static final int ALPHA_MAX = 88;

    private KelvinSummonerHotbarGlow() {}

    public static void renderIfSummonerSelected(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) {
            return;
        }
        Player player = mc.player;
        ItemStack selected = player.getInventory().getItem(player.getInventory().selected);
        if (!selected.is(ModItems.KELVIN_SUMMONER.get())) {
            return;
        }

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int slot = player.getInventory().selected;
        int slotLeft = sw / 2 - HOTBAR_HALF_SPAN + slot * SLOT_STEP;
        int slotTop = sh - HOTBAR_TOP_OFFSET;

        int x1 = slotLeft + INSET;
        int y1 = slotTop + INSET;
        int x2 = slotLeft + SLOT_W - INSET;
        int y2 = slotTop + SLOT_H - INSET;

        float t = 0.5F + 0.5F * (float) Math.sin(Util.getMillis() / 280.0);
        int alpha = (int) Mth.lerp(t, ALPHA_MIN, ALPHA_MAX);
        alpha = Mth.clamp(alpha, 0, 255);
        int color = (alpha << 24) | TINT_RGB;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.fill(x1, y1, x2, y2, color);
        RenderSystem.disableBlend();
    }
}
