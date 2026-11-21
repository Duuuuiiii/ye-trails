package com.ye.trails.ui;

import com.ye.trails.config.TrailsConfig;
import com.ye.trails.engine.ChunkTrailEngine;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * Clean 3-tab settings modal for Ye Trails (default F7).
 */
public class TrailsSettingsScreen extends Screen {
    private static final int BG_BACKDROP = 0x7006080E;
    private static final int BG_PANEL = 0xF20E121A;
    private static final int BG_ROW = 0xFF151A26;
    private static final int BORDER = 0xFF252D3E;
    private static final int ACCENT = 0xFF22D3EE;
    private static final int TEXT_MAIN = 0xFFF1F5F9;
    private static final int TEXT_MUTED = 0xFF94A3B8;
    private static final int TOGGLE_OFF = 0xFF2A3346;

    private int activeTab = 0;
    private static final String[] TABS = {"地图与小地图", "检测与显示", "数据管理"};

    private final List<Item> items = new ArrayList<>();
    private int panelX, panelY, panelW, panelH;
    private SliderItem draggingSlider = null;

    public TrailsSettingsScreen() {
        super(Component.literal("Ye Trails Settings"));
        rebuildTab();
    }

    private void rebuildTab() {
        TrailsConfig c = TrailsConfig.get();
        items.clear();

        if (activeTab == 0) {
            items.add(new ToggleItem("显示小地图与地图画面 (F9)", () -> c.showVisuals, v -> c.showVisuals = v));
            items.add(new ToggleItem("显示 2D 路径小地图", () -> c.showRadar, v -> c.showRadar = v));
            items.add(new CycleItem("小地图停靠角落", () -> c.radarAnchor.getLabel(), () -> {
                TrailsConfig.RadarAnchor[] vals = TrailsConfig.RadarAnchor.values();
                c.radarAnchor = vals[(c.radarAnchor.ordinal() + 1) % vals.length];
            }));
            items.add(new SliderItem("小地图水平边距 X", () -> c.radarOffsetX, v -> c.radarOffsetX = (int) Math.round(v), 0.0, 400.0, 2.0));
            items.add(new SliderItem("小地图垂直边距 Y", () -> c.radarOffsetY, v -> c.radarOffsetY = (int) Math.round(v), 0.0, 300.0, 2.0));
            items.add(new SliderItem("小地图尺寸 (像素)", () -> c.radarSize, v -> c.radarSize = (int) Math.round(v), 72.0, 240.0, 4.0));
            items.add(new SliderItem("小地图缩放 (像素/区块)", () -> c.radarChunkPx, v -> c.radarChunkPx = (int) Math.round(v), 2.0, 16.0, 1.0));
            items.add(new ButtonItem("打开全屏路径大地图 (O)", () -> {
                if (minecraft != null) minecraft.setScreen(new WorldMapScreen());
            }));
        } else if (activeTab == 1) {
            items.add(new ToggleItem("地图显示真实地形", () -> c.showTerrainOnMap, v -> c.showTerrainOnMap = v));
            items.add(new ToggleItem("新区块红色标出 (老路径保持原色)", () -> c.showNewChunks, v -> c.showNewChunks = v));
            items.add(new ToggleItem("地狱门区块紫色边缘高亮", () -> c.highlightPortals, v -> c.highlightPortals = v));
            items.add(new ToggleItem("显示路径点 (地图 & 信标)", () -> c.showWaypoints, v -> c.showWaypoints = v));
            items.add(new ToggleItem("Palette 调色板核心检测 (1.21)", () -> c.paletteExploit, v -> c.paletteExploit = v));
            items.add(new ToggleItem("老版本远古区块检测 (<1.17/1.16)", () -> c.oldGenDetector, v -> c.oldGenDetector = v));
            items.add(new ToggleItem("流体流动辅助检测", () -> c.liquidExploit, v -> c.liquidExploit = v));
            items.add(new ToggleItem("3D 世界渲染 (默认关闭)", () -> c.render3D, v -> c.render3D = v));
        } else {
            items.add(new ToggleItem("本地按服务器/维度保存路径与地形", () -> c.saveToDisk, v -> c.saveToDisk = v));
            items.add(new ButtonItem("清空当前维度路径与地形缓存", ChunkTrailEngine::clearCurrentData));
            items.add(new ButtonItem("打开数据文件夹", () -> Util.getPlatform().openPath(TrailsConfig.getBaseDir())));
        }
    }

    @Override
    protected void init() {
        panelW = Math.min(340, this.width - 24);
        panelH = Math.min(310, this.height - 24);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        TrailsConfig.save();
        super.onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, BG_BACKDROP);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, BG_PANEL);
        g.renderOutline(panelX, panelY, panelW, panelH, BORDER);
        g.fill(panelX, panelY, panelX + panelW, panelY + 2, ACCENT);

        g.drawString(font, "ye-trails", panelX + 12, panelY + 10, ACCENT, false);
        g.drawString(font, "F7设置 · F9显隐 · O大地图", panelX + 12 + font.width("ye-trails  "), panelY + 10, TEXT_MUTED, false);

        int closeX = panelX + panelW - 24;
        int closeY = panelY + 6;
        boolean closeHover = inside(mouseX, mouseY, closeX, closeY, 18, 18);
        if (closeHover) g.fill(closeX, closeY, closeX + 18, closeY + 18, 0x33EF4444);
        g.drawString(font, "×", closeX + 6, closeY + 5, closeHover ? 0xFFFCA5A5 : TEXT_MUTED, false);

        // Tabs bar
        int tabY = panelY + 26;
        int tabW = (panelW - 24 - 8) / TABS.length;
        for (int i = 0; i < TABS.length; i++) {
            int tx = panelX + 12 + i * (tabW + 4);
            boolean sel = i == activeTab;
            boolean hov = inside(mouseX, mouseY, tx, tabY, tabW, 20);
            g.fill(tx, tabY, tx + tabW, tabY + 20, sel ? 0x3322D3EE : (hov ? 0xFF1B2232 : BG_ROW));
            g.renderOutline(tx, tabY, tabW, 20, sel ? ACCENT : BORDER);
            int tw = font.width(TABS[i]);
            g.drawString(font, TABS[i], tx + (tabW - tw) / 2, tabY + 6, sel ? ACCENT : TEXT_MUTED, false);
        }

        int ry = tabY + 28;
        int rowX = panelX + 12;
        int rowW = panelW - 24;
        for (Item item : items) {
            item.render(g, font, rowX, ry, rowW, mouseX, mouseY);
            ry += item.height() + 4;
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();

        if (inside(mx, my, panelX + panelW - 24, panelY + 6, 18, 18)) {
            onClose();
            return true;
        }

        int tabY = panelY + 26;
        int tabW = (panelW - 24 - 8) / TABS.length;
        for (int i = 0; i < TABS.length; i++) {
            int tx = panelX + 12 + i * (tabW + 4);
            if (inside(mx, my, tx, tabY, tabW, 20)) {
                activeTab = i;
                rebuildTab();
                return true;
            }
        }

        int ry = tabY + 28;
        int rowX = panelX + 12;
        int rowW = panelW - 24;
        for (Item item : items) {
            int h = item.height();
            if (inside(mx, my, rowX, ry, rowW, h)) {
                item.onClick(mx, rowX, rowW);
                if (item instanceof SliderItem si) {
                    draggingSlider = si;
                }
                TrailsConfig.save();
                return true;
            }
            ry += h + 4;
        }

        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingSlider != null) {
            draggingSlider.updateFromMouse(event.x(), panelX + 12, panelW - 24);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingSlider != null) {
            draggingSlider = null;
            TrailsConfig.save();
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_F7 || event.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private interface Item {
        int height();
        void render(GuiGraphics g, net.minecraft.client.gui.Font font, int x, int y, int w, int mouseX, int mouseY);
        void onClick(double mx, int x, int w);
    }

    private record ToggleItem(String label, BooleanSupplier getter, Consumer<Boolean> setter) implements Item {
        @Override public int height() { return 22; }

        @Override
        public void render(GuiGraphics g, net.minecraft.client.gui.Font font, int x, int y, int w, int mouseX, int mouseY) {
            boolean hover = inside(mouseX, mouseY, x, y, w, 22);
            boolean on = getter.getAsBoolean();
            g.fill(x, y, x + w, y + 22, hover ? 0xFF1B2232 : BG_ROW);
            g.drawString(font, label, x + 8, y + 7, on ? TEXT_MAIN : TEXT_MUTED, false);

            int sw = 24, sh = 12;
            int sx = x + w - sw - 8;
            int sy = y + 5;
            g.fill(sx, sy, sx + sw, sy + sh, on ? ACCENT : TOGGLE_OFF);
            int kx = on ? (sx + sw - 10) : (sx + 2);
            g.fill(kx, sy + 2, kx + 8, sy + 10, TEXT_MAIN);
        }

        @Override
        public void onClick(double mx, int x, int w) {
            setter.accept(!getter.getAsBoolean());
        }
    }

    private record CycleItem(String label, Supplier<String> valueGetter, Runnable onCycle) implements Item {
        @Override public int height() { return 22; }

        @Override
        public void render(GuiGraphics g, net.minecraft.client.gui.Font font, int x, int y, int w, int mouseX, int mouseY) {
            boolean hover = inside(mouseX, mouseY, x, y, w, 22);
            g.fill(x, y, x + w, y + 22, hover ? 0xFF1B2232 : BG_ROW);
            g.drawString(font, label, x + 8, y + 7, TEXT_MAIN, false);

            String val = valueGetter.get();
            int bw = font.width(val) + 12;
            int bx = x + w - bw - 6;
            g.fill(bx, y + 3, bx + bw, y + 19, 0xFF1E2536);
            g.renderOutline(bx, y + 3, bw, 16, BORDER);
            g.drawString(font, val, bx + 6, y + 7, ACCENT, false);
        }

        @Override
        public void onClick(double mx, int x, int w) {
            onCycle.run();
        }
    }

    private record SliderItem(String label, DoubleSupplier getter, DoubleConsumer setter,
                              double min, double max, double step) implements Item {
        @Override public int height() { return 28; }

        @Override
        public void render(GuiGraphics g, net.minecraft.client.gui.Font font, int x, int y, int w, int mouseX, int mouseY) {
            g.fill(x, y, x + w, y + 28, BG_ROW);
            double val = getter.getAsDouble();
            String valStr = step >= 1.0 ? String.valueOf((int) Math.round(val)) : String.format(Locale.ROOT, "%.2f", val);
            g.drawString(font, label, x + 8, y + 4, TEXT_MUTED, false);
            g.drawString(font, valStr, x + w - font.width(valStr) - 8, y + 4, ACCENT, false);

            int bx = x + 8;
            int bw = w - 16;
            int by = y + 17;
            g.fill(bx, by, bx + bw, by + 4, TOGGLE_OFF);
            double pct = Math.max(0.0, Math.min(1.0, (val - min) / (max - min)));
            int fw = (int) Math.round(bw * pct);
            g.fill(bx, by, bx + fw, by + 4, ACCENT);
            g.fill(bx + fw - 2, by - 2, bx + fw + 2, by + 6, TEXT_MAIN);
        }

        @Override
        public void onClick(double mx, int x, int w) {
            updateFromMouse(mx, x, w);
        }

        void updateFromMouse(double mx, int x, int w) {
            int bx = x + 8;
            int bw = w - 16;
            double pct = Math.max(0.0, Math.min(1.0, (mx - bx) / (double) bw));
            double raw = min + pct * (max - min);
            double stepped = Math.round(raw / step) * step;
            setter.accept(Math.max(min, Math.min(max, stepped)));
        }
    }

    private record ButtonItem(String label, Runnable action) implements Item {
        @Override public int height() { return 22; }

        @Override
        public void render(GuiGraphics g, net.minecraft.client.gui.Font font, int x, int y, int w, int mouseX, int mouseY) {
            boolean hover = inside(mouseX, mouseY, x, y, w, 22);
            g.fill(x, y, x + w, y + 22, hover ? 0x3322D3EE : BG_ROW);
            g.renderOutline(x, y, w, 22, hover ? ACCENT : BORDER);
            int tw = font.width(label);
            g.drawString(font, label, x + (w - tw) / 2, y + 7, hover ? TEXT_MAIN : ACCENT, false);
        }

        @Override
        public void onClick(double mx, int x, int w) {
            action.run();
        }
    }
}
