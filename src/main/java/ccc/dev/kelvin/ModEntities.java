package ccc.dev.kelvin;

import ccc.dev.kelvin.entity.ApacheHelicopterEntity;
import ccc.dev.kelvin.entity.KelvinEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod.EventBusSubscriber(modid = CccKelvinMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ModEntities {
    private ModEntities() {}

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, CccKelvinMod.MOD_ID);

    public static final RegistryObject<EntityType<KelvinEntity>> KELVIN = ENTITY_TYPES.register("kelvin",
            () -> EntityType.Builder.of(KelvinEntity::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.8F)
                    .clientTrackingRange(8)
                    .build(ResourceLocation.fromNamespaceAndPath(CccKelvinMod.MOD_ID, "kelvin").toString()));

    public static final RegistryObject<EntityType<ApacheHelicopterEntity>> APACHE_HELICOPTER = ENTITY_TYPES.register("apache_helicopter",
            () -> EntityType.Builder.<ApacheHelicopterEntity>of(ApacheHelicopterEntity::new, MobCategory.MISC)
                    .sized(14.0F, 5.0F)
                    .clientTrackingRange(24)
                    .updateInterval(1)
                    .build(ResourceLocation.fromNamespaceAndPath(CccKelvinMod.MOD_ID, "apache_helicopter").toString()));

    @SubscribeEvent
    public static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(KELVIN.get(), KelvinEntity.createAttributes().build());
    }
}
