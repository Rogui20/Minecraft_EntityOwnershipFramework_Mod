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
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record BlockBreakRequestC2SPayload(
        BlockPos pos,
        boolean clientSpawnedDrops
) implements CustomPacketPayload {
    public static final Type<BlockBreakRequestC2SPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EOFramework.MODID, "block_break_request_c2s"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BlockBreakRequestC2SPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, BlockBreakRequestC2SPayload::pos,
                    ByteBufCodecs.BOOL, BlockBreakRequestC2SPayload::clientSpawnedDrops,
                    BlockBreakRequestC2SPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BlockBreakRequestC2SPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;

            var level = player.serverLevel();
            BlockPos pos = payload.pos();

            if (!level.hasChunkAt(pos)) return;
            if (level.isEmptyBlock(pos)) return;

            UUID existingOwnerUuid = BlockOwnershipManager.getOwner(level, pos);
            UUID ownerUuid = BlockOwnershipManager.getOrAssignOwner(level, pos, player);
            boolean requesterIsOwner = ownerUuid.equals(player.getUUID());

            PacketDistributor.sendToPlayer(player, new BlockOwnerSyncS2CPayload(pos, ownerUuid));

            EOFramework.LOGGER.info(
                    "[EOF BlockBreakRequest] pos={} owner={} requester={} requesterIsOwner={} clientSpawnedDrops={}",
                    pos,
                    ownerUuid,
                    player.getUUID(),
                    requesterIsOwner,
                    payload.clientSpawnedDrops()
            );

            if (existingOwnerUuid == null) {
                EOFramework.LOGGER.info(
                        "[EOF BlockBreakRequest] assigned owner pos={} owner={} requester={}",
                        pos,
                        ownerUuid,
                        player.getUUID()
                );
            }

            if (!requesterIsOwner && player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > 8.0D * 8.0D) {
                EOFramework.LOGGER.info(
                        "[EOF BlockBreakRequest] reject non-owner requester too far pos={} owner={} requester={}",
                        pos,
                        ownerUuid,
                        player.getUUID()
                );
                return;
            }

            if (requesterIsOwner && !payload.clientSpawnedDrops()
                    && player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > 8.0D * 8.0D) {
                EOFramework.LOGGER.info(
                        "[EOF BlockBreakRequest] reject owner physical break too far pos={} owner={} requester={}",
                        pos,
                        ownerUuid,
                        player.getUUID()
                );
                return;
            }

            if (!requesterIsOwner) {
                ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerUuid);

                if (owner != null) {
                    PacketDistributor.sendToPlayer(owner, new BlockOwnerSyncS2CPayload(pos, ownerUuid));
                    EOFramework.LOGGER.info(
                            "[EOF BlockBreakRequest] forwarding to owner pos={} owner={} requester={} clientSpawnedDrops={}",
                            pos,
                            ownerUuid,
                            player.getUUID(),
                            payload.clientSpawnedDrops()
                    );
                    PacketDistributor.sendToPlayer(
                            owner,
                            new BlockBreakOwnerRequestS2CPayload(
                                    player.getUUID(),
                                    pos,
                                    payload.clientSpawnedDrops()
                            )
                    );
                } else {
                    EOFramework.LOGGER.info(
                            "[EOF BlockBreakRequest] owner offline/invalid for pos={} owner={} requester={}",
                            pos,
                            ownerUuid,
                            player.getUUID()
                    );
                }

                return;
            }

            var state = level.getBlockState(pos);
            BlockEntity blockEntity = level.getBlockEntity(pos);
            ItemStack tool = player.getMainHandItem();

            boolean creative = player.getAbilities().instabuild;
            List<ItemStack> drops = new ArrayList<>();

            if (!creative && !payload.clientSpawnedDrops()) {
                drops.addAll(Block.getDrops(state, level, pos, blockEntity, player, tool));

                if (blockEntity instanceof Container container) {
                    for (int i = 0; i < container.getContainerSize(); i++) {
                        ItemStack stack = container.getItem(i);
                        if (!stack.isEmpty()) {
                            drops.add(stack.copy());
                        }
                    }
                }
            }

            level.destroyBlock(pos, false, player);

            if (creative || payload.clientSpawnedDrops()) {
                return;
            }

            for (ItemStack drop : drops) {
                if (drop.isEmpty()) continue;

                ItemEntity item = new ItemEntity(
                        level,
                        pos.getX() + 0.5D,
                        pos.getY() + 0.5D,
                        pos.getZ() + 0.5D,
                        drop.copy()
                );

                item.setTarget(player.getUUID());
                item.setNoPickUpDelay();
                item.setThrower(player);

                level.addFreshEntity(item);
            }
        });
    }
}