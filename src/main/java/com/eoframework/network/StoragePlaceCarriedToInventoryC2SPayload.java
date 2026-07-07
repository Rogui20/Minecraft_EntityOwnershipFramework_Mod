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

public record StoragePlaceCarriedToInventoryC2SPayload(
        BlockPos pos,
        int targetSlot,
        int storageSlots,
        ItemStack stack,
        long requestId
) implements CustomPacketPayload {
    public static final Type<StoragePlaceCarriedToInventoryC2SPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EOFramework.MODID, "storage_place_carried_to_inventory_c2s"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StoragePlaceCarriedToInventoryC2SPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public StoragePlaceCarriedToInventoryC2SPayload decode(RegistryFriendlyByteBuf buf) {
                    return new StoragePlaceCarriedToInventoryC2SPayload(
                            BlockPos.STREAM_CODEC.decode(buf),
                            buf.readVarInt(),
                            buf.readVarInt(),
                            ItemStack.OPTIONAL_STREAM_CODEC.decode(buf),
                            buf.readVarLong()
                    );
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, StoragePlaceCarriedToInventoryC2SPayload payload) {
                    BlockPos.STREAM_CODEC.encode(buf, payload.pos());
                    buf.writeVarInt(payload.targetSlot());
                    buf.writeVarInt(payload.storageSlots());
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, payload.stack());
                    buf.writeVarLong(payload.requestId());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StoragePlaceCarriedToInventoryC2SPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            StorageOwnershipManager.placeCarriedToInventoryForNonOwner(
                    player.serverLevel(),
                    payload.pos(),
                    player,
                    payload.targetSlot(),
                    payload.storageSlots(),
                    payload.stack(),
                    payload.requestId()
            );
        });
    }
}
