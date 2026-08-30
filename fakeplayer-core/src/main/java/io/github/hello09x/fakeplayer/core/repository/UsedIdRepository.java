package io.github.hello09x.fakeplayer.core.repository;

import com.google.common.base.Throwables;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.github.hello09x.devtools.core.utils.Exceptions;
import io.github.hello09x.fakeplayer.core.Main;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@Deprecated
@Singleton
public class UsedIdRepository {

    private final static Logger log = Main.getInstance().getLogger();

    private final Set<UUID> UUIDS = ConcurrentHashMap.newKeySet();

    @Inject
    public UsedIdRepository() {
        this.load();
    }

    public boolean contains(@NotNull UUID uuid) {
        return UUIDS.contains(uuid);
    }

    public void add(@NotNull UUID uuid) {
        UUIDS.add(uuid);
    }

    /**
     * Atomically claim a legacy UUID during migration. This prevents two
     * concurrent name-resolution tasks from both migrating the same record.
     */
    public boolean removeIfPresent(@NotNull UUID uuid) {
        return UUIDS.remove(uuid);
    }

    /**
     * 从文件里读取使用过的 UUIDs
     */
    public void load() {
        var file = new File(Main.getInstance().getDataFolder(), "used-uuids.txt");
        if (!file.exists() || !file.isFile()) {
            return;
        }

        try (BufferedReader in = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                try {
                    if (line.isBlank()) {
                        continue;
                    }
                    this.UUIDS.add(UUID.fromString(line));
                } catch (Throwable ignored) {
                    log.warning("used-uuids.txt contains illegal UUID: " + line);
                }
            }
        } catch (IOException e) {
            log.warning("Failed to read used-uuids.txt\n" + Throwables.getStackTraceAsString(e));
        }
    }

    /**
     * 将使用过的 UUIDs 写入文件
     */
    public void saveAll() {
        var folder = Main.getInstance().getDataFolder();
        if (!folder.exists() && !folder.mkdirs()) {
            return;
        }
        var file = new File(folder, "used-uuids.txt");
        var target = file.toPath();
        var temporary = Path.of(file.getPath() + ".tmp");
        var snapshot = Set.copyOf(UUIDS);

        try {
            try (var out = Files.newBufferedWriter(
                    temporary,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            )) {
                IOUtils.writeLines(snapshot, null, out);
            }
        } catch (IOException e) {
            log.warning("Failed to save used-uuids.txt\n" + Throwables.getStackTraceAsString(e));
            return;
        }

        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                log.warning("Failed to save used-uuids.txt\n" + Throwables.getStackTraceAsString(e));
            }
        } catch (IOException e) {
            log.warning("Failed to save used-uuids.txt\n" + Throwables.getStackTraceAsString(e));
        }
    }

    public void onDisable() {
        Exceptions.suppress(Main.getInstance(), this::saveAll);
    }


}
