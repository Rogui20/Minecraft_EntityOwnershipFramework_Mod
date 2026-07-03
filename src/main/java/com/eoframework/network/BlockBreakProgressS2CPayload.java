package com.eoframework.network;

import com.eoframework.EOFramework;
import com.eoframework.client.ClientBlockBreakRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record BlockBreakProgressS2CPayload(int breakerId, BlockPos pos, int stage) implements CustomPacketPayload {
    public static final Type<BlockBreakProgressS2CPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(EOFramework.MODID, "block_break_progress_s2c"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BlockBreakProgressS2CPayload> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, BlockBreakProgressS2CPayload::breakerId, BlockPos.STREAM_CODEC, BlockBreakProgressS2CPayload::pos, ByteBufCodecs.VAR_INT, BlockBreakProgressS2CPayload::stage, BlockBreakProgressS2CPayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void handle(BlockBreakProgressS2CPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientBlockBreakRuntime.showRemoteProgress(payload.breakerId(), payload.pos(), payload.stage()));
    }
}
