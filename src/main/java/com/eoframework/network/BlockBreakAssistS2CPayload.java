package com.eoframework.network;

import com.eoframework.EOFramework;
import com.eoframework.client.ClientBlockBreakRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record BlockBreakAssistS2CPayload(UUID requester, BlockPos pos, boolean active, float speed) implements CustomPacketPayload {
    public static final Type<BlockBreakAssistS2CPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EOFramework.MODID, "block_break_assist_s2c"));

    public static final net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, BlockBreakAssistS2CPayload> STREAM_CODEC = new net.minecraft.network.codec.StreamCodec<>() {
        @Override public BlockBreakAssistS2CPayload decode(RegistryFriendlyByteBuf buf) { return new BlockBreakAssistS2CPayload(buf.readUUID(), BlockPos.STREAM_CODEC.decode(buf), buf.readBoolean(), buf.readFloat()); }
        @Override public void encode(RegistryFriendlyByteBuf buf, BlockBreakAssistS2CPayload payload) { buf.writeUUID(payload.requester()); BlockPos.STREAM_CODEC.encode(buf, payload.pos()); buf.writeBoolean(payload.active()); buf.writeFloat(payload.speed()); }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(BlockBreakAssistS2CPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (Minecraft.getInstance().level == null) return;
            ClientBlockBreakRuntime.handleAssist(payload.requester(), payload.pos(), payload.active(), payload.speed());
        });
    }
}
