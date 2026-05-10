package ccc.dev.kelvin.client;

import ccc.dev.kelvin.CccKelvinMod;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.resources.ResourceLocation;

/**
 * Kelvin must not use {@link net.minecraft.client.model.geom.ModelLayers#PLAYER}: baked {@link
 * net.minecraft.client.model.geom.ModelPart} trees are shared. Keeping Kelvin on its own layer prevents animation mods
 * that patch player model parts from seeing Kelvin renders as player renders.
 */
public final class KelvinModelLayers {
    private KelvinModelLayers() {}

    public static final ModelLayerLocation KELVIN =
            new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(CccKelvinMod.MOD_ID, "kelvin"), "main");
    public static final ModelLayerLocation KELVIN_INNER_ARMOR =
            new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(CccKelvinMod.MOD_ID, "kelvin"), "inner_armor");
    public static final ModelLayerLocation KELVIN_OUTER_ARMOR =
            new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(CccKelvinMod.MOD_ID, "kelvin"), "outer_armor");
}
