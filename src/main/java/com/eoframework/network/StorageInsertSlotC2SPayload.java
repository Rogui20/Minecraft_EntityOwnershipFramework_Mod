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

public record StorageInsertSlotC2SPayload(
        BlockPos pos,
        int slot,
        int sourceSlot,
        int storageSlots,
        ItemStack stack,
        long requestId,
        long carriedToken,
        boolean quickMove
) implements CustomPacketPayload {
    public static final Type<StorageInsertSlotC2SPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EOFramework.MODID, "storage_insert_slot_c2s"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StorageInsertSlotC2SPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public StorageInsertSlotC2SPayload decode(RegistryFriendlyByteBuf buf) {
                    return new StorageInsertSlotC2SPayload(
                            BlockPos.STREAM_CODEC.decode(buf),
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readVarInt(),
                            ItemStack.OPTIONAL_STREAM_CODEC.decode(buf),
                            buf.readVarLong(),
                            buf.readVarLong(),
                            buf.readBoolean()
                    );
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, StorageInsertSlotC2SPayload payload) {
                    BlockPos.STREAM_CODEC.encode(buf, payload.pos());
                    buf.writeVarInt(payload.slot());
                    buf.writeVarInt(payload.sourceSlot());
                    buf.writeVarInt(payload.storageSlots());
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, payload.stack());
                    buf.writeVarLong(payload.requestId());
                    buf.writeVarLong(payload.carriedToken());
                    buf.writeBoolean(payload.quickMove());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StorageInsertSlotC2SPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;

            StorageOwnershipManager.insertSlotForNonOwner(
                    player.serverLevel(),
                    payload.pos(),
                    player,
                    payload.slot(),
                    payload.sourceSlot(),
                    payload.storageSlots(),
                    payload.stack(),
                    payload.requestId(),
                    payload.carriedToken(),
                    payload.quickMove()
            );
        });
    }
}