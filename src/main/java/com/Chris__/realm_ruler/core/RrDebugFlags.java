package com.Chris__.realm_ruler.core;

/**
 * Runtime flags controlled via JVM system properties.
 *
 * Usage:
 * - Enable debug logs/dumps:   -Drr.debug=true
 * - Enable verbose spam logs:  -Drr.verbose=true
 */
public final class RrDebugFlags {

    private RrDebugFlags() {}

    public static boolean debug() {
        return Boolean.getBoolean("rr.debug");
    }

    public static boolean verbose() {
        return Boolean.getBoolean("rr.verbose");
    }
}

