package com.eoframework.network;

import com.eoframework.EOFramework;
import com.eoframework.client.ClientReservedEntityIds;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ReservedEntityIdsS2CPayload(int start, int count) implements CustomPacketPayload {
    public static final Type<ReservedEntityIdsS2CPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EOFramework.MODID, "reserved_entity_ids_s2c"));

    public static final StreamCodec<ByteBuf, ReservedEntityIdsS2CPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ReservedEntityIdsS2CPayload::start,
            ByteBufCodecs.VAR_INT, ReservedEntityIdsS2CPayload::count,
            ReservedEntityIdsS2CPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ReservedEntityIdsS2CPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientReservedEntityIds.addRange(payload.start(), payload.count()));
    }
}