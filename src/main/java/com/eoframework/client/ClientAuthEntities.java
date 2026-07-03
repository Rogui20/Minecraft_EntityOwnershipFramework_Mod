package com.eoframework.client;

import com.eoframework.network.OwnerPickupItemC2SPayload;
import com.eoframework.network.OwnerSpawnItemC2SPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class ClientAuthEntities {
    private static final Set<Integer> CLIENT_AUTH_IDS = new HashSet<>();
    private static final Set<UUID> CLIENT_AUTH_UUIDS = new HashSet<>();

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
            if (isClientAuth(item.getId()) || isClientAuth(item.getUUID())) {
                tryPickupOwnerItem(item);
            }
        }
    }

    public static boolean tryPickupOwnerItem(ItemEntity item) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;

        ItemStack stack = item.getItem();
        if (stack.isEmpty()) return false;

        // Só interfere nos itens client-auth
        if (!isClientAuth(item.getId())) {
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