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
 * Caches 16x16-chunk regions:
 * 1) 256x256 terrain texture
 * 2) 16x16 chunk-trail overlay texture (1 pixel = 1 chunk) for O(1) lag-free WorldMap zooming
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
        public final Identifier overlayTextureId;

        private final NativeImage image;
        private final NativeImage overlayImage;
        private DynamicTexture texture;
        private DynamicTexture overlayTexture;
        private volatile boolean terrainDirty = true;
        private volatile boolean overlayDirty = true;
        private volatile boolean diskDirty = false;

        RegionTile(int rx, int rz, NativeImage image) {
            this.rx = rx;
            this.rz = rz;
            this.image = image;
            this.overlayImage = new NativeImage(CHUNKS_PER_REGION, CHUNKS_PER_REGION, true);
            for (int y = 0; y < CHUNKS_PER_REGION; y++) {
                for (int x = 0; x < CHUNKS_PER_REGION; x++) {
                    this.overlayImage.setPixel(x, y, 0x00000000);
                }
            }
            this.textureId = Identifier.fromNamespaceAndPath("ye_trails", "region_" + rx + "_" + rz);
            this.overlayTextureId = Identifier.fromNamespaceAndPath("ye_trails", "overlay_" + rx + "_" + rz);
        }

        public synchronized void setChunkPixels(int localCx, int localCz, int[] argb256) {
            int baseX = localCx * 16;
            int baseZ = localCz * 16;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    image.setPixel(baseX + x, baseZ + z, argb256[z * 16 + x]);
                }
            }
            terrainDirty = true;
            diskDirty = true;
        }

        public synchronized void setOverlayPixel(int localCx, int localCz, int argb) {
            overlayImage.setPixel(localCx, localCz, argb);
            overlayDirty = true;
        }

        public synchronized void uploadIfDirty() {
            if (texture == null) {
                texture = new DynamicTexture(() -> "ye_region_" + rx + "_" + rz, image);
                Minecraft.getInstance().getTextureManager().register(textureId, texture);
                terrainDirty = false;
            } else if (terrainDirty) {
                texture.upload();
                terrainDirty = false;
            }

            if (overlayTexture == null) {
                overlayTexture = new DynamicTexture(() -> "ye_overlay_" + rx + "_" + rz, overlayImage);
                Minecraft.getInstance().getTextureManager().register(overlayTextureId, overlayTexture);
                overlayDirty = false;
            } else if (overlayDirty) {
                overlayTexture.upload();
                overlayDirty = false;
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
            if (overlayTexture != null) {
                overlayTexture.close();
                overlayTexture = null;
            } else {
                overlayImage.close();
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

    private static RegionTile getOrCreateRegion(int cx, int cz) {
        int rx = Math.floorDiv(cx, CHUNKS_PER_REGION);
        int rz = Math.floorDiv(cz, CHUNKS_PER_REGION);
        return REGIONS.computeIfAbsent(packRegion(rx, rz), k -> {
            NativeImage img = new NativeImage(REGION_PIXELS, REGION_PIXELS, true);
            for (int py = 0; py < REGION_PIXELS; py++) {
                for (int px = 0; px < REGION_PIXELS; px++) {
                    img.setPixel(px, py, 0x00000000);
                }
            }
            return new RegionTile(rx, rz, img);
        });
    }

    public static void updateChunkOverlay(int cx, int cz, ChunkTrailEngine.ChunkType type) {
        int color = switch (type) {
            case NEW -> 0x70F43F5E;      // Red for newly generated / untouched chunks
            case UPDATING -> 0x48FBBF24; // Subtle amber for updating chunks
            case OLD, OLD_GEN -> 0x00000000; // Transparent = natural terrain color for player trails!
        };
        RegionTile tile = getOrCreateRegion(cx, cz);
        tile.setOverlayPixel(Math.floorMod(cx, CHUNKS_PER_REGION), Math.floorMod(cz, CHUNKS_PER_REGION), color);
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

        RegionTile tile = getOrCreateRegion(cx, cz);
        tile.setChunkPixels(Math.floorMod(cx, CHUNKS_PER_REGION), Math.floorMod(cz, CHUNKS_PER_REGION), pixels);
    }

    private static long packRegion(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }
}
