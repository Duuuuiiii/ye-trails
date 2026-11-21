package com.ye.trails.ui;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * High-DPI anti-aliased Apple macOS/iOS style UI primitives for Ye Trails.
 */
public final class AppleUiRenderer {
    public static final int BG_BACKDROP = 0x6805070B;
    public static final int BG_WINDOW = 0xED14161F;
    public static final int BG_CARD = 0xFF1C202B;
    public static final int BG_CARD_HOVER = 0xFF242938;
    public static final int APPLE_BLUE = 0xFF0A84FF;
    public static final int APPLE_GREEN = 0xFF30D158;
    public static final int APPLE_RED = 0xFFFF453A;
    public static final int TEXT_PRIMARY = 0xFFF5F5F7;
    public static final int TEXT_SECONDARY = 0xFF98989D;
    public static final int TEXT_TERTIARY = 0xFF636366;
    public static final int TOGGLE_OFF = 0xFF3A3A3C;

    private static final int TEX_SIZE = 64;
    private static final int CORNER_PX = 20;
    private static final Identifier ROUNDED_TEX = Identifier.fromNamespaceAndPath("ye_trails", "ui/sdf_rounded");
    private static final Identifier CIRCLE_TEX = Identifier.fromNamespaceAndPath("ye_trails", "ui/sdf_circle");
    private static boolean initialized = false;

    private AppleUiRenderer() {}

    public static void ensureInit() {
        if (initialized) return;
        initialized = true;
        Minecraft mc = Minecraft.getInstance();
        mc.getTextureManager().register(ROUNDED_TEX, new DynamicTexture(() -> "ye_trails_sdf_rounded", buildRoundedImage()));
        mc.getTextureManager().register(CIRCLE_TEX, new DynamicTexture(() -> "ye_trails_sdf_circle", buildCircleImage()));
    }

    private static NativeImage buildRoundedImage() {
        NativeImage img = new NativeImage(TEX_SIZE, TEX_SIZE, true);
        double r = CORNER_PX - 1.0;
        double half = TEX_SIZE / 2.0;
        double boxHalf = half - 1.0 - r;
        for (int y = 0; y < TEX_SIZE; y++) {
            for (int x = 0; x < TEX_SIZE; x++) {
                int cov = 0;
                for (int sy = 0; sy < 4; sy++) {
                    for (int sx = 0; sx < 4; sx++) {
                        double px = x + (sx + 0.5) / 4.0 - half;
                        double py = y + (sy + 0.5) / 4.0 - half;
                        double qx = Math.max(0.0, Math.abs(px) - boxHalf);
                        double qy = Math.max(0.0, Math.abs(py) - boxHalf);
                        if (Math.hypot(qx, qy) - r <= 0.0) cov++;
                    }
                }
                img.setPixel(x, y, ((cov * 255 / 16) << 24) | 0x00FFFFFF);
            }
        }
        return img;
    }

    private static NativeImage buildCircleImage() {
        int s = 32;
        NativeImage img = new NativeImage(s, s, true);
        double half = s / 2.0;
        double r = half - 1.5;
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                int cov = 0;
                for (int sy = 0; sy < 4; sy++) {
                    for (int sx = 0; sx < 4; sx++) {
                        double px = x + (sx + 0.5) / 4.0 - half;
                        double py = y + (sy + 0.5) / 4.0 - half;
                        if (Math.hypot(px, py) <= r) cov++;
                    }
                }
                img.setPixel(x, y, ((cov * 255 / 16) << 24) | 0x00FFFFFF);
            }
        }
        return img;
    }

    public static void drawRoundedRect(GuiGraphics g, int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0) return;
        ensureInit();
        int r = Math.min(radius, Math.min(w / 2, h / 2));
        if (r <= 1) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        int c = CORNER_PX;
        int midTex = TEX_SIZE - c * 2;
        int midW = w - r * 2;
        int midH = h - r * 2;

        g.blit(RenderPipelines.GUI_TEXTURED, ROUNDED_TEX, x, y, 0, 0, r, r, c, c, TEX_SIZE, TEX_SIZE, color);
        g.blit(RenderPipelines.GUI_TEXTURED, ROUNDED_TEX, x + w - r, y, TEX_SIZE - c, 0, r, r, c, c, TEX_SIZE, TEX_SIZE, color);
        g.blit(RenderPipelines.GUI_TEXTURED, ROUNDED_TEX, x, y + h - r, 0, TEX_SIZE - c, r, r, c, c, TEX_SIZE, TEX_SIZE, color);
        g.blit(RenderPipelines.GUI_TEXTURED, ROUNDED_TEX, x + w - r, y + h - r, TEX_SIZE - c, TEX_SIZE - c, r, r, c, c, TEX_SIZE, TEX_SIZE, color);

        if (midW > 0) {
            g.blit(RenderPipelines.GUI_TEXTURED, ROUNDED_TEX, x + r, y, c, 0, midW, r, midTex, c, TEX_SIZE, TEX_SIZE, color);
            g.blit(RenderPipelines.GUI_TEXTURED, ROUNDED_TEX, x + r, y + h - r, c, TEX_SIZE - c, midW, r, midTex, c, TEX_SIZE, TEX_SIZE, color);
        }
        if (midH > 0) {
            g.blit(RenderPipelines.GUI_TEXTURED, ROUNDED_TEX, x, y + r, 0, c, r, midH, c, midTex, TEX_SIZE, TEX_SIZE, color);
            g.blit(RenderPipelines.GUI_TEXTURED, ROUNDED_TEX, x + w - r, y + r, TEX_SIZE - c, c, r, midH, c, midTex, TEX_SIZE, TEX_SIZE, color);
        }
        if (midW > 0 && midH > 0) {
            g.fill(x + r, y + r, x + w - r, y + h - r, color);
        }
    }

    public static void drawCircle(GuiGraphics g, int x, int y, int size, int color) {
        ensureInit();
        g.blit(RenderPipelines.GUI_TEXTURED, CIRCLE_TEX, x, y, 0, 0, size, size, 32, 32, 32, 32, color);
    }

    public static void drawPillSwitch(GuiGraphics g, int x, int y, int w, int h, float progress) {
        int bg = lerpColor(TOGGLE_OFF, APPLE_GREEN, progress);
        drawRoundedRect(g, x, y, w, h, h / 2, bg);
        int knobSize = h - 4;
        float travel = w - knobSize - 4;
        int knobX = Math.round(x + 2 + travel * progress);
        drawCircle(g, knobX, y + 2, knobSize, 0xFFFFFFFF);
    }

    public static float smooth(float current, float target, float speed, float dt) {
        float t = 1.0F - (float) Math.exp(-speed * Math.min(0.1F, dt));
        return current + (target - current) * t;
    }

    public static int lerpColor(int a, int b, float t) {
        t = Math.max(0.0F, Math.min(1.0F, t));
        int aa = (a >>> 24) & 0xFF, ar = (a >>> 16) & 0xFF, ag = (a >>> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >>> 16) & 0xFF, bg = (b >>> 8) & 0xFF, bb = b & 0xFF;
        int oa = (int) (aa + (ba - aa) * t);
        int or = (int) (ar + (br - ar) * t);
        int og = (int) (ag + (bg - ag) * t);
        int ob = (int) (ab + (bb - ab) * t);
        return (oa << 24) | (or << 16) | (og << 8) | ob;
    }

    public static int withAlpha(int argb, float alphaMul) {
        int a = Math.round(((argb >>> 24) & 0xFF) * Math.max(0.0F, Math.min(1.0F, alphaMul)));
        return (a << 24) | (argb & 0x00FFFFFF);
    }
}
