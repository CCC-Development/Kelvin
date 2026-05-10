package com.mrcrayfish.vehicle;

import com.mrcrayfish.vehicle.block.VehicleCrateBlock;
import com.mrcrayfish.vehicle.client.model.ComponentManager;
import com.mrcrayfish.vehicle.client.model.VehicleModels;
import com.mrcrayfish.vehicle.common.CommonEvents;
import com.mrcrayfish.vehicle.common.cosmetic.CosmeticActions;
import com.mrcrayfish.vehicle.common.entity.HeldVehicleDataHandler;
import com.mrcrayfish.vehicle.datagen.*;
import com.mrcrayfish.vehicle.entity.properties.ExtendedProperties;
import com.mrcrayfish.vehicle.entity.properties.HelicopterProperties;
import com.mrcrayfish.vehicle.entity.properties.LandProperties;
import com.mrcrayfish.vehicle.entity.properties.MotorcycleProperties;
import com.mrcrayfish.vehicle.entity.properties.PlaneProperties;
import com.mrcrayfish.vehicle.entity.properties.PoweredProperties;
import com.mrcrayfish.vehicle.entity.properties.TrailerProperties;
import com.mrcrayfish.vehicle.entity.properties.VehicleProperties;
import com.mrcrayfish.vehicle.init.*;
import com.mrcrayfish.vehicle.network.PacketHandler;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.DataGenerator;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.data.ExistingFileHelper;
import net.minecraftforge.data.event.GatherDataEvent;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.RegistryObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Author: MrCrayfish
 * <p>Embedded in {@link ccc.dev.kelvin.CccKelvinMod}; registry namespace remains {@link Reference#MOD_ID}.</p>
 */
public class VehicleMod
{
    public static final Logger LOGGER = LogManager.getLogger(Reference.MOD_ID);

    public VehicleMod()
    {
        IEventBus eventBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModBlocks.REGISTER.register(eventBus);
        ModItems.REGISTER.register(eventBus);
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, Config.serverSpec);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, Config.clientSpec);
        ModEntities.REGISTER.register(eventBus);
        ModTileEntities.REGISTER.register(eventBus);
        ModContainers.REGISTER.register(eventBus);
        ModParticleTypes.REGISTER.register(eventBus);
        ModSounds.REGISTER.register(eventBus);

        eventBus.addListener(this::onCommonSetup);
        eventBus.addListener(this::onGatherData);
        eventBus.addListener(this::addCreative);
        MinecraftForge.EVENT_BUS.register(new CommonEvents());
        MinecraftForge.EVENT_BUS.register(new ModCommands());
        ExtendedProperties.register(new ResourceLocation(Reference.MOD_ID, "powered"), PoweredProperties.class, PoweredProperties::new);
        ExtendedProperties.register(new ResourceLocation(Reference.MOD_ID, "land"), LandProperties.class, LandProperties::new);
        ExtendedProperties.register(new ResourceLocation(Reference.MOD_ID, "motorcycle"), MotorcycleProperties.class, MotorcycleProperties::new);
        ExtendedProperties.register(new ResourceLocation(Reference.MOD_ID, "plane"), PlaneProperties.class, PlaneProperties::new);
        ExtendedProperties.register(new ResourceLocation(Reference.MOD_ID, "helicopter"), HelicopterProperties.class, HelicopterProperties::new);
        ExtendedProperties.register(new ResourceLocation(Reference.MOD_ID, "trailer"), TrailerProperties.class, TrailerProperties::new);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ComponentManager.registerLoader(VehicleModels.LOADER));
        VehicleMod.register(eventBus);
    }

    private void onCommonSetup(FMLCommonSetupEvent event)
    {
        VehicleProperties.loadDefaultProperties();
        PacketHandler.init();
        HeldVehicleDataHandler.register();
        ModDataKeys.register();
        ModLootFunctions.init();
        event.enqueueWork(() -> VehicleProperties.registerDynamicProvider(() -> new VehiclePropertiesGen(null)));
    }

    private void onGatherData(GatherDataEvent event)
    {
        DataGenerator generator = event.getGenerator();
        CompletableFuture<HolderLookup.Provider> provider = event.getLookupProvider();
        ExistingFileHelper existingFileHelper = event.getExistingFileHelper();

        generator.addProvider(true, LootTableGen.create(generator.getPackOutput()));
        generator.addProvider(true, new RecipeGen(generator));
        generator.addProvider(true, new VehiclePropertiesGen(generator));
        generator.addProvider(true, new BlockTagGen(event.getGenerator().getPackOutput(), provider, Reference.MOD_ID, existingFileHelper));
    }

    /**
     * Register with {@link DeferredRegister#create(ResourceLocation, String)} instead of
     * {@code ResourceKey.createRegistryKey}: Connector / hybrid clients can remap {@link ResourceKey} such that
     * {@code createRegistryKey(ResourceLocation)} is missing at runtime ({@link NoSuchMethodError}).
     */
    private static final ResourceLocation CREATIVE_MODE_TAB_REGISTRY_NAME =
            ResourceLocation.fromNamespaceAndPath("minecraft", "creative_mode_tab");

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(CREATIVE_MODE_TAB_REGISTRY_NAME, Reference.MOD_ID);

    public static RegistryObject<CreativeModeTab> VEHICLE_TAB = CREATIVE_MODE_TABS.register("vehiclemodtab", () ->
            CreativeModeTab.builder().icon(() -> new ItemStack(ModItems.WRENCH.get()))
                    .title(Component.literal("Golf Cart")).build());

    public static void register(IEventBus eventBus) {
        CREATIVE_MODE_TABS.register(eventBus);
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTab() == VEHICLE_TAB.get()) {
            ModItems.REGISTER.getEntries().forEach(item -> event.accept(item.get()));

            ResourceLocation golfCrateId = Objects.requireNonNull(ForgeRegistries.ENTITY_TYPES.getKey(ModEntities.GOLF_CART.get()), "golf_cart");
            CompoundTag blockEntityTag = new CompoundTag();
            blockEntityTag.putString("vehicle", golfCrateId.toString());
            blockEntityTag.putBoolean("creative", true);
            CompoundTag itemTag = new CompoundTag();
            itemTag.put("BlockEntityTag", blockEntityTag);
            ItemStack golfCrate = new ItemStack(ModBlocks.VEHICLE_CRATE.get());
            golfCrate.setTag(itemTag);
            event.accept(golfCrate);
        }
    }

}
