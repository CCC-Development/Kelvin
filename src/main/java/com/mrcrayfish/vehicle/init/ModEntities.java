package com.mrcrayfish.vehicle.init;

import com.mrcrayfish.vehicle.Reference;
import com.mrcrayfish.vehicle.block.VehicleCrateBlock;
import com.mrcrayfish.vehicle.common.VehicleRegistry;
import com.mrcrayfish.vehicle.entity.EntityJack;
import com.mrcrayfish.vehicle.entity.vehicle.GolfCartEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.function.Supplier;

/**
 * Author: MrCrayfish
 */
public class ModEntities
{
    public static final DeferredRegister<EntityType<?>> REGISTER = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, Reference.MOD_ID);

    public static final RegistryObject<EntityType<GolfCartEntity>> GOLF_CART = register("golf_cart", GolfCartEntity::new, 2.0F, 1.0F);

    public static final RegistryObject<EntityType<EntityJack>> JACK = REGISTER.register("jack", () -> EntityType.Builder.of((EntityType.EntityFactory<EntityJack>) EntityJack::new, MobCategory.MISC).setUpdateInterval(1).noSummon().fireImmune().sized(0F, 0F).setShouldReceiveVelocityUpdates(true).build("jack"));

    protected static <T extends Entity> RegistryObject<EntityType<T>> register(String name, EntityType.EntityFactory<T> factory, float width, float height)
    {
        return register(name, factory, width, height, true);
    }

    protected static <T extends Entity> RegistryObject<EntityType<T>> register(String name, EntityType.EntityFactory<T> factory, float width, float height, boolean crate)
    {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Reference.MOD_ID, name);

        Supplier<EntityType<T>> type = () ->
                EntityType.Builder.of(factory, MobCategory.MISC)
                        .sized(width, height)
                        .setTrackingRange(256)
                        .setUpdateInterval(1)
                        .fireImmune()
                        .setShouldReceiveVelocityUpdates(true)
                        .build(id.toString());

        VehicleRegistry.Entry<EntityType<T>> entry = new VehicleRegistry.Entry<>(id, type);
        VehicleRegistry.registerVehicle(entry);

        if(crate)
        {
            VehicleCrateBlock.registerVehicle(id);
        }

        return register(name, entry.entityType());
    }

    protected static <T extends Entity> RegistryObject<EntityType<T>> register(String name, Supplier<EntityType<T>> supplier)
    {
        return REGISTER.register(name, supplier);
    }
}
