package com.eoframework.network;

import com.eoframework.common.EOFDebug;
import com.eoframework.common.StorageOwnershipManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.eoframework.EOFramework;

public record StorageTakeSlotC2SPayload(
        BlockPos pos,
        int slot,
        boolean quickMove,
        long requestId
) implements CustomPacketPayload {
    public static final Type<StorageTakeSlotC2SPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EOFramework.MODID, "storage_take_slot_c2s"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StorageTakeSlotC2SPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public StorageTakeSlotC2SPayload decode(RegistryFriendlyByteBuf buf) {
                    return new StorageTakeSlotC2SPayload(
                            BlockPos.STREAM_CODEC.decode(buf),
                            buf.readVarInt(),
                            buf.readBoolean(),
                            buf.readVarLong()
                    );
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, StorageTakeSlotC2SPayload payload) {
                    BlockPos.STREAM_CODEC.encode(buf, payload.pos());
                    buf.writeVarInt(payload.slot());
                    buf.writeBoolean(payload.quickMove());
                    buf.writeVarLong(payload.requestId());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StorageTakeSlotC2SPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            EOFDebug.log(EOFDebug.Flag.STORAGE, "[EOF Storage] server received take request player={} pos={} slot={} requestId={}",
                    player.getGameProfile().getName(), payload.pos(), payload.slot(), payload.requestId());
            StorageOwnershipManager.takeSlotForNonOwner(
                    player.serverLevel(),
                    payload.pos(),
                    player,
                    payload.slot(),
                    payload.quickMove(),
                    payload.requestId()
            );
        });
    }
}
