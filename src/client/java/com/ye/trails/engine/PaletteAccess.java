package com.ye.trails.engine;

import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Palette;

public interface PaletteAccess<T> {
    Palette<T> ye$getPalette();
    BitStorage ye$getStorage();
}
