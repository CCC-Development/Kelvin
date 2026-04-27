package ccc.dev.kelvin.client;

import ccc.dev.kelvin.CccKelvinMod;
import ccc.dev.kelvin.item.KelvinSummonerItem;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = CccKelvinMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class KelvinGoMineClientForgeEvents {
    private KelvinGoMineClientForgeEvents() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!event.getLevel().isClientSide() || event.getEntity() != Minecraft.getInstance().player) {
            return;
        }
        if (KelvinSummonerItem.shouldCancelClientSummonerPunchOnStorage(
                event.getEntity(), event.getLevel(), event.getPos())) {
            event.setCanceled(true);
        }
    }
}
