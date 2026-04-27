package ccc.dev.kelvin;

import com.mrcrayfish.vehicle.VehicleMod;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(CccKelvinMod.MOD_ID)
public final class CccKelvinMod {
    public static final String MOD_ID = "ccc_kelvin";

    public CccKelvinMod() {
        var bus = FMLJavaModLoadingContext.get().getModEventBus();
        ModEntities.ENTITY_TYPES.register(bus);
        ModItems.ITEMS.register(bus);
        ModMenus.MENU_TYPES.register(bus);
        new VehicleMod();
    }
}
