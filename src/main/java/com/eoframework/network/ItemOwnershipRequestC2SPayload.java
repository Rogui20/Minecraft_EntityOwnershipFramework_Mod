package com.eoframework.network;

import com.eoframework.EOFramework;
import com.eoframework.common.ItemOwnershipManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record ItemOwnershipRequestC2SPayload(int entityId, UUID itemUuid) implements CustomPacketPayload {
    public static final Type<ItemOwnershipRequestC2SPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EOFramework.MODID, "item_ownership_request_c2s"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ItemOwnershipRequestC2SPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override public ItemOwnershipRequestC2SPayload decode(RegistryFriendlyByteBuf buf) {
            return new ItemOwnershipRequestC2SPayload(buf.readVarInt(), buf.readUUID());
        }
        @Override public void encode(RegistryFriendlyByteBuf buf, ItemOwnershipRequestC2SPayload payload) {
            buf.writeVarInt(payload.entityId());
            buf.writeUUID(payload.itemUuid());
        }
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(ItemOwnershipRequestC2SPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                ItemOwnershipManager.requestMigration(player, payload.entityId(), payload.itemUuid());
            }
        });
    }
}
