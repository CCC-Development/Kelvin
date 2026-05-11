package ccc.dev.kelvin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/**
 * MCglTF uses raw GL to bind textures while vanilla also tracks sampler ids in
 * {@link RenderSystem}'s shader-texture table. If those diverge, later draws in the same frame (player skin
 * overlays, translucent entity passes, etc.) can sample the wrong textures. Copy current 2D bindings into
 * RenderSystem so the next {@link net.minecraft.client.renderer.ShaderInstance#apply()} matches the GPU.
 */
public final class KelvinMcGltfPipelineSync {
    private KelvinMcGltfPipelineSync() {}

    /** Inclusive upper slot index; vanilla entity shaders typically use 0–2, Iris/modded packs may use more. */
    public static void syncShaderTextureIdsFromGl(int firstSlot, int lastInclusive) {
        int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        try {
            for (int i = firstSlot; i <= lastInclusive; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                RenderSystem._setShaderTexture(i, bound);
            }
        } finally {
            GL13.glActiveTexture(prevActive);
        }
    }
}
