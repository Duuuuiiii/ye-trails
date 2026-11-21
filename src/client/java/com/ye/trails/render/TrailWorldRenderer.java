package com.ye.trails.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.ye.trails.config.TrailsConfig;
import com.ye.trails.engine.ChunkTrailEngine;
import com.ye.trails.engine.WaypointManager;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * Renders 3D translucent chunk highlights, purple portal chunk borders, and waypoint pillars.
 */
public final class TrailWorldRenderer {
    public static final int PORTAL_RGB = 0xC084FC;

    private TrailWorldRenderer() {}

    public static void render(WorldRenderContext ctx) {
        TrailsConfig cfg = TrailsConfig.get();
        if (!cfg.showVisuals || !cfg.render3D) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        PoseStack poseStack = ctx.matrices();
        MultiBufferSource consumers = ctx.consumers();
        if (poseStack == null || consumers == null) return;

        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        int playerCx = mc.player.chunkPosition().x;
        int playerCz = mc.player.chunkPosition().z;
        int radius = cfg.renderRadius;

        float baseY = cfg.followPlayerY
                ? (float) (mc.player.getY() + cfg.followYOffset)
                : (float) cfg.renderY;

        int fillAlpha = (int) Math.max(0, Math.min(255, Math.round(cfg.fillAlpha * 255.0)));

        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        PoseStack.Pose pose = poseStack.last();

        // Pass 1: Translucent quads (must finish before requesting another unbuffered RenderType)
        if (fillAlpha > 0) {
            VertexConsumer quadConsumer = consumers.getBuffer(RenderTypes.debugQuads());
            for (Map.Entry<Long, ChunkTrailEngine.ChunkType> entry : ChunkTrailEngine.getChunks().entrySet()) {
                ChunkTrailEngine.ChunkType type = entry.getValue();
                if (!shouldShow(type, cfg)) continue;

                long packed = entry.getKey();
                int cx = ChunkPos.getX(packed);
                int cz = ChunkPos.getZ(packed);
                if (Math.abs(cx - playerCx) > radius || Math.abs(cz - playerCz) > radius) continue;

                int fillColor = (fillAlpha << 24) | colorFor(type);
                float x0 = cx * 16.0F;
                float z0 = cz * 16.0F;
                float x1 = x0 + 16.0F;
                float z1 = z0 + 16.0F;

                quadConsumer.addVertex(pose, x0, baseY, z0).setColor(fillColor);
                quadConsumer.addVertex(pose, x0, baseY, z1).setColor(fillColor);
                quadConsumer.addVertex(pose, x1, baseY, z1).setColor(fillColor);
                quadConsumer.addVertex(pose, x1, baseY, z0).setColor(fillColor);

                quadConsumer.addVertex(pose, x0, baseY, z0).setColor(fillColor);
                quadConsumer.addVertex(pose, x1, baseY, z0).setColor(fillColor);
                quadConsumer.addVertex(pose, x1, baseY, z1).setColor(fillColor);
                quadConsumer.addVertex(pose, x0, baseY, z1).setColor(fillColor);
            }
        }

        // Pass 2: Crisp chunk borders, portal highlights, and waypoint pillars
        VertexConsumer lineConsumer = consumers.getBuffer(RenderTypes.lines());
        for (Map.Entry<Long, ChunkTrailEngine.ChunkType> entry : ChunkTrailEngine.getChunks().entrySet()) {
            long packed = entry.getKey();
            ChunkTrailEngine.ChunkType type = entry.getValue();
            boolean isPortal = cfg.highlightPortals && ChunkTrailEngine.hasPortal(packed);
            if (!shouldShow(type, cfg) && !isPortal) continue;

            int cx = ChunkPos.getX(packed);
            int cz = ChunkPos.getZ(packed);
            if (Math.abs(cx - playerCx) > radius || Math.abs(cz - playerCz) > radius) continue;

            float x0 = cx * 16.0F;
            float z0 = cz * 16.0F;
            float x1 = x0 + 16.0F;
            float z1 = z0 + 16.0F;

            if (isPortal) {
                int portalLine = 0xFF000000 | PORTAL_RGB;
                float py = baseY + 0.05F;
                drawChunkBorder(lineConsumer, pose, x0, py, z0, x1, z1, portalLine, 3.0F);
                drawChunkBorder(lineConsumer, pose, x0, py + 0.35F, z0, x1, z1, portalLine, 2.0F);
            } else {
                int lineColor = 0xCC000000 | colorFor(type);
                drawChunkBorder(lineConsumer, pose, x0, baseY + 0.02F, z0, x1, z1, lineColor, 1.5F);
            }
        }

        if (cfg.highlightPortals) {
            for (long packed : ChunkTrailEngine.getPortalChunks()) {
                if (ChunkTrailEngine.getChunks().containsKey(packed)) continue;
                int cx = ChunkPos.getX(packed);
                int cz = ChunkPos.getZ(packed);
                if (Math.abs(cx - playerCx) > radius || Math.abs(cz - playerCz) > radius) continue;
                float x0 = cx * 16.0F;
                float z0 = cz * 16.0F;
                drawChunkBorder(lineConsumer, pose, x0, baseY + 0.05F, z0, x0 + 16.0F, z0 + 16.0F, 0xFF000000 | PORTAL_RGB, 3.0F);
            }
        }

        if (cfg.showWaypoints) {
            int minY = mc.level.getMinY();
            int maxY = minY + mc.level.getHeight();
            for (WaypointManager.Waypoint wp : WaypointManager.getAll()) {
                if (!wp.enabled) continue;
                double distSq = (wp.x - cam.x) * (wp.x - cam.x) + (wp.z - cam.z) * (wp.z - cam.z);
                if (distSq > 512.0 * 512.0) continue;
                float wx = wp.x + 0.5F;
                float wz = wp.z + 0.5F;
                drawLine(lineConsumer, pose, wx, minY, wz, wx, maxY, wz, 0xCC000000 | (wp.color & 0x00FFFFFF), 2.5F);
            }
        }

        poseStack.popPose();
    }

    private static void drawChunkBorder(VertexConsumer vc, PoseStack.Pose pose,
                                        float x0, float y, float z0, float x1, float z1,
                                        int color, float width) {
        drawLine(vc, pose, x0, y, z0, x1, y, z0, color, width);
        drawLine(vc, pose, x1, y, z0, x1, y, z1, color, width);
        drawLine(vc, pose, x1, y, z1, x0, y, z1, color, width);
        drawLine(vc, pose, x0, y, z1, x0, y, z0, color, width);
    }

    private static void drawLine(VertexConsumer vc, PoseStack.Pose pose,
                                 float x0, float y0, float z0,
                                 float x1, float y1, float z1, int color, float width) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float dz = z1 - z0;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-5f) return;
        float nx = dx / len, ny = dy / len, nz = dz / len;
        vc.addVertex(pose, x0, y0, z0).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
        vc.addVertex(pose, x1, y1, z1).setColor(color).setNormal(pose, nx, ny, nz).setLineWidth(width);
    }

    public static boolean shouldShow(ChunkTrailEngine.ChunkType type, TrailsConfig cfg) {
        return switch (type) {
            case OLD -> cfg.showOldChunks;
            case OLD_GEN -> cfg.showOldGenChunks;
            case UPDATING -> cfg.showUpdatingChunks;
            case NEW -> cfg.showNewChunks;
        };
    }

    public static int colorFor(ChunkTrailEngine.ChunkType type) {
        return switch (type) {
            case OLD -> 0x22D3EE;      // Cyan (player-loaded trail)
            case OLD_GEN -> 0xA78BFA;  // Violet (pre-1.17/1.16/1.13 old trail)
            case UPDATING -> 0xFBBF24; // Amber (being updated)
            case NEW -> 0xF43F5E;      // Coral red (newly generated)
        };
    }
}
