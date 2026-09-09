# Lophine 26.1.2 背包踢出与假人自动退出修复计划

## 1. 文档目的与边界

本文档是给后续实现对话使用的直接续作基线。本轮只完成源码、依赖和目标服务端调用链审计，不修改业务代码、不构建发布包、不部署服务器。

要修复的两个现象：

1. 真人打开假人背包后被客户端断开连接；
2. 假人创建约一段时间后自动退出。

本轮源码基线：

- 仓库：`/home/<user>/项目/插件移植/fakeplayer`
- 分支：`master`
- 提交：`fd630aa334bbb72a639158af027c9537bec4d1f1`
- 标签：`v0.3.19-folia.3`
- 审计开始时工作树：干净

本机用于反编译核对的目标服务端：

- 文件：`/home/<user>/项目/不常用/<other-project>/versions/26.1.2/lophine-26.1.2.jar`
- API：`lophine-api:26.1.2.build.638-stable`
- SHA-256：`e86d8055ad494f4ce4148969be25dd771b9fc076a401864469e463e6cc9b5b1a`

注意：以上是本机现有的 build.638 证据目标；正式服的精确 build、JAR 校验值、插件清单和运行配置本轮没有拿到。实现前必须通过 `/version` 和文件校验确认现场是否也是同一构建。

## 2. 结论摘要

| 编号 | 严重度 | 结论 | 置信度 |
| --- | --- | --- | --- |
| FP-L26-001 | P0 | 26.1.2 适配器留下了原版网络 listener 的 tick，同时插件又自行 tick 假人。原版 listener 周期性发送 keepalive，但当前假连接只收包不回包，默认约 30 秒后必然以 TIMEOUT 断开；期间还会重复执行 `ServerPlayer#doTick()`。 | 已确认 |
| FP-L26-002 | P0 | 当 `AUTO` 选中 OpenInv 时，OpenInv 打开的是 6 行特殊玩家背包；FakePlayer 随后调用 `InventoryView#setTitle`，Lophine 按 `InventoryType.PLAYER` 把同一窗口重新声明为 4 行，却继续发送 6 行菜单的内容，客户端菜单槽数与内容包长度不一致。 | 调用链已确认；现场是否走 OpenInv 分支待日志确认 |
| FP-L26-003 | P1 | 项目仍以 OpenInv 5.3.1 编译。5.3.2 修复了 Folia 错误调度上下文和 26.1.2 玩家/末影箱初始内容未复制的问题；当前最新版 5.3.3 仍支持 26.1.2。升级是必要兼容性工作，但不能替代 FP-L26-002 的标题修复。 | 已确认 |
| FP-L26-004 | P1 | `SIMPLE`/跨区域镜像把 43 槽 `PLAYER` 背包表示成 4 行通用容器，只暴露前 36 槽。该路径没有发现与 OpenInv 相同的 90/72 槽协议错配，但模型脆弱、装备栏不可见，不能用它证明所有现场踢出都已修好。 | 已确认结构限制；未确认它会踢人 |

## 3. 问题一：假人约 30 秒后自动退出

### 3.1 实际调用链

当前 26.1.2 适配器执行：

```text
NMSNetworkImpl.placeNewPlayer
  -> 创建真实 Connection + EmbeddedChannel
  -> PlayerList.placeNewPlayer(connection, player, cookie)
  -> Lophine 将 connection 加入当前 RegionizedWorldData.connections
  -> RegionizedWorldData.tickConnections()
  -> Connection.tick()
  -> TickablePacketListener.tick()
  -> ServerGamePacketListenerImpl.tick()
  -> keepConnectionAlive()
```

源码证据：

- `fakeplayer-v26_1_2/.../NMSNetworkImpl.java:76-100` 创建连接并调用 `PlayerList.placeNewPlayer`，保留服务端创建的原版 listener；
- 同文件 `:329-350` 捕获所有客户端方向的 Minecraft 包，放入 `outbound` 后终止 Netty 写出；
- 同文件 `:381-403` 消费队列时只处理 `ClientboundSetEntityMotionPacket` 和 `ClientboundCustomPayloadPacket`，没有处理 `ClientboundKeepAlivePacket`；
- `fakeplayer-core/.../FakeplayerTicker.java:85-93,113,131-134` 另行调用 `ServerPlayer#doTick()`。

对本机 Lophine build.638 的字节码核对结果：

1. `PlayerList.placeNewPlayer` 在创建 `ServerGamePacketListenerImpl` 后，将该 `Connection` 加入 `ServerLevel.getCurrentWorldData().connections`；
2. `RegionizedWorldData.tickConnections` 对在线连接调用 `Connection.tick()`；
3. `Connection.tick()` 对 `TickablePacketListener` 调用 `tick()`；
4. `ServerGamePacketListenerImpl.tick()` 首先调用 `keepConnectionAlive()`，随后 `tickPlayer()` 又调用一次 `ServerPlayer#doTick()`；
5. `ServerCommonPacketListenerImpl.keepConnectionAlive()` 每约 1 秒发送一个 `ClientboundKeepAlivePacket`；最老挑战超过 `paper.playerconnection.keepalive * 1000 ms` 即断开；默认系统属性值是 30 秒；
6. 超时时服务端日志文本为 `{} was kicked due to keepalive timeout!`，断开原因为 `TIMEOUT`，客户端文本键为 `disconnect.timeout`。

因此，在默认值下，只要没有更早的其他退出原因，当前假人约 30～31 秒后必然超时。它不是 `lifespan` 默认值导致的：默认 `lifespan: 0` 表示永久，`FakeplayerTicker` 也只有在传入 lifespan 大于 0 时才设置移除时间。

### 3.2 不应采用的伪修复

- 不要把 `prevent-kicking` 改成 `ALWAYS`。这会隐藏协议缺陷、阻止合法管理踢出，并可能让 keepalive 待确认队列持续增长；
- 不要只在 `drainOutbound` 中回 keepalive ACK 后就交付。这样虽可延后 TIMEOUT，却仍保留原版 listener 与 `FakeplayerTicker` 双重 `doTick`、原版飞行检查、空闲检查和客户端坐标回写；
- 不要只提高 `paper.playerconnection.keepalive`。它只改变复现时间；
- 不要把默认 `lifespan` 改大或改成 0 后宣称修复，当前默认本来就是 0。

### 3.3 正式修复设计

恢复“网络连接只负责生命周期和出站观察，假人实体只有一个 tick 所有者”的架构不变量：

```text
区域连接 tick
  -> 假人专用 packet listener.tick()：不执行原版玩家网络 tick

FakeplayerTicker
  -> 每服务器 tick 恰好一次 ServerPlayer#doTick()
```

推荐实现顺序：

1. 在 26.1.2 NMS provider 中安装真正的假人专用 `ServerGamePacketListenerImpl` 子类，而不是继续保留 `PlayerList.placeNewPlayer` 创建的普通玩家 listener；
2. 子类至少覆盖 `tick()` 为 no-op，并在该服务端方法存在时覆盖 `hasClientLoaded()` 返回 `true`；
3. 保留当前出站捕获中的运动包、BungeeCord payload 和断开包处理。不要把 `disconnect` 无条件 no-op：本插件依赖正常的 `PlayerKickEvent`、`PlayerQuitEvent`、`PlayerList.remove` 和生命周期事务；
4. 在 `placeNewPlayer` 完成且仍位于假人所属实体线程时，原子地把 `ServerPlayer.connection` 与 `Connection.packetListener` 都切换到新 listener，不能出现只换一侧的半安装状态；
5. `NMSBridgeImpl.verifyRuntime()` 必须验证构造器、`tick`、`hasClientLoaded`（如果使用）、listener 安装字段/方法和断开路径。目标结构不匹配时应拒绝启用该 provider，而不是带着普通 listener 继续运行；
6. 如果 Maven 无法可靠编译版本化 NMS 子类，应把 26.1.2 直接 NMS 适配器拆成独立构建模块，或使用经过测试的版本化字节码适配层。不要用只实现接口的 JDK Proxy 替代：Lophine 的 `Connection.tick()` 在断开队列中有 `instanceof ServerCommonPacketListenerImpl` 分支，普通 Proxy 会改变断开语义；
7. 只有在直接 listener 方案经构建约束证明不可行时，才考虑从 `RegionizedWorldData.connections` 注销假连接。采用注销方案必须同时重做显式踢出、外部踢出、跨区传送和停服清理，否则连接不再被区域循环收尾，会制造新的幽灵玩家或缺失 `PlayerQuitEvent`。

设计参考：Lophine 所基于的 Leaves 为内置 bot 使用独立 `ServerBotPacketListenerImpl`，其 `tick()` 为 no-op，而不是让 bot 回应普通客户端 keepalive。上游当前实现还明确让 `hasClientLoaded()` 返回 true。参考：<https://github.com/LeavesMC/Leaves/blob/master/leaves-server/src/main/java/org/leavesmc/leaves/bot/ServerBotPacketListenerImpl.java>。

## 4. 问题二：打开假人背包后真人被踢出

### 4.1 先确认运行时分支

默认 `invsee-implement: AUTO`。启动时：

- OpenInv 已启用且 `IOpenInv` 类存在：日志为 `Using OpenInv as invsee implement`；
- 否则：日志为 `Using simple invsee implement`。

选择发生在依赖注入初始化时，修改配置或增删 OpenInv 后仅 `/fp reload` 不会替换实现，必须完整重启。

### 4.2 OpenInv 分支的确定性协议错配

本项目的调用链：

```text
AbstractInvseeManager.invsee
  -> OpenInvInvseeManagerImpl.openInventory
  -> openInv.getSpecialInventory(whom, true)
  -> openInv.openInventory(viewer, specialInventory)
  -> 返回已经打开的 6 行特殊玩家背包 view
  -> AbstractInvseeManager 无条件 view.setTitle(...)
```

关键源码：

- `fakeplayer-core/.../OpenInvInvseeManagerImpl.java:30-35` 打开 OpenInv special inventory；
- `fakeplayer-core/.../AbstractInvseeManager.java:62-86` 在实现已经打开窗口后无条件调用 `view.setTitle`；
- `fakeplayer-core/.../FakeplayerModule.java:34-47` 决定 AUTO/SIMPLE 分支。

精确槽位链：

1. OpenInv 5.3.1 的 26.1.2 adapter 按源码公式计算 `player inventory 43 + crafting 4 + 额外保留 1 = 48`，向上取整成 54 个顶层槽；它以 `GENERIC_9x6` 打开窗口；
2. 返回的 Bukkit 顶层 inventory 仍报告 `InventoryType.PLAYER`；
3. Lophine build.638 的 `CraftInventoryView.sendInventoryTitleChange` 根据顶层 Bukkit 类型重新计算窗口类型。26.1.2 API 把 `PLAYER` 映射为 `GENERIC_9X4`，于是它用同一 container id 再发一个 4 行 `ClientboundOpenScreenPacket`；
4. 紧接着 `sendAllDataToRemote()` 仍从 OpenInv 的真实 6 行 menu 发送全部内容：54 个顶层槽 + 36 个查看者背包槽，共 90 项；
5. 客户端已按 4 行创建 36 + 36 = 72 槽 menu。共同的 `AbstractContainerMenu.initializeContents` 会遍历收到的整个列表并逐项 `getSlot(index)`，没有把输入裁剪到本地槽数；从 index 72 起越界，客户端把该入站包视为处理失败并断开。

这解释了“窗口刚打开，真人立刻被踢”的时序。根因在 FakePlayer 对第三方自定义 menu 调用了会重新声明窗口类型的通用标题 API；升级 OpenInv 本身不会消除这次二次声明。

OpenInv 5.3.1 的 54 槽计算可从其版本化源码核对：

- <https://github.com/Jikoo/OpenInv/blob/5.3.1/internal/paper26_1/src/main/java/com/lishid/openinv/internal/paper26_1/container/BaseOpenInventory.java>
- <https://github.com/Jikoo/OpenInv/blob/5.3.1/internal/paper26_1/src/main/java/com/lishid/openinv/internal/paper26_1/container/menu/BaseOpenInventoryMenu.java>

### 4.3 SIMPLE 与跨区域镜像分支

`SimpleInvseeManagerImpl` 直接执行 `viewer.openInventory(whom.getInventory())`。Lophine 会把 43 槽 `PLAYER` inventory 包成 4 行 chest menu，只取前 36 个顶层槽；随后的 `setTitle` 仍是 4 行，因此没有 OpenInv 分支的 90/72 长度错配。

跨区域分支在假人线程复制 43 项，在查看者线程创建 `InventoryType.PLAYER` 镜像并设为只读。它同样只以 4 行展示前 36 项。这个实现避免了直接跨区暴露原 inventory，但有三个问题：

- 装备、额外槽位和 26.1.2 新增的身体/坐骑类槽位不可见；
- 标题仍在窗口打开后修改，继续依赖服务端对 menu 类型的重建细节；
- 镜像只在打开时快照一次，之后不会刷新。

因此，若现场日志显示使用 SIMPLE，必须保留真人客户端的断开文本和服务端异常栈重新定因；不能把 OpenInv 条件链套到 SIMPLE 上。当前源码审计没有证明 SIMPLE 会触发同样的踢出。

### 4.4 正式修复设计

第一阶段先消除协议错配，保持最小产品变化：

1. 修改 invsee 接口，让具体实现拥有标题策略；禁止 `AbstractInvseeManager` 在任意第三方/自定义 menu 已打开后无条件 `view.setTitle`；
2. OpenInv 分支直接使用 OpenInv 在创建 `OpenInventoryMenu` 时提供的标题。若必须显示 FakePlayer 的本地化标题，需要通过 OpenInv 支持的创建期标题能力或自有 menu 实现，不能在打开后调用 Bukkit 通用 retitle；
3. SIMPLE/跨区镜像如需自定义标题，在 `Bukkit.createInventory(..., title)` 的创建阶段写入。不要“先打开，再重开同一 id 改标题”；
4. 为每次打开建立并断言以下协议不变量：声明的顶层行数、服务端 menu 顶层槽数、初始内容包槽数三者一致；
5. 将 `fakeplayer-core/pom.xml` 的 OpenInv 编译版本从 5.3.1 升到当前仍支持 26.1.2 的 5.3.3；运行时检测版本，低于 5.3.2 时 AUTO 应明确告警并回退 SIMPLE，不能静默启用；
6. 升级理由不只是版本号：OpenInv 5.3.2 已修复 Folia/fork 的错误调度上下文，以及玩家/末影箱初始内容发送前未复制 `ItemStack` 的问题。官方发布说明：<https://github.com/Jikoo/OpenInv/releases/tag/5.3.2>；当前 5.3.3 支持范围：<https://github.com/Jikoo/OpenInv/releases/tag/5.3.3>。

第二阶段再决定是否统一背包模型：

1. 推荐实现明确的 54 槽 `FakeInventoryLayout`，用 CHEST/`GENERIC_9x6` 表示主背包、快捷栏、盔甲、副手和 26.1.2 额外槽位，空位用不可交互占位物；
2. 槽位映射应是单一表，不要依赖 `PlayerInventory#getContents()` 的隐含排列。Lophine/Leaves 内置 bot 同样使用 54 槽包装器和显式 `convertSlot`，可作语义参考：<https://github.com/LeavesMC/Leaves/blob/master/leaves-server/src/main/java/org/leavesmc/leaves/bot/BotInventoryContainer.java>；
3. 最安全的第一版可让所有地区的查看都使用只读镜像；如果产品必须允许编辑，再将一次点击转换成单槽操作，在假人实体 scheduler 上验证旧值/修订号后提交并刷新；
4. 禁止关闭窗口时把整份旧快照写回假人。这会覆盖假人在查看期间拾取、消耗或装备的物品；
5. 查看者退出、假人退出、假人被同名替换或插件停用时，必须幂等关闭 session。

## 5. 实施阶段

### 阶段 A：现场证据封存（只读，先做）

在改代码前保存：

```text
/version
/plugins
/version OpenInv
```

同时保存以下文件或输出，不要记录密钥：

- 服务端 JAR SHA-256；
- `plugins/FakePlayer/config.yml` 中的 `lifespan`、`prevent-kicking`、`follow-quiting`、`kale-tps`、`kick-on-dead`、`invsee-implement`；
- `server.properties` 的 `player-idle-timeout`；
- JVM 是否设置 `-Dpaper.playerconnection.keepalive=...`；
- 假人创建时间、退出时间、真人点开背包时间；
- 真人客户端断开界面全文和对应前后至少 100 行服务端日志；
- 启动日志中的 `Using OpenInv as invsee implement` 或 `Using simple invsee implement`。

建议日志检索：

```bash
rg --search-zip -n -C 20 \
  'Using (OpenInv|simple) as invsee implement|keepalive timeout|disconnect.timeout|lost connection|DecoderException|IndexOutOfBoundsException|container|packet' \
  logs/latest.log logs/*.log.gz
```

如果日志时间证明退出不是约 30 秒，仍先修 FP-L26-001，因为该缺陷在 build.638 上必然存在；随后按日志继续排除 `lifespan`、创建者离线跟随、低 TPS、死亡和外部插件踢出。

### 阶段 B：修复网络 tick 所有权（P0）

建议修改范围：

- `fakeplayer-v26_1_2/.../NMSNetworkImpl.java`
- `fakeplayer-v26_1_2/.../NMSBridgeImpl.java`
- 必要时新增版本化 listener/connection 类及独立构建模块
- 对应 NMS provider 测试

完成条件：

- 连接仍被 Lophine 正常登记和清理；
- 网络 tick 不调用普通玩家的 `ServerGamePacketListenerImpl.tick()`；
- `FakeplayerTicker` 是 `ServerPlayer#doTick()` 的唯一所有者；
- 没有待确认 keepalive 累积，也没有超时日志；
- 显式 `/fp kill`、外部合法 kick、死亡、寿命到期、创建者退出和停服各触发一次且仅一次退出生命周期。

### 阶段 C：修复背包窗口协议（P0）

建议修改范围：

- `fakeplayer-core/.../AbstractInvseeManager.java`
- `fakeplayer-core/.../OpenInvInvseeManagerImpl.java`
- `fakeplayer-core/.../SimpleInvseeManagerImpl.java`
- `fakeplayer-core/.../FakeplayerModule.java`
- `fakeplayer-core/pom.xml`
- 背包布局/session 测试

先交付的最小闭环：

- 去掉 OpenInv view 的打开后 retitle；
- 跨区镜像在创建时带标题；
- OpenInv 编译版本更新为 5.3.3，运行时低版本拒绝 AUTO 联动；
- 真人客户端在 OpenInv AUTO 与 SIMPLE 两种矩阵均能打开/关闭，不断线。

随后再按产品要求决定是否统一为 54 槽布局及是否支持安全编辑。

### 阶段 D：回归、构建与交付

源码门禁：

```bash
git status --short
git diff --check
./mvnw -B -ntp -Drevision=0.3.19-folia.4 \
  -pl fakeplayer-modern-dist -am clean verify
```

正式版本号由实施对话确认；上面的 `0.3.19-folia.4` 是本次修复的建议候选，不应在未决定发布策略时直接写死。

需要检查最终 JAR：

- `META-INF/services` 中 1.21.11 与 26.1.2 provider 均存在且没有重复错误；
- 26.1.2 provider 的运行时结构验证能在不兼容 build 上明确失败；
- OpenInv 仍为 `provided`/可选依赖，没有被错误打入主 JAR；
- 记录产物 SHA-256。

## 6. 必须执行的验收矩阵

### 6.1 网络与生命周期

至少在目标 Lophine 26.1.2 精确 build 上执行：

| 场景 | 验收要求 |
| --- | --- |
| `lifespan: 0`，保持站立 | 10 分钟后仍在线；特别记录 29、31、60、600 秒状态 |
| 缩短 keepalive 阈值的隔离测试服 | 超过阈值两倍仍在线，日志无 keepalive timeout |
| `player-idle-timeout > 0` | 假人不被普通客户端 idle 逻辑误删；真人仍按原规则处理 |
| 假人受击/击退/移动/传送/跨世界 | 行为不回弹、不双倍加速；每秒 tick 计数约 20 而非约 40 |
| `/fp kill` | 一次 PlayerKickEvent/PlayerQuitEvent、一次持久化和一次后置命令 |
| 外部合法 kick | 按 `prevent-kicking` 配置执行，且不产生幽灵在线记录 |
| 死亡、寿命到期、创建者离线、低 TPS | 各自保留原有策略和原因，不因网络修复失效 |
| 插件 disable / 服务端 stop | 无退休 scheduler 二次调用、无残留连接/任务/玩家列表项 |

### 6.2 背包与真实客户端

必须用与服务器匹配的真实 Mojang 客户端执行；MockBukkit、编译成功和控制台命令不能替代：

| 实现 | 位置 | 打开方式 | 数据集 |
| --- | --- | --- | --- |
| SIMPLE | 同 Folia 区域 | 右键、命令 | 空包、全满、可堆叠物、复杂组件物品 |
| SIMPLE | 不同区域/世界 | 命令 | 同上 |
| AUTO + OpenInv 5.3.3 | 同区域 | 右键、命令 | 主背包、快捷栏、盔甲、副手、额外槽 |
| AUTO + OpenInv 5.3.3 | 不同区域/世界 | 命令 | 同上 |

每个用例都要验证：

- 真人打开后仍在线，客户端日志没有 packet/container 异常；
- 标题正确且没有可见的二次开窗闪烁；
- 服务端声明行数与内容槽数一致；
- 关闭、重开、连续快速打开不同假人均正常；
- 假人在查看期间继续拾取/消耗物品时，不发生复制、丢失或旧快照覆盖；
- 只读模式覆盖普通点击、Shift 点击、数字键换位、双击收集、拖拽、丢弃键和副手交换；
- 如保留编辑模式，每个写操作都在假人所属实体 scheduler 上完成并能处理并发冲突。

OpenInv 版本 A/B 只用于定因，不属于最终支持矩阵：可用 5.3.1 复现并保存证据，然后升级 5.3.3；最终产物不应把 5.3.1 当作受支持运行时。

## 7. Definition of Done

只有以下全部满足才可宣称完成：

1. `PASS`：精确 Lophine 26.1.2 目标 build 启动并选择预期 NMS provider；
2. `PASS`：假人在默认 keepalive 阈值两倍以上及 10 分钟长测后仍在线；
3. `PASS`：假人实体每秒只被模拟约 20 tick，没有双重 `doTick`；
4. `PASS`：真实客户端在 SIMPLE、AUTO + OpenInv 5.3.3、同区和跨区矩阵中打开背包均不掉线；
5. `PASS`：menu 行数/槽数协议不变量有自动测试，且 OpenInv 分支不执行打开后通用 retitle；
6. `PASS`：显式退出、外部踢出、死亡、寿命、创建者离线和停服生命周期均只执行一次；
7. `PASS`：Maven `clean verify`、`git diff --check` 和最终 JAR/provider 检查通过；
8. `PASS`：产物部署到目标服后记录版本、SHA-256、日志和真人客户端验收证据；
9. `NOT RUN` 不得写成 `PASS`。没有真人客户端、长测或现场目标 build 时，只能如实保留未验收边界。

## 8. 回滚方案

本次预计无数据库迁移。上线前保留：

- 旧 FakePlayer JAR 及 SHA-256；
- 旧 OpenInv JAR 及 SHA-256；
- FakePlayer/OpenInv 配置目录备份；
- 上线前玩家数据备份。

发生问题时完整停服，恢复两份旧 JAR 和配置后再启动；不要热替换 NMS listener 相关 JAR。回滚后要检查在线玩家列表、假人列表、未完成生命周期日志和玩家存档，确认没有幽灵玩家或重复退出钩子。

## 9. 本轮验证状态

| 项目 | 状态 | 说明 |
| --- | --- | --- |
| 当前源码/配置调用链 | PASS | 已核对 26.1.2 network、ticker、invsee、DI 和生命周期路径 |
| 本机 Lophine build.638 字节码链 | PASS | 已核对 connection 注册、区域 tick、keepalive 30 秒、窗口 retitle 与槽位初始化 |
| OpenInv 5.3.1/5.3.2 源码差异 | PASS | 已核对 54 槽模型及 5.3.2 的复制/调度修复 |
| 修复计划文档 | PASS | 本文档 |
| 业务代码修改 | NOT RUN | 用户要求本轮只分析和交接 |
| Maven 构建 | NOT RUN | 本轮未修改业务源码，且目标是诊断/计划 |
| 现场 Lophine 日志与配置 | NOT RUN | 当前工作区没有对应现场日志/config/plugin JAR 清单 |
| 真人客户端背包复现 | NOT RUN | 当前环境没有目标客户端会话 |
| 假人 10 分钟长测 | NOT RUN | 当前环境未启动目标服务端 |
