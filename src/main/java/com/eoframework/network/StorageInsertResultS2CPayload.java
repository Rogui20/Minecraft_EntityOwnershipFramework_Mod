package com.eoframework.network;

import com.eoframework.EOFramework;
import com.eoframework.client.ClientLocalStorageScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record StorageInsertResultS2CPayload(
        boolean accepted,
        int insertedCount,
        int sourceSlot,
        long requestId
) implements CustomPacketPayload {
    public static final Type<StorageInsertResultS2CPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EOFramework.MODID, "storage_insert_result_s2c"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StorageInsertResultS2CPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public StorageInsertResultS2CPayload decode(RegistryFriendlyByteBuf buf) {
                    return new StorageInsertResultS2CPayload(
                            buf.readBoolean(),
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readVarLong()
                    );
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, StorageInsertResultS2CPayload payload) {
                    buf.writeBoolean(payload.accepted());
                    buf.writeVarInt(payload.insertedCount());
                    buf.writeVarInt(payload.sourceSlot());
                    buf.writeVarLong(payload.requestId());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StorageInsertResultS2CPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();

            System.out.println("[EOF StorageResult] accepted="
                    + payload.accepted() + " operation=INSERT quick=false stack=EMPTY insertedCount=" + payload.insertedCount() + " sourceSlot=" + payload.sourceSlot() + " requestId=" + payload.requestId());

            if (mc.screen instanceof ClientLocalStorageScreen screen) {
                screen.handleValidatedInsertResult(
                        payload.accepted(),
                        payload.insertedCount(),
                        payload.sourceSlot(),
                        payload.requestId()
                );
            }
        });
    }
}