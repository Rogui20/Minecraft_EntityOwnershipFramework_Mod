package com.eoframework.network;

import com.eoframework.EOFramework;
import com.eoframework.client.ClientLocalStorageScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record StorageSlotRequestS2CPayload(
        UUID requester,
        BlockPos pos,
        int slot,
        boolean quickMove
) implements CustomPacketPayload {
    public static final Type<StorageSlotRequestS2CPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EOFramework.MODID, "storage_slot_request_s2c"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StorageSlotRequestS2CPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public StorageSlotRequestS2CPayload decode(RegistryFriendlyByteBuf buf) {
                    return new StorageSlotRequestS2CPayload(
                            buf.readUUID(),
                            BlockPos.STREAM_CODEC.decode(buf),
                            buf.readVarInt(),
                            buf.readBoolean()
                    );
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, StorageSlotRequestS2CPayload payload) {
                    buf.writeUUID(payload.requester());
                    BlockPos.STREAM_CODEC.encode(buf, payload.pos());
                    buf.writeVarInt(payload.slot());
                    buf.writeBoolean(payload.quickMove());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StorageSlotRequestS2CPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            System.out.println("[EOF Storage] owner received remote request slot=" + payload.slot());
            if (mc.screen instanceof ClientLocalStorageScreen screen
                    && screen.isForStorageLoose(payload.pos())) {
                screen.handleRemoteTakeRequest(
                        payload.requester(),
                        payload.slot(),
                        payload.quickMove()
                );
            }
        });
    }
}