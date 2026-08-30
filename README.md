# FakePlayer Folia

[![构建](https://github.com/IGNGserver/fakeplayer-folia/actions/workflows/build.yml/badge.svg)](https://github.com/IGNGserver/fakeplayer-folia/actions/workflows/build.yml)
[![最新版本](https://img.shields.io/github/v/release/IGNGserver/fakeplayer-folia)](https://github.com/IGNGserver/fakeplayer-folia/releases)
[![许可证](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE.txt)

一个面向 Paper、Purpur 和 Folia 的服务器假人插件。

FakePlayer 可以在服务器内部创建一个能够被原版系统和其他插件识别的假人。假人可以保持区块加载、执行服务器命令、携带物品、进行移动和交互动作，也可以作为自动化测试、红石机器、刷怪区域和插件联动的运行载体。

本分支重点解决 Folia 的多区域调度问题，并为 Minecraft 1.21.11 与 26.1.x 提供独立的运行时适配器。

## 当前版本

当前正式版本为 [v0.3.19-folia.3](https://github.com/IGNGserver/fakeplayer-folia/releases/tag/v0.3.19-folia.3)。

Folia 跨区域查看假人背包时使用查看者区域的只读镜像；同区域查看以及 Paper/Purpur 仍保持正常可编辑行为。

[演示视频](https://youtu.be/NePaDz-P5nI)

这个版本包含：

- Folia 的实体、区域、全局和异步调度适配。
- Minecraft 1.21.11 与 26.1.x 的现代运行时适配。
- 假人生成数量限制、名称来源校验和 BungeeCord 在线玩家列表解析。
- 跨区域背包查看的安全处理，以及关闭和销毁流程的兼容修复。
- 配套的 Maven Wrapper、单元测试、静态安全检查和 CI 构建验证。

## 支持范围

| 服务端 | 版本 | Java | 本版本验证情况 |
| --- | --- | --- | --- |
| Paper | 1.21.11-132 | 21 | 真实服务端通过 |
| Folia | 1.21.11-14 | 21 | 真实服务端通过 |
| Paper | 26.1.2-74 | 25 | 真实服务端通过 |
| Folia | 26.1.2-8 | 25 | 真实服务端通过 |
| Purpur | 对应 Paper 版本 | 21/25 | 使用前请按目标版本单独验证 |

说明：发布 JAR 是现代发行包，主要面向 1.21.11 和 26.1.x。更早的 1.20.x–1.21.10 版本仍保留对应模块，但完整打包需要在本地使用 BuildTools 准备重映射依赖，详见 [`BUILD.md`](BUILD.md)。

## 安装

1. 从 [Releases](https://github.com/IGNGserver/fakeplayer-folia/releases) 下载插件 JAR。
2. 将 JAR 放入服务器的 `plugins/` 目录。
3. 安装与服务器平台匹配的 [CommandAPI 11.2.0](https://github.com/CommandAPI/CommandAPI/releases)。Paper 和 Folia 应使用 CommandAPI 的 Paper 构建。
4. Minecraft 1.20.x–1.21.11 使用 Java 21；26.1.x 使用 Java 25。
5. 启动服务器。首次启动后，插件会在 `plugins/fakeplayer/` 生成 `config.tmpl.yml`。
6. 复制 `config.tmpl.yml` 为 `config.yml`，按需修改后执行 `/fp reload`。

OpenInv 和 PlaceholderAPI 都是可选依赖，不会被打包进 FakePlayer。需要背包编辑或变量功能时，再安装对应插件。

## 快速开始

```text
/fp spawn
/fp list
/fp status
/fp cmd <假人名称> me hello
/fp kill <假人名称>
```

命令前缀支持 `/fp` 和 `/fakeplayer`。假人名称在很多命令中可以省略；省略时使用当前选中的假人。可以使用服务器的 Tab 补全查看当前版本支持的参数。

## 功能概览

### 假人生命周期

- 创建、选择、查看、传送和移除假人。
- 设置单个玩家和整台服务器的假人数量上限。
- 支持假人存档持久化、自动存活时间和死亡后重生。
- 支持创建前、加入后、退出前和退出后的服务器命令钩子。

### 行为控制

假人可以执行攻击、挖掘、使用/交互、跳跃、移动、转身、注视、潜行、疾跑、骑乘、睡觉、交换手持物品等动作。动作通常支持单次执行、持续执行、按刻间隔执行和停止：

```text
/fp jump once Steve
/fp attack continuous Steve
/fp mine interval 2 Steve
/fp mine stop Steve
/fp move forward Steve
/fp stop Steve
```

### 背包、皮肤和状态

- 查看或管理假人背包。
- 切换快捷栏、丢弃手持物品或背包内容。
- 复制在线或离线玩家皮肤。
- 查看生命值、饥饿值、经验和当前特性。
- 配置碰撞、无敌、拾取、自动补货、自动钓鱼和自动看向实体等特性。

### 插件联动

- CommandAPI：必需，用于注册 `/fp` 命令。
- OpenInv：可选，用于更完整的假人背包查看和编辑。
- PlaceholderAPI：可选，提供假人数量、创建者和动作变量。
- AuthMe、BungeeCord/代理等插件：通过配置命令和在线列表解析进行联动。

## 常用命令

| 命令 | 用途 | 权限 |
| --- | --- | --- |
| `/fp spawn` | 创建假人 | `fakeplayer.command.spawn` |
| `/fp kill <名称>` | 移除指定假人 | `fakeplayer.command.kill` |
| `/fp killall` | 移除服务器中的全部假人 | OP |
| `/fp list` | 查看假人列表 | `fakeplayer.command.list` |
| `/fp select <名称>` | 选择默认假人 | `fakeplayer.command.select` |
| `/fp selection` | 查看当前选择 | `fakeplayer.command.selection` |
| `/fp status <名称>` | 查看假人状态 | `fakeplayer.command.status` |
| `/fp invsee <名称>` | 查看假人背包 | `fakeplayer.command.invsee` |
| `/fp skin <玩家> <名称>` | 复制玩家皮肤 | `fakeplayer.command.skin` |
| `/fp tp <名称>` | 传送到假人位置 | `fakeplayer.command.tp` |
| `/fp tphere <名称>` | 将假人传送到自己位置 | `fakeplayer.command.tphere` |
| `/fp tps <名称>` | 与假人交换位置 | `fakeplayer.command.tps` |
| `/fp set <配置项> <值> <名称>` | 修改假人特性 | `fakeplayer.command.set` |
| `/fp config list` | 查看个人默认配置 | `fakeplayer.command.config` |
| `/fp config set <配置项> <值>` | 修改个人默认配置 | `fakeplayer.command.config` |
| `/fp cmd <名称> <命令>` | 让假人执行命令 | `fakeplayer.command.cmd` |
| `/fp reload` | 重载配置 | OP |

动作命令包括 `/fp attack`、`/fp mine`、`/fp use`、`/fp jump`、`/fp drop`、`/fp sleep`、`/fp wakeup`、`/fp turn`、`/fp look`、`/fp move`、`/fp ride`、`/fp sneak`、`/fp sprint`、`/fp swap`、`/fp hold` 和 `/fp stop`。

## 权限与安全

每个命令都有独立权限，也提供以下权限组：

- `fakeplayer.spawn`：基础的创建、移除、查看和配置权限。
- `fakeplayer.tp`：传送和交换位置权限。
- `fakeplayer.exp`：经验转移权限。
- `fakeplayer.action`：动作、手持物和自动行为权限。
- `fakeplayer.*`：全部权限。

特别注意 `/fp cmd`：

- 执行者需要 `fakeplayer.command.cmd`，或者命令名称出现在 `allow-commands` 中。
- 假人自身还必须拥有目标命令的权限。
- 非 OP 不应被允许通过假人执行服务器管理命令。
- `allow-commands` 只是兼容旧配置的白名单方案，代码已标记为弃用；新部署建议使用权限插件为假人分配权限组。

不要把 `fakeplayer.command.cmd` 或 `fakeplayer.*` 直接授予普通玩家。不要把真实密码、服务器地址、代理密钥或其他凭据写入 `self-commands` 后提交到公开仓库。

`/fp reload` 会重载普通配置。修改 `invsee-implement` 或安装/卸载 OpenInv
后需要重启服务器，因为背包查看实现是在插件启动时选择的。

## 配置重点

配置模板位于 [`fakeplayer-core/src/main/resources/config.yml`](fakeplayer-core/src/main/resources/config.yml)。运行中的配置位于服务器的 `plugins/fakeplayer/config.yml`。

| 配置项 | 作用 |
| --- | --- |
| `server-limit` | 整台服务器允许存在的假人上限 |
| `player-limit` | 单个玩家允许创建的假人上限 |
| `detect-ip` | 是否按 IP 限制重复创建 |
| `lifespan` | 假人存活时间，单位为分钟，`0` 表示永久 |
| `persistent-data` | 是否保存假人数据和背包 |
| `prevent-kicking` | 兼容登录插件或其他插件踢出假人 |
| `invsee-implement` | 选择 `AUTO` 或 `SIMPLE` 背包查看实现 |
| `default-features` | 设置新假人的默认特性 |
| `self-commands` | 假人加入服务器后自动执行的命令 |
| `pre-spawn-commands` / `post-quit-commands` | 生命周期钩子命令 |

配置模板文件会在升级时重新生成。请修改 `config.yml`，不要直接修改 `config.tmpl.yml`。

## 生命周期命令安全

每一条非空 `pre-spawn-commands` 都必须在
`pre-spawn-rollback-commands` 的相同位置配置幂等的逆操作。插件会在生成前
记录这些命令，并在生成失败、正常退出、插件停用或崩溃恢复时按逆序执行补偿。
不完整的配置会导致插件启动失败。

恢复语义是至少一次：`pre-spawn-rollback-commands`、`post-quit-commands` 和
`after-quit-commands` 都必须幂等。进程可能在外部命令已生效、数据库检查点尚未
推进时停止；下次启动会安全地重试该操作。

```yaml
pre-spawn-commands:
  - 'whitelist add %p'
pre-spawn-rollback-commands:
  - 'whitelist remove %p'
```

启用 BungeeCord 模式时，`follow-quiting` 会 fail-closed 为禁用。内建
`PlayerList` 响应没有 nonce 或认证信息，绝不会被用来授权破坏性删除假人。

## PlaceholderAPI 变量

安装 PlaceholderAPI 后可以使用：

- `%fakeplayer_total%`：服务器当前假人总数。
- `%fakeplayer_creator%`：假人的创建者。
- `%fakeplayer_actions%`：假人当前动作，例如 `USE|ATTACK`。

## 常见问题

### 假人刚加入就掉线

某些登录、反作弊或连接注入插件会主动处理假人。可以尝试将配置中的 `prevent-kicking` 设置为 `ON_SPAWNING` 或 `ALWAYS`，并检查相关插件的白名单规则。

### 假人不会吸引仇恨

检查假人的 `invulnerable` 配置。默认特性会影响受伤、饥饿和仇恨行为，可以使用 `/fp config set invulnerable false` 后重新生成假人。

### AuthMe 等插件要求登录

可以在 `self-commands` 中配置注册和登录命令，但必须使用专门的测试账号和复杂密码。不要把真实账号密码放进公开仓库。

### Folia 上的跨区域操作

Folia 不存在可随意访问所有实体的传统主线程。本分支会根据实体位置选择 EntityScheduler、RegionScheduler、GlobalRegionScheduler 或 AsyncScheduler；跨区域背包查看使用查看者侧镜像，无法安全原子完成的跨区域骑乘会被明确拒绝。

## 自定义翻译

1. 在 `plugins/fakeplayer` 中创建 `message` 文件夹。
2. 将 [`message.properties`](fakeplayer-core/src/main/resources/message/message.properties) 复制到该文件夹。
3. 按 `message_language_region.properties` 格式重命名，例如 `message_zh_CN.properties`。
4. 在 `config.yml` 中将 `i18n.locale` 设置为对应后缀。
5. 执行 `/fp reload-translation`；如果修改了 `i18n.locale`，先执行 `/fp reload`。

翻译文件必须使用 UTF-8 编码。

## 其他常见问题

如果其他插件修改了假人的连接导致掉线，可以尝试将 `prevent-kicking` 设置为
`ALWAYS`，并检查连接注入、登录和反作弊插件的白名单规则。

## 构建

构建现代发行包需要 JDK 21、Maven 3.8+ 或仓库内置的 Maven Wrapper：

```bash
./mvnw -B -ntp -Drevision=0.3.19-folia.3 \
    -pl fakeplayer-modern-dist -am clean verify
```

产物路径：

```text
fakeplayer-modern-dist/target/fakeplayer-0.3.19-folia.3.jar
```

现代发行包不需要手动准备根目录 `lib/`。旧版本模块的完整打包需要本地运行 BuildTools，具体步骤见 [`BUILD.md`](BUILD.md)。

## 测试记录

本版本使用真实服务端进程完成以下测试：

- Paper 1.21.11-132：Java 21，加载、生成、状态、命令、销毁和关服通过。
- Folia 1.21.11-14：Java 21，区域调度、远坐标生成、销毁和关服通过。
- Paper 26.1.2-74：Java 25，选择 `v26_1_2` bridge，完整烟测通过。
- Folia 26.1.2-8：Java 25，选择 `v26_1_2` bridge，完整烟测通过。
- 单元测试：7 个通过，0 个失败。
- CI：构建、静态安全检查和 ServiceLoader provider 检查通过。

这些是集成烟测，不等同于完整漏洞审计，也不覆盖真实客户端 UI、所有权限组合、皮肤显示和所有可选插件组合。

## 来源与许可证

本仓库是独立维护的 Folia 移植与兼容性分支。代码基础来自 [tanyaofei/minecraft-fakeplayer](https://github.com/tanyaofei/minecraft-fakeplayer)，上游来源和本分支修改均按 Apache License 2.0 处理。

许可证文本见 [`LICENSE.txt`](LICENSE.txt)，版权与第三方声明见 [`NOTICE`](NOTICE)。问题反馈请提交到 [Issues](https://github.com/IGNGserver/fakeplayer-folia/issues)。
