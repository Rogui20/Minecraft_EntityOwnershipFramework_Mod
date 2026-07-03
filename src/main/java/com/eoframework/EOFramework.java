package com.eoframework;

import com.eoframework.network.EOFPackets;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(EOFramework.MODID)
public class EOFramework {
    public static final String MODID = "eoframework";

    public static final Logger LOGGER = LogUtils.getLogger();

    public EOFramework(IEventBus modBus) {
        modBus.addListener(EOFPackets::register);
    }
}