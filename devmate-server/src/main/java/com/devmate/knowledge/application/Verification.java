package com.devmate.knowledge.application;

/** A correlated atomic object proves the sole PUT committed, even if its HTTP response was lost. */
public record Verification(boolean exists, boolean correlatedWrite, boolean integrityMatches) {
    public static Verification missing() { return new Verification(false, false, false); }
}
