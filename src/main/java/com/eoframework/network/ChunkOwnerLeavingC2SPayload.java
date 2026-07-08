package com.eoframework.network;

import com.eoframework.EOFramework;
import com.eoframework.common.ChunkOwnershipManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ChunkOwnerLeavingC2SPayload(int chunkX, int chunkZ) implements CustomPacketPayload {
    public static final Type<ChunkOwnerLeavingC2SPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(EOFramework.MODID, "chunk_owner_leaving_c2s"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ChunkOwnerLeavingC2SPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ChunkOwnerLeavingC2SPayload::chunkX,
            ByteBufCodecs.VAR_INT, ChunkOwnerLeavingC2SPayload::chunkZ,
            ChunkOwnerLeavingC2SPayload::new
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void handle(ChunkOwnerLeavingC2SPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                ChunkOwnershipManager.leaving(player, payload.chunkX(), payload.chunkZ());
            }
        });
    }
}
