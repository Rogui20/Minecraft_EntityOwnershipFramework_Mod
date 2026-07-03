package com.eoframework.client;

import com.eoframework.EOFramework;
import com.eoframework.network.OwnerSpawnMobC2SPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

public class ClientAuthMobSpawner {
    public static boolean spawnZombieAt(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return false;

        int id = ClientReservedEntityIds.take();
        if (id == Integer.MIN_VALUE) {
            EOFramework.LOGGER.warn("[EOF ClientAuthMob] no reserved entity id available");
            return false;
        }

        UUID uuid = UUID.randomUUID();

        Entity entity = EntityType.ZOMBIE.create(mc.level);
        if (!(entity instanceof Mob mob)) {
            EOFramework.LOGGER.warn("[EOF ClientAuthMob] failed to create local zombie");
            return false;
        }

        double x = pos.getX() + 0.5D;
        double y = pos.getY();
        double z = pos.getZ() + 0.5D;

        float yRot = mc.player.getYRot();
        float xRot = 0.0F;

        mob.setId(id);
        mob.setUUID(uuid);
        mob.moveTo(x, y, z, yRot, xRot);
        mob.yHeadRot = yRot;
        mob.yBodyRot = yRot;

        mc.level.addEntity(mob);
        ClientAuthEntities.mark(id, uuid);

        PacketDistributor.sendToServer(new OwnerSpawnMobC2SPayload(
                id,
                uuid,
                BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.ZOMBIE),
                x,
                y,
                z,
                yRot,
                xRot
        ));

        EOFramework.LOGGER.info(
                "[EOF ClientAuthMob] local zombie spawned id={} uuid={} pos={}",
                id,
                uuid,
                pos
        );

        return true;
    }
}