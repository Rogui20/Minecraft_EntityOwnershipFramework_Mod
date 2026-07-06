package com.eoframework.network;

import com.eoframework.EOFramework;
import com.eoframework.common.StorageOwnershipManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record StorageOwnerSessionC2SPayload(BlockPos pos, boolean open) implements CustomPacketPayload {
    public static final Type<StorageOwnerSessionC2SPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EOFramework.MODID, "storage_owner_session_c2s"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StorageOwnerSessionC2SPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public StorageOwnerSessionC2SPayload decode(RegistryFriendlyByteBuf buf) {
            return new StorageOwnerSessionC2SPayload(BlockPos.STREAM_CODEC.decode(buf), buf.readBoolean());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, StorageOwnerSessionC2SPayload payload) {
            BlockPos.STREAM_CODEC.encode(buf, payload.pos());
            buf.writeBoolean(payload.open());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StorageOwnerSessionC2SPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                StorageOwnershipManager.setOwnerOpen(player.serverLevel(), payload.pos(), player, payload.open());
            }
        });
    }
}
