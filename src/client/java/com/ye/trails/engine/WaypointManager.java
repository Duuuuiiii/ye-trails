package com.ye.trails.engine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.ye.trails.config.TrailsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages persistent per-server/dimension map waypoints.
 */
public final class WaypointManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("ye-trails-waypoints");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<Waypoint>>() {}.getType();

    public static final int[] PALETTE = {
            0xFF38BDF8, // Sky
            0xFF34D399, // Emerald
            0xFFC084FC, // Purple
            0xFFFBBF24, // Amber
            0xFFF43F5E, // Rose
            0xFFFFFFFF  // White
    };

    public static final class Waypoint {
        public String id;
        public String name;
        public int x;
        public int y;
        public int z;
        public int color;
        public boolean enabled;

        public Waypoint() {}

        public Waypoint(String name, int x, int y, int z, int color) {
            this.id = UUID.randomUUID().toString().substring(0, 8);
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.color = color;
            this.enabled = true;
        }
    }

    private static final CopyOnWriteArrayList<Waypoint> WAYPOINTS = new CopyOnWriteArrayList<>();
    private static volatile String activeContext = "";

    private WaypointManager() {}

    public static List<Waypoint> getAll() {
        return Collections.unmodifiableList(WAYPOINTS);
    }

    public static void switchContext(String contextKey) {
        if (contextKey.equals(activeContext)) return;
        activeContext = contextKey;
        WAYPOINTS.clear();
        if (!contextKey.isEmpty()) {
            load(contextKey);
        }
    }

    public static void add(String name, int x, int y, int z, int color) {
        WAYPOINTS.add(new Waypoint(name, x, y, z, color));
        save();
    }

    public static void remove(String id) {
        WAYPOINTS.removeIf(w -> w.id.equals(id));
        save();
    }

    public static void toggle(String id) {
        for (Waypoint w : WAYPOINTS) {
            if (w.id.equals(id)) {
                w.enabled = !w.enabled;
                break;
            }
        }
        save();
    }

    private static Path resolveFile(String contextKey) {
        return TrailsConfig.getWaypointsDir().resolve(contextKey + ".json");
    }

    private static void load(String contextKey) {
        Path file = resolveFile(contextKey);
        if (!Files.exists(file)) return;
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            List<Waypoint> loaded = GSON.fromJson(raw, LIST_TYPE);
            if (loaded != null) {
                WAYPOINTS.addAll(loaded);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to load waypoints from {}", file, e);
        }
    }

    public static void save() {
        if (activeContext.isEmpty()) return;
        Path file = resolveFile(activeContext);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(new ArrayList<>(WAYPOINTS)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Failed to save waypoints", e);
        }
    }
}
