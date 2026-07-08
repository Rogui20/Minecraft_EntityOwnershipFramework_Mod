package com.eoframework.client;

import com.eoframework.network.ItemOwnershipRequestC2SPayload;
import com.eoframework.network.OwnerPickupItemC2SPayload;
import com.eoframework.network.OwnerSpawnItemC2SPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ClientAuthEntities {
    private static final Set<Integer> CLIENT_AUTH_IDS = new HashSet<>();
    private static final Set<UUID> CLIENT_AUTH_UUIDS = new HashSet<>();
    private static final Map<UUID, UUID> ITEM_OWNERS = new HashMap<>();
    private static final Map<UUID, Long> OWNER_ASSIGNED_TICKS = new HashMap<>();
    private static final Map<UUID, Long> LAST_OWNERSHIP_REQUEST_TICKS = new HashMap<>();
    private static final long OWNERSHIP_COOLDOWN_TICKS = 40L;
    private static final long OWNERSHIP_REQUEST_RATE_LIMIT_TICKS = 20L;
    private static final double OWNERSHIP_REQUEST_RANGE_DIST_SQ = 12.0D * 12.0D;
    private static final double CLOSER_MARGIN_DIST_SQ = 0.25D;

    public static boolean isClientAuth(int id) {
        return CLIENT_AUTH_IDS.contains(id);
    }

    public static boolean isClientAuth(UUID uuid) {
        return CLIENT_AUTH_UUIDS.contains(uuid);
    }

    public static void mark(int id) {
        CLIENT_AUTH_IDS.add(id);
    }

    public static void mark(int id, UUID uuid) {
        CLIENT_AUTH_IDS.add(id);
        CLIENT_AUTH_UUIDS.add(uuid);
    }

    public static void unmark(int id) {
        CLIENT_AUTH_IDS.remove(id);
    }

    public static void unmark(int id, UUID uuid) {
        CLIENT_AUTH_IDS.remove(id);
        CLIENT_AUTH_UUIDS.remove(uuid);
        ITEM_OWNERS.remove(uuid);
        OWNER_ASSIGNED_TICKS.remove(uuid);
        LAST_OWNERSHIP_REQUEST_TICKS.remove(uuid);
    }

    public static void syncItemOwner(int entityId, UUID itemUuid, UUID ownerUuid, long ownerAssignedGameTime) {
        Minecraft mc = Minecraft.getInstance();
        ITEM_OWNERS.put(itemUuid, ownerUuid);
        OWNER_ASSIGNED_TICKS.put(itemUuid, mc.level != null ? mc.level.getGameTime() : ownerAssignedGameTime);
        Entity entity = mc.level == null ? null : mc.level.getEntity(entityId);
        if (entity instanceof ItemEntity item) {
            item.setTarget(ownerUuid);
            if (mc.player != null && mc.player.getUUID().equals(ownerUuid)) {
                mark(entityId, itemUuid);
                item.setNoPickUpDelay();
                item.setThrower(mc.player);
            }
        }
    }

    public static ItemEntity spawnLocalItem(int entityId, UUID uuid, ItemStack stack, double x, double y, double z, double vx, double vy, double vz) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;

        ItemEntity item = new ItemEntity((Level) mc.level, x, y, z, stack.copy(), vx, vy, vz);
        item.setId(entityId);
        item.setUUID(uuid);
        item.setNoPickUpDelay();

        if (mc.player != null) {
            item.setTarget(mc.player.getUUID());
            item.setThrower(mc.player);
            ITEM_OWNERS.put(uuid, mc.player.getUUID());
            OWNER_ASSIGNED_TICKS.put(uuid, mc.level.getGameTime());
        }

        mc.level.addEntity(item);
        mark(entityId, uuid);

        return item;
    }

    public static void spawnAndSendItem(ItemStack stack, BlockPos pos) {
        int id = ClientReservedEntityIds.take();
        if (id == Integer.MIN_VALUE || stack.isEmpty()) return;

        UUID uuid = UUID.randomUUID();

        double x = pos.getX() + 0.5D;
        double y = pos.getY() + 0.35D;
        double z = pos.getZ() + 0.5D;

        spawnLocalItem(id, uuid, stack.copy(), x, y, z, 0.0D, 0.15D, 0.0D);

        PacketDistributor.sendToServer(new OwnerSpawnItemC2SPayload(
                id,
                uuid,
                pos.immutable(),
                stack.copy(),
                x, y, z,
                0.0D, 0.15D, 0.0D
        ));
    }

    public static void reconcileVanillaAdd(int entityId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Entity existing = mc.level.getEntity(entityId);
        if (existing != null) {
            // Não desmarca aqui.
            // O item confirmado pelo servidor ainda deve continuar client-auth
            // para permitir pickup instantâneo do owner.
        }
    }

    public static boolean tryPickupClientAuthItem(ItemEntity item) {
        return tryPickupOwnerItem(item);
    }
    public static void tickClientAuthPickups() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        var box = mc.player.getBoundingBox().inflate(1.0D, 0.5D, 1.0D);

        for (ItemEntity item : mc.level.getEntitiesOfClass(ItemEntity.class, box)) {
            if (isClientAuth(item.getId()) || isClientAuth(item.getUUID()) || ITEM_OWNERS.containsKey(item.getUUID())) {
                if (isLocalOwner(item)) {
                    tryPickupOwnerItem(item);
                } else {
                    maybeRequestOwnership(item);
                }
            }
        }
    }

    public static boolean tryPickupOwnerItem(ItemEntity item) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;

        ItemStack stack = item.getItem();
        if (stack.isEmpty()) return false;

        // Só interfere nos itens client-auth
        if (!isClientAuth(item.getId()) || !isLocalOwner(item)) {
            return false;
        }

        ItemStack copy = stack.copy();

        if (!mc.player.getInventory().add(copy)) {
            return false;
        }

        PacketDistributor.sendToServer(new OwnerPickupItemC2SPayload(
                item.getId(),
                item.getUUID(),
                stack.copy()
        ));
        playLocalPickupEffects(item, mc.player);
        item.discard();
        unmark(item.getId(), item.getUUID());

        return true;
    }

    private static boolean isLocalOwner(ItemEntity item) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        UUID owner = ITEM_OWNERS.getOrDefault(item.getUUID(), item.getTarget());
        return owner == null || owner.equals(mc.player.getUUID());
    }

    private static void maybeRequestOwnership(ItemEntity item) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        long now = mc.level.getGameTime();
        if (now - OWNER_ASSIGNED_TICKS.getOrDefault(item.getUUID(), 0L) < OWNERSHIP_COOLDOWN_TICKS) return;
        if (now - LAST_OWNERSHIP_REQUEST_TICKS.getOrDefault(item.getUUID(), Long.MIN_VALUE / 2) < OWNERSHIP_REQUEST_RATE_LIMIT_TICKS) return;

        UUID ownerUuid = ITEM_OWNERS.getOrDefault(item.getUUID(), item.getTarget());
        if (ownerUuid == null || ownerUuid.equals(mc.player.getUUID())) return;
        var owner = mc.level.getPlayerByUUID(ownerUuid);
        double requesterDistSq = mc.player.distanceToSqr(item);
        if (requesterDistSq > OWNERSHIP_REQUEST_RANGE_DIST_SQ) return;
        double ownerDistSq = owner == null ? Double.POSITIVE_INFINITY : owner.distanceToSqr(item);
        if (requesterDistSq < ownerDistSq - CLOSER_MARGIN_DIST_SQ) {
            LAST_OWNERSHIP_REQUEST_TICKS.put(item.getUUID(), now);
            PacketDistributor.sendToServer(new ItemOwnershipRequestC2SPayload(item.getId(), item.getUUID()));
        }
    }

    private static void playLocalPickupEffects(ItemEntity item, net.minecraft.client.player.LocalPlayer player) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        mc.level.playLocalSound(
                item.getX(),
                item.getY(),
                item.getZ(),
                net.minecraft.sounds.SoundEvents.ITEM_PICKUP,
                net.minecraft.sounds.SoundSource.PLAYERS,
                0.2F,
                (mc.level.random.nextFloat() - mc.level.random.nextFloat()) * 1.4F + 2.0F,
                false
        );

        mc.particleEngine.add(
                new net.minecraft.client.particle.ItemPickupParticle(
                        mc.getEntityRenderDispatcher(),
                        mc.renderBuffers(),
                        mc.level,
                        item,
                        player
                )
        );
    }
}