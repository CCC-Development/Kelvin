package com.mrcrayfish.vehicle.init;

import com.mrcrayfish.vehicle.Reference;
import com.mrcrayfish.vehicle.entity.EngineTier;
import com.mrcrayfish.vehicle.entity.EngineType;
import com.mrcrayfish.vehicle.entity.WheelType;
import com.mrcrayfish.vehicle.item.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.function.Supplier;

/**
 * Author: MrCrayfish
 */
public class ModItems
{
    public static final DeferredRegister<Item> REGISTER = DeferredRegister.create(ForgeRegistries.Keys.ITEMS, Reference.MOD_ID);

    public static final RegistryObject<Item> PANEL = register("panel", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> STANDARD_WHEEL = register("standard_wheel", () -> new WheelItem(WheelType.STANDARD, new Item.Properties()).setColored());
    public static final RegistryObject<Item> IRON_ELECTRIC_ENGINE = register("iron_electric_engine", () -> new EngineItem(EngineType.ELECTRIC_MOTOR, EngineTier.IRON, new Item.Properties()));
    public static final RegistryObject<Item> GOLD_ELECTRIC_ENGINE = register("gold_electric_engine", () -> new EngineItem(EngineType.ELECTRIC_MOTOR, EngineTier.GOLD, new Item.Properties()));
    public static final RegistryObject<Item> DIAMOND_ELECTRIC_ENGINE = register("diamond_electric_engine", () -> new EngineItem(EngineType.ELECTRIC_MOTOR, EngineTier.DIAMOND, new Item.Properties()));
    public static final RegistryObject<Item> NETHERITE_ELECTRIC_ENGINE = register("netherite_electric_engine", () -> new EngineItem(EngineType.ELECTRIC_MOTOR, EngineTier.NETHERITE, new Item.Properties()));
    public static final RegistryObject<Item> WRENCH = register("wrench", () -> new WrenchItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> HAMMER = register("hammer", () -> new HammerItem(new Item.Properties().durability(200)));
    public static final RegistryObject<Item> KEY = register("key", () -> new KeyItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<LazyBlockItem<Block>> VEHICLE_CRATE = register("vehicle_crate", () -> new LazyBlockItem<>(ModBlocks.VEHICLE_CRATE, new Item.Properties()));
    public static final RegistryObject<LazyBlockItem<Block>> JACK = register("jack", () -> new LazyBlockItem<>(ModBlocks.JACK, new Item.Properties()));

    protected static <T extends Item> RegistryObject<T> register(String id, Supplier<T> item)
    {
        return ModItems.REGISTER.register(id, item);
    }
}
