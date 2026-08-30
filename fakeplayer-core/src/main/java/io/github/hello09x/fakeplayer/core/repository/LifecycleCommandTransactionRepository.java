package io.github.hello09x.fakeplayer.core.repository;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.hello09x.devtools.database.jdbc.JdbcTemplate;
import io.github.hello09x.devtools.database.jdbc.RowMapper;
import io.github.hello09x.fakeplayer.core.lifecycle.LifecycleCommandTransaction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static io.github.hello09x.fakeplayer.core.lifecycle.LifecycleCommandTransaction.State;

/** Persistent write-ahead journal for lifecycle command recovery. */
@Singleton
public final class LifecycleCommandTransactionRepository {

    private final JdbcTemplate jdbc;

    @Inject
    public LifecycleCommandTransactionRepository(@NotNull JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.initTable();
    }

    public synchronized void insert(@NotNull LifecycleCommandTransaction tx) {
        jdbc.update("""
                INSERT INTO fakeplayer_lifecycle_tx (
                    tx_id, fake_name, fake_uuid, creator_name, state,
                    rollback_commands, pre_attempted, rollback_next,
                    post_quit_commands, post_quit_next,
                    after_quit_commands, after_quit_next
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tx.id().toString(),
                tx.fakeName(),
                tx.fakeUuid(),
                tx.creatorName(),
                tx.state().name(),
                io.github.hello09x.fakeplayer.core.lifecycle.LifecycleCommandCodec.encode(tx.rollbackCommands()),
                tx.preAttempted(),
                tx.rollbackNext(),
                io.github.hello09x.fakeplayer.core.lifecycle.LifecycleCommandCodec.encode(tx.postQuitCommands()),
                tx.postQuitNext(),
                io.github.hello09x.fakeplayer.core.lifecycle.LifecycleCommandCodec.encode(tx.afterQuitCommands()),
                tx.afterQuitNext()
        );
    }

    public synchronized @Nullable LifecycleCommandTransaction select(@NotNull UUID id) {
        return jdbc.queryForObject(
                "SELECT * FROM fakeplayer_lifecycle_tx WHERE tx_id = ?",
                Mapper.INSTANCE,
                id.toString()
        );
    }

    public synchronized @NotNull List<LifecycleCommandTransaction> selectAll() {
        return jdbc.query("SELECT * FROM fakeplayer_lifecycle_tx ORDER BY tx_id", Mapper.INSTANCE);
    }

    public synchronized void markPreAttempted(@NotNull UUID id, int attempted) {
        requireUpdated(jdbc.update(
                "UPDATE fakeplayer_lifecycle_tx SET pre_attempted = ? WHERE tx_id = ? AND state = ?",
                attempted,
                id.toString(),
                State.SPAWNING.name()
        ), id);
    }

    public synchronized void markActive(@NotNull UUID id) {
        requireUpdated(jdbc.update(
                "UPDATE fakeplayer_lifecycle_tx SET state = ? WHERE tx_id = ? AND state = ?",
                State.ACTIVE.name(),
                id.toString(),
                State.SPAWNING.name()
        ), id);
    }

    public synchronized void markQuitting(@NotNull UUID id) {
        var updated = jdbc.update(
                "UPDATE fakeplayer_lifecycle_tx SET state = ? WHERE tx_id = ? AND state IN (?, ?)",
                State.QUITTING.name(),
                id.toString(),
                State.ACTIVE.name(),
                State.QUITTING.name()
        );
        requireUpdated(updated, id);
    }

    public synchronized void markRollingBack(@NotNull UUID id, int rollbackNext) {
        requireUpdated(jdbc.update(
                "UPDATE fakeplayer_lifecycle_tx SET state = ?, rollback_next = ? WHERE tx_id = ? AND state IN (?, ?, ?, ?)",
                State.ROLLING_BACK.name(),
                rollbackNext,
                id.toString(),
                State.SPAWNING.name(),
                State.ACTIVE.name(),
                State.QUITTING.name(),
                State.ROLLING_BACK.name()
        ), id);
    }

    public synchronized void updateRollbackNext(@NotNull UUID id, int rollbackNext) {
        requireUpdated(jdbc.update(
                "UPDATE fakeplayer_lifecycle_tx SET rollback_next = ? WHERE tx_id = ? AND state = ?",
                rollbackNext,
                id.toString(),
                State.ROLLING_BACK.name()
        ), id);
    }

    public synchronized void updatePostQuitNext(@NotNull UUID id, int next) {
        requireUpdated(jdbc.update(
                "UPDATE fakeplayer_lifecycle_tx SET post_quit_next = ? WHERE tx_id = ? AND state = ?",
                next,
                id.toString(),
                State.QUITTING.name()
        ), id);
    }

    public synchronized void updateAfterQuitNext(@NotNull UUID id, int next) {
        requireUpdated(jdbc.update(
                "UPDATE fakeplayer_lifecycle_tx SET after_quit_next = ? WHERE tx_id = ? AND state = ?",
                next,
                id.toString(),
                State.QUITTING.name()
        ), id);
    }

    public synchronized void delete(@NotNull UUID id) {
        jdbc.update("DELETE FROM fakeplayer_lifecycle_tx WHERE tx_id = ?", id.toString());
    }

    private static void requireUpdated(int updated, @NotNull UUID id) {
        if (updated != 1) {
            throw new IllegalStateException("Lifecycle command transaction changed concurrently or is missing: " + id);
        }
    }

    private void initTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS fakeplayer_lifecycle_tx
                (
                    tx_id               text        not null primary key,
                    fake_name           text(32)    not null,
                    fake_uuid           text(36)    not null,
                    creator_name        text(64)    not null,
                    state               text(24)    not null,
                    rollback_commands   text        not null,
                    pre_attempted        integer     not null,
                    rollback_next        integer     not null,
                    post_quit_commands   text        not null,
                    post_quit_next       integer     not null,
                    after_quit_commands  text        not null,
                    after_quit_next      integer     not null
                )
                """);
    }

    private enum Mapper implements RowMapper<LifecycleCommandTransaction> {
        INSTANCE;

        @Override
        public LifecycleCommandTransaction mapRow(@NotNull ResultSet rs, int rowNum) throws SQLException {
            return new LifecycleCommandTransaction(
                    UUID.fromString(rs.getString("tx_id")),
                    rs.getString("fake_name"),
                    rs.getString("fake_uuid"),
                    rs.getString("creator_name"),
                    State.valueOf(rs.getString("state")),
                    io.github.hello09x.fakeplayer.core.lifecycle.LifecycleCommandCodec.decode(rs.getString("rollback_commands")),
                    rs.getInt("pre_attempted"),
                    rs.getInt("rollback_next"),
                    io.github.hello09x.fakeplayer.core.lifecycle.LifecycleCommandCodec.decode(rs.getString("post_quit_commands")),
                    rs.getInt("post_quit_next"),
                    io.github.hello09x.fakeplayer.core.lifecycle.LifecycleCommandCodec.decode(rs.getString("after_quit_commands")),
                    rs.getInt("after_quit_next")
            );
        }
    }
}
