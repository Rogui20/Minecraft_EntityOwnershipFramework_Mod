package com.eoframework.network;

import com.eoframework.EOFramework;
import com.eoframework.client.ClientAuthEntities;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record ItemOwnershipSyncS2CPayload(int entityId, UUID itemUuid, UUID ownerUuid, long ownerAssignedGameTime) implements CustomPacketPayload {
    public static final Type<ItemOwnershipSyncS2CPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EOFramework.MODID, "item_ownership_sync_s2c"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ItemOwnershipSyncS2CPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override public ItemOwnershipSyncS2CPayload decode(RegistryFriendlyByteBuf buf) {
            return new ItemOwnershipSyncS2CPayload(buf.readVarInt(), buf.readUUID(), buf.readUUID(), buf.readVarLong());
        }
        @Override public void encode(RegistryFriendlyByteBuf buf, ItemOwnershipSyncS2CPayload payload) {
            buf.writeVarInt(payload.entityId());
            buf.writeUUID(payload.itemUuid());
            buf.writeUUID(payload.ownerUuid());
            buf.writeVarLong(payload.ownerAssignedGameTime());
        }
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(ItemOwnershipSyncS2CPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientAuthEntities.syncItemOwner(payload.entityId(), payload.itemUuid(), payload.ownerUuid(), payload.ownerAssignedGameTime()));
    }
}
