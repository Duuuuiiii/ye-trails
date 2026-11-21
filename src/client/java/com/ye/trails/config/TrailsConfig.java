package com.ye.trails.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persistent JSON configuration for Ye Trails.
 */
public final class TrailsConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("ye-trails");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static TrailsConfig INSTANCE = new TrailsConfig();

    public enum RadarAnchor {
        TOP_RIGHT("右上角"),
        TOP_LEFT("左上角"),
        BOTTOM_RIGHT("右下角"),
        BOTTOM_LEFT("左下角");

        private final String label;
        RadarAnchor(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    // Visual display switch (F8). Detection ALWAYS runs in background!
    public boolean showVisuals = true;

    // Detection engines
    public boolean paletteExploit = true;
    public boolean oldGenDetector = true;
    public boolean beingUpdatedDetector = true;
    public boolean liquidExploit = true;

    // 3D World Rendering (off by default — map-first)
    public boolean render3D = false;
    public boolean followPlayerY = true;
    public double renderY = 64.0;
    public double followYOffset = -2.0;
    public int renderRadius = 16;
    public double fillAlpha = 0.25;

    // Chunk & Feature Visibility
    public boolean showOldChunks = true;
    public boolean showOldGenChunks = true;
    public boolean showUpdatingChunks = true;
    public boolean showNewChunks = true;
    public boolean highlightPortals = true;
    public boolean showTerrainOnMap = true;
    public boolean showWaypoints = true;

    // 2D Minimap Radar
    public boolean showRadar = true;
    public RadarAnchor radarAnchor = RadarAnchor.TOP_RIGHT;
    public int radarOffsetX = 8;
    public int radarOffsetY = 8;
    public int radarSize = 116;
    public int radarChunkPx = 6; // pixels per chunk on minimap

    // Keybinds (customizable inside F7 UI; F9 belongs to ye-mod)
    public int menuKey = org.lwjgl.glfw.GLFW.GLFW_KEY_F7;
    public int mapKey = org.lwjgl.glfw.GLFW.GLFW_KEY_P;
    public int toggleKey = -1;

    // Persistence
    public boolean saveToDisk = true;

    public static TrailsConfig get() {
        return INSTANCE;
    }

    public static Path getBaseDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("ye-trails");
    }

    public static Path getDataDir() {
        return getBaseDir().resolve("data");
    }

    public static Path getRegionsDir() {
        return getBaseDir().resolve("regions");
    }

    public static Path getWaypointsDir() {
        return getBaseDir().resolve("waypoints");
    }

    public static Path getConfigFile() {
        return getBaseDir().resolve("config.json");
    }

    public static void load() {
        try {
            Files.createDirectories(getBaseDir());
            Files.createDirectories(getDataDir());
            Files.createDirectories(getRegionsDir());
            Files.createDirectories(getWaypointsDir());
            Path file = getConfigFile();
            if (Files.exists(file)) {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                TrailsConfig loaded = GSON.fromJson(json, TrailsConfig.class);
                if (loaded != null) {
                    if (loaded.radarAnchor == null) loaded.radarAnchor = RadarAnchor.TOP_RIGHT;
                    INSTANCE = loaded;
                }
            } else {
                save();
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load ye-trails config", e);
        }
    }

    public static void save() {
        try {
            Files.createDirectories(getBaseDir());
            Files.writeString(getConfigFile(), GSON.toJson(INSTANCE), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Failed to save ye-trails config", e);
        }
    }
}
