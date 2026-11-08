package dev.nitro.util;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.render.*;
import org.joml.Matrix4f;

/**
 * Minimal immediate-mode helpers for drawing boxes and lines through walls.
 * All coordinates must already be camera-relative.
 */
public final class RenderUtil {
    private RenderUtil() {}

    public static void begin() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
    }

    public static void end() {
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.lineWidth(1f);
    }

    public static BufferBuilder lines(float width) {
        RenderSystem.setShader(ShaderProgramKeys.RENDERTYPE_LINES);
        RenderSystem.lineWidth(width);
        return Tessellator.getInstance().begin(VertexFormat.DrawMode.LINES, VertexFormats.LINES);
    }

    public static BufferBuilder quads() {
        RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);
        return Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
    }

    public static BufferBuilder tris() {
        RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);
        return Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
    }

    public static void draw(BufferBuilder b) {
        BuiltBuffer built = b.endNullable();
        if (built != null) BufferRenderer.drawWithGlobalProgram(built);
    }

    public static void line(BufferBuilder b, Matrix4f m, float x1, float y1, float z1, float x2, float y2, float z2, int argb) {
        float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6f) return;
        dx /= len;
        dy /= len;
        dz /= len;
        b.vertex(m, x1, y1, z1).color(argb).normal(dx, dy, dz);
        b.vertex(m, x2, y2, z2).color(argb).normal(dx, dy, dz);
    }

    public static void boxLines(BufferBuilder b, Matrix4f m, float x1, float y1, float z1, float x2, float y2, float z2, int argb) {
        // bottom
        line(b, m, x1, y1, z1, x2, y1, z1, argb);
        line(b, m, x2, y1, z1, x2, y1, z2, argb);
        line(b, m, x2, y1, z2, x1, y1, z2, argb);
        line(b, m, x1, y1, z2, x1, y1, z1, argb);
        // top
        line(b, m, x1, y2, z1, x2, y2, z1, argb);
        line(b, m, x2, y2, z1, x2, y2, z2, argb);
        line(b, m, x2, y2, z2, x1, y2, z2, argb);
        line(b, m, x1, y2, z2, x1, y2, z1, argb);
        // verticals
        line(b, m, x1, y1, z1, x1, y2, z1, argb);
        line(b, m, x2, y1, z1, x2, y2, z1, argb);
        line(b, m, x2, y1, z2, x2, y2, z2, argb);
        line(b, m, x1, y1, z2, x1, y2, z2, argb);
    }

    private static void v(BufferBuilder b, Matrix4f m, float x, float y, float z, int argb) {
        b.vertex(m, x, y, z).color(argb);
    }

    public static void boxFill(BufferBuilder b, Matrix4f m, float x1, float y1, float z1, float x2, float y2, float z2, int argb) {
        // bottom
        v(b, m, x1, y1, z1, argb); v(b, m, x2, y1, z1, argb); v(b, m, x2, y1, z2, argb); v(b, m, x1, y1, z2, argb);
        // top
        v(b, m, x1, y2, z1, argb); v(b, m, x1, y2, z2, argb); v(b, m, x2, y2, z2, argb); v(b, m, x2, y2, z1, argb);
        // north
        v(b, m, x1, y1, z1, argb); v(b, m, x1, y2, z1, argb); v(b, m, x2, y2, z1, argb); v(b, m, x2, y1, z1, argb);
        // south
        v(b, m, x1, y1, z2, argb); v(b, m, x2, y1, z2, argb); v(b, m, x2, y2, z2, argb); v(b, m, x1, y2, z2, argb);
        // west
        v(b, m, x1, y1, z1, argb); v(b, m, x1, y1, z2, argb); v(b, m, x1, y2, z2, argb); v(b, m, x1, y2, z1, argb);
        // east
        v(b, m, x2, y1, z1, argb); v(b, m, x2, y2, z1, argb); v(b, m, x2, y2, z2, argb); v(b, m, x2, y1, z2, argb);
    }
}
