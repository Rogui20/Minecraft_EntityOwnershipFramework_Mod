package com.eoframework.mixin.client.core;

import com.eoframework.client.ClientAuthEntities;
import com.eoframework.client.ClientLocalStorageSession;
import com.eoframework.client.ClientOwnedBlockRuntime;
import com.eoframework.client.ClientStorageAnimationSuppressor;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.LevelEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
    @Shadow
    private ClientLevel level;

    @Inject(method = "handleAddEntity", at = @At("HEAD"), cancellable = true)
    private void eof$skipDuplicateClientAuthAdd(ClientboundAddEntityPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        if (ClientAuthEntities.isClientAuth(packet.getId()) || ClientAuthEntities.isClientAuth(packet.getUUID())) {
            Entity existing = mc.level.getEntity(packet.getId());

            if (existing != null) {
                if (existing instanceof ItemEntity item && mc.player != null) {
                    item.setTarget(mc.player.getUUID());
                    item.setThrower(mc.player);
                    item.setNoPickUpDelay();
                } else {
                    existing.recreateFromPacket(packet);
                }

                ci.cancel();
            }
        }
    }

    @Inject(method = "handleBlockEvent", at = @At("HEAD"), cancellable = true)
    private void eof$suppressStorageBlockEvent(ClientboundBlockEventPacket packet, CallbackInfo ci) {
        if (ClientLocalStorageSession.shouldSuppressBlockEvent(packet.getPos())) {
            ci.cancel();
        }
    }

    @Inject(method = "handleOpenScreen", at = @At("HEAD"), cancellable = true)
    private void eof$suppressStorageOpenScreen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        if (ClientLocalStorageSession.shouldSuppressMenuPacket()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleLevelEvent", at = @At("HEAD"), cancellable = true)
    private void eof$suppressDelayedBreakParticles(ClientboundLevelEventPacket packet, CallbackInfo ci) {
        if (packet.getType() != LevelEvent.PARTICLES_DESTROY_BLOCK) {
            return;
        }

        if (ClientOwnedBlockRuntime.shouldSuppressBreakEcho(packet.getPos())) {
            ci.cancel();
        }
    }

    @Inject(method = "handleRemoveEntities", at = @At("HEAD"), cancellable = true)
    private void eof$suppressClientAuthRemove(ClientboundRemoveEntitiesPacket packet, CallbackInfo ci) {
        boolean hasClientAuth = false;
        IntArrayList remaining = new IntArrayList();

        for (int id : packet.getEntityIds()) {
            if (ClientAuthEntities.isClientAuth(id)) {
                hasClientAuth = true;
                System.out.println("[EOF ClientAuthItem] suppress remove id=" + id);
            } else {
                remaining.add(id);
            }
        }

        if (!hasClientAuth) {
            return;
        }

        ci.cancel();

        // Se o packet tinha entidades normais junto, remove só elas.
        for (int id : remaining) {
            this.level.removeEntity(id, Entity.RemovalReason.DISCARDED);
        }
    }

    @Inject(method = "handleSetEntityMotion", at = @At("HEAD"), cancellable = true)
    private void eof$suppressClientAuthMotion(ClientboundSetEntityMotionPacket packet, CallbackInfo ci) {
        if (ClientAuthEntities.isClientAuth(packet.getId())) {
            System.out.println("[EOF ClientAuthItem] suppress motion id=" + packet.getId());
            ci.cancel();
        }
    }

    @Inject(method = "handleTeleportEntity", at = @At("HEAD"), cancellable = true)
    private void eof$suppressClientAuthTeleport(ClientboundTeleportEntityPacket packet, CallbackInfo ci) {
        if (ClientAuthEntities.isClientAuth(packet.getId())) {
            System.out.println("[EOF ClientAuthItem] suppress teleport id=" + packet.getId());
            ci.cancel();
        }
    }

    @Inject(method = "handleMoveEntity", at = @At("HEAD"), cancellable = true)
    private void eof$suppressClientAuthMove(ClientboundMoveEntityPacket packet, CallbackInfo ci) {
        Entity entity = packet.getEntity(this.level);

        if (entity != null && ClientAuthEntities.isClientAuth(entity.getId())) {
            System.out.println("[EOF ClientAuthItem] suppress move id=" + entity.getId());
            ci.cancel();
        }
    }

    @Inject(method = "handleSetEntityData", at = @At("HEAD"), cancellable = true)
    private void eof$suppressClientAuthEntityData(ClientboundSetEntityDataPacket packet, CallbackInfo ci) {
        if (ClientAuthEntities.isClientAuth(packet.id())) {
            System.out.println("[EOF ClientAuthItem] suppress entity data id=" + packet.id());
            ci.cancel();
        }
    }
}