package com.eoframework.network;

import com.eoframework.EOFramework;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record OwnerSpawnItemC2SPayload(
        int entityId,
        UUID uuid,
        BlockPos sourcePos,
        ItemStack stack,
        double x, double y, double z,
        double vx, double vy, double vz
) implements CustomPacketPayload {
    public static final Type<OwnerSpawnItemC2SPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EOFramework.MODID, "owner_spawn_item_c2s"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OwnerSpawnItemC2SPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OwnerSpawnItemC2SPayload decode(RegistryFriendlyByteBuf buf) {
                    return new OwnerSpawnItemC2SPayload(
                            // decode
                            buf.readVarInt(),
                            buf.readUUID(),
                            BlockPos.STREAM_CODEC.decode(buf),
                            ItemStack.OPTIONAL_STREAM_CODEC.decode(buf),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble()
                    );
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OwnerSpawnItemC2SPayload payload) {
                    // encode
                    buf.writeVarInt(payload.entityId());
                    buf.writeUUID(payload.uuid());
                    BlockPos.STREAM_CODEC.encode(buf, payload.sourcePos());
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, payload.stack());
                    buf.writeDouble(payload.x());
                    buf.writeDouble(payload.y());
                    buf.writeDouble(payload.z());
                    buf.writeDouble(payload.vx());
                    buf.writeDouble(payload.vy());
                    buf.writeDouble(payload.vz());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OwnerSpawnItemC2SPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;

            ItemStack stack = payload.stack();
            if (stack.isEmpty()) return;
            if (stack.getCount() > stack.getMaxStackSize()) return;

            var level = player.serverLevel();

            if (!level.hasChunkAt(payload.sourcePos())) return;

            if (player.distanceToSqr(
                    payload.sourcePos().getX() + 0.5D,
                    payload.sourcePos().getY() + 0.5D,
                    payload.sourcePos().getZ() + 0.5D
            ) > 12.0D * 12.0D) {
                return;
            }

            if (player.distanceToSqr(payload.x(), payload.y(), payload.z()) > 12.0D * 12.0D) {
                return;
            }

            if (payload.entityId() < 1_000_000_000) {
                return;
            }

            ItemEntity item = new ItemEntity(
                    level,
                    payload.x(),
                    payload.y(),
                    payload.z(),
                    stack.copy(),
                    payload.vx(),
                    payload.vy(),
                    payload.vz()
            );

            item.setId(payload.entityId());
            item.setUUID(payload.uuid());
            item.setTarget(player.getUUID());
            item.setNoPickUpDelay();
            item.setThrower(player);

            level.addFreshEntity(item);
        });
    }
}