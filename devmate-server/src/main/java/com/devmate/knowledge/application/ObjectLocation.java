package com.devmate.knowledge.application;

/** Internal locator; deliberately excludes its values from diagnostic string conversion. */
public record ObjectLocation(String bucket, String key) {
    @Override public String toString() { return "ObjectLocation[private]"; }
}
