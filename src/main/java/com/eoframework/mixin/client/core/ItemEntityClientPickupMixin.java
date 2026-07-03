package com.eoframework.mixin.client.core;

import com.eoframework.client.ClientAuthEntities;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public class ItemEntityClientPickupMixin {
    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void eof$clientAuthPickup(Player player, CallbackInfo ci) {
        ItemEntity self = (ItemEntity)(Object)this;

        if (!self.level().isClientSide) return;

        if (ClientAuthEntities.tryPickupClientAuthItem(self)) {
            ci.cancel();
        }
    }
}