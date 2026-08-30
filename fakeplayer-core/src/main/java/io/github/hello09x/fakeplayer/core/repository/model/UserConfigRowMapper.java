package io.github.hello09x.fakeplayer.core.repository.model;

import io.github.hello09x.devtools.database.jdbc.RowMapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * @author tanyaofei
 * @since 2024/7/27
 **/
public class UserConfigRowMapper implements RowMapper<UserConfig> {

    public final static UserConfigRowMapper instance = new UserConfigRowMapper();
    private final static Logger log = Logger.getLogger(UserConfigRowMapper.class.getName());

    @Override
    public @Nullable UserConfig mapRow(@NotNull ResultSet rs, int rowNum) throws SQLException {
        try {
            return new UserConfig(
                    rs.getInt("id"),
                    UUID.fromString(rs.getString("player_id")),
                    Feature.valueOf(rs.getString("key")),
                    rs.getString("value")
            );
        } catch (RuntimeException malformed) {
            log.warning("Ignoring malformed user_config row " + rowNum + ": " + malformed.getMessage());
            return null;
        }
    }
}
