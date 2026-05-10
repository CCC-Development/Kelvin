package ccc.dev.kelvin;

import ccc.dev.kelvin.config.KelvinModConfig;
import com.mrcrayfish.vehicle.VehicleMod;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(CccKelvinMod.MOD_ID)
public final class CccKelvinMod {
    public static final String MOD_ID = "ccc_kelvin";

    public CccKelvinMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, KelvinModConfig.SPEC);
        var bus = FMLJavaModLoadingContext.get().getModEventBus();
        ModEntities.ENTITY_TYPES.register(bus);
        ModItems.ITEMS.register(bus);
        ModMenus.MENU_TYPES.register(bus);
        new VehicleMod();
    }
}
