package com.eoframework.client;

import java.util.ArrayDeque;

public class ClientReservedEntityIds {
    private static final ArrayDeque<Integer> IDS = new ArrayDeque<>();

    public static void addRange(int start, int count) {
        for (int i = 0; i < count; i++) {
            IDS.add(start + i);
        }
    }

    public static int take() {
        return IDS.isEmpty() ? Integer.MIN_VALUE : IDS.removeFirst();
    }

    public static int remaining() {
        return IDS.size();
    }
}