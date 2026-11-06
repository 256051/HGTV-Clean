package com.hgtv.clean;

import java.security.SecureRandom;
import java.util.LinkedHashSet;
import java.util.Set;

/** Keeps the model endpoint's temporary recovery parameters separate from registered device identity. */
final class ModelDeviceState {
    private final SecureRandom random = new SecureRandom();
    private final Set<String> rejected = new LinkedHashSet<>();
    private String registered = "", current = "";

    synchronized String current(String device) {
        if (!registered.equals(device)) { registered = device; current = device; rejected.clear(); }
        return current;
    }

    synchronized void reject(String device) {
        current(device);
        rejected.add(current);
        while (rejected.size() > 64) rejected.remove(rejected.iterator().next());
        String next;
        do { next = candidate(random.nextLong()); }
        while (next.equals(registered) || next.equals(current) || rejected.contains(next));
        current = next;
    }

    static String candidate(long random) {
        long value = random % 900000000000000L;
        if (value < 0) value += 900000000000000L;
        return Long.toString(value + 100000000000000L);
    }
}
