package com.virtualdap.fixture.feature;

/** Exists exclusively in the independently signed feature split APK. */
public final class FeatureMarker {
    public static String value() { return "FEATURE SPLIT LOADED"; }
    private FeatureMarker() {}
}
