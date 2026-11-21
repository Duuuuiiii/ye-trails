package com.ye.trails.ui;

import com.ye.trails.config.TrailsConfig;
import com.ye.trails.engine.ChunkTrailEngine;
import com.ye.trails.engine.TerrainRegionCache;
import com.ye.trails.engine.WaypointManager;
import com.ye.trails.render.TrailWorldRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Map;

/**
 * Full-screen interactive Xaero-style World Map with terrain, chunk trails,
 * purple portal chunk borders, cursor coordinates, and waypoints (default O).
 */
public class WorldMapScreen extends Screen {
    private static final int BG = 0xFF090C12;
    private static final int BAR_BG = 0xE60E121B;
    private static final int BORDER = 0xFF252D3E;
    private static final int ACCENT = 0xFF22D3EE;
    private static final int TEXT_MAIN = 0xFFF1F5F9;
    private static final int TEXT_MUTED = 0xFF94A3B8;

    private double camBlockX = 0;
    private double camBlockZ = 0;
    private double pxPerChunk = 12.0;

    private boolean dragging = false;
    private boolean showWaypointDrawer = false;

    // Add waypoint modal state
    private boolean addingWaypoint = false;
    private int wpX, wpY, wpZ;
    private String wpNameInput = "路径点";
    private int wpColorIndex = 0;

    public WorldMapScreen() {
        super(Component.literal("Ye Trails World Map"));
    }

    @Override
    protected void init() {
        if (minecraft != null && minecraft.player != null && camBlockX == 0 && camBlockZ == 0) {
            camBlockX = minecraft.player.getX();
            camBlockZ = minecraft.player.getZ();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, BG);

        TrailsConfig cfg = TrailsConfig.get();
        int topH = 28;
        int bottomH = 22;
        int mapH = this.height - topH - bottomH;
        int mapCenterX = this.width / 2;
        int mapCenterY = topH + mapH / 2;

        double camChunkX = camBlockX / 16.0;
        double camChunkZ = camBlockZ / 16.0;

        g.enableScissor(0, topH, this.width, this.height - bottomH);

        // 1. Terrain regions
        if (cfg.showTerrainOnMap) {
            double regionPxD = TerrainRegionCache.CHUNKS_PER_REGION * pxPerChunk;
            int regionPx = Math.max(1, (int) Math.round(regionPxD));
            for (TerrainRegionCache.RegionTile tile : TerrainRegionCache.getRegions()) {
                int rStartCx = tile.rx * TerrainRegionCache.CHUNKS_PER_REGION;
                int rStartCz = tile.rz * TerrainRegionCache.CHUNKS_PER_REGION;
                int sx = (int) Math.round(mapCenterX + (rStartCx - camChunkX) * pxPerChunk);
                int sz = (int) Math.round(mapCenterY + (rStartCz - camChunkZ) * pxPerChunk);
                if (sx + regionPx < 0 || sx > this.width || sz + regionPx < topH || sz > this.height - bottomH) {
                    continue;
                }
                tile.uploadIfDirty();
                g.blit(RenderPipelines.GUI_TEXTURED, tile.textureId,
                        sx, sz, 0.0F, 0.0F,
                        regionPx, regionPx,
                        TerrainRegionCache.REGION_PIXELS, TerrainRegionCache.REGION_PIXELS,
                        TerrainRegionCache.REGION_PIXELS, TerrainRegionCache.REGION_PIXELS);
            }
        }

        // 2. Chunk trail overlay
        int chunkPx = Math.max(1, (int) Math.ceil(pxPerChunk));
        for (Map.Entry<Long, ChunkTrailEngine.ChunkType> entry : ChunkTrailEngine.getChunks().entrySet()) {
            ChunkTrailEngine.ChunkType type = entry.getValue();
            if (!TrailWorldRenderer.shouldShow(type, cfg)) continue;

            int cx = ChunkPos.getX(entry.getKey());
            int cz = ChunkPos.getZ(entry.getKey());
            int sx = (int) Math.floor(mapCenterX + (cx - camChunkX) * pxPerChunk);
            int sz = (int) Math.floor(mapCenterY + (cz - camChunkZ) * pxPerChunk);
            if (sx + chunkPx < 0 || sx > this.width || sz + chunkPx < topH || sz > this.height - bottomH) {
                continue;
            }
            int alpha = cfg.showTerrainOnMap
                    ? (type == ChunkTrailEngine.ChunkType.NEW ? 0x3C000000 : 0x6E000000)
                    : (type == ChunkTrailEngine.ChunkType.NEW ? 0x66000000 : 0xCC000000);
            g.fill(sx, sz, sx + chunkPx, sz + chunkPx, alpha | TrailWorldRenderer.colorFor(type));
        }

        // 3. Purple border for chunks containing Nether Portals
        if (cfg.highlightPortals) {
            int borderSize = Math.max(3, chunkPx);
            for (long packed : ChunkTrailEngine.getPortalChunks()) {
                int cx = ChunkPos.getX(packed);
                int cz = ChunkPos.getZ(packed);
                int sx = (int) Math.floor(mapCenterX + (cx - camChunkX) * pxPerChunk);
                int sz = (int) Math.floor(mapCenterY + (cz - camChunkZ) * pxPerChunk);
                if (sx + borderSize < 0 || sx > this.width || sz + borderSize < topH || sz > this.height - bottomH) {
                    continue;
                }
                g.renderOutline(sx, sz, borderSize, borderSize, 0xFF000000 | TrailWorldRenderer.PORTAL_RGB);
                if (borderSize >= 8) {
                    g.renderOutline(sx + 1, sz + 1, borderSize - 2, borderSize - 2, 0xAA000000 | TrailWorldRenderer.PORTAL_RGB);
                }
            }
        }

        // 4. Waypoints
        if (cfg.showWaypoints) {
            for (WaypointManager.Waypoint wp : WaypointManager.getAll()) {
                if (!wp.enabled) continue;
                int wx = (int) Math.round(mapCenterX + ((wp.x / 16.0) - camChunkX) * pxPerChunk);
                int wz = (int) Math.round(mapCenterY + ((wp.z / 16.0) - camChunkZ) * pxPerChunk);
                if (wx < -40 || wx > this.width + 40 || wz < topH - 20 || wz > this.height - bottomH + 20) continue;

                int c = wp.color | 0xFF000000;
                g.fill(wx - 3, wz - 3, wx + 3, wz + 3, c);
                g.renderOutline(wx - 3, wz - 3, 6, 6, 0xFF000000);
                int tw = font.width(wp.name);
                g.fill(wx - tw / 2 - 3, wz - 16, wx + tw / 2 + 3, wz - 5, 0xB8090C12);
                g.drawString(font, wp.name, wx - tw / 2, wz - 14, c, false);
            }
        }

        // 5. Player marker
        if (minecraft != null && minecraft.player != null) {
            int px = (int) Math.round(mapCenterX + ((minecraft.player.getX() / 16.0) - camChunkX) * pxPerChunk);
            int pz = (int) Math.round(mapCenterY + ((minecraft.player.getZ() / 16.0) - camChunkZ) * pxPerChunk);
            g.fill(px - 3, pz - 3, px + 3, pz + 3, 0xFFFFFFFF);
            g.renderOutline(px - 3, pz - 3, 6, 6, 0xFF000000);
            double yawRad = Math.toRadians(minecraft.player.getYRot());
            int dx = (int) Math.round(-Math.sin(yawRad) * 8.0);
            int dz = (int) Math.round(Math.cos(yawRad) * 8.0);
            g.fill(px + dx - 1, pz + dz - 1, px + dx + 2, pz + dz + 2, ACCENT);
        }

        g.disableScissor();

        renderTopBar(g, cfg, mouseX, mouseY, topH);
        renderBottomBar(g, mouseX, mouseY, topH, bottomH, mapCenterX, mapCenterY, camChunkX, camChunkZ);

        if (showWaypointDrawer) {
            renderWaypointDrawer(g, topH, this.height - topH - bottomH, mouseX, mouseY);
        }

        if (addingWaypoint) {
            renderAddWaypointModal(g, mouseX, mouseY);
        }
    }

    private void renderTopBar(GuiGraphics g, TrailsConfig cfg, int mouseX, int mouseY, int topH) {
        g.fill(0, 0, this.width, topH, BAR_BG);
        g.fill(0, topH - 1, this.width, topH, BORDER);
        g.drawString(font, "ye-trails 路径大地图", 10, 10, ACCENT, false);

        int bx = 135;
        bx = drawChip(g, bx, 5, "地形", cfg.showTerrainOnMap, mouseX, mouseY);
        bx = drawChip(g, bx, 5, "路径", cfg.showOldChunks || cfg.showNewChunks, mouseX, mouseY);
        bx = drawChip(g, bx, 5, "地狱门", cfg.highlightPortals, mouseX, mouseY);
        bx = drawChip(g, bx, 5, "路径点", cfg.showWaypoints, mouseX, mouseY);
        bx = drawChip(g, bx, 5, "回到玩家(C)", false, mouseX, mouseY);
        drawChip(g, bx, 5, "路径点列表", showWaypointDrawer, mouseX, mouseY);

        int closeX = this.width - 24;
        boolean ch = inside(mouseX, mouseY, closeX, 5, 18, 18);
        if (ch) g.fill(closeX, 5, closeX + 18, 23, 0x33EF4444);
        g.drawString(font, "×", closeX + 6, 10, ch ? 0xFFFCA5A5 : TEXT_MUTED, false);
    }

    private int drawChip(GuiGraphics g, int x, int y, String label, boolean active, int mouseX, int mouseY) {
        int w = font.width(label) + 12;
        int h = 18;
        boolean hover = inside(mouseX, mouseY, x, y, w, h);
        g.fill(x, y, x + w, y + h, active ? 0x3322D3EE : (hover ? 0xFF1E2536 : 0xFF141925));
        g.renderOutline(x, y, w, h, active ? ACCENT : BORDER);
        g.drawString(font, label, x + 6, y + 5, active ? ACCENT : TEXT_MUTED, false);
        return x + w + 5;
    }

    private void renderBottomBar(GuiGraphics g, int mouseX, int mouseY, int topH, int bottomH,
                                 int mapCenterX, int mapCenterY, double camChunkX, double camChunkZ) {
        int by = this.height - bottomH;
        g.fill(0, by, this.width, this.height, BAR_BG);
        g.fill(0, by, this.width, by + 1, BORDER);

        // Cursor coordinates
        double worldChunkX = camChunkX + (mouseX - mapCenterX) / pxPerChunk;
        double worldChunkZ = camChunkZ + (mouseY - mapCenterY) / pxPerChunk;
        int cursorX = (int) Math.floor(worldChunkX * 16.0);
        int cursorZ = (int) Math.floor(worldChunkZ * 16.0);
        int cx = Math.floorDiv(cursorX, 16);
        int cz = Math.floorDiv(cursorZ, 16);

        ChunkTrailEngine.ChunkType type = ChunkTrailEngine.getChunks().get(ChunkPos.asLong(cx, cz));
        String typeLabel = switch (type) {
            case OLD -> "老路径";
            case OLD_GEN -> "远古路径";
            case UPDATING -> "更新中";
            case NEW -> "新区块";
            case null -> "未探测";
        };

        String cursorInfo = String.format("指针: %d, %d  (区块 %d, %d · %s)", cursorX, cursorZ, cx, cz, typeLabel);
        g.drawString(font, cursorInfo, 10, by + 7, TEXT_MAIN, false);

        String hint = "左键拖拽 · 滚轮缩放 · 右键添加路径点";
        g.drawString(font, hint, this.width - font.width(hint) - 10, by + 7, TEXT_MUTED, false);
    }

    private void renderWaypointDrawer(GuiGraphics g, int topY, int h, int mouseX, int mouseY) {
        int dw = 185;
        int dx = this.width - dw;
        g.fill(dx, topY, this.width, topY + h, 0xEB0E121B);
        g.fill(dx, topY, dx + 1, topY + h, BORDER);

        g.drawString(font, "路径点列表", dx + 10, topY + 10, ACCENT, false);
        List<WaypointManager.Waypoint> list = WaypointManager.getAll();
        if (list.isEmpty()) {
            g.drawString(font, "右键地图任意位置添加", dx + 10, topY + 30, TEXT_MUTED, false);
            return;
        }

        int ry = topY + 28;
        for (WaypointManager.Waypoint wp : list) {
            if (ry + 28 > topY + h) break;
            boolean hover = inside(mouseX, mouseY, dx + 8, ry, dw - 16, 26);
            g.fill(dx + 8, ry, dx + dw - 8, ry + 26, hover ? 0xFF1B2232 : 0xFF141925);
            g.fill(dx + 8, ry + 4, dx + 10, ry + 22, wp.enabled ? (wp.color | 0xFF000000) : 0xFF475569);
            g.drawString(font, wp.name, dx + 15, ry + 4, wp.enabled ? TEXT_MAIN : TEXT_MUTED, false);
            g.drawString(font, wp.x + ", " + wp.z, dx + 15, ry + 15, TEXT_MUTED, false);

            // Delete button
            int delX = dx + dw - 24;
            g.drawString(font, "×", delX, ry + 9, 0xFFFCA5A5, false);
            ry += 30;
        }
    }

    private void renderAddWaypointModal(GuiGraphics g, int mouseX, int mouseY) {
        g.fill(0, 0, this.width, this.height, 0x80000000);
        int mw = 220, mh = 128;
        int mx = (this.width - mw) / 2;
        int my = (this.height - mh) / 2;
        g.fill(mx, my, mx + mw, my + mh, 0xFF101520);
        g.renderOutline(mx, my, mw, mh, ACCENT);
        g.drawString(font, "添加路径点 (" + wpX + ", " + wpZ + ")", mx + 10, my + 10, TEXT_MAIN, false);

        // Name input box
        g.fill(mx + 10, my + 28, mx + mw - 10, my + 48, 0xFF182030);
        g.renderOutline(mx + 10, my + 28, mw - 20, 20, ACCENT);
        g.drawString(font, wpNameInput + "_", mx + 16, my + 34, TEXT_MAIN, false);

        // Color swatches
        int sx = mx + 10;
        for (int i = 0; i < WaypointManager.PALETTE.length; i++) {
            int c = WaypointManager.PALETTE[i];
            g.fill(sx, my + 58, sx + 18, my + 74, c);
            if (i == wpColorIndex) {
                g.renderOutline(sx - 1, my + 57, 20, 18, 0xFFFFFFFF);
            }
            sx += 24;
        }

        // Confirm / Cancel
        int okX = mx + 10, okY = my + 92, btnW = 94, btnH = 22;
        g.fill(okX, okY, okX + btnW, okY + btnH, 0x3322D3EE);
        g.renderOutline(okX, okY, btnW, btnH, ACCENT);
        g.drawString(font, "确认添加", okX + 24, okY + 7, ACCENT, false);

        int cancelX = mx + mw - 10 - btnW;
        g.fill(cancelX, okY, cancelX + btnW, okY + btnH, 0xFF182030);
        g.renderOutline(cancelX, okY, btnW, btnH, BORDER);
        g.drawString(font, "取消", cancelX + 36, okY + 7, TEXT_MUTED, false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        int btn = event.button();

        if (addingWaypoint) {
            int mw = 220, mh = 128;
            int boxX = (this.width - mw) / 2;
            int boxY = (this.height - mh) / 2;
            int sx = boxX + 10;
            for (int i = 0; i < WaypointManager.PALETTE.length; i++) {
                if (inside(mx, my, sx, boxY + 58, 18, 16)) {
                    wpColorIndex = i;
                    return true;
                }
                sx += 24;
            }
            int okX = boxX + 10, okY = boxY + 92, btnW = 94, btnH = 22;
            if (inside(mx, my, okX, okY, btnW, btnH)) {
                String finalName = wpNameInput.isBlank() ? "路径点" : wpNameInput.trim();
                WaypointManager.add(finalName, wpX, wpY, wpZ, WaypointManager.PALETTE[wpColorIndex]);
                addingWaypoint = false;
                return true;
            }
            int cancelX = boxX + mw - 10 - btnW;
            if (inside(mx, my, cancelX, okY, btnW, btnH)) {
                addingWaypoint = false;
                return true;
            }
            return true;
        }

        int topH = 28;
        int bottomH = 22;

        // Top bar buttons
        if (my < topH) {
            if (inside(mx, my, this.width - 24, 5, 18, 18)) {
                onClose();
                return true;
            }
            TrailsConfig cfg = TrailsConfig.get();
            int bx = 135;
            if (hitChip(mx, my, bx, "地形")) { cfg.showTerrainOnMap = !cfg.showTerrainOnMap; TrailsConfig.save(); return true; }
            bx += font.width("地形") + 17;
            if (hitChip(mx, my, bx, "路径")) {
                boolean next = !(cfg.showOldChunks || cfg.showNewChunks);
                cfg.showOldChunks = next; cfg.showOldGenChunks = next; cfg.showUpdatingChunks = next; cfg.showNewChunks = next;
                TrailsConfig.save();
                return true;
            }
            bx += font.width("路径") + 17;
            if (hitChip(mx, my, bx, "地狱门")) { cfg.highlightPortals = !cfg.highlightPortals; TrailsConfig.save(); return true; }
            bx += font.width("地狱门") + 17;
            if (hitChip(mx, my, bx, "路径点")) { cfg.showWaypoints = !cfg.showWaypoints; TrailsConfig.save(); return true; }
            bx += font.width("路径点") + 17;
            if (hitChip(mx, my, bx, "回到玩家(C)")) {
                if (minecraft != null && minecraft.player != null) {
                    camBlockX = minecraft.player.getX();
                    camBlockZ = minecraft.player.getZ();
                }
                return true;
            }
            bx += font.width("回到玩家(C)") + 17;
            if (hitChip(mx, my, bx, "路径点列表")) { showWaypointDrawer = !showWaypointDrawer; return true; }
            return true;
        }

        // Waypoint drawer clicks
        if (showWaypointDrawer && mx >= this.width - 185 && my < this.height - bottomH) {
            int dx = this.width - 185;
            int ry = topH + 28;
            for (WaypointManager.Waypoint wp : WaypointManager.getAll()) {
                if (inside(mx, my, dx + 8, ry, 169, 26)) {
                    if (mx >= dx + 185 - 28) {
                        WaypointManager.remove(wp.id);
                    } else if (btn == 1) {
                        WaypointManager.toggle(wp.id);
                    } else {
                        camBlockX = wp.x;
                        camBlockZ = wp.z;
                    }
                    return true;
                }
                ry += 30;
            }
            return true;
        }

        // Map area clicks
        if (my >= topH && my < this.height - bottomH) {
            if (btn == 1) {
                int mapH = this.height - topH - bottomH;
                int mapCenterX = this.width / 2;
                int mapCenterY = topH + mapH / 2;
                double worldChunkX = (camBlockX / 16.0) + (mx - mapCenterX) / pxPerChunk;
                double worldChunkZ = (camBlockZ / 16.0) + (my - mapCenterY) / pxPerChunk;
                wpX = (int) Math.floor(worldChunkX * 16.0);
                wpZ = (int) Math.floor(worldChunkZ * 16.0);
                wpY = minecraft != null && minecraft.player != null ? minecraft.player.getBlockY() : 64;
                wpNameInput = "路径点";
                addingWaypoint = true;
                return true;
            } else if (btn == 0) {
                dragging = true;
                return true;
            }
        }

        return super.mouseClicked(event, doubleClick);
    }

    private boolean hitChip(double mx, double my, int x, String label) {
        return inside(mx, my, x, 5, font.width(label) + 12, 18);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging && !addingWaypoint) {
            double blocksPerPx = 16.0 / pxPerChunk;
            camBlockX -= dx * blocksPerPx;
            camBlockZ -= dy * blocksPerPx;
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        dragging = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (addingWaypoint) return true;
        double factor = scrollY > 0 ? 1.2 : (1.0 / 1.2);
        pxPerChunk = Math.max(2.0, Math.min(48.0, pxPerChunk * factor));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (addingWaypoint) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                addingWaypoint = false;
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                String finalName = wpNameInput.isBlank() ? "路径点" : wpNameInput.trim();
                WaypointManager.add(finalName, wpX, wpY, wpZ, WaypointManager.PALETTE[wpColorIndex]);
                addingWaypoint = false;
                return true;
            }
            if (key == GLFW.GLFW_KEY_BACKSPACE && !wpNameInput.isEmpty()) {
                wpNameInput = wpNameInput.substring(0, wpNameInput.length() - 1);
                return true;
            }
            return true;
        }

        if (key == GLFW.GLFW_KEY_C && minecraft != null && minecraft.player != null) {
            camBlockX = minecraft.player.getX();
            camBlockZ = minecraft.player.getZ();
            return true;
        }
        if (key == GLFW.GLFW_KEY_O || key == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (addingWaypoint && event.isAllowedChatCharacter() && wpNameInput.length() < 24) {
            wpNameInput += event.codepointAsString();
            return true;
        }
        return super.charTyped(event);
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }
}
