package com.eoframework.network;

import com.eoframework.EOFramework;
import com.eoframework.common.BlockOwnershipManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record BlockBreakProgressC2SPayload(BlockPos pos, int stage) implements CustomPacketPayload {
    public static final Type<BlockBreakProgressC2SPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(EOFramework.MODID, "block_break_progress_c2s"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BlockBreakProgressC2SPayload> STREAM_CODEC = StreamCodec.composite(BlockPos.STREAM_CODEC, BlockBreakProgressC2SPayload::pos, ByteBufCodecs.VAR_INT, BlockBreakProgressC2SPayload::stage, BlockBreakProgressC2SPayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void handle(BlockBreakProgressC2SPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer owner)) return;
            var level = owner.serverLevel();
            if (!BlockOwnershipManager.isOwner(level, payload.pos(), owner)) return;
            for (ServerPlayer player : level.players()) {
                if (player == owner) continue;
                if (player.distanceToSqr(payload.pos().getX() + 0.5D, payload.pos().getY() + 0.5D, payload.pos().getZ() + 0.5D) <= 32.0D * 32.0D) {
                    PacketDistributor.sendToPlayer(player, new BlockBreakProgressS2CPayload(owner.getId(), payload.pos(), payload.stage()));
                }
            }
        });
    }
}
