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

public record ChunkOwnershipClaimC2SPayload(int chunkX, int chunkZ) implements CustomPacketPayload {
    public static final Type<ChunkOwnershipClaimC2SPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(EOFramework.MODID, "chunk_ownership_claim_c2s"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ChunkOwnershipClaimC2SPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ChunkOwnershipClaimC2SPayload::chunkX,
            ByteBufCodecs.VAR_INT, ChunkOwnershipClaimC2SPayload::chunkZ,
            ChunkOwnershipClaimC2SPayload::new
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void handle(ChunkOwnershipClaimC2SPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                ChunkOwnershipManager.claim(player, payload.chunkX(), payload.chunkZ());
            }
        });
    }
}
