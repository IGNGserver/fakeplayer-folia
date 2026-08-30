package io.github.hello09x.fakeplayer.core.repository;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.hello09x.devtools.database.jdbc.JdbcTemplate;
import io.github.hello09x.devtools.database.jdbc.rowmapper.BooleanRowMapper;
import io.github.hello09x.fakeplayer.core.repository.model.FakePlayerProfile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * @author tanyaofei
 * @since 2024/8/3
 **/
@Singleton
public class FakeplayerProfileRepository {

    private final JdbcTemplate jdbc;

    @Inject
    public FakeplayerProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.initTables();
    }

    public void insert(@NotNull String name, @NotNull UUID uuid) {
        var sql = "INSERT INTO fake_player_profile (name, uuid) VALUES (?, ?)";
        jdbc.update(sql, name, uuid.toString());
    }

    public boolean existsByUUID(@NotNull UUID uuid) {
        var sql = "SELECT EXISTS(SELECT 1 FROM fake_player_profile WHERE uuid = ?)";
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, new BooleanRowMapper(), uuid.toString()));
    }

    public @Nullable UUID selectUUIDByName(@NotNull String name) {
        var profile = this.selectByName(name);
        if (profile == null) {
            return null;
        }
        try {
            return UUID.fromString(profile.uuid());
        } catch (RuntimeException malformed) {
            // Returning null here is not isolation: NameManager interprets null
            // as "no row" and attempts an INSERT with the same unique name,
            // so the corrupted row still blocks spawning with a less useful
            // constraint violation. Preserve the distinction for callers.
            throw new MalformedProfileException(name, profile.uuid(), malformed);
        }
    }

    public static final class MalformedProfileException extends IllegalStateException {
        public MalformedProfileException(
                @NotNull String name,
                @Nullable String rawUuid,
                @NotNull Throwable cause
        ) {
            super("Malformed UUID in fake_player_profile for '" + name + "': " + rawUuid, cause);
        }
    }

    public @Nullable FakePlayerProfile selectByName(@NotNull String name) {
        var sql = "SELECT * FROM fake_player_profile WHERE name = ?";
        return jdbc.queryForObject(sql, FakePlayerProfile.FakePlayerProfileRowMapper.instance, name);
    }

    private void initTables() {
        jdbc.execute("""
                             create table if not exists fake_player_profile
                             (
                                 id   integer  not null primary key autoincrement,
                                 name text(32) not null,
                                 uuid text(36) not null
                             );
                             """);

        jdbc.execute("""
                             create unique index if not exists fake_player_profile_name
                                        on fake_player_profile (name);
                             """);

        jdbc.execute("""
                             create unique index if not exists fake_player_profile_uuid
                                        on fake_player_profile (uuid);
                             """);
    }


}
