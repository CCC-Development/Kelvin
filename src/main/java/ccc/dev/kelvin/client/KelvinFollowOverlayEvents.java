package ccc.dev.kelvin.client;

import ccc.dev.kelvin.CccKelvinMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = CccKelvinMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class KelvinFollowOverlayEvents {
    private KelvinFollowOverlayEvents() {}

    @SubscribeEvent
    public static void afterHotbar(RenderGuiOverlayEvent.Post event) {
        if (!event.getOverlay().id().equals(VanillaGuiOverlay.HOTBAR.id())) {
            return;
        }
        GuiGraphics g = event.getGuiGraphics();
        KelvinSummonerHotbarGlow.renderIfSummonerSelected(g);
        KelvinProtectHud.renderAboveHotbar(g);
        KelvinFollowHud.renderAboveHotbar(g);
        KelvinSummonerHud.renderAboveHotbar(g);
        KelvinGoMineHud.renderAboveHotbar(g);
    }
}
