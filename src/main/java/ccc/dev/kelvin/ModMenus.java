package ccc.dev.kelvin;

import ccc.dev.kelvin.world.inventory.KelvinInventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModMenus {
    private ModMenus() {}

    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, CccKelvinMod.MOD_ID);

    public static final RegistryObject<MenuType<KelvinInventoryMenu>> KELVIN_INVENTORY = MENU_TYPES.register("kelvin_inventory",
            () -> IForgeMenuType.create(KelvinInventoryMenu::fromNetwork));
}
