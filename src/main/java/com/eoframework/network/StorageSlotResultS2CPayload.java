package com.eoframework.network;

import com.eoframework.EOFramework;
import com.eoframework.client.ClientLocalStorageScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record StorageSlotResultS2CPayload(
        boolean accepted,
        boolean quickMove,
        ItemStack stack
) implements CustomPacketPayload {
    public static final Type<StorageSlotResultS2CPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EOFramework.MODID, "storage_slot_result_s2c"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StorageSlotResultS2CPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public StorageSlotResultS2CPayload decode(RegistryFriendlyByteBuf buf) {
                    return new StorageSlotResultS2CPayload(
                            buf.readBoolean(),
                            buf.readBoolean(),
                            ItemStack.OPTIONAL_STREAM_CODEC.decode(buf)
                    );
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, StorageSlotResultS2CPayload payload) {
                    buf.writeBoolean(payload.accepted());
                    buf.writeBoolean(payload.quickMove());
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, payload.stack());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StorageSlotResultS2CPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            System.out.println("[EOF StorageResult] accepted="
                    + payload.accepted() + " quick=" + payload.quickMove() + " stack=" + payload.stack() + " insertedCount=0");
            if (mc.screen instanceof ClientLocalStorageScreen screen) {
                screen.handleValidatedTakeResult(
                        payload.accepted(),
                        payload.quickMove(),
                        payload.stack()
                );
            }
        });
    }
}