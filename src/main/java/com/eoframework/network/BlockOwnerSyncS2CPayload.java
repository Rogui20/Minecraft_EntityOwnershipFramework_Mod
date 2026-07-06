package com.eoframework.network;

import com.eoframework.EOFramework;
import com.eoframework.client.ClientOwnedBlockRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record BlockOwnerSyncS2CPayload(BlockPos pos, UUID owner) implements CustomPacketPayload {
    public static final Type<BlockOwnerSyncS2CPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EOFramework.MODID, "block_owner_sync_s2c"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BlockOwnerSyncS2CPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,
                    BlockOwnerSyncS2CPayload::pos,
                    ByteBufCodecs.STRING_UTF8.map(UUID::fromString, UUID::toString),
                    BlockOwnerSyncS2CPayload::owner,
                    BlockOwnerSyncS2CPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BlockOwnerSyncS2CPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientOwnedBlockRuntime.setCellOwner(payload.pos(), payload.owner()));
    }
}
