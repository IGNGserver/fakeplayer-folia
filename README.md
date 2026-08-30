# FakePlayer (Folia fork)

> **This is the `fakeplayer-folia` fork** of
> [tanyaofei/minecraft-fakeplayer](https://github.com/tanyaofei/minecraft-fakeplayer).
> It is a derivative work under the same Apache-2.0 license (see `LICENSE.txt` and
> `NOTICE`). Changes: Folia support, per-region action ticking, and version modules
> for Minecraft 1.21.11 and the new 26.x version format.

![BANNER_IMAGE](.github/README/BANNER.png)

English | [简体中文](README_zh.md)

[![Build](https://github.com/IGNGserver/fakeplayer-folia/actions/workflows/build.yml/badge.svg)](https://github.com/IGNGserver/fakeplayer-folia/actions/workflows/build.yml)
[![Latest release](https://img.shields.io/github/v/release/IGNGserver/fakeplayer-folia)](https://github.com/IGNGserver/fakeplayer-folia/releases)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE.txt)

This is a server-side plugin inspired by [Carpet-Mod](https://github.com/gnembon/fabric-carpet) for Minecraft `1.20.x`, `1.21.x` (including `1.21.11`) and the tested `26.1.2` version.

It runs on **Paper**, **Purpur** and **Folia**. On Folia every task is dispatched
through a scheduler adapter to the correct region/global/async scheduler, so the
plugin behaves like upstream on Paper while being region-thread-safe on Folia.

On Folia, an inventory view opened across regions uses a viewer-owned read-only
mirror; same-region views and Paper/Purpur retain the normal editable inventory.

[Click me](https://youtu.be/NePaDz-P5nI) to visit a demo video.

## Features

+ Lets you spawn fake players who look like real to the server, they can keep chunk loading
+ Fake players can be controlled by vanilla commands and plugin commands, such as `/ban` and `/tp`
+ You can open and edit their inventory via `/fp invsee` or Right-Clicking on them
+ You can fully control their moving, jumping, attacking... What's better ? Make it periodical
+ Each player can configure his personal configuration

## Requirements

+ [Paper](https://papermc.io), [Purpur](http://purpurmc.org), or [Folia](https://github.com/PaperMC/Folia)
+ [CommandAPI](https://commandapi.jorel.dev) plugin `11.2.0`
+ Java 21 for Minecraft `1.20.x`–`1.21.11`; Java 25 for Minecraft `26.1.2`.

## Tested versions

The modern distribution is tested against real server processes. The current
release test matrix is:

| Server | Build | Result |
| --- | --- | --- |
| Paper | 1.21.11-132 | passed |
| Folia | 1.21.11-14 | passed |
| Paper | 26.1.2-74 | passed with Java 25 |
| Folia | 26.1.2-8 | passed with Java 25 |

The live smoke tests cover plugin loading, NMS bridge selection, fake-player
spawn/list/status, player command execution, single-player removal, distant
overworld spawning, `killall`, empty-list cleanup and graceful shutdown. They
do not replace client-side UI, permission-matrix, skin, inventory-viewing or
security-audit testing.

## Config file

**Fakeplayer only generates a template config file named `config.tmpl.yml`.**
You need to rename this file to `config.yml` as your configuration file.
This approach can let you preview new content when you are upgrading it.

[Click to visit file content](fakeplayer-core/src/main/resources/config.yml)

> Security note: `/fp cmd` lets a fake player execute commands for which that
> fake player has permission. Keep `fakeplayer.command.cmd` restricted and use
> the legacy `allow-commands` configuration only for deliberately approved
> commands; prefer permission-based control for new installations.

`/fp reload` reloads ordinary configuration values. Changing `invsee-implement`
or installing/removing OpenInv requires a server restart because the inventory
viewer implementation is selected during plugin startup.

## Commands

| Command       | Description                               | Permission                   | Note                                                            |
|---------------|-------------------------------------------|------------------------------|-----------------------------------------------------------------|
| /fp spawn     | Spawn a fake player                       | fakeplayer.command.spawn     |                                                                 |
| /fp kill      | Kill a fake player                        | fakeplayer.command.kill      |                                                                 |
| /fp killall   | Kill all fake players on the server       | OP                           |                                                                 |
| /fp select    | Select a fake player as default           | fakeplayer.command.select    | Available  when player spawned more then 1 fake players         |
| /fp selection | View selected fake player                 | fakeplayer.command.selection | Available  only when player spawned more then 1 fake players    |
| /fp list      | List spawned fake players                 | fakeplayer.command.list      |                                                                 |
| /fp distance  | Show distance to a fake player            | fakeplayer.command.distance  |                                                                 |
| /fp drop      | Drop held item                            | fakeplayer.command.drop      |                                                                 |
| /fp dropstack | Drop entire stack of the held item        | fakeplayer.command.dropstack |                                                                 |
| /fp dropinv   | Drop all items in the inventory           | fakeplayer.command.dropinv   |                                                                 |
| /fp skin      | Copy skin from another player             | fakeplayer.command.skin      | 60 seconds cooldown if copy from a offline player               | 
| /fp invsee    | Open an inventory of a fake player        | fakeplayer.command.invsee    | Right-clicking on fake players has the same effect              |
| /fp sleep     | Sleep                                     | fakeplayer.command.sleep     |                                                                 |
| /fp wakeup    | Wake up                                   | fakeplayer.command.wakeup    |                                                                 |
| /fp status    | Show status                               | fakeplayer.command.status    |                                                                 |
| /fp respawn   | Respawn a dead fake player                | fakeplayer.command.respawn   | Available when server config does not kick on fake player death |
| /fp tp        | Teleport to a fake player                 | fakeplayer.command.tp        |                                                                 |
| /fp tphere    | Teleport a fake player to you             | fakeplayer.command.tphere    |                                                                 |
| /fp tps       | Swap positions with fake player           | fakeplayer.command.tps       |                                                                 |
| /fp set       | Change the configuration of a fake player | fakeplayer.command.set       |                                                                 |
| /fp config    | Change default configuration              | fakeplayer.command.config    |                                                                 |
| /fp expme     | Transfer exp to you                       | fakeplayer.command.expme     |                                                                 |
| /fp attack    | Attack                                    | fakeplayer.command.attack    |                                                                 |
| /fp mine      | Mine                                      | fakeplayer.command.mine      |                                                                 |
| /fp use       | Use/Interact/Place                        | fakeplayer.command.use       |                                                                 |
| /fp jump      | Jump                                      | fakeplayer.command.jump      |                                                                 |
| /fp stop      | Stop all actions                          | fakeplayer.command.stop      |                                                                 |
| /fp turn      | Turn around                               | fakeplayer.command.turn      |                                                                 |
| /fp look      | Look at specified location                | fakeplayer.command.look      |                                                                 |
| /fp move      | Move                                      | fakeplayer.command.move      |                                                                 |
| /fp ride      | Ride                                      | fakeplayer.command.ride      |                                                                 |
| /fp sneak     | Sneak                                     | fakeplayer.command.sneak     |                                                                 |
| /fp sprint    | Sprinting                                 | fakeplayer.command.sprint    |                                                                 |
| /fp swap      | Swap main and off-hand items              | fakeplayer.command.swap      |                                                                 |
| /fp hold      | Hold corresponding hotbar item            | fakeplayer.command.hold      |                                                                 |
| /fp cmd       | Execute command                           | fakeplayer.command.cmd       |                                                                 |
| /fp reload    | Reload config file                        | OP                           |                                                                 |

## Personal Configuration

**Each player** can configure his **own** configuration, it will take effect on the next spawning

Command examples:

+ `/fp config list` - View all personalized configurations
+ `/fp config set collidable false` - Set personalized configuration

| Configuration Item | Note                                                                                                                                |
|--------------------|-------------------------------------------------------------------------------------------------------------------------------------|
| collidable         | Whether collision box is enabled                                                                                                    |
| invulnerable       | Whether invincible mode is enabled                                                                                                  |
| wolverine          | Whether super heal mode is enabled                                                                                                  |
| look_at_entity     | Automatically look at nearby attackable entities (including players), can be combined with `attack` to automatically fight monsters |
| pickup_items       | Whether to pick up items                                                                                                            |
| skin               | Whether to use your skin                                                                                                            |
| replenish          | Whether to auto-replenish                                                                                                           |
| autofish           | Whether to autofish                                                                                                                 |

## Lifecycle command safety

Every non-empty `pre-spawn-commands` entry must have an idempotent inverse at
the same index in `pre-spawn-rollback-commands`. The plugin journals these
commands before spawning and runs compensation in reverse order after a failed
spawn, normal quit, plugin disable, or crash recovery. An unsafe unmatched
configuration fails plugin startup.

Recovery is at-least-once: `pre-spawn-rollback-commands`,
`post-quit-commands`, and `after-quit-commands` must all be idempotent. A
process can stop after an external command has taken effect but before its
database checkpoint is advanced, in which case startup safely retries it.

```yaml
pre-spawn-commands:
  - 'whitelist add %p'
pre-spawn-rollback-commands:
  - 'whitelist remove %p'
```

When BungeeCord mode is enabled, `follow-quiting` fails closed to disabled.
The built-in `PlayerList` response has no nonce or authentication and is never
allowed to authorize destructive fake-player deletion.

## Permissions

<details>
<summary>Click to visit</summary>

Each command has its own permission node, but we provided some permissions packs

### Permission `fakeplayer.spawn`

`fakeplayer.spawn` includes the following permissions:

- fakeplayer.command.spawn - Create fake player
- fakeplayer.command.kill - Kill fake player
- fakeplayer.command.list - List fake players
- fakeplayer.command.distance - View distance
- fakeplayer.command.select - Select fake player
- fakeplayer.command.selection - View selected fake player
- fakeplayer.command.drop - Drop an item
- fakeplayer.command.dropstack - Drop entire stack of items
- fakeplayer.command.dropinv - Drop all inventory items
- fakeplayer.command.skin - Copy skin
- fakeplayer.command.invsee - View inventory
- fakeplayer.command.status - View status
- fakeplayer.command.respawn - Respawn fake player
- fakeplayer.command.config - Set default settings
- fakeplayer.command.set - Set fake player settings

### Permission `fakeplayer.tp`

`fakeplayer.tp` includes the following permissions:

- fakeplayer.command.tp
- fakeplayer.command.tphere
- fakeplayer.command.tps

### Permission `fakeplayer.action`

`fakeplayer.action` includes the following permissions:

- fakeplayer.command.attack - Attack
- fakeplayer.command.mine - Mine
- fakeplayer.command.use - Use
- fakeplayer.command.jump - Jump
- fakeplayer.command.sneak - Sneak
- fakeplayer.command.sprint - Sprinting
- fakeplayer.command.look - Look
- fakeplayer.command.turn - Turn
- fakeplayer.command.move - Move
- fakeplayer.command.ride - Ride
- fakeplayer.command.swap - Swap main and off-hand items
- fakeplayer.command.sleep - Sleep
- fakeplayer.command.wakeup - Wake up
- fakeplayer.command.stop - Stop all actions
- fakeplayer.command.hold - Switch hotbar
- fakeplayer.config.replenish - Auto-replenish
- fakeplayer.config.replenish.chest - Can replenish from nearby chests when auto-replenishing
- fakeplayer.config.autofish - Autofish

If your server does not restrict various player commands, you can use this directly.
</details>

## Placeholder Variables

+ `%fakeplayer_total%`: Total count of fake players
+ `%fakeplayer_creator%`: The creator name of a fake player
+ `%fakeplayer_actions%`: Active actions of a fake player such as `USE|ATTACK`

## Custom translation

1. Create a `message` folder in `plugins/fakeplayer`
2. Copy [this file](fakeplayer-core/src/main/resources/message/message.properties) to `message` folder
3. Rename the file to `message_language_region.properties` such as `message_en_us.properties`
4. Edit your `config.yml`, set `i18n.locale` to the name suffix which you just created such as `en_us`
5. Type `/fp reload-translation` to reload the translation file. If you change `i18n.locale`, run `/fp reload` first.

**Make sure the translation file is encoded with UTF-8**

# FAQs

## xxx lost connection: PacketEvents 2.0 failed to inject

Some plugin changes the `Connection` of the fake player, You can set `prevent-kicking` to `ALWAYS` to solve it.

```yaml
# config.yml
prevent-kicking: ALWAYS
```

## Fake players do not attract aggression

By default, fake players are not in invincible mode. If invincibility is enabled in your configuration or personal settings, turn it off with `/fp config set invulnerable false` to attract aggression. After turning it off, they will
receive hunger and health effects. You may need to use `res` or beacon to ensure the fake player's `hunger` and `health`.

## Fake players automatically log out after a while

This may be because plugins like `AutheMe` detect that fake players have not logged in for a long time. You can include the login command in the configuration file's `self-commands` to prevent the
plugin from kicking out players for being idle:

```yaml
# Note: You should use a complex password, or AuthMe may reject it
self-commands:
  - '/register abc123! abc123!'
  - '/login abc123!'
```

## Build project

The recommended modern build is:

```bash
./mvnw -B -ntp -Drevision=0.3.19-folia.2 \
    -pl fakeplayer-modern-dist -am clean verify
```

The final JAR is written to
`fakeplayer-modern-dist/target/fakeplayer-0.3.19-folia.2.jar`. See
[`BUILD.md`](BUILD.md) for legacy modules and BuildTools requirements.

## Support and feedback

- Upstream: [tanyaofei/minecraft-fakeplayer](https://github.com/tanyaofei/minecraft-fakeplayer)
- Bug reports: [Issues](https://github.com/IGNGserver/fakeplayer-folia/issues)
- Releases: [GitHub Releases](https://github.com/IGNGserver/fakeplayer-folia/releases)


