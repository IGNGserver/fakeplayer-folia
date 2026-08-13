# Build introduction

This is the **fakeplayer-folia** fork. It is functionally identical to upstream
[`tanyaofei/minecraft-fakeplayer`](https://github.com/tanyaofei/minecraft-fakeplayer)
plus Folia support and additional Minecraft versions (1.21.11, the 26.x line).

It is a Maven multi-module project and depends on remapped Spigot NMS artifacts
that Mojang does not allow to be redistributed. You must produce them yourself
with BuildTools.

## Prerequisites

- JDK 21 (the project targets Java 21; the api module is compiled with Java 17)
- Apache Maven 3.8+
- [BuildTools](https://www.spigotmc.org/wiki/buildtools/)

## 1. Install remapped NMS artifacts

Run BuildTools for every Minecraft version you intend to build for:

```
java -jar BuildTools.jar --rev 1.21 --remapped
java -jar BuildTools.jar --rev 1.21.1 --remapped
java -jar BuildTools.jar --rev 1.21.3 --remapped
java -jar BuildTools.jar --rev 1.21.4 --remapped
java -jar BuildTools.jar --rev 1.21.5 --remapped
java -jar BuildTools.jar --rev 1.21.6 --remapped
java -jar BuildTools.jar --rev 1.21.7 --remapped
java -jar BuildTools.jar --rev 1.21.8 --remapped
java -jar BuildTools.jar --rev 1.21.9 --remapped
java -jar BuildTools.jar --rev 1.21.10 --remapped
```

For the new modules in this fork:

```
# 1.21.11 - reuses the 1.21.10 R6 remapped NMS surface by default.
# If Spigot ships a standalone 1.21.11 remapped artifact, install it too and
# switch <nms.version> in fakeplayer-v1_21_11/pom.xml accordingly.
java -jar BuildTools.jar --rev 1.21.10 --remapped   # shared by v1_21_9 / v1_21_10 / v1_21_11

# 26.1.2 (new version format). Required before the stub bridge is implemented.
java -jar BuildTools.jar --rev 26.1.2 --remapped
```

This installs `org.spigotmc:spigot:<rev>-R0.1-SNAPSHOT:remapped-mojang`,
`org.spigotmc:minecraft-server:<rev>-R0.1-SNAPSHOT:txt:maps-mojang` and the
`maps-spigot` csrg into your local `~/.m2` repository.

The module `fakeplayer-v26_1_2` has no NMS dependency yet, so it compiles even if
the 26.1.2 NMS artifacts are absent. Its bridge reports a clear, actionable error
until the impl is filled in (see `fakeplayer-v26_1_2/pom.xml`).

## 2. Provide manual dependencies

Two optional integrations are referenced through system-scope jars. Drop them
into a `lib/` folder at the repository root (this folder is git-ignored):

- `OpenInv.jar` (for the `OpenInv` invsee implementation)
- `PlaceholderAPI-2.11.6.jar` (for PlaceholderAPI placeholders)

If they are missing the build still works (these integrations are optional and
activated only at runtime when the corresponding plugins are present).

## 3. Build

```
mvn -q -DskipTests clean package
```

The shaded jar is produced at `target/fakeplayer-0.3.19-folia.1.jar`. Copy it to
your server's `plugins/` folder.

## 4. Runtime platforms

The plugin runs unchanged on Paper / Purpur, and on Folia (`folia-supported: true`
is declared in `plugin.yml`). On Folia all scheduler activity is dispatched via a
reflective adapter (`io.github.hello09x.fakeplayer.core.util.scheduler.Tasks`) to
the EntityScheduler / RegionScheduler / GlobalRegionScheduler / AsyncScheduler,
so fake-player ticking, actions and per-tick work run on the correct region
thread. On Paper everything falls back to the legacy main-thread scheduler, so
behavior matches upstream.

Legend:

| Simulation platform        | Status |
|----------------------------|--------|
| Paper / Purpur 1.20.1-1.21.11 | supported, behaves as upstream |
| Folia (1.21.x)             | supported via the scheduler adapter |
| Paper / Purpur 26.1.2      | loads; NMS impl is a documented TODO stub |
| Folia 26.1.2               | loads; NMS impl is a documented TODO stub |