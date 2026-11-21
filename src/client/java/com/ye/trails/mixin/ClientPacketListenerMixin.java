package com.ye.trails.mixin;

import com.ye.trails.engine.ChunkTrailEngine;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Inject(method = "handleLevelChunkWithLight", at = @At("HEAD"))
    private void ye$onChunkPacket(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        ChunkTrailEngine.onChunkPacket(packet);
    }

    @Inject(method = "handleBlockUpdate", at = @At("HEAD"))
    private void ye$onBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        ChunkTrailEngine.onBlockUpdate(packet);
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("HEAD"))
    private void ye$onSectionBlocksUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        ChunkTrailEngine.onSectionBlocksUpdate(packet);
    }
}
