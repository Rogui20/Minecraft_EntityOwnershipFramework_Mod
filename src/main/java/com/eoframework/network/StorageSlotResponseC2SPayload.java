package com.eoframework.network;

import com.eoframework.EOFramework;
import com.eoframework.common.StorageOwnershipManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record StorageSlotResponseC2SPayload(
        UUID requester,
        BlockPos pos,
        int slot,
        boolean accepted,
        boolean quickMove,
        ItemStack stack,
        long requestId
) implements CustomPacketPayload {
    public static final Type<StorageSlotResponseC2SPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EOFramework.MODID, "storage_slot_response_c2s"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StorageSlotResponseC2SPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public StorageSlotResponseC2SPayload decode(RegistryFriendlyByteBuf buf) {
                    return new StorageSlotResponseC2SPayload(
                            buf.readUUID(),
                            BlockPos.STREAM_CODEC.decode(buf),
                            buf.readVarInt(),
                            buf.readBoolean(),
                            buf.readBoolean(),
                            ItemStack.OPTIONAL_STREAM_CODEC.decode(buf),
                            buf.readVarLong()
                    );
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, StorageSlotResponseC2SPayload payload) {
                    buf.writeUUID(payload.requester());
                    BlockPos.STREAM_CODEC.encode(buf, payload.pos());
                    buf.writeVarInt(payload.slot());
                    buf.writeBoolean(payload.accepted());
                    buf.writeBoolean(payload.quickMove());
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, payload.stack());
                    buf.writeVarLong(payload.requestId());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StorageSlotResponseC2SPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer owner)) return;

            StorageOwnershipManager.applyOwnerSlotResponse(
                    owner.serverLevel(),
                    owner,
                    payload.requester(),
                    payload.pos(),
                    payload.slot(),
                    payload.accepted(),
                    payload.quickMove(),
                    payload.stack(),
                    payload.requestId()
            );
        });
    }
}