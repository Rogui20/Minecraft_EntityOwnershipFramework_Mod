package com.eoframework.common;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class OwnershipManager {
    private static final double CLAIM_DISTANCE_SQR = 48.0 * 48.0;
    private static final double STEAL_MARGIN_SQR = 8.0 * 8.0;

    private static final Map<UUID, UUID> OWNERS = new HashMap<>();

    private static final Map<UUID, Long> PLAYER_HEARTBEATS = new HashMap<>();

    public static void markHeartbeat(ServerPlayer player) {
        PLAYER_HEARTBEATS.put(player.getUUID(), player.level().getGameTime());
    }

    public static boolean isOwnerAlive(ServerLevel level, UUID playerId) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
        if (player == null) return false;

        long last = PLAYER_HEARTBEATS.getOrDefault(playerId, -999999L);
        return level.getGameTime() - last <= 60;
    }

    public static UUID getOwner(Entity entity) {
        return OWNERS.get(entity.getUUID());
    }

    public static boolean setOwner(Entity entity, UUID owner) {
        UUID id = entity.getUUID();
        UUID old = OWNERS.get(id);

        if (owner == null) {
            return OWNERS.remove(id) != null;
        }

        if (owner.equals(old)) return false;

        OWNERS.put(id, owner);
        return true;
    }

    public static boolean canClaim(ServerPlayer player, Mob mob) {
        double dist = player.distanceToSqr(mob);
        if (dist > CLAIM_DISTANCE_SQR) return false;

        UUID oldOwner = getOwner(mob);
        if (oldOwner == null) return true;
        if (oldOwner.equals(player.getUUID())) return true;

        if (!(player.level() instanceof ServerLevel level)) return false;

        // Owner caiu, desconectou, travou ou parou de mandar heartbeat.
        if (!isOwnerAlive(level, oldOwner)) {
            return true;
        }

        ServerPlayer oldPlayer = player.server.getPlayerList().getPlayer(oldOwner);
        if (oldPlayer == null) return true;
        if (oldPlayer.level() != player.level()) return true;

        double oldDist = oldPlayer.distanceToSqr(mob);

        return dist + STEAL_MARGIN_SQR < oldDist;
    }
    public static boolean isOwner(Entity entity, UUID playerId) {
        UUID owner = getOwner(entity);
        return owner != null && owner.equals(playerId);
    }

    public static boolean hasOwner(Entity entity) {
        return OWNERS.containsKey(entity.getUUID());
    }
    public static void remove(Entity entity) {
        OWNERS.remove(entity.getUUID());
    }

    public static void removePlayer(UUID playerId) {
        PLAYER_HEARTBEATS.remove(playerId);

        OWNERS.entrySet().removeIf(entry -> entry.getValue().equals(playerId));
    }
}