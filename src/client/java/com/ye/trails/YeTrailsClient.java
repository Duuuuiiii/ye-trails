package com.ye.trails;

import com.mojang.blaze3d.platform.InputConstants;
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
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Client entrypoint for Ye Trails (1.21.11 Fabric).
 * F7: Settings | F8: Toggle Visuals (background detection always active) | O: Fullscreen Map
 */
public class YeTrailsClient implements ClientModInitializer {
    private static KeyMapping menuKey;
    private static KeyMapping toggleKey;
    private static KeyMapping mapKey;

    @Override
    public void onInitializeClient() {
        TrailsConfig.load();

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("ye_trails", "general"));
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.ye_trails.menu",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F7,
                category
        ));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.ye_trails.toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                category
        ));
        mapKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.ye_trails.map",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                category
        ));

        WorldRenderEvents.AFTER_ENTITIES.register(TrailWorldRenderer::render);
        HudRenderCallback.EVENT.register(TrailRadarHud::render);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ChunkTrailEngine.onWorldOrDimensionCheck();

            while (menuKey.consumeClick()) {
                if (client.screen == null) {
                    client.setScreen(new TrailsSettingsScreen());
                }
            }

            while (toggleKey.consumeClick()) {
                TrailsConfig cfg = TrailsConfig.get();
                cfg.showVisuals = !cfg.showVisuals;
                TrailsConfig.save();
                if (client.player != null) {
                    client.player.displayClientMessage(
                            Component.literal("§b[ye-trails] §f路径显示已" + (cfg.showVisuals ? "§a开启" : "§e隐藏 §7(后台持续检测中)")),
                            true
                    );
                }
            }

            while (mapKey.consumeClick()) {
                if (client.screen == null) {
                    client.setScreen(new WorldMapScreen());
                }
            }
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            TrailsConfig.save();
            TerrainRegionCache.saveAllDirty();
            WaypointManager.save();
        });
    }
}
