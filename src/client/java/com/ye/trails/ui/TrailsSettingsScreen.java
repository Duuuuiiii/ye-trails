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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Apple macOS-inspired 4-tab settings modal for Ye Trails (default F7).
 */
public class TrailsSettingsScreen extends Screen {
    private int activeTab = 0;
    private static final String[] TABS = {"地图设置", "检测设置", "快捷键", "数据管理"};

    private final List<Item> items = new ArrayList<>();
    private final Map<String, Float> toggleAnims = new HashMap<>();
    private int panelX, panelY, panelW, panelH;
    private SliderItem draggingSlider = null;
    private KeybindItem bindingItem = null;

    private float openAnim = 0.0F;
    private long lastNanos = System.nanoTime();

    public TrailsSettingsScreen() {
        super(Component.literal("Ye Trails Settings"));
        rebuildTab();
    }

    private void rebuildTab() {
        TrailsConfig c = TrailsConfig.get();
        items.clear();
        bindingItem = null;

        if (activeTab == 0) {
            items.add(new ToggleItem("vis", "显示小地图与地图画面", () -> c.showVisuals, v -> c.showVisuals = v));
            items.add(new ToggleItem("rad", "显示 2D 路径小地图", () -> c.showRadar, v -> c.showRadar = v));
            items.add(new CycleItem("小地图停靠角落", () -> c.radarAnchor.getLabel(), () -> {
                TrailsConfig.RadarAnchor[] vals = TrailsConfig.RadarAnchor.values();
                c.radarAnchor = vals[(c.radarAnchor.ordinal() + 1) % vals.length];
            }));
            items.add(new SliderItem("小地图水平边距 X", () -> c.radarOffsetX, v -> c.radarOffsetX = (int) Math.round(v), 0.0, 400.0, 2.0));
            items.add(new SliderItem("小地图垂直边距 Y", () -> c.radarOffsetY, v -> c.radarOffsetY = (int) Math.round(v), 0.0, 300.0, 2.0));
            items.add(new SliderItem("小地图尺寸 (像素)", () -> c.radarSize, v -> c.radarSize = (int) Math.round(v), 72.0, 240.0, 4.0));
            items.add(new SliderItem("小地图缩放 (像素/区块)", () -> c.radarChunkPx, v -> c.radarChunkPx = (int) Math.round(v), 2.0, 16.0, 1.0));
            items.add(new ButtonItem("打开全屏路径大地图 (" + getKeyName(c.mapKey) + ")", () -> {
                if (minecraft != null) minecraft.setScreen(new WorldMapScreen());
            }));
        } else if (activeTab == 1) {
            items.add(new ToggleItem("ter", "地图显示真实地形", () -> c.showTerrainOnMap, v -> c.showTerrainOnMap = v));
            items.add(new ToggleItem("newc", "新区块红色标出 (老路径保持原色)", () -> c.showNewChunks, v -> c.showNewChunks = v));
            items.add(new ToggleItem("port", "地狱门区块紫色边缘高亮", () -> c.highlightPortals, v -> c.highlightPortals = v));
            items.add(new ToggleItem("wp", "显示路径点 (地图 & 信标)", () -> c.showWaypoints, v -> c.showWaypoints = v));
            items.add(new ToggleItem("pal", "Palette 调色板核心检测 (1.21.11)", () -> c.paletteExploit, v -> c.paletteExploit = v));
            items.add(new ToggleItem("oldg", "老版本远古区块检测 (<1.17/1.16)", () -> c.oldGenDetector, v -> c.oldGenDetector = v));
            items.add(new ToggleItem("liq", "流体流动辅助检测", () -> c.liquidExploit, v -> c.liquidExploit = v));
            items.add(new ToggleItem("r3d", "3D 世界地面渲染 (默认关闭)", () -> c.render3D, v -> c.render3D = v));
        } else if (activeTab == 2) {
            items.add(new KeybindItem("打开 Ye Trails 设置面板", () -> c.menuKey, k -> c.menuKey = k, GLFW.GLFW_KEY_F7));
            items.add(new KeybindItem("打开全屏路径大地图", () -> c.mapKey, k -> c.mapKey = k, GLFW.GLFW_KEY_P));
            items.add(new KeybindItem("开关路径与小地图显隐", () -> c.toggleKey, k -> c.toggleKey = k, -1));
        } else {
            items.add(new ToggleItem("save", "本地按服务器/维度保存路径与地形", () -> c.saveToDisk, v -> c.saveToDisk = v));
            items.add(new ButtonItem("清空当前维度路径与地形缓存", ChunkTrailEngine::clearCurrentData));
            items.add(new ButtonItem("打开数据文件夹", () -> Util.getPlatform().openPath(TrailsConfig.getBaseDir())));
        }
    }

    @Override
    protected void init() {
        panelW = Math.min(360, this.width - 24);
        panelH = Math.min(330, this.height - 24);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        lastNanos = System.nanoTime();
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
        long now = System.nanoTime();
        float dt = Math.max(0.001F, (now - lastNanos) / 1_000_000_000.0F);
        lastNanos = now;
        openAnim = AppleUiRenderer.smooth(openAnim, 1.0F, 15.0F, dt);

        g.fill(0, 0, this.width, this.height, AppleUiRenderer.withAlpha(AppleUiRenderer.BG_BACKDROP, openAnim));
        AppleUiRenderer.drawRoundedRect(g, panelX - 2, panelY - 2, panelW + 4, panelH + 4, 15, AppleUiRenderer.withAlpha(0x44000000, openAnim));
        AppleUiRenderer.drawRoundedRect(g, panelX, panelY, panelW, panelH, 14, AppleUiRenderer.withAlpha(AppleUiRenderer.BG_WINDOW, openAnim));

        AppleUiRenderer.drawCircle(g, panelX + 14, panelY + 13, 7, 0xFFFF5F57);
        AppleUiRenderer.drawCircle(g, panelX + 25, panelY + 13, 7, 0xFFFEBC2E);
        AppleUiRenderer.drawCircle(g, panelX + 36, panelY + 13, 7, 0xFF28C840);
        g.drawString(font, "Ye Trails", panelX + 52, panelY + 12, AppleUiRenderer.TEXT_PRIMARY, false);

        int closeX = panelX + panelW - 28;
        int closeY = panelY + 8;
        boolean closeHover = inside(mouseX, mouseY, closeX, closeY, 20, 20);
        AppleUiRenderer.drawRoundedRect(g, closeX, closeY, 20, 20, 7, closeHover ? 0x44FF453A : AppleUiRenderer.BG_CARD);
        g.drawString(font, "×", closeX + 7, closeY + 6, closeHover ? AppleUiRenderer.APPLE_RED : AppleUiRenderer.TEXT_SECONDARY, false);

        // Tabs bar
        int tabY = panelY + 34;
        int tabW = (panelW - 24 - 12) / TABS.length;
        for (int i = 0; i < TABS.length; i++) {
            int tx = panelX + 12 + i * (tabW + 4);
            boolean sel = i == activeTab;
            boolean hov = inside(mouseX, mouseY, tx, tabY, tabW, 22);
            AppleUiRenderer.drawRoundedRect(g, tx, tabY, tabW, 22, 7,
                    sel ? AppleUiRenderer.APPLE_BLUE : (hov ? AppleUiRenderer.BG_CARD_HOVER : AppleUiRenderer.BG_CARD));
            int tw = font.width(TABS[i]);
            g.drawString(font, TABS[i], tx + (tabW - tw) / 2, tabY + 7, sel ? 0xFFFFFFFF : AppleUiRenderer.TEXT_SECONDARY, false);
        }

        int ry = tabY + 30;
        int rowX = panelX + 12;
        int rowW = panelW - 24;
        for (Item item : items) {
            item.render(g, font, rowX, ry, rowW, mouseX, mouseY, dt, toggleAnims, bindingItem);
            ry += item.height() + 5;
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        int btn = event.button();

        if (inside(mx, my, panelX + panelW - 28, panelY + 8, 20, 20)) {
            onClose();
            return true;
        }

        int tabY = panelY + 34;
        int tabW = (panelW - 24 - 12) / TABS.length;
        for (int i = 0; i < TABS.length; i++) {
            int tx = panelX + 12 + i * (tabW + 4);
            if (inside(mx, my, tx, tabY, tabW, 22)) {
                activeTab = i;
                rebuildTab();
                return true;
            }
        }

        int ry = tabY + 30;
        int rowX = panelX + 12;
        int rowW = panelW - 24;
        for (Item item : items) {
            int h = item.height();
            if (inside(mx, my, rowX, ry, rowW, h)) {
                if (item instanceof KeybindItem ki) {
                    if (btn == 1) {
                        ki.setter.accept(-1);
                        bindingItem = null;
                    } else {
                        bindingItem = (bindingItem == ki) ? null : ki;
                    }
                    TrailsConfig.save();
                    return true;
                }
                item.onClick(mx, rowX, rowW);
                if (item instanceof SliderItem si) {
                    draggingSlider = si;
                }
                TrailsConfig.save();
                return true;
            }
            ry += h + 5;
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
        int key = event.key();
        if (bindingItem != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_BACKSPACE) {
                bindingItem.setter.accept(bindingItem.defaultKey);
            } else {
                bindingItem.setter.accept(key);
            }
            bindingItem = null;
            TrailsConfig.save();
            return true;
        }
        if (key == TrailsConfig.get().menuKey || key == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static String getKeyName(int key) {
        if (key <= 0) return "未绑定";
        String name = GLFW.glfwGetKeyName(key, 0);
        if (name != null) return name.toUpperCase(Locale.ROOT);
        return switch (key) {
            case GLFW.GLFW_KEY_F6 -> "F6";
            case GLFW.GLFW_KEY_F7 -> "F7";
            case GLFW.GLFW_KEY_F8 -> "F8";
            case GLFW.GLFW_KEY_F9 -> "F9";
            case GLFW.GLFW_KEY_F10 -> "F10";
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> "RSHIFT";
            default -> "KEY_" + key;
        };
    }

    private interface Item {
        int height();
        void render(GuiGraphics g, net.minecraft.client.gui.Font font, int x, int y, int w, int mouseX, int mouseY,
                    float dt, Map<String, Float> anims, KeybindItem binding);
        void onClick(double mx, int x, int w);
    }

    private record ToggleItem(String id, String label, BooleanSupplier getter, Consumer<Boolean> setter) implements Item {
        @Override public int height() { return 24; }

        @Override
        public void render(GuiGraphics g, net.minecraft.client.gui.Font font, int x, int y, int w, int mouseX, int mouseY,
                           float dt, Map<String, Float> anims, KeybindItem binding) {
            boolean hover = inside(mouseX, mouseY, x, y, w, 24);
            boolean on = getter.getAsBoolean();
            AppleUiRenderer.drawRoundedRect(g, x, y, w, 24, 7, hover ? AppleUiRenderer.BG_CARD_HOVER : AppleUiRenderer.BG_CARD);
            g.drawString(font, label, x + 10, y + 8, on ? AppleUiRenderer.TEXT_PRIMARY : AppleUiRenderer.TEXT_SECONDARY, false);

            float cur = anims.getOrDefault(id, on ? 1.0F : 0.0F);
            float next = AppleUiRenderer.smooth(cur, on ? 1.0F : 0.0F, 16.0F, dt);
            anims.put(id, next);
            AppleUiRenderer.drawPillSwitch(g, x + w - 34, y + 5, 26, 14, next);
        }

        @Override
        public void onClick(double mx, int x, int w) {
            setter.accept(!getter.getAsBoolean());
        }
    }

    private record CycleItem(String label, Supplier<String> valueGetter, Runnable onCycle) implements Item {
        @Override public int height() { return 24; }

        @Override
        public void render(GuiGraphics g, net.minecraft.client.gui.Font font, int x, int y, int w, int mouseX, int mouseY,
                           float dt, Map<String, Float> anims, KeybindItem binding) {
            boolean hover = inside(mouseX, mouseY, x, y, w, 24);
            AppleUiRenderer.drawRoundedRect(g, x, y, w, 24, 7, hover ? AppleUiRenderer.BG_CARD_HOVER : AppleUiRenderer.BG_CARD);
            g.drawString(font, label, x + 10, y + 8, AppleUiRenderer.TEXT_PRIMARY, false);

            String val = valueGetter.get();
            int bw = font.width(val) + 14;
            int bx = x + w - bw - 8;
            AppleUiRenderer.drawRoundedRect(g, bx, y + 4, bw, 16, 5, 0xFF282E3D);
            g.drawString(font, val, bx + 7, y + 8, AppleUiRenderer.APPLE_BLUE, false);
        }

        @Override
        public void onClick(double mx, int x, int w) {
            onCycle.run();
        }
    }

    private record KeybindItem(String label, IntSupplier getter, IntConsumer setter, int defaultKey) implements Item {
        @Override public int height() { return 26; }

        @Override
        public void render(GuiGraphics g, net.minecraft.client.gui.Font font, int x, int y, int w, int mouseX, int mouseY,
                           float dt, Map<String, Float> anims, KeybindItem binding) {
            boolean hover = inside(mouseX, mouseY, x, y, w, 26);
            boolean active = binding == this;
            AppleUiRenderer.drawRoundedRect(g, x, y, w, 26, 7, hover ? AppleUiRenderer.BG_CARD_HOVER : AppleUiRenderer.BG_CARD);
            g.drawString(font, label, x + 10, y + 9, AppleUiRenderer.TEXT_PRIMARY, false);

            String val = active ? "请按下按键..." : getKeyName(getter.getAsInt());
            int bw = Math.max(54, font.width(val) + 16);
            int bx = x + w - bw - 8;
            AppleUiRenderer.drawRoundedRect(g, bx, y + 4, bw, 18, 6, active ? AppleUiRenderer.APPLE_BLUE : 0xFF282E3D);
            g.drawString(font, val, bx + (bw - font.width(val)) / 2, y + 9, active ? 0xFFFFFFFF : AppleUiRenderer.TEXT_PRIMARY, false);
        }

        @Override
        public void onClick(double mx, int x, int w) {}
    }

    private record SliderItem(String label, DoubleSupplier getter, DoubleConsumer setter,
                              double min, double max, double step) implements Item {
        @Override public int height() { return 30; }

        @Override
        public void render(GuiGraphics g, net.minecraft.client.gui.Font font, int x, int y, int w, int mouseX, int mouseY,
                           float dt, Map<String, Float> anims, KeybindItem binding) {
            AppleUiRenderer.drawRoundedRect(g, x, y, w, 30, 7, AppleUiRenderer.BG_CARD);
            double val = getter.getAsDouble();
            String valStr = step >= 1.0 ? String.valueOf((int) Math.round(val)) : String.format(Locale.ROOT, "%.2f", val);
            g.drawString(font, label, x + 10, y + 5, AppleUiRenderer.TEXT_SECONDARY, false);
            g.drawString(font, valStr, x + w - font.width(valStr) - 10, y + 5, AppleUiRenderer.APPLE_BLUE, false);

            int bx = x + 10;
            int bw = w - 20;
            int by = y + 19;
            AppleUiRenderer.drawRoundedRect(g, bx, by, bw, 4, 2, AppleUiRenderer.TOGGLE_OFF);
            double pct = Math.max(0.0, Math.min(1.0, (val - min) / (max - min)));
            int fw = (int) Math.round(bw * pct);
            if (fw > 0) AppleUiRenderer.drawRoundedRect(g, bx, by, fw, 4, 2, AppleUiRenderer.APPLE_BLUE);
            AppleUiRenderer.drawCircle(g, bx + fw - 5, by - 3, 10, 0xFFFFFFFF);
        }

        @Override
        public void onClick(double mx, int x, int w) {
            updateFromMouse(mx, x, w);
        }

        void updateFromMouse(double mx, int x, int w) {
            int bx = x + 10;
            int bw = w - 20;
            double pct = Math.max(0.0, Math.min(1.0, (mx - bx) / (double) bw));
            double raw = min + pct * (max - min);
            double stepped = Math.round(raw / step) * step;
            setter.accept(Math.max(min, Math.min(max, stepped)));
        }
    }

    private record ButtonItem(String label, Runnable action) implements Item {
        @Override public int height() { return 24; }

        @Override
        public void render(GuiGraphics g, net.minecraft.client.gui.Font font, int x, int y, int w, int mouseX, int mouseY,
                           float dt, Map<String, Float> anims, KeybindItem binding) {
            boolean hover = inside(mouseX, mouseY, x, y, w, 24);
            AppleUiRenderer.drawRoundedRect(g, x, y, w, 24, 7, hover ? AppleUiRenderer.APPLE_BLUE : 0x260A84FF);
            int tw = font.width(label);
            g.drawString(font, label, x + (w - tw) / 2, y + 8, hover ? 0xFFFFFFFF : AppleUiRenderer.APPLE_BLUE, false);
        }

        @Override
        public void onClick(double mx, int x, int w) {
            action.run();
        }
    }
}
