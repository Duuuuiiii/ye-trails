package com.ye.trails.engine;

import com.ye.trails.config.TrailsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.FluidState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Always-on multi-engine chunk trail detector (PaletteExploit + OldGen + LiquidExploit)
 * with Nether Portal detection and terrain tile capture.
 */
public final class ChunkTrailEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger("ye-trails");

    public enum ChunkType {
        OLD,       // Previously loaded by players (Trail!)
        OLD_GEN,   // Pre-1.17 / 1.16 / 1.13 old version chunk (Trail!)
        UPDATING,  // Old chunk being updated to newer generation
        NEW        // Freshly generated chunk (Unexplored)
    }

    private static final Map<Long, ChunkType> CHUNK_MAP = new ConcurrentHashMap<>();
    private static final Set<Long> PORTAL_CHUNKS = ConcurrentHashMap.newKeySet();
    private static final Set<Long> CAPTURED_TERRAIN = ConcurrentHashMap.newKeySet();

    private static final ExecutorService ANALYZER = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(128),
            r -> {
                Thread t = new Thread(r, "ye-trails-analyzer");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy()
    );
    private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ye-trails-io");
        t.setDaemon(true);
        return t;
    });

    private static final Direction[] CARDINAL_AND_UP = {
            Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH, Direction.UP
    };

    private static final Set<Block> ORE_BLOCKS = Set.of(
            Blocks.COAL_ORE, Blocks.IRON_ORE, Blocks.GOLD_ORE, Blocks.REDSTONE_ORE,
            Blocks.LAPIS_ORE, Blocks.DIAMOND_ORE, Blocks.EMERALD_ORE
    );

    private static final Set<Block> NEW_OVERWORLD_BLOCKS = Set.of(
            Blocks.DEEPSLATE, Blocks.COBBLED_DEEPSLATE, Blocks.TUFF, Blocks.CALCITE,
            Blocks.AMETHYST_BLOCK, Blocks.BUDDING_AMETHYST, Blocks.COPPER_ORE,
            Blocks.DEEPSLATE_COPPER_ORE, Blocks.GLOW_LICHEN, Blocks.DRIPSTONE_BLOCK,
            Blocks.POINTED_DRIPSTONE, Blocks.MOSS_BLOCK, Blocks.AZALEA, Blocks.FLOWERING_AZALEA,
            Blocks.SCULK, Blocks.TRIAL_SPAWNER, Blocks.CHISELED_TUFF
    );

    private static final Set<Block> NEW_NETHER_BLOCKS = Set.of(
            Blocks.ANCIENT_DEBRIS, Blocks.BASALT, Blocks.BLACKSTONE, Blocks.CRIMSON_NYLIUM,
            Blocks.WARPED_NYLIUM, Blocks.NETHER_GOLD_ORE, Blocks.SOUL_SOIL,
            Blocks.SHROOMLIGHT, Blocks.CRIMSON_STEM, Blocks.WARPED_STEM
    );

    private static volatile String activeContextKey = "";
    private static volatile int oldCount = 0;
    private static volatile int newCount = 0;
    private static volatile int portalCount = 0;

    private ChunkTrailEngine() {}

    public static Map<Long, ChunkType> getChunks() {
        return CHUNK_MAP;
    }

    public static Set<Long> getPortalChunks() {
        return PORTAL_CHUNKS;
    }

    public static boolean hasPortal(long packedChunk) {
        return PORTAL_CHUNKS.contains(packedChunk);
    }

    public static int getOldCount() {
        return oldCount;
    }

    public static int getNewCount() {
        return newCount;
    }

    public static int getPortalCount() {
        return portalCount;
    }

    public static void onWorldOrDimensionCheck() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            if (!activeContextKey.isEmpty()) {
                TerrainRegionCache.saveAllDirty();
                activeContextKey = "";
                CHUNK_MAP.clear();
                PORTAL_CHUNKS.clear();
                CAPTURED_TERRAIN.clear();
                TerrainRegionCache.switchContext("");
                WaypointManager.switchContext("");
                recount();
            }
            return;
        }

        String key = buildContextKey(mc);
        if (!key.equals(activeContextKey)) {
            TerrainRegionCache.saveAllDirty();
            activeContextKey = key;
            CHUNK_MAP.clear();
            PORTAL_CHUNKS.clear();
            CAPTURED_TERRAIN.clear();
            recount();
            TerrainRegionCache.switchContext(key);
            WaypointManager.switchContext(key);
            if (TrailsConfig.get().saveToDisk) {
                loadSavedData(key);
            }
        }
    }

    public static void clearCurrentData() {
        CHUNK_MAP.clear();
        PORTAL_CHUNKS.clear();
        CAPTURED_TERRAIN.clear();
        recount();
        TerrainRegionCache.clearCurrent();

        String key = activeContextKey;
        if (!key.isEmpty()) {
            Path chunksFile = resolveDataFile(key);
            Path portalsFile = resolvePortalFile(key);
            IO_EXECUTOR.submit(() -> {
                try {
                    Files.deleteIfExists(chunksFile);
                    Files.deleteIfExists(portalsFile);
                } catch (IOException e) {
                    LOGGER.warn("Failed to delete trail data", e);
                }
            });
        }
    }

    public static void onChunkPacket(ClientboundLevelChunkWithLightPacket packet) {
        TrailsConfig cfg = TrailsConfig.get();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        onWorldOrDimensionCheck();

        int cx = packet.getX();
        int cz = packet.getZ();

        // Always copy buffer immediately on caller thread before Netty releases/mutates it
        FriendlyByteBuf buf = packet.getChunkData().getReadBuffer();
        byte[] rawBytes = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), rawBytes);

        var heightmaps = packet.getChunkData().getHeightmaps();
        var beConsumer = packet.getChunkData().getBlockEntitiesTagsConsumer(cx, cz);

        ANALYZER.submit(() -> {
            try {
                if (mc.level == null) return;
                LevelChunk tempChunk = new LevelChunk(mc.level, new ChunkPos(cx, cz));
                FriendlyByteBuf copyBuf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(rawBytes));
                try {
                    tempChunk.replaceWithPacketData(copyBuf, heightmaps, beConsumer);
                } finally {
                    copyBuf.release();
                }

                long packed = ChunkPos.asLong(cx, cz);

                // Capture 16x16 terrain tile once per session for map & minimap
                if (CAPTURED_TERRAIN.add(packed)) {
                    TerrainRegionCache.captureChunk(mc.level, tempChunk);
                }

                // Scan for Nether Portals
                if (containsPortal(tempChunk)) {
                    recordPortal(cx, cz, cfg.saveToDisk);
                }

                if (!CHUNK_MAP.containsKey(packed)) {
                    ChunkType detected = analyzeChunk(mc.level, tempChunk, cfg);
                    if (detected != null) {
                        recordChunk(cx, cz, detected, cfg.saveToDisk);
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("Chunk analysis skipped for [{}, {}]: {}", cx, cz, e.getMessage());
            }
        });
    }

    public static void onBlockUpdate(ClientboundBlockUpdatePacket packet) {
        TrailsConfig cfg = TrailsConfig.get();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        if (packet.getBlockState().is(Blocks.NETHER_PORTAL)) {
            ChunkPos cp = new ChunkPos(packet.getPos());
            recordPortal(cp.x, cp.z, cfg.saveToDisk);
        }

        if (cfg.liquidExploit) {
            checkFluidPos(mc.level, packet.getPos(), packet.getBlockState(), cfg);
        }
    }

    public static void onSectionBlocksUpdate(ClientboundSectionBlocksUpdatePacket packet) {
        TrailsConfig cfg = TrailsConfig.get();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        packet.runUpdates((pos, state) -> {
            if (state.is(Blocks.NETHER_PORTAL)) {
                ChunkPos cp = new ChunkPos(pos);
                recordPortal(cp.x, cp.z, cfg.saveToDisk);
            }
            if (cfg.liquidExploit) {
                checkFluidPos(mc.level, pos, state, cfg);
            }
        });
    }

    private static boolean containsPortal(LevelChunk chunk) {
        for (LevelChunkSection section : chunk.getSections()) {
            if (section == null || section.hasOnlyAir()) continue;
            if (section.maybeHas(state -> state.is(Blocks.NETHER_PORTAL))) {
                return true;
            }
        }
        return false;
    }

    private static void checkFluidPos(ClientLevel level, BlockPos pos, BlockState state, TrailsConfig cfg) {
        FluidState fluid = state.getFluidState();
        if (fluid.isEmpty() || fluid.isSource()) return;

        ChunkPos cp = new ChunkPos(pos);
        long packed = cp.toLong();
        if (CHUNK_MAP.containsKey(packed)) return;

        for (Direction dir : CARDINAL_AND_UP) {
            if (level.getBlockState(pos.relative(dir)).getFluidState().isSource()) {
                recordChunk(cp.x, cp.z, ChunkType.NEW, cfg.saveToDisk);
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static ChunkType analyzeChunk(ClientLevel level, LevelChunk chunk, TrailsConfig cfg) {
        LevelChunkSection[] sections = chunk.getSections();
        var dim = level.dimension();

        // 1. Old-version chunk detection (Pre-1.17 Overworld, Pre-1.16 Nether, Pre-1.13 End)
        if (cfg.oldGenDetector && chunk.getPersistedStatus().isOrAfter(ChunkStatus.FULL) && !chunk.isEmpty()) {
            if (dim == Level.OVERWORLD) {
                boolean foundOre = false;
                boolean foundNewBlock = false;
                int limit = Math.min(sections.length, 17);
                for (int i = 0; i < limit && !foundNewBlock; i++) {
                    LevelChunkSection sec = sections[i];
                    if (sec == null || sec.hasOnlyAir()) continue;
                    for (int x = 0; x < 16 && !foundNewBlock; x++) {
                        for (int y = 0; y < 16 && !foundNewBlock; y++) {
                            for (int z = 0; z < 16; z++) {
                                Block b = sec.getBlockState(x, y, z).getBlock();
                                if (!foundOre && ORE_BLOCKS.contains(b)) foundOre = true;
                                if (((y >= 5 && i == 4) || i > 4) && NEW_OVERWORLD_BLOCKS.contains(b)) {
                                    foundNewBlock = true;
                                    break;
                                }
                            }
                        }
                    }
                }
                if (foundOre && !foundNewBlock) {
                    return ChunkType.OLD_GEN;
                }
            } else if (dim == Level.NETHER) {
                boolean foundNewNether = false;
                int limit = Math.min(sections.length, 8);
                for (int i = 0; i < limit && !foundNewNether; i++) {
                    LevelChunkSection sec = sections[i];
                    if (sec == null || sec.hasOnlyAir()) continue;
                    for (int x = 0; x < 16 && !foundNewNether; x++) {
                        for (int y = 0; y < 16 && !foundNewNether; y++) {
                            for (int z = 0; z < 16; z++) {
                                if (NEW_NETHER_BLOCKS.contains(sec.getBlockState(x, y, z).getBlock())) {
                                    foundNewNether = true;
                                    break;
                                }
                            }
                        }
                    }
                }
                if (!foundNewNether) {
                    return ChunkType.OLD_GEN;
                }
            } else if (dim == Level.END && sections.length > 0) {
                PalettedContainerRO<Holder<Biome>> biomesRO = sections[0].getBiomes();
                if (biomesRO instanceof PalettedContainer<Holder<Biome>> biomesContainer) {
                    Palette<Holder<Biome>> palette = ((PaletteAccess<Holder<Biome>>) biomesContainer).ye$getPalette();
                    for (int i = 0; i < palette.getSize(); i++) {
                        if (palette.valueFor(i).unwrapKey().filter(Biomes.THE_END::equals).isPresent()) {
                            return ChunkType.OLD_GEN;
                        }
                    }
                }
            }
        }

        // 2. PaletteExploit (1.18 - 1.21+ core detection)
        if (cfg.paletteExploit) {
            int nonAirSections = 0;
            int newChunkQuantifier = 0;
            int oldChunkQuantifier = 0;
            boolean beingUpdated = false;
            boolean endMarkedNew = false;

            for (LevelChunkSection section : sections) {
                if (section == null) continue;

                int isNewSection = 0;
                int isBeingUpdatedSection = 0;

                if (!section.hasOnlyAir()) {
                    PalettedContainer<BlockState> container = section.getStates();
                    Palette<BlockState> palette = ((PaletteAccess<BlockState>) container).ye$getPalette();
                    int paletteSize = palette.getSize();

                    if (palette instanceof HashMapPalette<BlockState>) {
                        var storage = ((PaletteAccess<BlockState>) container).ye$getStorage();
                        boolean[] seen = new boolean[paletteSize];
                        int uniqueSize = 0;
                        int total = storage.getSize();
                        for (int idx = 0; idx < total; idx++) {
                            int id = storage.get(idx);
                            if (id >= 0 && id < paletteSize && !seen[id]) {
                                seen[id] = true;
                                uniqueSize++;
                                if (uniqueSize >= paletteSize) break;
                            }
                        }
                        if (uniqueSize <= 1) uniqueSize = paletteSize;
                        if (uniqueSize < paletteSize) {
                            isNewSection = 2;
                        }
                    }

                    for (int i = 0; i < paletteSize; i++) {
                        Block b = palette.valueFor(i).getBlock();
                        if (i == 0 && b == Blocks.AIR && dim != Level.NETHER && dim != Level.END) {
                            isNewSection++;
                        }
                        if (i == 1 && (b == Blocks.WATER || b == Blocks.STONE || b == Blocks.GRASS_BLOCK || b == Blocks.SNOW_BLOCK)
                                && dim != Level.NETHER && dim != Level.END) {
                            isNewSection++;
                        }
                        if (i == 2 && (b == Blocks.SNOW_BLOCK || b == Blocks.DIRT || b == Blocks.POWDER_SNOW)
                                && dim != Level.NETHER && dim != Level.END) {
                            isNewSection++;
                        }
                        if (nonAirSections == 4 && b == Blocks.BEDROCK && dim != Level.NETHER && dim != Level.END) {
                            if (cfg.beingUpdatedDetector) {
                                beingUpdated = true;
                            }
                        }
                        if (b == Blocks.AIR && (dim == Level.NETHER || dim == Level.END)) {
                            isBeingUpdatedSection++;
                        }
                    }

                    if (isBeingUpdatedSection >= 2) oldChunkQuantifier++;
                    if (isNewSection >= 2) newChunkQuantifier++;
                    nonAirSections++;
                }

                if (dim == Level.END) {
                    PalettedContainerRO<Holder<Biome>> biomesRO = section.getBiomes();
                    if (biomesRO instanceof PalettedContainer<Holder<Biome>> biomesContainer) {
                        Palette<Holder<Biome>> biomePalette = ((PaletteAccess<Holder<Biome>>) biomesContainer).ye$getPalette();
                        if (biomePalette.getSize() > 0 && biomePalette.valueFor(0).unwrapKey().map(k -> k == Biomes.PLAINS).orElse(false)) {
                            endMarkedNew = true;
                        }
                    }
                }
            }

            if (beingUpdated) {
                return ChunkType.UPDATING;
            }

            if (nonAirSections > 0) {
                if (dim == Level.NETHER || dim == Level.END) {
                    double pct = ((double) oldChunkQuantifier / nonAirSections) * 100.0;
                    if (cfg.beingUpdatedDetector && pct >= 25.0) {
                        return ChunkType.UPDATING;
                    }
                    if (dim == Level.END && endMarkedNew) {
                        return ChunkType.NEW;
                    }
                    return ChunkType.OLD;
                } else {
                    double pct = ((double) newChunkQuantifier / nonAirSections) * 100.0;
                    return pct >= 51.0 ? ChunkType.NEW : ChunkType.OLD;
                }
            }
        }

        return null;
    }

    private static void recordChunk(int cx, int cz, ChunkType type, boolean persist) {
        long packed = ChunkPos.asLong(cx, cz);
        ChunkType prev = CHUNK_MAP.putIfAbsent(packed, type);
        if (prev != null) return;

        recount();

        if (persist && !activeContextKey.isEmpty()) {
            String key = activeContextKey;
            IO_EXECUTOR.submit(() -> appendLine(resolveDataFile(key), cx + "," + cz + "," + type.name() + "\n"));
        }
    }

    private static void recordPortal(int cx, int cz, boolean persist) {
        long packed = ChunkPos.asLong(cx, cz);
        if (!PORTAL_CHUNKS.add(packed)) return;
        portalCount = PORTAL_CHUNKS.size();

        if (persist && !activeContextKey.isEmpty()) {
            String key = activeContextKey;
            IO_EXECUTOR.submit(() -> appendLine(resolvePortalFile(key), cx + "," + cz + "\n"));
        }
    }

    private static void recount() {
        int oldC = 0, newC = 0;
        for (ChunkType t : CHUNK_MAP.values()) {
            if (t == ChunkType.NEW) newC++;
            else oldC++;
        }
        oldCount = oldC;
        newCount = newC;
        portalCount = PORTAL_CHUNKS.size();
    }

    private static String buildContextKey(Minecraft mc) {
        String serverPart = "singleplayer";
        if (mc.getCurrentServer() != null && mc.getCurrentServer().ip != null) {
            serverPart = mc.getCurrentServer().ip;
        } else if (mc.getSingleplayerServer() != null) {
            serverPart = "sp_" + mc.getSingleplayerServer().getWorldData().getLevelName();
        }
        String dimPart = mc.level != null ? mc.level.dimension().identifier().getPath() : "unknown";
        String safeServer = serverPart.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        String safeDim = dimPart.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return safeServer + "/" + safeDim;
    }

    private static Path resolveDataFile(String contextKey) {
        return TrailsConfig.getDataDir().resolve(contextKey + ".csv");
    }

    private static Path resolvePortalFile(String contextKey) {
        return TrailsConfig.getDataDir().resolve(contextKey + "_portals.csv");
    }

    private static void loadSavedData(String contextKey) {
        IO_EXECUTOR.submit(() -> {
            Path file = resolveDataFile(contextKey);
            if (Files.exists(file)) {
                try {
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (String line : lines) {
                        String[] parts = line.trim().split(",");
                        if (parts.length < 3) continue;
                        int x = Integer.parseInt(parts[0]);
                        int z = Integer.parseInt(parts[1]);
                        ChunkType type = ChunkType.valueOf(parts[2]);
                        if (contextKey.equals(activeContextKey)) {
                            CHUNK_MAP.putIfAbsent(ChunkPos.asLong(x, z), type);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to load trail history from {}", file, e);
                }
            }

            Path pFile = resolvePortalFile(contextKey);
            if (Files.exists(pFile)) {
                try {
                    List<String> lines = Files.readAllLines(pFile, StandardCharsets.UTF_8);
                    for (String line : lines) {
                        String[] parts = line.trim().split(",");
                        if (parts.length < 2) continue;
                        int x = Integer.parseInt(parts[0]);
                        int z = Integer.parseInt(parts[1]);
                        if (contextKey.equals(activeContextKey)) {
                            PORTAL_CHUNKS.add(ChunkPos.asLong(x, z));
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to load portal history from {}", pFile, e);
                }
            }
            recount();
        });
    }

    private static void appendLine(Path file, String line) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.debug("Failed to write trail file: {}", e.getMessage());
        }
    }
}
