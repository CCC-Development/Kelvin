package ccc.dev.kelvin.client;

import ccc.dev.kelvin.cutscene.HeliIntroCutsceneIds;
import ccc.dev.kelvin.entity.ApacheHelicopterEntity;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.modularmods.mcgltf.IGltfModelReceiver;
import com.modularmods.mcgltf.MCglTF;
import com.modularmods.mcgltf.RenderedGltfModel;
import com.modularmods.mcgltf.RenderedGltfScene;
import com.modularmods.mcgltf.animation.GltfAnimationCreator;
import com.modularmods.mcgltf.animation.InterpolatedChannel;
import de.javagl.jgltf.model.AnimationModel;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;

public final class ApacheHelicopterRenderer extends EntityRenderer<ApacheHelicopterEntity> implements IGltfModelReceiver {

    private static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath("ccc_kelvin", "models/entity/apache_helicopter.gltf");

    private RenderedGltfScene renderedScene;
    private List<List<InterpolatedChannel>> animations;

    /** Model units are large; tuned for on-screen size in-world. */
    private static final float MODEL_SCALE = 0.08F * 7.0F * 2.0F;

    public ApacheHelicopterRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.0F;
    }

    @Override
    public ResourceLocation getModelLocation() {
        return MODEL;
    }

    @Override
    public void onReceiveSharedModel(RenderedGltfModel renderedModel) {
        this.renderedScene = renderedModel.renderedGltfScenes.get(0);
        List<AnimationModel> animationModels = renderedModel.gltfModel.getAnimationModels();
        this.animations = new ArrayList<>(animationModels.size());
        for (AnimationModel animationModel : animationModels) {
            this.animations.add(GltfAnimationCreator.createGltfAnimation(animationModel));
        }
    }

    @Override
    public ResourceLocation getTextureLocation(ApacheHelicopterEntity entity) {
        return null;
    }

    @Override
    public void render(
            ApacheHelicopterEntity entity,
            float entityYaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight) {
        if (this.renderedScene == null || this.animations == null) {
            return;
        }
        // Hide the model the moment impact is confirmed client-side (entity will be discarded on server
        // within the same tick; this prevents a 1-2 frame visual linger at the crash pose).
        if (entity.isIntroImpacted()) {
            return;
        }
        if (entity.getPersistentData().getBoolean(HeliIntroCutsceneIds.ENTITY_INTRO_WRECK_TAG)
                && HeliIntroCutsceneClient.isActive()) {
            super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
            return;
        }

        float timeS = (entity.level().getGameTime() + partialTick) / 20.0F;
        for (List<InterpolatedChannel> animation : this.animations) {
            animation.parallelStream().forEach(channel -> {
                float[] keys = channel.getKeys();
                if (keys.length == 0) {
                    return;
                }
                float end = keys[keys.length - 1];
                channel.update(end > 1.0E-6F ? timeS % end : 0.0F);
            });
        }

        int currentVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int currentArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int currentElementArrayBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);

        boolean cullFace = GL11.glGetBoolean(GL11.GL_CULL_FACE);
        boolean depthTest = GL11.glGetBoolean(GL11.GL_DEPTH_TEST);
        boolean blend = GL11.glGetBoolean(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GlStateManager._blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        poseStack.pushPose();
        poseStack.translate(0.0, 1.25F * 0.08F / MODEL_SCALE, 0.0);
        poseStack.scale(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE);
        poseStack.mulPose(Axis.YP.rotationDegrees(Mth.rotLerp(partialTick, entity.yRotO, entity.getYRot())));
        if (entity.getPersistentData().getBoolean(HeliIntroCutsceneIds.ENTITY_CUTSCENE_TAG)
                || entity.getPersistentData().getBoolean(HeliIntroCutsceneIds.ENTITY_INTRO_WRECK_TAG)) {
            float pitch = Mth.rotLerp(partialTick, entity.xRotO, entity.getXRot());
            poseStack.mulPose(Axis.XP.rotationDegrees(pitch));
        }
        RenderedGltfModel.setCurrentPose(poseStack.last().pose());
        RenderedGltfModel.setCurrentNormal(poseStack.last().normal());
        poseStack.popPose();

        GL30.glVertexAttribI2i(RenderedGltfModel.vaUV2, packedLight & 65535, packedLight >> 16 & 65535);
        GL20.glVertexAttrib2f(RenderedGltfModel.vaUV1, 0.0F, 0.0F);

        if (MCglTF.getInstance().isShaderModActive()) {
            this.renderedScene.renderForShaderMod();
        } else {
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            int tex2 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, MCglTF.getInstance().getLightTexture().getId());

            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            int tex1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            int tex0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

            renderVanillaSceneWithBaseTextureUnit0(this.renderedScene);

            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex2);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex1);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex0);
        }

        GL30.glVertexAttribI2i(RenderedGltfModel.vaUV2, 0, 0);

        if (!depthTest) {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
        }
        if (!blend) {
            GL11.glDisable(GL11.GL_BLEND);
        }
        if (cullFace) {
            GL11.glEnable(GL11.GL_CULL_FACE);
        } else {
            GL11.glDisable(GL11.GL_CULL_FACE);
        }

        GL30.glBindVertexArray(currentVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, currentArrayBuffer);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, currentElementArrayBuffer);
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
    }

    /**
     * Same as {@link RenderedGltfScene#renderForVanilla()} but activates {@link GL13#GL_TEXTURE0} before each draw
     * command so base-color binds hit sampler 0 (see Hunter Chopper / MCglTF vanilla path).
     */
    private static void renderVanillaSceneWithBaseTextureUnit0(RenderedGltfScene scene) {
        int currentProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);

        if (!scene.skinningCommands.isEmpty()) {
            GL20.glUseProgram(MCglTF.getInstance().getGlProgramSkinnig());
            GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
            scene.skinningCommands.forEach(Runnable::run);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
            GL40.glBindTransformFeedback(GL40.GL_TRANSFORM_FEEDBACK, 0);
            GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
        }

        RenderedGltfModel.CURRENT_SHADER_INSTANCE = GameRenderer.getRendertypeEntitySolidShader();
        int entitySolidProgram = RenderedGltfModel.CURRENT_SHADER_INSTANCE.getId();
        GL20.glUseProgram(entitySolidProgram);

        RenderedGltfModel.CURRENT_SHADER_INSTANCE.PROJECTION_MATRIX.set(RenderSystem.getProjectionMatrix());
        RenderedGltfModel.CURRENT_SHADER_INSTANCE.PROJECTION_MATRIX.upload();

        RenderedGltfModel.CURRENT_SHADER_INSTANCE.INVERSE_VIEW_ROTATION_MATRIX.set(RenderSystem.getInverseViewRotationMatrix());
        RenderedGltfModel.CURRENT_SHADER_INSTANCE.INVERSE_VIEW_ROTATION_MATRIX.upload();

        RenderedGltfModel.CURRENT_SHADER_INSTANCE.FOG_START.set(RenderSystem.getShaderFogStart());
        RenderedGltfModel.CURRENT_SHADER_INSTANCE.FOG_START.upload();

        RenderedGltfModel.CURRENT_SHADER_INSTANCE.FOG_END.set(RenderSystem.getShaderFogEnd());
        RenderedGltfModel.CURRENT_SHADER_INSTANCE.FOG_END.upload();

        RenderedGltfModel.CURRENT_SHADER_INSTANCE.FOG_COLOR.set(RenderSystem.getShaderFogColor());
        RenderedGltfModel.CURRENT_SHADER_INSTANCE.FOG_COLOR.upload();

        RenderedGltfModel.CURRENT_SHADER_INSTANCE.FOG_SHAPE.set(RenderSystem.getShaderFogShape().getIndex());
        RenderedGltfModel.CURRENT_SHADER_INSTANCE.FOG_SHAPE.upload();

        RenderedGltfModel.CURRENT_SHADER_INSTANCE.COLOR_MODULATOR.set(1.0F, 1.0F, 1.0F, 1.0F);
        RenderedGltfModel.CURRENT_SHADER_INSTANCE.COLOR_MODULATOR.upload();

        GL20.glUniform1i(GL20.glGetUniformLocation(entitySolidProgram, "Sampler0"), 0);
        GL20.glUniform1i(GL20.glGetUniformLocation(entitySolidProgram, "Sampler1"), 1);
        GL20.glUniform1i(GL20.glGetUniformLocation(entitySolidProgram, "Sampler2"), 2);

        RenderSystem.setupShaderLights(RenderedGltfModel.CURRENT_SHADER_INSTANCE);
        RenderedGltfModel.LIGHT0_DIRECTION =
                new Vector3f(RenderedGltfModel.CURRENT_SHADER_INSTANCE.LIGHT0_DIRECTION.getFloatBuffer());
        RenderedGltfModel.LIGHT1_DIRECTION =
                new Vector3f(RenderedGltfModel.CURRENT_SHADER_INSTANCE.LIGHT1_DIRECTION.getFloatBuffer());

        for (Runnable command : scene.vanillaRenderCommands) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL20.glVertexAttrib2f(RenderedGltfModel.vaUV1, 0.0F, 0.0F);
            command.run();
        }

        GL20.glUseProgram(currentProgram);

        RenderedGltfModel.NODE_GLOBAL_TRANSFORMATION_LOOKUP_CACHE.clear();
    }
}
