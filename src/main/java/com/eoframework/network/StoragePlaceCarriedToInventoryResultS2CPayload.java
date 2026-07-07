package com.eoframework.network;

import com.eoframework.EOFramework;
import com.eoframework.client.ClientLocalStorageScreen;
import com.eoframework.client.StorageDebug;

import static com.eoframework.client.StorageDebug.Flag.STORAGE_RESULT;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record StoragePlaceCarriedToInventoryResultS2CPayload(
        boolean accepted,
        int placedCount,
        int targetSlot,
        long requestId
) implements CustomPacketPayload {
    public static final Type<StoragePlaceCarriedToInventoryResultS2CPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EOFramework.MODID, "storage_place_carried_to_inventory_result_s2c"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StoragePlaceCarriedToInventoryResultS2CPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public StoragePlaceCarriedToInventoryResultS2CPayload decode(RegistryFriendlyByteBuf buf) {
                    return new StoragePlaceCarriedToInventoryResultS2CPayload(
                            buf.readBoolean(),
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readVarLong()
                    );
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, StoragePlaceCarriedToInventoryResultS2CPayload payload) {
                    buf.writeBoolean(payload.accepted());
                    buf.writeVarInt(payload.placedCount());
                    buf.writeVarInt(payload.targetSlot());
                    buf.writeVarLong(payload.requestId());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StoragePlaceCarriedToInventoryResultS2CPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            StorageDebug.log(STORAGE_RESULT, "accepted={} operation=PLACE_CARRIED_TO_INVENTORY placedCount={} targetSlot={} requestId={}", payload.accepted(), payload.placedCount(), payload.targetSlot(), payload.requestId());
            if (mc.screen instanceof ClientLocalStorageScreen screen) {
                screen.handleValidatedPlaceCarriedToInventoryResult(
                        payload.accepted(),
                        payload.placedCount(),
                        payload.targetSlot(),
                        payload.requestId()
                );
            }
        });
    }
}
