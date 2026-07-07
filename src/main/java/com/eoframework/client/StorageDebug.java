package com.eoframework.client;

import com.eoframework.EOFramework;

import java.util.EnumSet;
import java.util.Locale;

public final class StorageDebug {
    public enum Flag {
        STORAGE_CLICK,
        STORAGE_PENDING,
        STORAGE_TAKE,
        STORAGE_INSERT,
        STORAGE_CURSOR,
        STORAGE_SNAPSHOT,
        STORAGE_RESULT,
        STORAGE_GUARD
    }

    private static final EnumSet<Flag> DEFAULT_ENABLED = EnumSet.of(
            Flag.STORAGE_CLICK,
            Flag.STORAGE_CURSOR,
            Flag.STORAGE_RESULT
    );

    private static final EnumSet<Flag> ENABLED = loadEnabledFlags();

    private StorageDebug() {
    }

    public static boolean isEnabled(Flag flag) {
        return ENABLED.contains(flag);
    }

    public static void log(Flag flag, String message, Object... args) {
        if (isEnabled(flag)) {
            EOFramework.LOGGER.info("[EOF {}] " + message, prepend(flag, args));
        }
    }

    private static Object[] prepend(Flag flag, Object[] args) {
        Object[] combined = new Object[args.length + 1];
        combined[0] = flag.name();
        System.arraycopy(args, 0, combined, 1, args.length);
        return combined;
    }

    private static EnumSet<Flag> loadEnabledFlags() {
        String configured = System.getProperty("eof.storage.debug", "").trim();
        if (configured.isEmpty()) {
            return EnumSet.copyOf(DEFAULT_ENABLED);
        }
        if (configured.equalsIgnoreCase("all")) {
            return EnumSet.allOf(Flag.class);
        }
        if (configured.equalsIgnoreCase("none") || configured.equalsIgnoreCase("off")) {
            return EnumSet.noneOf(Flag.class);
        }
        EnumSet<Flag> flags = EnumSet.noneOf(Flag.class);
        for (String part : configured.split(",")) {
            String name = part.trim().toUpperCase(Locale.ROOT);
            if (name.isEmpty()) continue;
            if (!name.startsWith("STORAGE_")) {
                name = "STORAGE_" + name;
            }
            try {
                flags.add(Flag.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                EOFramework.LOGGER.warn("[EOF StorageDebug] Unknown storage debug flag '{}'", part.trim());
            }
        }
        return flags;
    }
}
