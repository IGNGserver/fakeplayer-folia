# Build introduction

This is the **fakeplayer-folia** fork. It is functionally identical to upstream
[`tanyaofei/minecraft-fakeplayer`](https://github.com/tanyaofei/minecraft-fakeplayer)
plus Folia support and additional Minecraft versions (1.21.11 and the tested 26.1.2).

It is a Maven multi-module project. The modern distribution for Folia/Paper
1.21.11 and 26.1.2 builds from the public Paper API and the reflection-backed
adapter, so it does not require legacy NMS artifacts. The optional legacy
distribution still depends on remapped Spigot artifacts that Mojang does not
allow to be redistributed; those must be produced locally with BuildTools.

## Prerequisites

- JDK 21 (the project targets Java 21; the api module is compiled with Java 17)
- Apache Maven 3.8+ (or the included `mvnw` wrapper)
- [BuildTools](https://www.spigotmc.org/wiki/buildtools/) (only for the legacy
  distribution)

On Windows, install Maven or set `FAKEPLAYER_MAVEN_HOME`; the checked-in
bootstrap script is intended for Unix-like shells and CI.

## 1. Build the modern distribution

This is the recommended build for Folia/Paper 1.21.11 and 26.1.2. It builds only
the API, core, 1.21.11, 26.1.2 and modern shaded-distribution modules, so a clean
checkout does not need a root `lib/` directory or any BuildTools-generated NMS
artifact:

```
./mvnw -B -ntp -Drevision=0.3.19-folia.2 \
    -pl fakeplayer-modern-dist -am clean verify
```

The modern distribution forces recreation of its unshaded intermediate JAR,
so incremental builds are safe; `clean` remains recommended for release and
runtime verification.

The final plugin is produced at
`fakeplayer-modern-dist/target/fakeplayer-0.3.19-folia.2.jar`. The shaded jar
contains both modern ServiceLoader providers and keeps OpenInv, PlaceholderAPI
and CommandAPI as server-side dependencies.

The CI build also checks that both ServiceLoader entries are present in the
final jar. This prevents a packaging change from silently dropping the
1.21.11 or 26.1.2 provider.

## 2. Install remapped NMS artifacts for the legacy distribution

Run BuildTools for every legacy Minecraft version you intend to package:

```
java -jar BuildTools.jar --rev 1.20.1 --remapped
java -jar BuildTools.jar --rev 1.20.2 --remapped
java -jar BuildTools.jar --rev 1.20.3 --remapped
java -jar BuildTools.jar --rev 1.20.4 --remapped
java -jar BuildTools.jar --rev 1.20.5 --remapped
java -jar BuildTools.jar --rev 1.20.6 --remapped
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

The 1.21.11 and 26.1.2 modules in this fork do not use the old versioned
CraftBukkit/Spigot remapping pipeline. They compile against the public Paper API
and resolve the Mojang-named runtime classes through the reflection adapter in
`fakeplayer-v26_1_2`; no BuildTools NMS artifact is required for those two
modules. The 1.21.11 module delegates to that adapter because Folia 1.21.11 also
uses unversioned CraftBukkit packages.

This installs `org.spigotmc:spigot:<rev>-R0.1-SNAPSHOT:remapped-mojang`,
`org.spigotmc:minecraft-server:<rev>-R0.1-SNAPSHOT:txt:maps-mojang` and the
`maps-spigot` csrg into your local `~/.m2` repository.

The module `fakeplayer-v26_1_2` compiles without NMS artifacts and supports the
explicitly verified 26.1.2 runtime. Unknown 26.x patches fail closed until their
runtime members have been verified and the support matrix is updated.

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
./mvnw -B -ntp -Drevision=0.3.19-folia.2 verify
```

The full shaded jar is produced at `target/fakeplayer-0.3.19-folia.2.jar` by
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

Build verification is not a substitute for a live server smoke test. Before a
release, test spawn/quit/respawn, all action commands, command permissions,
configuration persistence, lifecycle journal recovery, BungeeCord fail-closed behavior, inventory synchronization and
shutdown on at least one Paper and one Folia server. Cross-region Folia
inventory viewing uses a viewer-owned mirror; cross-region riding is rejected
with an explicit error because Folia does not provide a safe atomic passenger
mutation across entity regions.
