package com.eoframework.common;

import com.eoframework.EOFramework;

import java.util.EnumSet;
import java.util.Locale;

public final class EOFDebug {
    public enum Flag {
        STORAGE,
        STORAGE_CLICK,
        STORAGE_CURSOR,
        STORAGE_PENDING,
        STORAGE_TAKE,
        STORAGE_INSERT,
        STORAGE_RESULT,
        STORAGE_SNAPSHOT,
        STORAGE_GUARD,
        BLOCK_OWNERSHIP,
        BLOCK_BREAK,
        CLIENT_AUTH_ITEM,
        NETWORK
    }

    private static final EnumSet<Flag> DEFAULT_ENABLED = EnumSet.noneOf(Flag.class);
    private static final EnumSet<Flag> ENABLED = loadEnabledFlags();

    private EOFDebug() {
    }

    public static boolean isEnabled(Flag flag) {
        return ENABLED.contains(flag);
    }

    public static void log(Flag flag, String msg, Object... args) {
        if (!isEnabled(flag)) return;
        EOFramework.LOGGER.info("[EOF {}] " + msg, prepend(flag, args));
    }

    private static Object[] prepend(Flag flag, Object[] args) {
        Object[] combined = new Object[args.length + 1];
        combined[0] = flag.name();
        System.arraycopy(args, 0, combined, 1, args.length);
        return combined;
    }

    private static EnumSet<Flag> loadEnabledFlags() {
        String configured = System.getProperty("eof.debug", "").trim();
        if (configured.isEmpty()) return EnumSet.copyOf(DEFAULT_ENABLED);
        if (configured.equalsIgnoreCase("all")) return EnumSet.allOf(Flag.class);
        if (configured.equalsIgnoreCase("none") || configured.equalsIgnoreCase("off")) return EnumSet.noneOf(Flag.class);

        EnumSet<Flag> flags = EnumSet.noneOf(Flag.class);
        for (String part : configured.split(",")) {
            String name = part.trim().toUpperCase(Locale.ROOT);
            if (name.isEmpty()) continue;
            try {
                flags.add(Flag.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                EOFramework.LOGGER.warn("[EOF Debug] Unknown debug flag '{}'", part.trim());
            }
        }
        return flags;
    }
}
