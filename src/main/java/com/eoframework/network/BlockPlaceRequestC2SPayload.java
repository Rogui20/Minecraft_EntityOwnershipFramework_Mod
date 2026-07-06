package com.eoframework.network;

import com.eoframework.EOFramework;
import com.eoframework.common.BlockOwnershipManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record BlockPlaceRequestC2SPayload(
        BlockPos pos,
        Direction face,
        ItemStack stack
) implements CustomPacketPayload {
    public static final Type<BlockPlaceRequestC2SPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EOFramework.MODID, "block_place_request_c2s"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BlockPlaceRequestC2SPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, BlockPlaceRequestC2SPayload::pos,
                    Direction.STREAM_CODEC, BlockPlaceRequestC2SPayload::face,
                    ItemStack.OPTIONAL_STREAM_CODEC, BlockPlaceRequestC2SPayload::stack,
                    BlockPlaceRequestC2SPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BlockPlaceRequestC2SPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;

            Level level = player.level();
            BlockPos pos = payload.pos();
            ItemStack payloadStack = payload.stack();

            if (payloadStack.isEmpty()) return;
            if (!(payloadStack.getItem() instanceof BlockItem blockItem)) return;
            if (!level.isEmptyBlock(pos)) return;
            if (player.distanceToSqr(Vec3.atCenterOf(pos)) > 8.0D * 8.0D) return;
            if (!BlockOwnershipManager.isOwner(player.serverLevel(), pos, player)) return;

            ItemStack held = player.getMainHandItem();
            if (!player.getAbilities().instabuild) {
                if (held.isEmpty()) return;
                if (!ItemStack.isSameItemSameComponents(held, payloadStack)) return;
            }

            BlockHitResult hit = new BlockHitResult(
                    Vec3.atCenterOf(pos),
                    payload.face(),
                    pos.relative(payload.face().getOpposite()),
                    false
            );

            BlockPlaceContext placeContext = new BlockPlaceContext(
                    player,
                    InteractionHand.MAIN_HAND,
                    payloadStack.copyWithCount(1),
                    hit
            );

            var result = blockItem.place(placeContext);

            if (result.consumesAction() && !player.getAbilities().instabuild) {
                held.shrink(1);
            }
        });
    }
}