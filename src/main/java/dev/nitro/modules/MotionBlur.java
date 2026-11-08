package dev.nitro.modules;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.nitro.module.Module;
import dev.nitro.settings.Setting;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;

import java.nio.ByteBuffer;

/**
 * Motion Blur by frame accumulation: every frame the previous (already blurred) frame is blended over the new one,
 * then the result is copied into a texture for the next frame. Runs at the start of the HUD pass, so it blurs the world
 * and the vanilla HUD, while NITRO's own HUD panels (registered after this module) stay sharp.
 * The accumulation texture is RGB only, which also sidesteps the framebuffer's unreliable alpha channel.
 */
public final class MotionBlur extends Module {
    private final Setting.Num strength = new Setting.Num("Blur amount", 55, 10, 90, 1);
    private final Setting.Bool fpsIndependent = new Setting.Bool("Frame-rate independent", true);

    private int tex, texW, texH;
    private boolean filled;
    private long lastNs;

    public MotionBlur() {
        super("Motion Blur", "Blends previous frames into the current one for a smooth blur.", Category.RENDER);
        add(strength, fpsIndependent);
    }

    @Override
    public void onDisable() {
        release();
    }

    @Override
    public void onWorldChanged() {
        filled = false; // do not smear the old world into the new one
    }

    private void release() {
        if (tex != 0) {
            GlStateManager._deleteTexture(tex);
            tex = 0;
        }
        filled = false;
        texW = texH = 0;
        lastNs = 0;
    }

    private void alloc(int w, int h) {
        if (tex != 0) GlStateManager._deleteTexture(tex);
        tex = GL11.glGenTextures();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        GlStateManager._bindTexture(tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB8, w, h, 0, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        texW = w;
        texH = h;
        filled = false;
    }

    @Override
    public void renderHud(DrawContext ctx) {
        int w = mc.getWindow().getFramebufferWidth();
        int h = mc.getWindow().getFramebufferHeight();
        if (w <= 0 || h <= 0) return;

        long now = System.nanoTime();
        float dt = lastNs == 0 ? 1f / 60f : Math.min(0.25f, (now - lastNs) / 1e9f);
        lastNs = now;

        ctx.draw(); // flush anything DrawContext still has queued so the draw order stays correct
        if (tex == 0 || w != texW || h != texH) alloc(w, h);

        if (filled) {
            float s = strength.f() / 100f; // weight of the previous frame
            if (fpsIndependent.on()) s = (float) Math.pow(s, Math.max(0.05, dt * 60.0));
            s = Math.max(0.1f, Math.min(0.96f, s));
            blend(ctx, s);
        }

        // copy the finished frame (read framebuffer = main framebuffer) into the accumulation texture
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        GlStateManager._bindTexture(tex);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
        filled = true;
    }

    private void blend(DrawContext ctx, float s) {
        Matrix4f m = ctx.getMatrices().peek().getPositionMatrix();
        float sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        int col = (Math.round(s * 255f) << 24) | 0xFFFFFF;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(ShaderProgramKeys.POSITION_TEX_COLOR);
        RenderSystem.setShaderTexture(0, tex);
        BufferBuilder b = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);
        b.vertex(m, 0f, sh, 0f).texture(0f, 0f).color(col);
        b.vertex(m, sw, sh, 0f).texture(1f, 0f).color(col);
        b.vertex(m, sw, 0f, 0f).texture(1f, 1f).color(col);
        b.vertex(m, 0f, 0f, 0f).texture(0f, 1f).color(col);
        BufferRenderer.drawWithGlobalProgram(b.end());
        RenderSystem.enableDepthTest();
    }
}
