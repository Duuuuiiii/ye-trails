package com.ye.trails.render;

import com.ye.trails.config.TrailsConfig;
import com.ye.trails.engine.ChunkTrailEngine;
import com.ye.trails.engine.TerrainRegionCache;
import com.ye.trails.engine.WaypointManager;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.level.ChunkPos;

import java.util.Map;

/**
 * Customizable 2D minimap showing real terrain, chunk trails, purple portal chunk borders, and waypoints.
 */
public final class TrailRadarHud {
    private TrailRadarHud() {}

    public static void render(GuiGraphics g, DeltaTracker deltaTracker) {
        TrailsConfig cfg = TrailsConfig.get();
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;
        if (!cfg.showVisuals || !cfg.showRadar) return;

        int size = Math.max(72, Math.min(240, cfg.radarSize));
        int cell = Math.max(2, Math.min(16, cfg.radarChunkPx));
        int footerH = 16;

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        int x = switch (cfg.radarAnchor) {
            case TOP_LEFT, BOTTOM_LEFT -> cfg.radarOffsetX;
            case TOP_RIGHT, BOTTOM_RIGHT -> screenW - size - cfg.radarOffsetX;
        };
        int y = switch (cfg.radarAnchor) {
            case TOP_LEFT, TOP_RIGHT -> cfg.radarOffsetY;
            case BOTTOM_LEFT, BOTTOM_RIGHT -> screenH - (size + footerH) - cfg.radarOffsetY;
        };

        x = Math.max(2, Math.min(screenW - size - 2, x));
        y = Math.max(2, Math.min(screenH - size - footerH - 2, y));

        // Container
        g.fill(x, y, x + size, y + size + footerH, 0xCC0D1118);
        g.renderOutline(x, y, size, size + footerH, 0xFF252D3D);
        g.fill(x, y, x + size, y + 2, 0xFF22D3EE);

        int centerX = x + size / 2;
        int centerY = y + size / 2;
        double playerChunkX = mc.player.getX() / 16.0;
        double playerChunkZ = mc.player.getZ() / 16.0;
        int playerCx = mc.player.chunkPosition().x;
        int playerCz = mc.player.chunkPosition().z;
        int chunkRadius = (size / 2) / cell + 2;

        g.enableScissor(x + 1, y + 2, x + size - 1, y + size);

        // 1. Real terrain layer
        if (cfg.showTerrainOnMap) {
            int regionPx = TerrainRegionCache.CHUNKS_PER_REGION * cell;
            for (TerrainRegionCache.RegionTile tile : TerrainRegionCache.getRegions()) {
                int rStartCx = tile.rx * TerrainRegionCache.CHUNKS_PER_REGION;
                int rStartCz = tile.rz * TerrainRegionCache.CHUNKS_PER_REGION;
                int rxScreen = (int) Math.round(centerX + (rStartCx - playerChunkX) * cell);
                int rzScreen = (int) Math.round(centerY + (rStartCz - playerChunkZ) * cell);
                if (rxScreen + regionPx < x || rxScreen > x + size || rzScreen + regionPx < y || rzScreen > y + size) {
                    continue;
                }
                tile.uploadIfDirty();
                g.blit(RenderPipelines.GUI_TEXTURED, tile.textureId,
                        rxScreen, rzScreen, 0.0F, 0.0F,
                        regionPx, regionPx,
                        TerrainRegionCache.REGION_PIXELS, TerrainRegionCache.REGION_PIXELS,
                        TerrainRegionCache.REGION_PIXELS, TerrainRegionCache.REGION_PIXELS);
            }
        }

        // 2. Chunk trail overlay
        for (Map.Entry<Long, ChunkTrailEngine.ChunkType> entry : ChunkTrailEngine.getChunks().entrySet()) {
            ChunkTrailEngine.ChunkType type = entry.getValue();
            if (!TrailWorldRenderer.shouldShow(type, cfg)) continue;

            int cx = ChunkPos.getX(entry.getKey());
            int cz = ChunkPos.getZ(entry.getKey());
            if (Math.abs(cx - playerCx) > chunkRadius || Math.abs(cz - playerCz) > chunkRadius) continue;

            int px = (int) Math.round(centerX + (cx - playerChunkX) * cell);
            int py = (int) Math.round(centerY + (cz - playerChunkZ) * cell);
            int alpha = cfg.showTerrainOnMap
                    ? (type == ChunkTrailEngine.ChunkType.NEW ? 0x45000000 : 0x7A000000)
                    : (type == ChunkTrailEngine.ChunkType.NEW ? 0x66000000 : 0xD8000000);
            g.fill(px, py, px + cell, py + cell, alpha | TrailWorldRenderer.colorFor(type));
        }

        // 3. Purple border for chunks containing Nether Portals
        if (cfg.highlightPortals) {
            for (long packed : ChunkTrailEngine.getPortalChunks()) {
                int cx = ChunkPos.getX(packed);
                int cz = ChunkPos.getZ(packed);
                if (Math.abs(cx - playerCx) > chunkRadius || Math.abs(cz - playerCz) > chunkRadius) continue;
                int px = (int) Math.round(centerX + (cx - playerChunkX) * cell);
                int py = (int) Math.round(centerY + (cz - playerChunkZ) * cell);
                g.renderOutline(px, py, Math.max(3, cell), Math.max(3, cell), 0xFF000000 | TrailWorldRenderer.PORTAL_RGB);
            }
        }

        // 4. Waypoints
        if (cfg.showWaypoints) {
            for (WaypointManager.Waypoint wp : WaypointManager.getAll()) {
                if (!wp.enabled) continue;
                int wx = (int) Math.round(centerX + ((wp.x / 16.0) - playerChunkX) * cell);
                int wz = (int) Math.round(centerY + ((wp.z / 16.0) - playerChunkZ) * cell);
                if (wx >= x && wx <= x + size && wz >= y && wz <= y + size) {
                    g.fill(wx - 2, wz - 2, wx + 2, wz + 2, wp.color | 0xFF000000);
                    g.renderOutline(wx - 2, wz - 2, 4, 4, 0xFF000000);
                }
            }
        }

        // 5. Player crosshair & facing dot
        g.fill(centerX - 1, centerY - 1, centerX + 2, centerY + 2, 0xFFFFFFFF);
        double yawRad = Math.toRadians(mc.player.getYRot());
        int dirX = (int) Math.round(-Math.sin(yawRad) * 5.0);
        int dirZ = (int) Math.round(Math.cos(yawRad) * 5.0);
        g.fill(centerX + dirX - 1, centerY + dirZ - 1, centerX + dirX + 1, centerY + dirZ + 1, 0xFF38BDF8);

        g.disableScissor();

        // Footer stats
        g.fill(x + 1, y + size, x + size - 1, y + size + 1, 0xFF252D3D);
        String stats = "路径:" + ChunkTrailEngine.getOldCount() + " 门:" + ChunkTrailEngine.getPortalCount();
        g.drawString(mc.font, stats, x + 5, y + size + 4, 0xFFE2E8F0, false);
    }
}
