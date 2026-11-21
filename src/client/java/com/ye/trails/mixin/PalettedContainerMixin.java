package com.ye.trails.mixin;

import com.ye.trails.engine.PaletteAccess;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(PalettedContainer.class)
public abstract class PalettedContainerMixin<T> implements PaletteAccess<T> {
    @Shadow
    private volatile PalettedContainer.Data<T> data;

    @Override
    public Palette<T> ye$getPalette() {
        return this.data.palette();
    }
}
