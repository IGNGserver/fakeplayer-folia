# Build introduction

This is the **fakeplayer-folia** fork. It is functionally identical to upstream
[`tanyaofei/minecraft-fakeplayer`](https://github.com/tanyaofei/minecraft-fakeplayer)
plus Folia support and additional Minecraft versions (1.21.11 and 26.1.2+).

It is a Maven multi-module project. The modern distribution for Folia/Paper
1.21.11 and 26.x builds from the public Paper API and the reflection-backed
adapter, so it does not require legacy NMS artifacts. The optional legacy
distribution still depends on remapped Spigot artifacts that Mojang does not
allow to be redistributed; those must be produced locally with BuildTools.

## Prerequisites

- JDK 21 (the project targets Java 21; the api module is compiled with Java 17)
- Apache Maven 3.8+
- [BuildTools](https://www.spigotmc.org/wiki/buildtools/) (only for the legacy
  distribution)

## 1. Build the modern distribution

This is the recommended build for Folia/Paper 1.21.11 and 26.x. It builds only
the API, core, 1.21.11, 26.x and modern shaded-distribution modules, so a clean
checkout does not need a root `lib/` directory or any BuildTools-generated NMS
artifact:

```
mvn -B -ntp -Drevision=0.3.19-folia.1 -DskipTests \
    -pl fakeplayer-modern-dist -am clean package
```

The final plugin is produced at
`fakeplayer-modern-dist/target/fakeplayer-0.3.19-folia.1.jar`. The shaded jar
contains both modern ServiceLoader providers and keeps OpenInv, PlaceholderAPI
and CommandAPI as server-side dependencies.

## 2. Install remapped NMS artifacts for the legacy distribution

Run BuildTools for every legacy Minecraft version you intend to package:

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

This installs `org.spigotmc:spigot:<rev>-R0.1-SNAPSHOT:remapped-mojang`,
`org.spigotmc:minecraft-server:<rev>-R0.1-SNAPSHOT:txt:maps-mojang` and the
`maps-spigot` csrg into your local `~/.m2` repository.

The module `fakeplayer-v26_1_2` compiles without NMS artifacts and supports the
26.x runtime family, including 26.1.2. The adapter is intentionally isolated so
future 26.x patch changes can be handled without reintroducing versioned NMS
dependencies.

## 3. Optional integrations

OpenInv and PlaceholderAPI are compile-time-only optional integrations. Their
APIs are resolved from Maven repositories, so a clean checkout no longer needs
manually copied jars in a root `lib/` directory. The finished plugin only
activates these integrations when the corresponding server plugin is present.

The pinned compile-time API versions are OpenInv 5.3.1 and PlaceholderAPI
2.12.3. They are not shaded into the distribution jar and must still be
installed separately on the server when those integrations are desired. For
PlaceholderAPI, use its official server plugin distribution at runtime; the
Maven artifact is only used to compile the optional expansion integration.

## 4. Build the full legacy distribution

After installing the matching BuildTools artifacts for every legacy module, run:

```
mvn -B -ntp -Drevision=0.3.19-folia.1 -DskipTests clean package
```

The full shaded jar is produced at `target/fakeplayer-0.3.19-folia.1.jar` by
`fakeplayer-dist`. Copy the appropriate modern or full distribution jar to
your server's `plugins/` folder.

## 5. Runtime platforms

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
