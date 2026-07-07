package com.eoframework.network;

import com.eoframework.common.EOFDebug;
import com.eoframework.EOFramework;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record OwnerSpawnMobC2SPayload(
        int entityId,
        UUID uuid,
        ResourceLocation entityTypeId,
        double x,
        double y,
        double z,
        float yRot,
        float xRot
) implements CustomPacketPayload {
    public static final Type<OwnerSpawnMobC2SPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EOFramework.MODID, "owner_spawn_mob_c2s"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OwnerSpawnMobC2SPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OwnerSpawnMobC2SPayload decode(RegistryFriendlyByteBuf buf) {
                    return new OwnerSpawnMobC2SPayload(
                            buf.readVarInt(),
                            buf.readUUID(),
                            buf.readResourceLocation(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readDouble(),
                            buf.readFloat(),
                            buf.readFloat()
                    );
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, OwnerSpawnMobC2SPayload payload) {
                    buf.writeVarInt(payload.entityId());
                    buf.writeUUID(payload.uuid());
                    buf.writeResourceLocation(payload.entityTypeId());
                    buf.writeDouble(payload.x());
                    buf.writeDouble(payload.y());
                    buf.writeDouble(payload.z());
                    buf.writeFloat(payload.yRot());
                    buf.writeFloat(payload.xRot());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OwnerSpawnMobC2SPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;

            ServerLevel level = player.serverLevel();

            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(payload.entityTypeId());
            if (type == null) return;

            Entity entity = type.create(level);
            if (entity == null) return;

            entity.setId(payload.entityId());
            entity.setUUID(payload.uuid());
            entity.moveTo(
                    payload.x(),
                    payload.y(),
                    payload.z(),
                    payload.yRot(),
                    payload.xRot()
            );

            if (entity instanceof Mob mob) {
                mob.yHeadRot = mob.getYRot();
                mob.yBodyRot = mob.getYRot();

                mob.finalizeSpawn(
                        level,
                        level.getCurrentDifficultyAt(mob.blockPosition()),
                        MobSpawnType.SPAWN_EGG,
                        null
                );

                if (mob.isSpawnCancelled()) {
                    mob.discard();
                    return;
                }
            }

            level.addFreshEntity(entity);

            EOFDebug.log(EOFDebug.Flag.NETWORK, 
                    "[EOF ClientAuthMob] server spawned type={} id={} uuid={}",
                    payload.entityTypeId(),
                    payload.entityId(),
                    payload.uuid()
            );
        });
    }
}
