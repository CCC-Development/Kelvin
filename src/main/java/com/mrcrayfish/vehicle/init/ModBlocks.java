package com.mrcrayfish.vehicle.init;

import com.mrcrayfish.vehicle.Reference;
import com.mrcrayfish.vehicle.block.JackBlock;
import com.mrcrayfish.vehicle.block.JackHeadBlock;
import com.mrcrayfish.vehicle.block.VehicleCrateBlock;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.function.Supplier;

/**
 * Author: MrCrayfish
 */
public class ModBlocks
{
    public static final DeferredRegister<Block> REGISTER = DeferredRegister.create(ForgeRegistries.Keys.BLOCKS, Reference.MOD_ID);

    public static final RegistryObject<Block> VEHICLE_CRATE = register("vehicle_crate", VehicleCrateBlock::new);
    public static final RegistryObject<Block> JACK = register("jack", JackBlock::new);
    public static final RegistryObject<Block> JACK_HEAD = register("jack_head", JackHeadBlock::new);

    private static <T extends Block> RegistryObject<T> register(String id, Supplier<T> block)
    {
        return ModBlocks.REGISTER.register(id, block);
    }
}
