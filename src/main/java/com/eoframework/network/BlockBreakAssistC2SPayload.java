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

public record BlockBreakAssistC2SPayload(BlockPos pos, boolean active) implements CustomPacketPayload {
    public static final Type<BlockBreakAssistC2SPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EOFramework.MODID, "block_break_assist_c2s"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BlockBreakAssistC2SPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, BlockBreakAssistC2SPayload::pos,
                    ByteBufCodecs.BOOL, BlockBreakAssistC2SPayload::active,
                    BlockBreakAssistC2SPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BlockBreakAssistC2SPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;

            var level = player.serverLevel();
            BlockPos pos = payload.pos();

            if (!level.hasChunkAt(pos)) return;
            if (level.isEmptyBlock(pos) && payload.active()) return;

            var ownerUuid = BlockOwnershipManager.getOrAssignOwner(level, pos, player);
            EOFramework.LOGGER.info(
                    "[EOF BlockBreakAssist] pos={} owner={} requester={} active={}",
                    pos,
                    ownerUuid,
                    player.getUUID(),
                    payload.active()
            );

            if (ownerUuid.equals(player.getUUID())) return;

            if (player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > 8.0D * 8.0D) {
                EOFramework.LOGGER.info(
                        "[EOF BlockBreakAssist] reject requester too far pos={} owner={} requester={}",
                        pos,
                        ownerUuid,
                        player.getUUID()
                );
                return;
            }

            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerUuid);
            if (owner == null) {
                EOFramework.LOGGER.info(
                        "[EOF BlockBreakAssist] owner offline/invalid pos={} owner={} requester={}",
                        pos,
                        ownerUuid,
                        player.getUUID()
                );
                return;
            }

            PacketDistributor.sendToPlayer(owner, new BlockBreakAssistS2CPayload(player.getUUID(), pos, payload.active()));
        });
    }
}
