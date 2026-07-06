package com.eoframework.network;

import com.eoframework.EOFramework;
import com.eoframework.client.ClientOwnedBlockRuntime;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record ChunkOwnerSyncS2CPayload(String dimension, int chunkX, int chunkZ, UUID owner) implements CustomPacketPayload {
    public static final Type<ChunkOwnerSyncS2CPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EOFramework.MODID, "chunk_owner_sync_s2c"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChunkOwnerSyncS2CPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ChunkOwnerSyncS2CPayload decode(RegistryFriendlyByteBuf buf) {
            String dimension = ByteBufCodecs.STRING_UTF8.decode(buf);
            int chunkX = ByteBufCodecs.VAR_INT.decode(buf);
            int chunkZ = ByteBufCodecs.VAR_INT.decode(buf);
            UUID owner = buf.readBoolean() ? buf.readUUID() : null;
            return new ChunkOwnerSyncS2CPayload(dimension, chunkX, chunkZ, owner);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, ChunkOwnerSyncS2CPayload payload) {
            ByteBufCodecs.STRING_UTF8.encode(buf, payload.dimension());
            ByteBufCodecs.VAR_INT.encode(buf, payload.chunkX());
            ByteBufCodecs.VAR_INT.encode(buf, payload.chunkZ());
            buf.writeBoolean(payload.owner() != null);
            if (payload.owner() != null) {
                buf.writeUUID(payload.owner());
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ChunkOwnerSyncS2CPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientOwnedBlockRuntime.setChunkOwner(payload.dimension(), payload.chunkX(), payload.chunkZ(), payload.owner()));
    }
}
