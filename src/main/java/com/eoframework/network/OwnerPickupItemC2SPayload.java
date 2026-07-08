package com.eoframework.network;

import com.eoframework.EOFramework;
import com.eoframework.common.ItemOwnershipManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record OwnerPickupItemC2SPayload(
        int entityId,
        UUID uuid,
        ItemStack stack
) implements CustomPacketPayload {
    public static final Type<OwnerPickupItemC2SPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EOFramework.MODID, "owner_pickup_item_c2s"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OwnerPickupItemC2SPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OwnerPickupItemC2SPayload decode(RegistryFriendlyByteBuf buf) {
                    return new OwnerPickupItemC2SPayload(
                            buf.readVarInt(),
                            buf.readUUID(),
                            ItemStack.OPTIONAL_STREAM_CODEC.decode(buf)
                    );
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OwnerPickupItemC2SPayload payload) {
                    buf.writeVarInt(payload.entityId());
                    buf.writeUUID(payload.uuid());
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, payload.stack());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OwnerPickupItemC2SPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;

            Entity entity = player.serverLevel().getEntity(payload.uuid());
            if (!(entity instanceof ItemEntity item)) return;
            if (item.getId() != payload.entityId()) return;
            if (item.distanceToSqr(player) > 3.0D * 3.0D) return;
            if (!ItemOwnershipManager.isOwner(item, player.getUUID())) return;

            ItemStack stack = item.getItem();
            if (stack.isEmpty()) return;
            if (!ItemStack.isSameItemSameComponents(stack, payload.stack())) return;

            ItemStack toAdd = stack.copy();

            if (player.getInventory().add(toAdd)) {
                item.discard();
                player.containerMenu.broadcastChanges();
            }
        });
    }
}