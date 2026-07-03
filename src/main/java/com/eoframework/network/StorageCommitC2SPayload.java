package com.eoframework.network;

import com.eoframework.EOFramework;
import com.eoframework.common.StorageOwnershipManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

public record StorageCommitC2SPayload(
        BlockPos pos,
        List<ItemStack> items
) implements CustomPacketPayload {
    public static final Type<StorageCommitC2SPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EOFramework.MODID, "storage_commit_c2s"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StorageCommitC2SPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public StorageCommitC2SPayload decode(RegistryFriendlyByteBuf buf) {
                    BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
                    int size = buf.readVarInt();
                    List<ItemStack> items = new ArrayList<>();

                    for (int i = 0; i < size; i++) {
                        items.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
                    }

                    return new StorageCommitC2SPayload(pos, items);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, StorageCommitC2SPayload payload) {
                    BlockPos.STREAM_CODEC.encode(buf, payload.pos());
                    buf.writeVarInt(payload.items().size());

                    for (ItemStack stack : payload.items()) {
                        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack);
                    }
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StorageCommitC2SPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!StorageOwnershipManager.isOwner(player.serverLevel(), payload.pos(), player)) {
                EOFramework.LOGGER.warn(
                        "[EOF Storage] ignored non-owner commit pos={} player={}",
                        payload.pos(),
                        player.getGameProfile().getName()
                );
                return;
            }

            StorageOwnershipManager.applySnapshot(
                    player.serverLevel(),
                    payload.pos(),
                    player,
                    payload.items()
            );
        });
    }
}