package com.ye.trails.engine;

import com.mojang.blaze3d.platform.NativeImage;
import com.ye.trails.config.TrailsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Caches top-down 16x16 terrain colors per chunk grouped into 16x16-chunk (256x256 px) DynamicTexture regions.
 */
public final class TerrainRegionCache {
    private static final Logger LOGGER = LoggerFactory.getLogger("ye-trails-terrain");
    public static final int CHUNKS_PER_REGION = 16;
    public static final int REGION_PIXELS = CHUNKS_PER_REGION * 16; // 256

    private static final Map<Long, RegionTile> REGIONS = new ConcurrentHashMap<>();
    private static volatile String activeContext = "";

    public static final class RegionTile {
        public final int rx;
        public final int rz;
        public final Identifier textureId;
        private final NativeImage image;
        private DynamicTexture texture;
        private volatile boolean dirty = true;
        private volatile boolean diskDirty = false;

        RegionTile(int rx, int rz, NativeImage image) {
            this.rx = rx;
            this.rz = rz;
            this.image = image;
            this.textureId = Identifier.fromNamespaceAndPath("ye_trails", "region_" + rx + "_" + rz);
        }

        public synchronized void setChunkPixels(int localCx, int localCz, int[] argb256) {
            int baseX = localCx * 16;
            int baseZ = localCz * 16;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    image.setPixel(baseX + x, baseZ + z, argb256[z * 16 + x]);
                }
            }
            dirty = true;
            diskDirty = true;
        }

        public synchronized void uploadIfDirty() {
            if (texture == null) {
                texture = new DynamicTexture(() -> "ye_region_" + rx + "_" + rz, image);
                Minecraft.getInstance().getTextureManager().register(textureId, texture);
                dirty = false;
                return;
            }
            if (dirty) {
                texture.upload();
                dirty = false;
            }
        }

        public synchronized void saveToFile(Path file) {
            if (!diskDirty) return;
            try {
                Files.createDirectories(file.getParent());
                image.writeToFile(file);
                diskDirty = false;
            } catch (IOException e) {
                LOGGER.debug("Failed to save region {}: {}", file, e.getMessage());
            }
        }

        public synchronized void close() {
            if (texture != null) {
                texture.close();
                texture = null;
            } else {
                image.close();
            }
        }
    }

    private TerrainRegionCache() {}

    public static Collection<RegionTile> getRegions() {
        return REGIONS.values();
    }

    public static void switchContext(String contextKey) {
        if (contextKey.equals(activeContext)) return;
        saveAllDirty();
        clearMemory();
        activeContext = contextKey;
        if (!contextKey.isEmpty() && TrailsConfig.get().saveToDisk) {
            loadSavedRegions(contextKey);
        }
    }

    public static void clearCurrent() {
        clearMemory();
        if (!activeContext.isEmpty()) {
            Path dir = TrailsConfig.getRegionsDir().resolve(activeContext);
            if (Files.exists(dir)) {
                try (Stream<Path> s = Files.list(dir)) {
                    s.forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
                } catch (IOException ignored) {}
            }
        }
    }

    private static void clearMemory() {
        for (RegionTile tile : REGIONS.values()) {
            Minecraft.getInstance().execute(tile::close);
        }
        REGIONS.clear();
    }

    public static void saveAllDirty() {
        if (activeContext.isEmpty() || !TrailsConfig.get().saveToDisk) return;
        Path dir = TrailsConfig.getRegionsDir().resolve(activeContext);
        for (RegionTile tile : REGIONS.values()) {
            tile.saveToFile(dir.resolve("r." + tile.rx + "." + tile.rz + ".png"));
        }
    }

    private static void loadSavedRegions(String contextKey) {
        Path dir = TrailsConfig.getRegionsDir().resolve(contextKey);
        if (!Files.exists(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".png")).forEach(p -> {
                String name = p.getFileName().toString();
                String[] parts = name.split("\\.");
                if (parts.length == 4 && parts[0].equals("r")) {
                    try {
                        int rx = Integer.parseInt(parts[1]);
                        int rz = Integer.parseInt(parts[2]);
                        try (InputStream in = Files.newInputStream(p)) {
                            NativeImage img = NativeImage.read(in);
                            if (img.getWidth() == REGION_PIXELS && img.getHeight() == REGION_PIXELS) {
                                REGIONS.put(packRegion(rx, rz), new RegionTile(rx, rz, img));
                            } else {
                                img.close();
                            }
                        }
                    } catch (Exception ignored) {}
                }
            });
        } catch (IOException ignored) {}
    }

    public static void captureChunk(ClientLevel level, LevelChunk chunk) {
        if (level == null || chunk == null) return;
        int cx = chunk.getPos().x;
        int cz = chunk.getPos().z;
        int[] pixels = new int[256];
        int[] heights = new int[256];
        boolean isNether = level.dimension() == Level.NETHER;
        int minY = level.getMinY();
        int maxY = minY + level.getHeight() - 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int topY = minY;
                BlockState topState = null;

                if (isNether) {
                    // Scan below Nether bedrock ceiling (y=122 down to 0) so highways & lava lakes are visible
                    boolean passedCeiling = false;
                    for (int y = Math.min(122, maxY); y >= minY; y--) {
                        pos.set(cx * 16 + x, y, cz * 16 + z);
                        BlockState st = chunk.getBlockState(pos);
                        if (st.isAir()) {
                            passedCeiling = true;
                        } else if (passedCeiling) {
                            topY = y;
                            topState = st;
                            break;
                        }
                    }
                    if (topState == null) {
                        pos.set(cx * 16 + x, 127, cz * 16 + z);
                        topState = chunk.getBlockState(pos);
                        topY = 127;
                    }
                } else {
                    int surfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                    for (int y = Math.min(surfaceY + 1, maxY); y >= minY; y--) {
                        pos.set(cx * 16 + x, y, cz * 16 + z);
                        BlockState st = chunk.getBlockState(pos);
                        if (!st.isAir() && st.getMapColor(level, pos) != MapColor.NONE) {
                            topY = y;
                            topState = st;
                            break;
                        }
                    }
                }

                heights[z * 16 + x] = topY;
                if (topState == null) {
                    pixels[z * 16 + x] = 0x00000000;
                } else {
                    pos.set(cx * 16 + x, topY, cz * 16 + z);
                    MapColor mc = topState.getMapColor(level, pos);
                    MapColor.Brightness bright = MapColor.Brightness.NORMAL;
                    if (z > 0) {
                        int prevY = heights[(z - 1) * 16 + x];
                        if (topY > prevY) bright = MapColor.Brightness.HIGH;
                        else if (topY < prevY) bright = MapColor.Brightness.LOW;
                    }
                    pixels[z * 16 + x] = mc.calculateARGBColor(bright);
                }
            }
        }

        int rx = Math.floorDiv(cx, CHUNKS_PER_REGION);
        int rz = Math.floorDiv(cz, CHUNKS_PER_REGION);
        int localCx = Math.floorMod(cx, CHUNKS_PER_REGION);
        int localCz = Math.floorMod(cz, CHUNKS_PER_REGION);

        RegionTile tile = REGIONS.computeIfAbsent(packRegion(rx, rz), k -> {
            NativeImage img = new NativeImage(REGION_PIXELS, REGION_PIXELS, true);
            for (int py = 0; py < REGION_PIXELS; py++) {
                for (int px = 0; px < REGION_PIXELS; px++) {
                    img.setPixel(px, py, 0x00000000);
                }
            }
            return new RegionTile(rx, rz, img);
        });
        tile.setChunkPixels(localCx, localCz, pixels);
    }

    private static long packRegion(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }
}
