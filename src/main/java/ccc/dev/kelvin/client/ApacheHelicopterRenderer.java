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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.culling.Frustum;
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

    /**
     * CurseForge {@code mcgltf-1.20.1.jar} predates {@link RenderedGltfModel#clearTransientPipelineState()}; calling
     * it directly causes {@link NoSuchMethodError} at render time. Prefer reflective invoke when present, else clear
     * the same static fields MCglTF uses for vanilla draw state.
     */
    private static final Method MCGLTF_CLEAR_TRANSIENT_PIPELINE;

    static {
        Method m = null;
        try {
            m = RenderedGltfModel.class.getMethod("clearTransientPipelineState");
        } catch (NoSuchMethodException ignored) {
        }
        MCGLTF_CLEAR_TRANSIENT_PIPELINE = m;
    }

    private static void clearMcGltfTransientPipelineStateCompat() {
        if (MCGLTF_CLEAR_TRANSIENT_PIPELINE != null) {
            try {
                MCGLTF_CLEAR_TRANSIENT_PIPELINE.invoke(null);
            } catch (ReflectiveOperationException ignored) {
            }
            return;
        }
        clearRenderedGltfModelStaticField("CURRENT_SHADER_INSTANCE");
        clearRenderedGltfModelStaticField("LIGHT0_DIRECTION");
        clearRenderedGltfModelStaticField("LIGHT1_DIRECTION");
        clearRenderedGltfModelStaticField("CURRENT_POSE");
        clearRenderedGltfModelStaticField("CURRENT_NORMAL");
    }

    private static void clearRenderedGltfModelStaticField(String name) {
        try {
            var f = RenderedGltfModel.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(null, null);
        } catch (ReflectiveOperationException ignored) {
        }
    }

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

    /**
     * Skip camera-frustum rejection — third-party culling (Embeddium options, Entity Culling, etc.) often uses tight
     * boxes or async tests that hide large glTF meshes at extreme distances. Distance is still gated by
     * {@link ApacheHelicopterEntity#shouldRender(double, double, double)} (we relax that on the entity).
     */
    @Override
    public boolean shouldRender(
            ApacheHelicopterEntity entity,
            Frustum frustum,
            double camX,
            double camY,
            double camZ) {
        return entity.shouldRender(camX, camY, camZ);
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

        ShaderInstance restorePipelineShader = RenderSystem.getShader();
        int[] snapshotShaderTextures = snapshotRenderSystemShaderTextures();
        try {
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

        // MCglTF (especially renderForShaderMod) rebinds texture units 0/1/3 internally but not always unit 2
        // (lightmap). If those bindings leak after the helicopter draw, vanilla entity + skin layered renders
        // sample the wrong textures on the next frames.
        GL13.glActiveTexture(GL13.GL_TEXTURE3);
        int savedTex3 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        int savedTex2 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        int savedTex1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int savedTex0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            if (MCglTF.getInstance().isShaderModActive()) {
                this.renderedScene.renderForShaderMod();
            } else {
                GL13.glActiveTexture(GL13.GL_TEXTURE2);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, MCglTF.getInstance().getLightTexture().getId());

                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

                GL13.glActiveTexture(GL13.GL_TEXTURE0);

                renderVanillaSceneWithBaseTextureUnit0(this.renderedScene);
            }
        } finally {
            GL13.glActiveTexture(GL13.GL_TEXTURE3);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, savedTex3);
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, savedTex2);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, savedTex1);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, savedTex0);
            restoreRenderSystemShaderTextures(snapshotShaderTextures);
        }

        GL30.glVertexAttribI2i(RenderedGltfModel.vaUV2, 0, 0);
        GL20.glVertexAttrib2f(RenderedGltfModel.vaUV1, 0.0F, 0.0F);

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
        } finally {
            clearMcGltfTransientPipelineStateCompat();
            if (restorePipelineShader != null) {
                RenderSystem.setShader(() -> restorePipelineShader);
                restorePipelineShader.apply();
            }
        }
    }

    /**
     * MCglTF binds texture samplers with raw GL calls. Vanilla also mirrors bound IDs in
     * {@link RenderSystem}'s shader-texture table; if that table is stale, the next {@link ShaderInstance#apply()}
     * can rebind the wrong textures (lightmap on slot 2 is the usual casualty), breaking translucent passes such as
     * player skin outer / overlay layers.
     */
    private static final int RENDER_SYSTEM_SHADER_TEXTURE_SLOTS = 16;

    private static int[] snapshotRenderSystemShaderTextures() {
        int[] saved = new int[RENDER_SYSTEM_SHADER_TEXTURE_SLOTS];
        for (int i = 0; i < RENDER_SYSTEM_SHADER_TEXTURE_SLOTS; i++) {
            saved[i] = RenderSystem.getShaderTexture(i);
        }
        return saved;
    }

    private static void restoreRenderSystemShaderTextures(int[] saved) {
        for (int i = 0; i < saved.length; i++) {
            RenderSystem._setShaderTexture(i, saved[i]);
        }
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
        clearMcGltfTransientPipelineStateCompat();
    }
}
