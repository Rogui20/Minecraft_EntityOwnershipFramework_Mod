package com.eoframework.client;

import com.eoframework.EOFramework;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public class ClientLocalStorageOpener {
    public static boolean openCachedStorage(BlockPos pos, Component title) {
        return openCachedStorage(pos, title, ClientStorageCache.isOwner(ClientStorageCache.canonicalPos(pos)));
    }

    public static boolean openCachedStorage(BlockPos pos, Component title, boolean ownerView) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return false;

        if (!ClientStorageCache.has(pos)) {
            EOFramework.LOGGER.info("[EOF Storage] no cached storage at {}", pos);
            return false;
        }

        BlockPos canonicalPos = ClientStorageCache.canonicalPos(pos);
        List<ItemStack> cached = ClientStorageCache.get(canonicalPos);
        if (cached.isEmpty()) {
            EOFramework.LOGGER.info("[EOF Storage] cached storage empty/invalid at {}", pos);
            return false;
        }

        int size = cached.size();
        int rows = ClientStorageCache.positions(canonicalPos).size() > 1
                ? 6
                : Math.max(1, Math.min(6, (size + 8) / 9));
        int menuSize = rows * 9;

        SimpleContainer container = new SimpleContainer(menuSize);

        for (int i = 0; i < Math.min(size, menuSize); i++) {
            container.setItem(i, cached.get(i).copy());
        }

        int localContainerId = mc.player.containerMenu.containerId + 1;

        var type = switch (rows) {
            case 1 -> net.minecraft.world.inventory.MenuType.GENERIC_9x1;
            case 2 -> net.minecraft.world.inventory.MenuType.GENERIC_9x2;
            case 3 -> net.minecraft.world.inventory.MenuType.GENERIC_9x3;
            case 4 -> net.minecraft.world.inventory.MenuType.GENERIC_9x4;
            case 5 -> net.minecraft.world.inventory.MenuType.GENERIC_9x5;
            default -> net.minecraft.world.inventory.MenuType.GENERIC_9x6;
        };

        ChestMenu menu = new ChestMenu(
                type,
                localContainerId,
                mc.player.getInventory(),
                container,
                rows
        );

        mc.player.containerMenu = menu;
        mc.setScreen(new ClientLocalStorageScreen(menu, mc.player.getInventory(), title, canonicalPos, ownerView));

        EOFramework.LOGGER.info(
                "[EOF Storage] opened cached storage pos={} slots={} rows={}",
                pos,
                size,
                rows
        );
        ClientLocalStorageSession.begin(ClientStorageAnimations.animationPositions(canonicalPos));
        ClientStorageAnimations.open(canonicalPos);
        return true;
    }
}