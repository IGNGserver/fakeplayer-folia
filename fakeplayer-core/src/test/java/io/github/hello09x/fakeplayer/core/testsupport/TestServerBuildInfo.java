package io.github.hello09x.fakeplayer.core.testsupport;

import io.papermc.paper.ServerBuildInfo;
import net.kyori.adventure.key.Key;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Minimal Paper build metadata for Bukkit's API-only test fixture.
 */
public final class TestServerBuildInfo implements ServerBuildInfo {

    @Override
    public Key brandId() {
        return ServerBuildInfo.BRAND_PAPER_ID;
    }

    @Override
    public boolean isBrandCompatible(Key brand) {
        return ServerBuildInfo.BRAND_PAPER_ID.equals(brand);
    }

    @Override
    public String brandName() {
        return "Paper";
    }

    @Override
    public String minecraftVersionId() {
        return "1.21.7";
    }

    @Override
    public String minecraftVersionName() {
        return "1.21.7";
    }

    @Override
    public OptionalInt buildNumber() {
        return OptionalInt.of(1);
    }

    @Override
    public Instant buildTime() {
        return Instant.EPOCH;
    }

    @Override
    public Optional<String> gitBranch() {
        return Optional.of("test");
    }

    @Override
    public Optional<String> gitCommit() {
        return Optional.of("test");
    }

    @Override
    public String asString(StringRepresentation representation) {
        return switch (representation) {
            case VERSION_SIMPLE -> "Paper 1.21.7";
            case VERSION_FULL -> "Paper 1.21.7 (test)";
        };
    }
}
