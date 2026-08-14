# Build introduction

This is the **fakeplayer-folia** fork. It is functionally identical to upstream
[`tanyaofei/minecraft-fakeplayer`](https://github.com/tanyaofei/minecraft-fakeplayer)
plus Folia support and additional Minecraft versions (1.21.11 and 26.1.2+).

It is a Maven multi-module project and depends on remapped Spigot NMS artifacts
that Mojang does not allow to be redistributed. You must produce them yourself
with BuildTools.

## Prerequisites

- JDK 21 (the project targets Java 21; the api module is compiled with Java 17)
- Apache Maven 3.8+
- [BuildTools](https://www.spigotmc.org/wiki/buildtools/)

## 1. Install remapped NMS artifacts for legacy modules

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

The 1.21.11 and 26.x modules in this fork do not use the old versioned
CraftBukkit/Spigot remapping pipeline. They compile against the public Paper API
and resolve the Mojang-named runtime classes through the reflection adapter in
`fakeplayer-v26_1_2`; no BuildTools NMS artifact is required for those two
modules. The 1.21.11 module delegates to that adapter because Folia 1.21.11 also
uses unversioned CraftBukkit packages.

For the legacy modules, continue to install the matching artifacts as needed:

```
# 1.21.10 is still needed by the legacy v1_21_10 module.
java -jar BuildTools.jar --rev 1.21.10 --remapped
```

This installs `org.spigotmc:spigot:<rev>-R0.1-SNAPSHOT:remapped-mojang`,
`org.spigotmc:minecraft-server:<rev>-R0.1-SNAPSHOT:txt:maps-mojang` and the
`maps-spigot` csrg into your local `~/.m2` repository.

The module `fakeplayer-v26_1_2` compiles without NMS artifacts and supports the
26.x runtime family, including 26.1.2. The adapter is intentionally isolated so
future 26.x patch changes can be handled without reintroducing versioned NMS
dependencies.

## 2. Provide manual dependencies

Two optional integrations are referenced by the existing upstream source through
system-scope jars. For a full Maven build, drop them into a `lib/` folder at the
repository root (this folder is git-ignored):

- `OpenInv.jar` (for the `OpenInv` invsee implementation)
- `PlaceholderAPI-2.11.6.jar` (for PlaceholderAPI placeholders)

The integrations remain runtime-optional: the finished plugin only activates
them when the corresponding server plugin is present, but Maven still needs the
compile-time API jars because the upstream integration classes are part of the
core module.

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
| Paper / Purpur 26.1.2      | supported through the reflection-backed NMS adapter |
| Folia 26.1.2               | supported through the reflection-backed NMS adapter |
