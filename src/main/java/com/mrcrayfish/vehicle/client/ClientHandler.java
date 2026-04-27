package com.mrcrayfish.vehicle.client;

import com.mrcrayfish.vehicle.Reference;
import com.mrcrayfish.vehicle.client.handler.*;
import com.mrcrayfish.vehicle.client.model.ComponentManager;
import com.mrcrayfish.vehicle.client.particle.DustParticle;
import com.mrcrayfish.vehicle.client.particle.TyreSmokeParticle;
import com.mrcrayfish.vehicle.client.raytrace.EntityRayTracer;
import com.mrcrayfish.vehicle.client.render.layer.LayerHeldVehicle;
import com.mrcrayfish.vehicle.client.render.tileentity.VehicleCrateRenderer;
import com.mrcrayfish.vehicle.client.render.vehicle.GolfCartRenderer;
import com.mrcrayfish.vehicle.client.screen.EditVehicleScreen;
import com.mrcrayfish.vehicle.client.screen.StorageScreen;
import com.mrcrayfish.vehicle.client.util.OptifineHelper;
import com.mrcrayfish.vehicle.init.ModContainers;
import com.mrcrayfish.vehicle.init.ModEntities;
import com.mrcrayfish.vehicle.init.ModParticleTypes;
import com.mrcrayfish.vehicle.init.ModTileEntities;
import com.mrcrayfish.vehicle.item.PartItem;
import com.mrcrayfish.vehicle.util.FluidUtils;
import com.mrcrayfish.vehicle.util.VehicleUtil;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;

/**
 * Author: MrCrayfish
 */
@Mod.EventBusSubscriber(modid = Reference.HOST_MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ClientHandler
{
    private static boolean controllableLoaded = false;

    public static boolean isControllableLoaded()
    {
        return controllableLoaded;
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event)
    {
        if(ModList.get().isLoaded("controllable"))
        {
            ClientHandler.controllableLoaded = true;
            ControllerHandler.init();
        }

        MinecraftForge.EVENT_BUS.register(EntityRayTracer.instance());
        MinecraftForge.EVENT_BUS.register(CosmeticCache.instance());
        MinecraftForge.EVENT_BUS.register(CameraHandler.instance());
        MinecraftForge.EVENT_BUS.register(new FuelingHandler());
        MinecraftForge.EVENT_BUS.register(new HeldVehicleHandler());
        MinecraftForge.EVENT_BUS.register(new InputHandler());
        MinecraftForge.EVENT_BUS.register(new OverlayHandler());
        MinecraftForge.EVENT_BUS.register(new PlayerModelHandler());
        MinecraftForge.EVENT_BUS.register(new ClientEvents());

        setupCustomBlockModels();
        setupInteractableVehicles();
    }

    private static void setupCustomBlockModels()
    {
    }

    protected static void onResourceManagerReload(ResourceManager manager)
    {
        FluidUtils.clearCacheFluidColor();
        OptifineHelper.refresh();
        EntityRayTracer.instance().clearDataForReregistration();
        ComponentManager.clearCache();
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event)
    {
        VehicleUtil.registerVehicleRenderer(event, ModEntities.GOLF_CART.get(), GolfCartRenderer::new);
        event.registerEntityRenderer(ModEntities.JACK.get(), com.mrcrayfish.vehicle.client.render.JackRenderer::new);

        event.registerBlockEntityRenderer(ModTileEntities.VEHICLE_CRATE.get(), VehicleCrateRenderer::new);
        event.registerBlockEntityRenderer(ModTileEntities.JACK.get(), com.mrcrayfish.vehicle.client.render.tileentity.JackRenderer::new);
    }

    @SubscribeEvent
    public static void onAddLayers(EntityRenderersEvent.AddLayers event)
    {
        for (String skinName : event.getSkins())
        {
            PlayerRenderer renderer = event.getSkin(skinName);
            renderer.addLayer(new LayerHeldVehicle<>(renderer));
        }
    }

    @SubscribeEvent
    public static void registerClientReloadListeners(RegisterClientReloadListenersEvent event)
    {
        event.registerReloadListener((ResourceManagerReloadListener) ClientHandler::onResourceManagerReload);
    }

    @SubscribeEvent
    public static void onRegisterItemColorHandlersEvent(RegisterColorHandlersEvent.Item event)
    {
        net.minecraft.client.color.item.ItemColor color = (stack, index) ->
        {
            if(index != 0 || stack.getTag() == null || !stack.getTag().contains(com.mrcrayfish.vehicle.item.IDyeable.NBT_KEY))
            {
                return 0xFFFFFF;
            }

            return stack.getTag().getInt(com.mrcrayfish.vehicle.item.IDyeable.NBT_KEY);
        };

        for(Item item : ForgeRegistries.ITEMS)
        {
            if(item instanceof PartItem pi && pi.isColored())
            {
                event.register(color, item);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void registerScreens(RegisterEvent event)
    {
        event.register(ForgeRegistries.MENU_TYPES.getRegistryKey(), helper -> {
            MenuScreens.register(ModContainers.EDIT_VEHICLE.get(), EditVehicleScreen::new);
            MenuScreens.register(ModContainers.STORAGE.get(), StorageScreen::new);
        });
    }

    private static void setupInteractableVehicles() {}

    @SubscribeEvent
    public static void registerParticleFactories(RegisterParticleProvidersEvent event)
    {
        event.registerSpriteSet(ModParticleTypes.TYRE_SMOKE.get(), TyreSmokeParticle.Factory::new);
        event.registerSpriteSet(ModParticleTypes.DUST.get(), DustParticle.Factory::new);
    }
}
