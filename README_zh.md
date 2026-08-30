# FakePlayer —— Folia 假人插件

[English](README.md) | 简体中文

[![构建](https://github.com/IGNGserver/fakeplayer-folia/actions/workflows/build.yml/badge.svg)](https://github.com/IGNGserver/fakeplayer-folia/actions/workflows/build.yml)
[![最新版本](https://img.shields.io/github/v/release/IGNGserver/fakeplayer-folia)](https://github.com/IGNGserver/fakeplayer-folia/releases)
[![许可证](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE.txt)

这是 [tanyaofei/minecraft-fakeplayer](https://github.com/tanyaofei/minecraft-fakeplayer) 的 Folia 移植分支，保留上游功能，并补充 Folia 调度器适配、Minecraft 1.21.11 与已验证的 26.1.2 适配。

FakePlayer 会在服务器中创建一个对 Bukkit/Paper/插件而言都像真实玩家的假人，可用于区块加载、刷怪、自动化操作和插件联动。

## 支持情况

| 平台 | 版本 | 状态 |
| --- | --- | --- |
| Paper / Purpur | 1.20.x–1.21.x | 支持 |
| Folia | 1.21.x（包含 1.21.11） | 支持，使用区域调度器 |
| Paper / Purpur | 26.1.2 | 使用反射适配器，已实测 |
| Folia | 26.1.2 | 使用反射适配器，已实测 |

运行要求：

- Minecraft 1.20.x–1.21.11 使用 Java 21；Minecraft 26.1.2 使用 Java 25。
- Paper、Purpur 或 Folia 服务端。
- [CommandAPI 11.2.0](https://commandapi.jorel.dev)（必需）。
- [OpenInv](https://github.com/Jikoo/OpenInv) 和 [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) 为可选联动插件。

Folia 没有传统的全局主线程。本分支会根据操作对象切换到实体、区域、全局或异步调度器；Paper/Purpur 则继续使用传统调度器。

Folia 跨区域查看假人背包时使用查看者区域的只读镜像；同区域查看以及 Paper/Purpur 仍保持正常可编辑行为。

本分支已经在真实服务端完成以下烟测：插件加载、NMS bridge 选择、生成/列表/状态、假人执行命令、单体销毁、远坐标生成、`killall`、空列表清理和正常关服。客户端 UI、完整权限组合、皮肤、跨区背包查看以及安全审计不属于这组烟测的覆盖范围。

## 安装

1. 从仓库的 [Releases](https://github.com/IGNGserver/fakeplayer-folia/releases) 下载插件 JAR。
2. 将 JAR 放入服务端的 `plugins/` 目录。
3. 安装 CommandAPI 11.2.0。
4. 如需背包查看或 PlaceholderAPI 变量，再安装 OpenInv、PlaceholderAPI。
5. 启动服务器。插件会生成 `plugins/fakeplayer/config.tmpl.yml`。
6. 将 `config.tmpl.yml` 复制或重命名为 `config.yml`，按需修改后执行 `/fp reload`。

OpenInv、PlaceholderAPI 不会被打包进 FakePlayer；只有对应插件存在时，相关联动功能才会启用。

`/fp reload` 会重载普通配置。修改 `invsee-implement` 或安装/卸载 OpenInv
后需要重启服务器，背包查看实现是在插件启动时选择的。

> 安全提示：`/fp cmd` 会让假人执行它自身拥有权限的命令。请严格限制
> `fakeplayer.command.cmd`。`allow-commands` 仅为旧配置兼容保留，新增配置建议使用权限控制。

## 主要功能

- 创建可被原版命令和其他插件识别的假人。
- 保持区块加载并参与服务器中的正常玩家流程。
- 查看和管理假人背包。
- 复制在线或离线玩家皮肤。
- 控制假人移动、跳跃、攻击、挖掘、交互、放置、潜行、疾跑、睡觉和骑乘。
- 将动作设置为单次、持续或按间隔执行。
- 为每个创建者保存独立的假人配置。
- 与 AuthMe、BungeeCord/代理、PlaceholderAPI、OpenInv 等插件联动。

## 命令

命令前缀可以使用 `/fp` 或 `/fakeplayer`。假人名称参数通常可以省略；省略时使用当前选中的假人。

| 命令 | 作用 | 权限 |
| --- | --- | --- |
| `/fp spawn` | 创建假人 | `fakeplayer.command.spawn` |
| `/fp kill <名称>` | 移除假人 | `fakeplayer.command.kill` |
| `/fp killall` | 移除服务器中的所有假人 | OP |
| `/fp list` | 查看假人列表 | `fakeplayer.command.list` |
| `/fp select <名称>` | 选择默认假人 | `fakeplayer.command.select` |
| `/fp selection` | 查看当前选择 | `fakeplayer.command.selection` |
| `/fp status <名称>` | 查看假人状态 | `fakeplayer.command.status` |
| `/fp invsee <名称>` | 查看假人背包 | `fakeplayer.command.invsee` |
| `/fp skin <玩家> <名称>` | 复制玩家皮肤 | `fakeplayer.command.skin` |
| `/fp hold <槽位> <名称>` | 切换快捷栏 | `fakeplayer.command.hold` |
| `/fp tp <名称>` | 传送到假人位置 | `fakeplayer.command.tp` |
| `/fp tphere <名称>` | 将假人传送到自己位置 | `fakeplayer.command.tphere` |
| `/fp tps <名称>` | 与假人交换位置 | `fakeplayer.command.tps` |
| `/fp set <配置项> <值> <名称>` | 修改假人配置 | `fakeplayer.command.set` |
| `/fp config list` | 查看个人配置 | `fakeplayer.command.config` |
| `/fp config set <配置项> <值>` | 修改个人默认配置 | `fakeplayer.command.config` |
| `/fp cmd <名称> <命令>` | 让假人执行命令 | `fakeplayer.command.cmd` |
| `/fp reload` | 重载配置 | OP |

动作命令支持 `once`、`continuous`、`interval <刻>` 和 `stop`：

```text
/fp jump once Steve
/fp attack continuous Steve
/fp mine interval 2 Steve
/fp mine stop Steve
/fp use once Steve
/fp move forward Steve
/fp stop Steve
```

其他动作命令包括 `/fp attack`、`/fp mine`、`/fp use`、`/fp jump`、`/fp drop`、`/fp sleep`、`/fp wakeup`、`/fp turn`、`/fp look`、`/fp move`、`/fp ride`、`/fp sneak`、`/fp sprint` 和 `/fp swap`。

## 权限组

每个命令都有独立权限，也提供以下权限组：

- `fakeplayer.spawn`：创建、移除、查看、配置和管理假人。
- `fakeplayer.tp`：传送、交换位置。
- `fakeplayer.action`：动作控制、快捷栏、自动补货和自动钓鱼。
不要直接向普通玩家授予 `fakeplayer.command.cmd`，否则他们可能让假人执行其自身拥有权限的任意命令。建议使用配置文件中的命令白名单限制可执行命令。

## 配置

模板配置位于 [`fakeplayer-core/src/main/resources/config.yml`](fakeplayer-core/src/main/resources/config.yml)，运行后会复制为 `plugins/fakeplayer/config.tmpl.yml`。

`pre-spawn-commands` 中每个非空命令都必须在 `pre-spawn-rollback-commands` 的同一序号配置幂等反向命令。补偿日志会在生成前写入数据库；生成失败、假人退出、插件停止或进程崩溃恢复时都会按逆序执行。不完整的配对会使插件拒绝启动。

恢复语义是“至少一次”：进程可能在外部命令已生效、但数据库进度尚未推进时停止。因此 `pre-spawn-rollback-commands`、`post-quit-commands` 和 `after-quit-commands` 都必须可安全重复执行。

```yaml
pre-spawn-commands:
  - 'whitelist add %p'
pre-spawn-rollback-commands:
  - 'whitelist remove %p'
```

常用个人配置项：

| 配置项 | 作用 |
| --- | --- |
| `collidable` | 是否启用碰撞箱 |
| `invulnerable` | 是否无敌 |
| `wolverine` | 是否启用快速恢复 |
| `look_at_entity` | 是否自动看向附近可攻击实体 |
| `pickup_items` | 是否拾取物品 |
| `skin` | 是否使用创建者皮肤 |
| `replenish` | 是否自动补货 |
| `autofish` | 是否自动钓鱼 |

## PlaceholderAPI

安装 PlaceholderAPI 后，FakePlayer 提供以下变量：

- `%fakeplayer_total%`：假人总数。
- `%fakeplayer_creator%`：当前假人的创建者。
- `%fakeplayer_actions%`：当前动作，例如 `USE|ATTACK`。

## 插件联动与常见问题

### AuthMe 等登录插件让假人掉线

可以在 `config.yml` 的 `self-commands` 中加入注册和登录命令。请使用复杂密码，并注意不要将真实密码提交到仓库或公开配置中：

```yaml
self-commands:
  - '/register 请替换为复杂密码 请替换为复杂密码'
  - '/login 请替换为复杂密码'
```

### 假人不会吸引仇恨

默认不开启无敌；如果你的配置或个人设置开启了无敌，可执行以下命令关闭：

```text
/fp config set invulnerable false
```

### BungeeCord/代理服务器

如果 `spigot.yml` 中启用了 `bungeecord: true`，`follow-quiting` 会安全失效为禁用。BungeeCord 内建 `PlayerList` 没有 nonce 或签名，插件不再接收该响应，也不允许它驱动假人删除。在安装带 nonce/HMAC 的认证代理伴生端之前，请显式移除跨服创建者的假人。

### UUID 安全

插件会记录已经使用过的假人 UUID，避免真实玩家使用相同 UUID 登录。如果误占用 UUID，请先备份数据，再按照 [`README.md`](README.md) 或插件配置说明处理 `used-uuids.txt` 中对应的记录。

## 构建

现代发行版推荐使用以下命令：

```bash
./mvnw -B -ntp -Drevision=0.3.19-folia.3 \
    -pl fakeplayer-modern-dist -am clean verify
```

产物位于 `fakeplayer-modern-dist/target/fakeplayer-0.3.19-folia.3.jar`。现代发行版不需要手动准备根目录 `lib/`，OpenInv、PlaceholderAPI 和 CommandAPI 仍由服务器单独安装。

旧版本完整构建需要使用 BuildTools 准备对应的重映射 NMS 依赖。详细说明见 [`BUILD.md`](BUILD.md)。

## 开发与反馈

- 上游项目：[tanyaofei/minecraft-fakeplayer](https://github.com/tanyaofei/minecraft-fakeplayer)
- 问题反馈：[Issues](https://github.com/IGNGserver/fakeplayer-folia/issues)
- 构建说明：[BUILD.md](BUILD.md)

提交日志、配置和截图前，请删除服务器地址、账号、密码、令牌、IP、插件私有配置等敏感信息。

## 许可证

本分支遵循上游的 Apache License 2.0。详见 [`LICENSE.txt`](LICENSE.txt) 与 [`NOTICE`](NOTICE)。
