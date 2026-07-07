package com.eoframework.mixin.client.core;

import com.eoframework.client.ClientLocalStorageScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeStorageClickMixin {
    @Inject(method = "handleInventoryMouseClick", at = @At("HEAD"), cancellable = true)
    private void eof$cancelVanillaStorageClickForNonOwner(
            int containerId,
            int slotId,
            int mouseButton,
            ClickType clickType,
            Player player,
            CallbackInfo ci
    ) {
        Minecraft mc = Minecraft.getInstance();

        if (!(mc.screen instanceof ClientLocalStorageScreen screen)) return;

        boolean cancel = screen.shouldBlockVanillaClick(slotId, clickType);
        ItemStack beforeCarried = player.containerMenu.getCarried().copy();
        if (!cancel) {
            screen.logStorageGuard("MultiPlayerGameMode.handleInventoryMouseClick", slotId, clickType, beforeCarried, false);
            return;
        }

        AbstractContainerMenu menu = player.containerMenu;
        screen.handleNonOwnerValidatedClick(
                slotId >= 0 && slotId < menu.slots.size() ? menu.getSlot(slotId) : null,
                slotId,
                mouseButton,
                clickType
        );
        screen.logStorageGuard("MultiPlayerGameMode.handleInventoryMouseClick", slotId, clickType, beforeCarried, true);
        ci.cancel();
    }
}
