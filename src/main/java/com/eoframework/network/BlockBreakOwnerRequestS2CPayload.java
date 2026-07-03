package com.eoframework.network;

import com.eoframework.EOFramework;
import com.eoframework.client.ClientOwnedBlockRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record BlockBreakOwnerRequestS2CPayload(
        UUID requester,
        BlockPos pos,
        boolean requesterClientSpawnedDrops
) implements CustomPacketPayload {
    public static final Type<BlockBreakOwnerRequestS2CPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EOFramework.MODID, "block_break_owner_request_s2c"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BlockBreakOwnerRequestS2CPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8.map(UUID::fromString, UUID::toString),
                    BlockBreakOwnerRequestS2CPayload::requester,
                    BlockPos.STREAM_CODEC,
                    BlockBreakOwnerRequestS2CPayload::pos,
                    ByteBufCodecs.BOOL,
                    BlockBreakOwnerRequestS2CPayload::requesterClientSpawnedDrops,
                    BlockBreakOwnerRequestS2CPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BlockBreakOwnerRequestS2CPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) return;

            ClientOwnedBlockRuntime.handleRemoteBreakRequest(
                    payload.requester(),
                    payload.pos()
            );
        });
    }
}