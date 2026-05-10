package ccc.dev.kelvin.client;

import ccc.dev.kelvin.CccKelvinMod;
import ccc.dev.kelvin.entity.KelvinEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

public final class KelvinRenderer extends HumanoidMobRenderer<KelvinEntity, PlayerModel<KelvinEntity>> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(CccKelvinMod.MOD_ID, "textures/entity/kelvin.png");

    public KelvinRenderer(EntityRendererProvider.Context context) {
        super(context, new KelvinPlayerModel(context.bakeLayer(KelvinModelLayers.KELVIN), false), 0.5F);
        this.addLayer(new HumanoidArmorLayer<>(
                this,
                new HumanoidModel<>(context.bakeLayer(KelvinModelLayers.KELVIN_INNER_ARMOR)),
                new HumanoidModel<>(context.bakeLayer(KelvinModelLayers.KELVIN_OUTER_ARMOR)),
                context.getModelManager()));
    }

    @Override
    public ResourceLocation getTextureLocation(KelvinEntity entity) {
        return TEXTURE;
    }

    @Override
    protected void setupRotations(KelvinEntity entity, PoseStack poseStack, float bob, float yRot, float partialTick) {
        if (entity.isRemoved()) {
            super.setupRotations(entity, poseStack, bob, yRot, partialTick);
            return;
        }
        if (entity.isDowned()) {
            super.setupRotations(entity, poseStack, bob, yRot, partialTick);
            poseStack.translate(0.0F, entity.getBbHeight() * 0.35F, 0.0F);
            poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
            poseStack.translate(0.0F, -entity.getBbHeight() * 0.15F, 0.0F);
            return;
        }
        super.setupRotations(entity, poseStack, bob, yRot, partialTick);
    }

    /**
     * Left segment of the nameplate: current HP (rounded up) plus a heart, then a space before the name (see
     * {@link KelvinNameTagClientEvents}). Uses U+2764 (heavy black heart) — it matches what most resource packs expect
     * better than U+2665; bold styling is avoided because it often turns small glyphs into a blob in nameplate text.
     */
    static Component kelvinHealthNameLine(KelvinEntity entity) {
        int hp = Mth.ceil(entity.getHealth());
        MutableComponent line =
                Component.literal(String.valueOf(hp))
                        .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(kelvinHealthRgb(entity) & 0xFFFFFF)));
        line.append(Component.literal(" "));
        line.append(Component.literal("\u2764").withStyle(ChatFormatting.RED));
        return line;
    }

    /**
     * Health ratio {@code t = health / max} drives colour: strong green when high, amber in the middle band, then
     * saturated red that eases into a darker, lower-saturation red near empty.
     */
    private static int kelvinHealthRgb(KelvinEntity entity) {
        float max = entity.getMaxHealth();
        float t = max > 1.0E-3F ? Mth.clamp(entity.getHealth() / max, 0.0F, 1.0F) : 0.0F;

        // Stops (RGB): dark critical → vivid red → amber → yellow-amber → healthy green (t increases left to right).
        final float r0 = 78f, g0 = 12f, b0 = 16f;
        final float r1 = 232f, g1 = 52f, b1 = 46f;
        final float r2 = 236f, g2 = 152f, b2 = 38f;
        final float r3 = 196f, g3 = 178f, b3 = 48f;
        final float r4 = 44f, g4 = 208f, b4 = 102f;

        float r;
        float g;
        float b;
        if (t <= 0.14f) {
            float u = t / 0.14f;
            r = Mth.lerp(u, r0, r1);
            g = Mth.lerp(u, g0, g1);
            b = Mth.lerp(u, b0, b1);
        } else if (t <= 0.38f) {
            float u = Mth.inverseLerp(0.14f, 0.38f, t);
            r = Mth.lerp(u, r1, r2);
            g = Mth.lerp(u, g1, g2);
            b = Mth.lerp(u, b1, b2);
        } else if (t <= 0.62f) {
            float u = Mth.inverseLerp(0.38f, 0.62f, t);
            r = Mth.lerp(u, r2, r3);
            g = Mth.lerp(u, g2, g3);
            b = Mth.lerp(u, b2, b3);
        } else {
            float u = Mth.inverseLerp(0.62f, 1.0f, t);
            r = Mth.lerp(u, r3, r4);
            g = Mth.lerp(u, g3, g4);
            b = Mth.lerp(u, b3, b4);
        }
        int ri = Mth.clamp((int) (r + 0.5f), 0, 255);
        int gi = Mth.clamp((int) (g + 0.5f), 0, 255);
        int bi = Mth.clamp((int) (b + 0.5f), 0, 255);
        return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
    }
}
