package com.eoframework.common;

import com.eoframework.network.ItemOwnershipSyncS2CPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ItemOwnershipManager {
    private static final long MIGRATION_COOLDOWN_TICKS = 40L;
    private static final double REQUESTER_CLOSE_ENOUGH_DIST_SQ = 12.0D * 12.0D;
    private static final double CLOSER_MARGIN_DIST_SQ = 0.25D;
    private static final Map<UUID, Ownership> OWNERSHIPS = new HashMap<>();

    private ItemOwnershipManager() {}

    public static void register(ItemEntity item, UUID ownerUuid, long gameTime) {
        OWNERSHIPS.put(item.getUUID(), new Ownership(item.getId(), item.getUUID(), ownerUuid, gameTime, gameTime));
        EOFDebug.log(EOFDebug.Flag.CLIENT_AUTH_ITEM,
                "[ItemOwnership] registered entityId={} itemUuid={} owner={} ownerAssignedGameTime={}",
                item.getId(), item.getUUID(), ownerUuid, gameTime);
    }

    public static boolean isOwner(ItemEntity item, UUID playerUuid) {
        Ownership ownership = OWNERSHIPS.get(item.getUUID());
        UUID owner = ownership != null ? ownership.ownerUuid() : item.getTarget();
        return owner == null || owner.equals(playerUuid);
    }

    public static void requestMigration(ServerPlayer requester, int entityId, UUID itemUuid) {
        ServerLevel level = requester.serverLevel();
        Entity entity = level.getEntity(itemUuid);
        if (!(entity instanceof ItemEntity item) || item.getId() != entityId) {
            deny(entityId, itemUuid, requester, null, -1, -1, "missing_item");
            return;
        }

        Ownership ownership = OWNERSHIPS.get(itemUuid);
        UUID ownerUuid = ownership != null ? ownership.ownerUuid() : item.getTarget();
        long ownerAssigned = ownership != null ? ownership.ownerAssignedGameTime() : 0L;
        long lastMigration = ownership != null ? ownership.lastMigrationGameTime() : ownerAssigned;
        long now = level.getGameTime();
        double requesterDistSq = requester.distanceToSqr(item);

        if (requesterDistSq > REQUESTER_CLOSE_ENOUGH_DIST_SQ) {
            deny(entityId, itemUuid, requester, ownerUuid, requesterDistSq, -1, "requester_too_far");
            return;
        }
        if (ownerUuid != null && ownerUuid.equals(requester.getUUID())) {
            deny(entityId, itemUuid, requester, ownerUuid, requesterDistSq, requesterDistSq, "requester_already_owner");
            return;
        }
        long cooldownStart = Math.max(ownerAssigned, lastMigration);
        if (now - cooldownStart < MIGRATION_COOLDOWN_TICKS) {
            deny(entityId, itemUuid, requester, ownerUuid, requesterDistSq, -1, "cooldown");
            return;
        }

        ServerPlayer owner = ownerUuid == null ? null : level.getServer().getPlayerList().getPlayer(ownerUuid);
        double ownerDistSq = Double.POSITIVE_INFINITY;
        if (owner != null && owner.level() == level) {
            ownerDistSq = owner.distanceToSqr(item);
            if (!(requesterDistSq < ownerDistSq - CLOSER_MARGIN_DIST_SQ)) {
                deny(entityId, itemUuid, requester, ownerUuid, requesterDistSq, ownerDistSq, "not_closer");
                return;
            }
        }

        UUID newOwner = requester.getUUID();
        OWNERSHIPS.put(itemUuid, new Ownership(entityId, itemUuid, newOwner, now, now));
        item.setTarget(newOwner);
        item.setThrower(requester);
        ItemOwnershipSyncS2CPayload sync = new ItemOwnershipSyncS2CPayload(entityId, itemUuid, newOwner, now);
        PacketDistributor.sendToPlayersTrackingEntity(item, sync);
        PacketDistributor.sendToPlayer(requester, sync);
        EOFDebug.log(EOFDebug.Flag.CLIENT_AUTH_ITEM,
                "[ItemOwnership] accepted request entityId={} itemUuid={} requester={} owner={} requesterDist={} ownerDist={}",
                entityId, itemUuid, requester.getUUID(), ownerUuid, requesterDistSq, ownerDistSq);
    }

    private static void deny(int entityId, UUID itemUuid, ServerPlayer requester, UUID ownerUuid, double requesterDistSq, double ownerDistSq, String reason) {
        EOFDebug.log(EOFDebug.Flag.CLIENT_AUTH_ITEM,
                "[ItemOwnership] denied reason={} request entityId={} itemUuid={} requester={} owner={} requesterDist={} ownerDist={}",
                reason, entityId, itemUuid, requester.getUUID(), ownerUuid, requesterDistSq, ownerDistSq);
    }

    private record Ownership(int entityId, UUID itemUuid, UUID ownerUuid, long ownerAssignedGameTime, long lastMigrationGameTime) {}
}
