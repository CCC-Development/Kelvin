package ccc.dev.kelvin.network;

import ccc.dev.kelvin.CccKelvinMod;
import ccc.dev.kelvin.event.KelvinGoMineEvents;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod.EventBusSubscriber(modid = CccKelvinMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ModNetworkSetup {
    private ModNetworkSetup() {}

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            ModNetwork.register();
            // Forge @SubscribeEvent has no receiveCancelled; this runs even if LeftClickBlock was cancelled.
            MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, KelvinGoMineEvents::onLeftClickBlock);
        });
    }
}
