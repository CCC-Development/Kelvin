package ccc.dev.kelvin;

import ccc.dev.kelvin.item.KelvinSummonerItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    private ModItems() {}

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, CccKelvinMod.MOD_ID);

    public static final RegistryObject<Item> KELVIN_SUMMONER = ITEMS.register("kelvin_summoner",
            () -> new KelvinSummonerItem(new Item.Properties()));
}
