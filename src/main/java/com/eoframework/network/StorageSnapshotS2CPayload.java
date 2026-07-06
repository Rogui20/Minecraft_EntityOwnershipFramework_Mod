package com.eoframework.network;

import com.eoframework.EOFramework;
import com.eoframework.client.ClientLocalStorageScreen;
import com.eoframework.client.ClientStorageCache;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

public record StorageSnapshotS2CPayload(
        BlockPos canonicalStoragePos,
        List<BlockPos> storagePositions,
        List<ItemStack> items,
        boolean owner
) implements CustomPacketPayload {
    public static final Type<StorageSnapshotS2CPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EOFramework.MODID, "storage_snapshot_s2c"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StorageSnapshotS2CPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public StorageSnapshotS2CPayload decode(RegistryFriendlyByteBuf buf) {
                    BlockPos canonicalStoragePos = BlockPos.STREAM_CODEC.decode(buf);
                    int posCount = buf.readVarInt();
                    List<BlockPos> storagePositions = new ArrayList<>();
                    for (int i = 0; i < posCount; i++) {
                        storagePositions.add(BlockPos.STREAM_CODEC.decode(buf));
                    }

                    int size = buf.readVarInt();
                    List<ItemStack> items = new ArrayList<>();
                    for (int i = 0; i < size; i++) {
                        items.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
                    }

                    boolean owner = buf.readBoolean();
                    return new StorageSnapshotS2CPayload(canonicalStoragePos, storagePositions, items, owner);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, StorageSnapshotS2CPayload payload) {
                    BlockPos.STREAM_CODEC.encode(buf, payload.canonicalStoragePos());
                    buf.writeVarInt(payload.storagePositions().size());
                    for (BlockPos pos : payload.storagePositions()) {
                        BlockPos.STREAM_CODEC.encode(buf, pos);
                    }

                    buf.writeVarInt(payload.items().size());
                    for (ItemStack stack : payload.items()) {
                        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack);
                    }
                    buf.writeBoolean(payload.owner());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StorageSnapshotS2CPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientStorageCache.put(payload.canonicalStoragePos(), payload.storagePositions(), payload.items(), payload.owner());
            var mc = net.minecraft.client.Minecraft.getInstance();

            if (mc.screen instanceof ClientLocalStorageScreen screen
                    && screen.isForStorageLoose(payload.canonicalStoragePos())) {
                screen.refreshFromCache();
            }
        });
    }
}
