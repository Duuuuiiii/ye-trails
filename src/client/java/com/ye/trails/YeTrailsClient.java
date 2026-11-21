package com.ye.trails;

import com.ye.trails.config.TrailsConfig;
import com.ye.trails.engine.ChunkTrailEngine;
import com.ye.trails.engine.TerrainRegionCache;
import com.ye.trails.engine.WaypointManager;
import com.ye.trails.render.TrailRadarHud;
import com.ye.trails.render.TrailWorldRenderer;
import com.ye.trails.ui.TrailsSettingsScreen;
import com.ye.trails.ui.WorldMapScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Client entrypoint for Ye Trails (1.21.11 Fabric).
 * Defaults: F7 = Settings | P = Fullscreen Map | F9 reserved for ye-mod
 */
public class YeTrailsClient implements ClientModInitializer {
    private static boolean prevMenuDown = false;
    private static boolean prevMapDown = false;
    private static boolean prevToggleDown = false;

    @Override
    public void onInitializeClient() {
        TrailsConfig.load();

        WorldRenderEvents.AFTER_ENTITIES.register(TrailWorldRenderer::render);
        HudRenderCallback.EVENT.register(TrailRadarHud::render);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ChunkTrailEngine.onWorldOrDimensionCheck();

            if (client.screen == null && client.getWindow() != null) {
                long handle = client.getWindow().handle();
                TrailsConfig cfg = TrailsConfig.get();

                boolean menuDown = cfg.menuKey > 0 && GLFW.glfwGetKey(handle, cfg.menuKey) == GLFW.GLFW_PRESS;
                if (menuDown && !prevMenuDown) {
                    client.setScreen(new TrailsSettingsScreen());
                }
                prevMenuDown = menuDown;

                boolean mapDown = cfg.mapKey > 0 && GLFW.glfwGetKey(handle, cfg.mapKey) == GLFW.GLFW_PRESS;
                if (mapDown && !prevMapDown) {
                    client.setScreen(new WorldMapScreen());
                }
                prevMapDown = mapDown;

                boolean toggleDown = cfg.toggleKey > 0 && GLFW.glfwGetKey(handle, cfg.toggleKey) == GLFW.GLFW_PRESS;
                if (toggleDown && !prevToggleDown) {
                    cfg.showVisuals = !cfg.showVisuals;
                    TrailsConfig.save();
                    if (client.player != null) {
                        client.player.displayClientMessage(
                                Component.literal("§b[ye-trails] §f路径显示已" + (cfg.showVisuals ? "§a开启" : "§e隐藏 §7(后台持续检测中)")),
                                true
                        );
                    }
                }
                prevToggleDown = toggleDown;
            } else {
                prevMenuDown = false;
                prevMapDown = false;
                prevToggleDown = false;
            }
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            TrailsConfig.save();
            TerrainRegionCache.saveAllDirty();
            WaypointManager.save();
        });
    }
}
