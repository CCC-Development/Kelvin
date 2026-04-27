package com.mrcrayfish.vehicle.datagen;

import com.mrcrayfish.vehicle.client.CameraProperties;
import com.mrcrayfish.vehicle.common.Seat;
import com.mrcrayfish.vehicle.common.entity.Wheel;
import com.mrcrayfish.vehicle.entity.EngineType;
import com.mrcrayfish.vehicle.common.entity.Transform;
import com.mrcrayfish.vehicle.entity.properties.PoweredProperties;
import com.mrcrayfish.vehicle.entity.properties.VehicleProperties;
import com.mrcrayfish.vehicle.init.ModEntities;
import com.mrcrayfish.vehicle.init.ModSounds;
import net.minecraft.data.DataGenerator;

/**
 * Author: MrCrayfish
 */
public class VehiclePropertiesGen extends VehiclePropertiesProvider
{
    public VehiclePropertiesGen(DataGenerator generator)
    {
        super(generator);
    }

    @Override
    public void registerProperties()
    {
        this.add(ModEntities.GOLF_CART.get(), VehicleProperties.builder()
                .setAxleOffset(-0.5F)
                .setBodyTransform(Transform.create(1.15))
                .setDisplayTransform(Transform.create(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.25))
                .setHeldOffset(1.5, 2.5, 0.0)
                .addWheel(Wheel.builder()
                        .setSide(Wheel.Side.LEFT)
                        .setPosition(Wheel.Position.FRONT)
                        .setOffset(9.0, 0.0, 16.0)
                        .setScale(1.1))
                .addWheel(Wheel.builder()
                        .setSide(Wheel.Side.RIGHT)
                        .setPosition(Wheel.Position.FRONT)
                        .setOffset(9.0, 0.0, 16.0)
                        .setScale(1.1))
                .addWheel(Wheel.builder()
                        .setSide(Wheel.Side.LEFT)
                        .setPosition(Wheel.Position.REAR)
                        .setOffset(9.0, 0.0, -12.5)
                        .setScale(1.1)
                        .setParticles(true))
                .addWheel(Wheel.builder()
                        .setSide(Wheel.Side.RIGHT)
                        .setPosition(Wheel.Position.REAR)
                        .setOffset(9.0, 0.0, -12.5)
                        .setScale(1.1)
                        .setParticles(true))
                .addSeat(Seat.of(5.5, 5.0, -6.0, true))
                .addSeat(Seat.of(-5.5, 5.0, -6.0))
                .addSeat(Seat.of(5.5, 5.0, -15.0, 180F))
                .addSeat(Seat.of(-5.5, 5.0, -15.0, 180F))
                .setCanChangeWheels(true)
                .setCanBePainted(true)
                .setCanFitInTrailer(false)
                .setCamera(CameraProperties.builder()
                        .setDistance(5.0)
                        .setPosition(0, 2, 0))
                .addExtended(PoweredProperties.builder()
                        .setEngineType(EngineType.ELECTRIC_MOTOR)
                        .setEnginePower(25F)
                        .setMaxEnginePitch(1.0F)
                        .setFuelFillerTransform(Transform.create(-13, 5.5, -6.0, 0.0, -90.0, 0.0, 0.5))
                        .setIgnitionTransform(Transform.create(-8.5, 2.75, 8.5, -67.5, 0.0, 0.0, 0.5))
                        .setFrontAxleOffset(16.0)
                        .setRearAxleOffset(-12.5)
                        .setEngineSound(ModSounds.ENTITY_VEHICLE_HELICOPTER_ROTOR.getId())
                        .build()));
    }
}
