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

/**
 * Clean 2D minimap showing terrain, red new-chunk overlay (old trails stay natural terrain color),
 * purple portal chunk borders, and waypoints — with no bottom text clutter.
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

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        int x = switch (cfg.radarAnchor) {
            case TOP_LEFT, BOTTOM_LEFT -> cfg.radarOffsetX;
            case TOP_RIGHT, BOTTOM_RIGHT -> screenW - size - cfg.radarOffsetX;
        };
        int y = switch (cfg.radarAnchor) {
            case TOP_LEFT, TOP_RIGHT -> cfg.radarOffsetY;
            case BOTTOM_LEFT, BOTTOM_RIGHT -> screenH - size - cfg.radarOffsetY;
        };

        x = Math.max(2, Math.min(screenW - size - 2, x));
        y = Math.max(2, Math.min(screenH - size - 2, y));

        // Clean container (no bottom text)
        g.fill(x, y, x + size, y + size, 0xCC0D1118);
        g.renderOutline(x, y, size, size, 0xFF252D3D);

        int centerX = x + size / 2;
        int centerY = y + size / 2;
        double playerChunkX = mc.player.getX() / 16.0;
        double playerChunkZ = mc.player.getZ() / 16.0;
        int playerCx = mc.player.chunkPosition().x;
        int playerCz = mc.player.chunkPosition().z;
        int chunkRadius = (size / 2) / cell + 2;

        g.enableScissor(x + 1, y + 1, x + size - 1, y + size - 1);

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
            if (cfg.showTerrainOnMap) {
                g.blit(RenderPipelines.GUI_TEXTURED, tile.textureId,
                        rxScreen, rzScreen, 0.0F, 0.0F,
                        regionPx, regionPx,
                        TerrainRegionCache.REGION_PIXELS, TerrainRegionCache.REGION_PIXELS,
                        TerrainRegionCache.REGION_PIXELS, TerrainRegionCache.REGION_PIXELS);
            }
            if (cfg.showNewChunks) {
                g.blit(RenderPipelines.GUI_TEXTURED, tile.overlayTextureId,
                        rxScreen, rzScreen, 0.0F, 0.0F,
                        regionPx, regionPx,
                        TerrainRegionCache.CHUNKS_PER_REGION, TerrainRegionCache.CHUNKS_PER_REGION,
                        TerrainRegionCache.CHUNKS_PER_REGION, TerrainRegionCache.CHUNKS_PER_REGION);
            }
        }

        // Purple border ONLY on chunks containing Nether Portals
        if (cfg.highlightPortals) {
            for (int cz = playerCz - chunkRadius; cz <= playerCz + chunkRadius; cz++) {
                for (int cx = playerCx - chunkRadius; cx <= playerCx + chunkRadius; cx++) {
                    if (ChunkTrailEngine.hasPortal(ChunkPos.asLong(cx, cz))) {
                        int px = (int) Math.round(centerX + (cx - playerChunkX) * cell);
                        int py = (int) Math.round(centerY + (cz - playerChunkZ) * cell);
                        g.renderOutline(px, py, Math.max(3, cell), Math.max(3, cell), 0xFF000000 | TrailWorldRenderer.PORTAL_RGB);
                    }
                }
            }
        }

        // Waypoints
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

        // Player crosshair & facing dot
        g.fill(centerX - 1, centerY - 1, centerX + 2, centerY + 2, 0xFFFFFFFF);
        double yawRad = Math.toRadians(mc.player.getYRot());
        int dirX = (int) Math.round(-Math.sin(yawRad) * 5.0);
        int dirZ = (int) Math.round(Math.cos(yawRad) * 5.0);
        g.fill(centerX + dirX - 1, centerY + dirZ - 1, centerX + dirX + 1, centerY + dirZ + 1, 0xFF38BDF8);

        g.disableScissor();
    }
}
