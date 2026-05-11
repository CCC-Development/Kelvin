package ccc.dev.kelvin.client;

import ccc.dev.kelvin.ModEntities;
import ccc.dev.kelvin.ModItems;
import ccc.dev.kelvin.ModMenus;
import com.modularmods.mcgltf.MCglTF;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.LayerDefinitions;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = ccc.dev.kelvin.CccKelvinMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ModClientEvents {
    private ModClientEvents() {}

    @SubscribeEvent
    public static void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(
                KelvinModelLayers.KELVIN,
                () -> LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE, false), 64, 64));
        event.registerLayerDefinition(
                KelvinModelLayers.KELVIN_INNER_ARMOR,
                () ->
                        LayerDefinition.create(
                                PlayerModel.createMesh(LayerDefinitions.INNER_ARMOR_DEFORMATION, false), 64, 64));
        event.registerLayerDefinition(
                KelvinModelLayers.KELVIN_OUTER_ARMOR,
                () ->
                        LayerDefinition.create(
                                PlayerModel.createMesh(LayerDefinitions.OUTER_ARMOR_DEFORMATION, false), 64, 64));
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.KELVIN.get(), KelvinRenderer::new);
        event.registerEntityRenderer(ModEntities.APACHE_HELICOPTER.get(), context -> {
            ApacheHelicopterRenderer renderer = new ApacheHelicopterRenderer(context);
            MCglTF.getInstance().addGltfModelReceiver(renderer);
            return renderer;
        });
    }

    @SubscribeEvent
    public static void addPlayerLayers(EntityRenderersEvent.AddLayers event) {
        for (String skin : event.getSkins()) {
            PlayerRenderer renderer = event.getSkin(skin);
            if (renderer != null) {
                renderer.addLayer(new LocalPlayerSkinLayerVisibilityBridge(renderer));
            }
        }
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(ModMenus.KELVIN_INVENTORY.get(), KelvinInventoryScreen::new));
    }

    @SubscribeEvent
    public static void onBuildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ModItems.KELVIN_SUMMONER.get());
        }
    }
}
