package com.eoframework.mixin.client.core;

import com.eoframework.client.ClientLocalStorageScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
public class AbstractContainerMenuStorageMixin {
    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void eof$cancelLocalStorageMenuClickForNonOwner(
            int slotId,
            int button,
            ClickType clickType,
            Player player,
            CallbackInfo ci
    ) {
        Minecraft mc = Minecraft.getInstance();

        if (!(mc.screen instanceof ClientLocalStorageScreen screen)) return;
        if (!screen.shouldBlockVanillaClick(slotId, clickType)) return;

        System.out.println("[EOF StorageGuard] AbstractContainerMenu local mutation canceled slot="
                + slotId
                + " type="
                + clickType);
        ci.cancel();
    }
}
