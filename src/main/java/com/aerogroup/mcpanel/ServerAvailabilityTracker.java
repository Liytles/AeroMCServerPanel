package com.aerogroup.mcpanel;

/** Tek bir başarısız/başarılı ping'in sahte durum bildirimi üretmesini engeller. */
final class ServerAvailabilityTracker {
    enum Change { FIRST_ONLINE, FIRST_OFFLINE, VERIFYING_ONLINE, VERIFYING_OFFLINE, ONLINE, OFFLINE, NONE }
    private static final int CONFIRMATIONS = 2;
    private boolean initialized, online;
    private int successes, failures;

    Change success() {
        failures = 0;
        if (!initialized) { initialized = true; online = true; successes = 0; return Change.FIRST_ONLINE; }
        if (online) { successes = 0; return Change.NONE; }
        if (++successes < CONFIRMATIONS) return Change.VERIFYING_ONLINE;
        successes = 0; online = true; return Change.ONLINE;
    }

    Change failure() {
        successes = 0;
        if (!initialized) {
            if (++failures < CONFIRMATIONS) return Change.VERIFYING_OFFLINE;
            failures = 0; initialized = true; online = false; return Change.FIRST_OFFLINE;
        }
        if (!online) { failures = 0; return Change.NONE; }
        if (++failures < CONFIRMATIONS) return Change.VERIFYING_OFFLINE;
        failures = 0; online = false; return Change.OFFLINE;
    }
}
