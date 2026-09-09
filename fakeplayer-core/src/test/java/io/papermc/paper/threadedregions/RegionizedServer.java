package io.papermc.paper.threadedregions;

/**
 * Test-only marker matching the class used by {@code Tasks} to detect Folia.
 * The Paper API exposes the scheduler interfaces but not this server marker.
 */
public final class RegionizedServer {

    private RegionizedServer() {
    }
}
