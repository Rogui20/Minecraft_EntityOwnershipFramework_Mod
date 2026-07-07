package com.eoframework.network;

import com.eoframework.common.EOFDebug;
import com.eoframework.EOFramework;
import com.eoframework.common.BlockOwnershipManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record BlockBreakAssistC2SPayload(BlockPos pos, boolean active, float speed) implements CustomPacketPayload {
    public static final Type<BlockBreakAssistC2SPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EOFramework.MODID, "block_break_assist_c2s"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BlockBreakAssistC2SPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public BlockBreakAssistC2SPayload decode(RegistryFriendlyByteBuf buf) {
            return new BlockBreakAssistC2SPayload(BlockPos.STREAM_CODEC.decode(buf), buf.readBoolean(), buf.readFloat());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, BlockBreakAssistC2SPayload payload) {
            BlockPos.STREAM_CODEC.encode(buf, payload.pos());
            buf.writeBoolean(payload.active());
            buf.writeFloat(payload.speed());
        }
    };

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

            var existingOwnerUuid = BlockOwnershipManager.getOwner(level, pos);
            var ownerUuid = BlockOwnershipManager.getOrAssignOwner(level, pos, player);
            PacketDistributor.sendToPlayer(player, new ChunkOwnerSyncS2CPayload(level.dimension().location().toString(), pos.getX() >> 4, pos.getZ() >> 4, ownerUuid));

            EOFDebug.log(EOFDebug.Flag.BLOCK_BREAK, 
                    "[EOF BlockBreakAssist] assist received requester={} owner={} pos={} speed={} active={}",
                    player.getUUID(),
                    ownerUuid,
                    pos,
                    payload.speed(),
                    payload.active()
            );

            if (existingOwnerUuid == null) {
                EOFDebug.log(EOFDebug.Flag.BLOCK_BREAK, 
                        "[EOF BlockBreakAssist] assigned owner pos={} owner={} requester={}",
                        pos,
                        ownerUuid,
                        player.getUUID()
                );
            }

            if (ownerUuid.equals(player.getUUID())) return;

            if (player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > 8.0D * 8.0D) {
                EOFDebug.log(EOFDebug.Flag.BLOCK_BREAK, 
                        "[EOF BlockBreakAssist] reject requester too far pos={} owner={} requester={}",
                        pos,
                        ownerUuid,
                        player.getUUID()
                );
                return;
            }

            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerUuid);
            if (owner == null) {
                EOFDebug.log(EOFDebug.Flag.BLOCK_BREAK, 
                        "[EOF BlockBreakAssist] owner offline/invalid pos={} owner={} requester={}",
                        pos,
                        ownerUuid,
                        player.getUUID()
                );
                return;
            }

            PacketDistributor.sendToPlayer(owner, new BlockBreakAssistS2CPayload(player.getUUID(), pos, payload.active(), payload.speed()));
        });
    }
}
