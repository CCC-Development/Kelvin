package com.mrcrayfish.vehicle.client.raytrace;

import com.mrcrayfish.vehicle.entity.PoweredVehicleEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;

/**
 * Author: MrCrayfish
 */
public interface RayTraceFunction
{
    @Nullable
    InteractionHand apply(EntityRayTracer rayTracer, VehicleRayTraceResult result, Player player);

    /** Gas pumps and jerry cans are not included in ccc_kelvin; golf cart does not consume fuel. */
    RayTraceFunction FUNCTION_FUELING = (rayTracer, result, player) ->
    {
        Entity entity = result.getEntity();
        if(!(entity instanceof PoweredVehicleEntity poweredVehicle))
            return null;
        if(!poweredVehicle.requiresEnergy() || poweredVehicle.getCurrentEnergy() >= poweredVehicle.getEnergyCapacity())
            return null;
        return null;
    };
}
