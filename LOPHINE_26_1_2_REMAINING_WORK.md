# Lophine 26.1.2 修复复核与剩余工作

## 1. 复核结论（实施前历史基线）

**以下结论记录修复实施前状态；最新实施结果见第 3 节更新项和第 9 节。**

本轮按只审计边界复核现有工作区，没有修改业务源码。当前实现已经消除了两个原始根因中的关键代码：

- 26.1.2 provider 已安装专用 `ServerGamePacketListenerImpl` 子类，`tick()` 不再执行普通玩家网络 tick；
- invsee 公共路径已移除窗口打开后的 `InventoryView#setTitle(...)`；
- 跨区镜像改为创建时声明 36 槽和标题；
- OpenInv 编译依赖升级到 5.3.3，并对低于 5.3.2 的运行时回退 SIMPLE。

但是，当时改动又引入了一个确定可复现的背包校验错误，并且停服顺序和 NMS 安装失败回滚仍有缺口。原计划要求的真实客户端、10 分钟、单 tick 所有权以及目标服验证也尚未执行。因此当时不能把“编译通过”和“隔离服短时存活”写成整体修复完成。

## 2. 复核基线与重要更正

- 仓库：`/home/<user>/项目/插件移植/fakeplayer`
- 分支：`master`
- 当前 HEAD：`fd630aa334bbb72a639158af027c9537bec4d1f1`
- 当前标签：`v0.3.19-folia.3`
- `origin/master`：同一提交
- AI 的全部业务修改仍是未提交工作区改动；本轮复核开始前共有 10 个已跟踪文件被修改，并新增源码、测试和原修复计划。

原计划把本机服务端写成 build.638，需要更正：

- 文件：`/home/<user>/项目/不常用/<other-project>/versions/26.1.2/lophine-26.1.2.jar`
- SHA-256：`e86d8055ad494f4ce4148969be25dd771b9fc076a401864469e463e6cc9b5b1a`
- JAR manifest：`Implementation-Version: 26.1.2-470-13c5bd7`、`Build-Number: 470`
- 实际启动：`Lophine 26.1.2-470-ver/26.1.2@13c5bd7`，API `26.1.2.build.470-stable`

本机 Maven 缓存里存在 build.638 API 不能证明运行的服务端也是 build.638。以下运行时结论只对本机 **build 470** 的隔离服成立；生产服的精确 build、服务端 JAR 校验值、插件清单和配置仍需现场采集。

## 3. 验证状态（实施前历史基线，最新结果见第 9 节）

| 项目 | 状态 | 证据与边界 |
| --- | --- | --- |
| 移除 OpenInv 打开后通用 retitle | PASS | `AbstractInvseeManager` 已删除 `view.setTitle(...)`，原 6 行菜单被二次声明成 4 行的直接根因已移除 |
| 跨区镜像创建期声明 36 槽和标题 | PASS | 使用 `Bukkit.createInventory(null, 36, title)`，不再创建 `InventoryType.PLAYER` 后二次改标题 |
| OpenInv 5.3.3 编译依赖和最低版本门禁 | PASS | 隔离服日志确认选择 `Using OpenInv 5.3.3 as invsee implement` |
| 新增的菜单 shape 运行时校验 | PASS（已移除） | 错误 validator 已从运行时路径删除；详见第 9 节 |
| 26.1.2 专用 NMS listener 定义、加载和正常安装 | PASS（build 470 短测） | build 470 启动选择 `v26_1_2` provider，成功创建假人 |
| 假人超过旧 keepalive 超时时间仍在线 | PASS（build 470 短测） | `AuditBot` 创建后约 82 秒仍在 `/list` 和假人列表中，无 keepalive timeout |
| 显式 `/fp kill` 正常退出 | PASS（单次短测） | 日志各出现一次 `lost connection` 和 `left the game`，随后在线人数为 0 |
| NMS listener 安装失败的完整回滚 | PASS（自动测试） | 已加入构造失败、两侧字段写入失败和 drain task 失败回滚测试；详见第 9 节 |
| 正常启用后的 clean stop | PASS（单次短测） | build 470 隔离服正常停止，数据源正常关闭，无明显清理异常 |
| 停服时异步任务、生命周期恢复和数据源关闭顺序 | PASS（自动测试） | 统一协调器按 STOP_ACCEPTING → STOP_ASYNC → RECOVER_JOURNAL → CLEANUP 执行，并覆盖异步 continuation 竞态 |
| 部分启用失败后的清理 | PASS（自动测试） | 协调器只执行已注册组件的清理，不再通过 Guice 懒创建未初始化运行期服务；真实生产 enable 失败未运行 |
| Maven `clean verify` | PASS | JDK 21 下六模块成功，core 29 个测试、v26_1_2 5 个测试，0 failure / 0 error / 0 skipped |
| `git diff --check` | PASS | 只有仓库换行属性提示，没有 whitespace error |
| 候选 JAR/provider/可选依赖检查 | PASS | 两个 NMS provider 都在 ServiceLoader 中；OpenInv 未被 shade 进主 JAR |
| 精确生产 Lophine build 验证 | **NOT RUN** | 当前没有生产服 `/version`、JAR SHA-256 和启动日志 |
| 假人 10 分钟存活 | **NOT RUN** | build470 短测超过 4 分钟；完整 600 秒观察仍未完成 |
| 实体每秒约 20 tick、无重复 `doTick()` | PASS（短 probe） | build470 10 秒 tick probe 约 20 tick/s；长测和行为负载仍未完成 |
| 真人客户端完整背包矩阵 | **NOT RUN** | AUTO 跨区有成功开窗证据；最新 SIMPLE 测试受 Folia watchdog 卡顿影响，完整 SIMPLE 同区/跨区矩阵不能结案 |
| 外部 kick、死亡、lifespan、创建者离线、低 TPS、跨世界 | **NOT RUN** | 外部 `/kick`、死亡 `/kill`、显式 `/fp kill` 已有短测 PASS；其余逐项验收未完成 |
| 正式提交、版本、目标服部署及回滚验证 | **NOT RUN** | HEAD 未变化，候选包仅用于审计 |

审计构建产物仅供复核，不能作为正式发布件：

- 文件：`fakeplayer-modern-dist/target/fakeplayer-0.3.19-folia.4-audit.jar`
- SHA-256：`85d2f8eba3613b49e869b6656acf1d34f5e6a74f697313e686bd63d7bec866fa`

本次实施候选包（仍非正式发布件）：

- 文件：`fakeplayer-modern-dist/target/fakeplayer-0.3.19-folia.4.jar`
- SHA-256：`a8e3cf3e374cbd6f781a22a8c6d1400413e891713ed3f3dab0807eb0ed9f7711`

## 4. 实施前发现的修复项（已在本次实施中处理）

### FP-REVIEW-001（P0）：菜单 shape 校验使用了错误的 Bukkit 语义

涉及代码：

- `fakeplayer-core/src/main/java/io/github/hello09x/fakeplayer/core/manager/invsee/InventoryMenuProtocol.java:36-49`
- `fakeplayer-core/src/main/java/io/github/hello09x/fakeplayer/core/manager/invsee/OpenInvInvseeManagerImpl.java:30-43`
- `fakeplayer-core/src/main/java/io/github/hello09x/fakeplayer/core/manager/invsee/AbstractInvseeManager.java:118-150`

当前 `assertViewShape` 假定：

```text
bottomSlots == 36
view.countSlots() == topSlots + bottomSlots
```

这不符合 Lophine 26.1.2 的 Bukkit 表示：

1. build 470 的 `CraftInventoryPlayer#setItem` 明确接受索引 `0..42`，因此 Bukkit `PlayerInventory#getSize()` 是 43，不是协议菜单里通常显示的 36 个查看者底栏槽；
2. `CraftAbstractInventoryView#countSlots()` 的普通实现直接返回 `top.getSize() + bottom.getSize()`；
3. OpenInv 5.3.3 使用自定义 `InventoryView`，它的 `countSlots()` 又具有自己的菜单语义，不能按普通 CraftBukkit view 的公式解释。

隔离服已经创建两个位于同一区域的假人，并让 `AuditViewer` 以玩家命令身份执行 `fakeplayer invsee AuditTarget`。实际 FakePlayer -> OpenInv 5.3.3 调用链在窗口打开后得到：

```text
viewer=AuditViewer target=AuditTarget
viewClass=com.lishid.openinv.internal.paper26_2.container.menu.BaseOpenInventoryMenu$1
type=PLAYER topType=PLAYER top=54 bottom=43 count=90
menuType=minecraft:generic_9x6
```

随即 FakePlayer 记录：

```text
OpenInv returned an inconsistent inventory view for AuditTarget:
Opened inventory view reports an inconsistent slot layout: top=54, bottom=43, content=90
```

菜单实际声明 6 行，上层 54 槽加查看者协议底栏 36 槽正好是 90，这是合法布局。由于 Bukkit `PlayerInventory` 对外尺寸仍是 43，新校验既要求它等于 36，又把合法的 `content=90` 与 Bukkit 尺寸之和 `54 + 43 = 97` 比较，所以会确定性误拒绝该视图。

此处假人仅用于提供真实的 server-side `Player` 命令调用者，没有真人客户端，因此它证明的是校验错误和实际菜单结构，不替代最终“真人不会被踢”的验收。该实现还有两个结构问题：

- 校验发生在 `openInv.openInventory(...)` 或 `viewer.openInventory(...)` **之后**，初始开窗包已经发送，因而它不能在协议错误发生前保护客户端；
- OpenInv 路径捕获异常后返回 `null`，却没有撤销已经打开的窗口；跨区路径则会把合法的 36 槽窗口立即关闭并显示错误。

36 槽跨区窗口在 build 470 普通 view 中会报告 `top=36, bottom=43, count=79`，也必然触发同一个错误分支。因此当前背包功能不是完整修复状态。

现有 `InventoryMenuProtocolTest` 只测试手工传入的四个整数，没有调用 `assertViewShape(InventoryView)`，也没有覆盖 OpenInv 自定义 view，所以 4 个测试通过不能发现这个回归。

必须完成：

1. 删除这两个打开后 `assertViewShape(...)` 调用；如果没有真正基于 NMS menu 的实现，删除误导性的 `InventoryMenuProtocol`；
2. 保留已经正确的改动：禁止打开后 retitle、跨区窗口创建时指定 36 槽和标题、OpenInv 5.3.3 门禁；
3. 如果仍要做协议断言，应由 26.1.2 版本适配层检查真实 `AbstractContainerMenu` 的 `menuType`、槽表和初始同步内容，且必须在发包前完成；不能从 Bukkit `getSize()/countSlots()` 推断线上包长度；
4. 增加覆盖实际调用语义的测试：OpenInv `top=54/bottom=43/count=90` 不应被假拒绝，普通 36 槽 view `top=36/bottom=43/count=79` 不应被假拒绝；
5. 用真人客户端完成 SIMPLE 与 AUTO + OpenInv 5.3.3、同区与跨区矩阵后，才能将原“打开背包被踢”问题标为 PASS。

### FP-REVIEW-002（P1）：PluginDisableEvent 提前恢复事务，破坏自身要求的停服顺序

涉及代码：

- `fakeplayer-core/src/main/java/io/github/hello09x/fakeplayer/core/listener/FakeplayerLifecycleListener.java:78-98`
- `fakeplayer-core/src/main/java/io/github/hello09x/fakeplayer/core/Main.java:47-81,131-145`
- `fakeplayer-core/src/main/java/io/github/hello09x/fakeplayer/core/util/async/PluginAsyncExecutor.java:73-85`
- `fakeplayer-core/src/main/java/io/github/hello09x/fakeplayer/core/lifecycle/LifecycleCommandCoordinator.java:145-154`
- `fakeplayer-core/src/main/java/io/github/hello09x/fakeplayer/core/manager/FakeplayerManager.java:784-814`

新监听器在 `PluginDisableEvent` 的 LOWEST 阶段直接执行：

```text
FakeplayerLifecycleListener.onDisable()
FakeplayerManager.onDisable()
  -> LifecycleCommandCoordinator.recoverPendingSynchronously()
```

而 `Main.onDisable()` 原本的正确意图是：

```text
取消延迟 hook
-> manager.beginShutdown()
-> PluginAsyncExecutor.shutdown()
-> manager.onDisable()/recoverPendingSynchronously()
-> 其他资源清理
```

`LifecycleCommandCoordinator` 自己也注明同步恢复必须在 async executors 已停止后运行。当前 LOWEST 路径提前调用 `manager.onDisable()`，会在异步数据库 continuation 仍可能运行时恢复同一批持久化事务。随后 `Main.onDisable()` 因 `shutdownFinalized` 已置位，不能重新按正确顺序补做恢复。

引入 LOWEST 监听的动机成立：随包数据库模块的 `DatasourceListener` 在 `PluginDisableEvent` 的 MONITOR 阶段关闭 Hikari 数据源。但正确做法必须把**完整停服序列**放到该阶段之前，而不是只提前执行序列中的末段。

部分启用失败实测也证明当前路径不安全。用不兼容 CommandAPI 触发 `CommandRegistry.register()` 在监听器注册前失败后，日志依次出现：

```text
HikariDataSource ... Shutdown completed
Lifecycle finalization remains pending ... HikariDataSource ... has been closed
IllegalPluginAccessException: Plugin attempted to register task while disabled
```

原因是生命周期监听器直到命令注册、恢复和 `WildFakeplayerManager` 初始化之后才注册；失败后 `Main.onDisable()` 又通过 Guice 懒创建从未成功初始化的组件。

必须完成：

1. 建立一个幂等的统一 shutdown coordinator；LOWEST `PluginDisableEvent` 和 `Main.onDisable()` 必须调用同一个完整序列，而不是各自执行半套清理；
2. 固定顺序为：停止接单/取消延迟任务 -> 停止并等待插件自有异步执行器 -> 同步恢复生命周期 journal -> 清理假人/动作/野生假人/ID 仓库 -> 允许 MONITOR 数据源监听器关闭连接池；
3. 让这个协调器在任何可能失败的 enable 步骤之前可用并注册，或以不依赖晚期监听器注册的方式接管顺序；
4. 跟踪哪些组件确实完成初始化，部分启用失败时不得通过 `getInstance(...)` 懒创建 scheduler、manager 或其他运行期服务；
5. 增加两类验证：异步 DB continuation 尚在执行时停服；命令注册或其他中途 enable 步骤失败。两者都必须无 closed-datasource、无 disabled-plugin task、journal 可恢复且清理只执行一次。

### FP-REVIEW-003（P1）：专用 NMS listener 安装失败后可能留下已登记玩家

涉及代码：

- `fakeplayer-v26_1_2/src/main/java/io/github/hello09x/fakeplayer/v26_1_2/spi/NMSNetworkImpl.java:97-168,198-275`

正常路径已在 build 470 短测通过，但失败路径没有满足原计划要求的原子安装与回滚：

1. `PlayerList.placeNewPlayer(...)` 已先把玩家和连接登记到服务端；
2. `NmsAccess.newFakePlayerPacketListener(...)` 在 `installFakePlayerListener` 的局部 `try` 之外构造；
3. `ServerGamePacketListenerImpl` 的构造过程本身会写入 `ServerPlayer.connection`。若构造中途失败，局部恢复代码不会执行；
4. 外层 catch 只调用 `NMSNetworkImpl.close()`；该方法在 Folia 分支刻意不执行 native disconnect / PlayerList remove，所以已登记玩家可能残留为半初始化或幽灵玩家。

必须完成：

1. 把新 listener 构造、两侧字段切换和可见性校验纳入同一失败保护；任何失败都恢复 `ServerPlayer.connection` 与 `Connection.packetListener`；
2. 对 `placeNewPlayer` 成功之后的任意失败增加明确的 Folia 实体线程清理：通过唯一、规范的 disconnect/remove 路径移出 PlayerList，并保证 `PlayerQuitEvent`、名字释放、journal 和网络资源各处理一次；
3. 不要复用正常 `PlayerQuitEvent` 回调中的轻量 `close()` 充当“创建失败撤销”；这两个生命周期阶段需要不同的清理入口；
4. 加入 fault-injection 测试，至少覆盖 listener 构造失败、第一侧字段写入失败、第二侧字段写入失败和调度 drain task 失败；每个用例都断言没有在线玩家、连接、任务或半安装 listener 残留。

## 5. 已正确的改动应保留

后续对话不应把以下内容整体回滚：

1. OpenInv/第三方 menu 打开后不再调用通用 `InventoryView#setTitle(...)`；
2. 跨区只读镜像在 `Bukkit.createInventory(...)` 时确定尺寸和标题；
3. 跨区快照取消所有 click/drag 模式，避免通过查看者底栏操作污染快照语义；
4. OpenInv 编译版本 5.3.3、运行时最低 5.3.2、AUTO 不兼容时告警并回退 SIMPLE；
5. 26.1.2 使用真正的 `ServerGamePacketListenerImpl` 子类，覆盖 `tick()` 为 no-op、`hasClientLoaded()` 为 true，并同时替换 player/connection 两侧引用；
6. provider 启用前的结构检查和 ServiceLoader 中同时保留 1.21.11、26.1.2 provider。

## 6. 建议实施顺序（历史记录）

### 阶段 A：先修 P0 背包回归

1. 去掉错误的打开后 view shape 校验，补齐代表真实 Bukkit/OpenInv 语义的测试；
2. 保留创建期标题和版本门禁；
3. Maven 全量验证；
4. 在隔离服用真人客户端完成 AUTO + OpenInv 5.3.3 与 SIMPLE 的同区、跨区打开/关闭测试。

完成阶段 A 前，不要继续用“协议断言存在”作为安全证明；当前断言既错误，又发生得太晚。

### 阶段 B：统一停服协调器

1. 合并 `PluginDisableEvent` 与 `Main.onDisable()` 的停服入口；
2. 修正 async shutdown、journal 恢复、数据源关闭的先后顺序；
3. 修复 partial-enable cleanup 不应懒创建未初始化组件的问题；
4. 增加并运行停服竞态与启用失败测试。

### 阶段 C：补齐 NMS 创建失败事务

1. 为 `placeNewPlayer` 之后的安装过程建立显式提交/撤销边界；
2. 加入故障注入测试；
3. 在目标 Lophine build 上验证失败后没有幽灵在线记录或重复退出事件。

### 阶段 D：完成正式验收和交付

1. 从目标服保存 `/version`、`/plugins`、`/version OpenInv`、服务端 JAR SHA-256、相关配置和 JVM keepalive 参数；
2. 在精确目标 build 上观察 `lifespan: 0` 假人至少 10 分钟，记录 29、31、60、600 秒状态；
3. 用计数器或等价可审计证据证明实体约 20 tick/s，而非重复约 40 tick/s；
4. 执行 `/fp kill`、外部 kick、死亡、lifespan、创建者离线、低 TPS、插件 disable/server stop，逐项证明退出事务一次且仅一次；
5. 真人客户端执行 SIMPLE、AUTO + OpenInv 5.3.3、同区和跨区矩阵，包括空包、满包、组件物品、连续开关和所有只读交互模式；
6. 运行 JDK 21 Maven `clean verify`、`git diff --check`，复查 provider、可选依赖和最终版本号；
7. 提交源码，生成正式 JAR 与 SHA-256，备份旧 JAR/配置后部署目标服，并保存启动、玩家和客户端验收证据。

## 7. 完成门槛

只有以下全部为 PASS，才能在下一对话宣称修复完成：

- FP-REVIEW-001、002、003 均已修复，并有能覆盖真实失败语义的自动测试；
- 精确目标 Lophine 26.1.2 build 启动并选择 26.1.2 provider；
- 假人超过 keepalive 阈值两倍且 10 分钟仍在线，实体只有约 20 tick/s；
- 真人客户端完成 SIMPLE / AUTO + OpenInv 5.3.3、同区 / 跨区背包矩阵，全程不断线；
- 所有退出和停服路径一次且仅一次，无幽灵玩家、残留连接、任务或未解释 journal；
- Maven、diff、最终 JAR、版本、SHA-256、部署与回滚验证均通过；
- 任何 `NOT RUN` 都必须继续写作 `NOT RUN`，不得折算成 PASS。

## 8. 给下一对话的直接执行指令（历史记录）

```text
请先阅读 LOPHINE_26_1_2_REPAIR_PLAN.md 和
LOPHINE_26_1_2_REMAINING_WORK.md。保留文档中列出的正确改动，依次修复
FP-REVIEW-001、FP-REVIEW-002、FP-REVIEW-003；不要只补单元测试绕过真实
Bukkit/OpenInv/NMS 语义。完成源码修复后执行 JDK 21 全量构建，并按文档的
目标 Lophine、10 分钟、20 tick/s、真人客户端背包和生命周期矩阵逐项给出
PASS / FAIL / NOT RUN 证据。在验收门槛全部 PASS 前不要发布或声称完成。
```

## 9. 本次实施执行记录（2026-09-05）

本节追加记录本次用户要求的修复执行结果；前文的审计结论保留为历史基线。

### 已完成修复

- FP-REVIEW-001：删除基于 Bukkit `InventoryView` 尺寸的错误 shape validator，删除所有打开后通用 `InventoryView#setTitle`；跨区镜像改为创建时使用 36 槽和标题；OpenInv 5.3.3 运行时门禁保留。
- FP-REVIEW-002：新增 Main 所有的幂等 `FakeplayerShutdownCoordinator`，统一停止接单、取消延迟任务、停止并等待自有异步执行器、恢复 journal、清理运行期资源的顺序；部分启用失败不再通过 Guice 懒创建未初始化组件。
- FP-REVIEW-003：新增专用 NMS listener 安装事务和 placement rollback；构造/任一侧字段写入失败会恢复两侧 listener，PlayerList 移除、断开、任务和合成连接资源均走独立回滚路径。
- 另修复 Folia `ScheduledTask` 实现类为 package-private 时取消反射失败的问题；新增假人名称槽位的一次性释放保护。
- 跨区 invsee session 现在绑定目标 `Player` 实例；目标退出时在查看者 scheduler 关闭对应镜像，并处理快照、目标退出和开窗之间的竞态。

### 本次证据

- JDK 21 Maven `clean verify`：PASS；六模块成功，core 29 个测试、v26_1_2 5 个测试均为 0 failure / 0 error / 0 skipped。停服协调器故障注入产生的 SEVERE 日志是测试预期注入，不是测试失败。
- `git diff --check`：PASS（仅有仓库 CRLF 属性提示）。
- 当前候选包：`fakeplayer-modern-dist/target/fakeplayer-0.3.19-folia.4.jar`，SHA-256：`a8e3cf3e374cbd6f781a22a8c6d1400413e891713ed3f3dab0807eb0ed9f7711`；ServiceLoader 同时包含 1.21.11 和 26.1.2 provider，OpenInv 未被打入主包。
- 本机隔离服：Lophine `26.1.2-470-ver/26.1.2@13c5bd7`，API `26.1.2.build.470-stable`；当前 JAR 启动并选择 `v26_1_2.spi.NMSBridgeImpl`，AUTO 选择 OpenInv 5.3.3。
- 当前候选包 build470 短测：在 5 秒 keepalive 隔离阈值下已超过 4 分钟仍在线；10 秒 tick probe 约 20 tick/s。完整 600 秒长测仍为 NOT RUN。
- 当前候选包真实客户端（Mojang 26.1.2、Xvfb，仅作可执行客户端验收）：AUTO 跨区开窗后目标 `/fp kill`，窗口已返回游戏画面，客户端连接未断；服务端各出现一次 fake `lost connection` 与 `left the game`。最新 SIMPLE 测试在 invsee 后出现 Folia watchdog 卡顿，因此完整 SIMPLE 同区/跨区矩阵仍为 NOT RUN，不能把该轮写成完整客户端 PASS。
- 当前候选包 build470 已验证外部 `/kick`、`/kill` 死亡、显式 `/fp kill` 各一次退出；`fp list` 无幽灵记录；正常停服 Hikari、fakeplayer 和 CommandAPI 清理完成。

### 仍必须保留为 NOT RUN

- 计划文字所称精确 Lophine build638：NOT RUN。目标路径实际 JAR 是 build470（SHA-256 `e86d8055ad494f4ce4148969be25dd771b9fc076a401864469e463e6cc9b5b1a`）；本机和 workstation 均未找到 build638 服务端 JAR，不能把 build470 证据外推为 build638。
- 生产服 `/version`、`/plugins`、`/version OpenInv`、配置/JVM 参数、部署和回滚：NOT RUN；未修改生产环境，未提交 Git commit。
- 创建者离线满 10 分钟 grace、低 TPS、跨世界/移动/受击行为及每条持久化 hook 一次且仅一次：NOT RUN；现有代码测试与隔离服短测不能替代这些逐项现场验收。
- workstation 人工 GUI 客户端验收：NOT RUN；本次客户端是官方 26.1.2 客户端在 Xvfb 中运行，不能等同于 Windows 真人桌面会话。
- 受 Folia watchdog 影响的完整 SIMPLE 同区/跨区真人客户端背包矩阵：NOT RUN；需要在无 watchdog 干扰的独立会话中重跑并保存客户端连接与服务端 packet/container 证据。

## 10. 最新完成度复核（2026-09-06）

本节是对当前未提交工作树的最新只读复核，覆盖第 3 节和第 9 节中已经过时的状态描述；前述章节继续保留为历史记录。

### 10.1 结论

总体结论：**FAIL，修复尚未真正完成，不得发布或声称已经完成。**

| 项目 | 最新状态 | 结论与边界 |
| --- | --- | --- |
| FP-REVIEW-001 背包协议根因修复 | PASS（源码与 build470 主要路径） | 打开后通用 retitle 和错误 Bukkit shape validator 已移除；OpenInv 同区、跨区均有合法菜单形状与客户端不断线证据 |
| 完整真人客户端背包矩阵 | **NOT RUN** | SIMPLE 跨区、空包、满包、组件物品、连续开关及全部只读交互模式没有形成完整证据矩阵 |
| 假人 10 分钟存活 | PASS（build470） | 两个样本分别存活约 627 秒、647 秒仍在线；10 秒 tick probe 约 20 tick/s |
| FP-REVIEW-002 停服异步屏障 | **FAIL** | 不响应中断的任务可令 `STOP_ASYNC` 失败，但协调器仍继续执行 `RECOVER_JOURNAL`；本轮已确定性复现 |
| FP-REVIEW-003 NMS placement rollback | **FAIL（完成门槛）** | 抽象 helper 单测 PASS；真实 build470 NMS 故障注入 NOT RUN，且残留状态异常在 `NMSNetworkImpl` 中被记录后吞掉 |
| JDK 21 Maven `clean verify` | PASS | 六模块成功；core 29 tests、v26_1_2 5 tests，0 failure / 0 error / 0 skipped |
| 候选 JAR 完整性 | PASS（非发布件） | ZIP 完整，两个现代 NMS provider 都存在，OpenInv 未被 shade |
| build638、生产服、正式提交、部署与回滚 | **NOT RUN** | 当前运行证据来自 build470；工作树仍未提交，也没有生产部署或回滚验收 |

### 10.2 FP-REVIEW-002：`STOP_ASYNC` 不是 `RECOVER_JOURNAL` 的硬屏障

当前调用链：

- `PluginAsyncExecutor.shutdown()` 在取消 future、对 IO/CPU 两个池调用 `shutdownNow()` 后，依次等待两个池；单个池 5 秒未终止就抛出异常；
- `FakeplayerShutdownCoordinator.shutdown()` 捕获每个阶段动作的所有异常，仅记录日志，然后无条件继续后续 phase；
- 因此 IO/数据库任务忽略中断时，`STOP_ASYNC` 报错后仍会在该任务运行期间同步执行 lifecycle journal 恢复；
- `awaitTermination(io)` 一旦抛错，当前调用也不会继续等待 CPU 池。两个池虽然都收到 `shutdownNow()`，但没有证明它们在 journal 恢复前都已经终止；
- `PluginAsyncExecutor.shutdown()` 的一次性 `shuttingDown` 标记还会令失败后的重复调用直接返回，不能在任务稍后退出时重新确认终止状态。

本轮直接对当前编译 class 注入了一个忽略 `InterruptedException`、由外部 latch 才能释放的 IO 任务。实际结果：

```text
started=true
STOP_ASYNC: fakeplayer async executor did not terminate within 5 seconds
elapsedMs=5556 recoveryRan=true taskStillBlocked=true
```

这证明 journal recovery 开始时插件自有异步任务仍在运行。当前四个协调器测试只覆盖正常 phase 顺序、失败后继续 cleanup、部分启用和“能够响应中断”的任务，不能阻止上述回归。

必须继续完成：

1. 将 `STOP_ASYNC -> RECOVER_JOURNAL` 改成硬依赖：只要任一自有执行器未确认终止，就不得在本次停服中恢复 journal；journal 应原样保留给下一次安全启动恢复；
2. 分别停止并等待 IO、CPU 两个池，收集两个池的结果，不能因等待第一个池失败而跳过第二个池的终止确认；
3. 让重复 shutdown 能返回当前真实终止结果，而不是因为一次性标记直接假定完成；
4. 协调器应区分“禁止继续 journal 的屏障失败”和“可继续执行的独立资源清理”。屏障失败后可继续不读写 journal 的安全 cleanup，但必须明确记录 journal 未恢复；
5. 新增不响应中断的 IO/CPU 自动测试，至少断言：任务存活时 recovery 不运行、两个池都被停止并等待、安全 cleanup 的行为符合设计、任务释放后可以重新确认终止；
6. 在 build470 隔离服以阻塞的数据库/lifecycle continuation 重跑 plugin disable/server stop，证明没有并发 journal 恢复、没有关闭数据源后的数据库访问，也没有未解释残留任务。

### 10.3 FP-REVIEW-003：真实 NMS 回滚仍未闭环

当前 `NmsPlacementRollback` helper 会执行 PlayerList remove、disconnect、remove retry、资源释放和最终 `isClean()` 检查；五个单元测试均通过。但这些测试使用内存布尔状态模拟 listener、注册和连接，不会调用 build470 的真实 `PlayerList`、`Connection`、实体 scheduler 或 synthetic channel。

仍存在两个明确缺口：

1. 测试只覆盖第一次 remove 瞬时失败后第二次成功，没有覆盖 remove、disconnect 或最终 invariant 持续失败；
2. helper 在最终状态不干净时会抛错，但 `NMSNetworkImpl.rollbackFailedPlacement()` 只记录 warning，并吞掉该 cleanup failure。外层继续上抛原始 placement 异常，清理失败既没有作为 suppressed exception 附加，也没有形成可供 manager 决策的 rollback 结果。随后 manager 仍可能删除本地事务、释放名称，而真实 PlayerList/连接残留没有被可靠暴露。

必须继续完成：

1. 让 placement 原始异常与 rollback 异常合并后向上传播；至少将 cleanup failure 附加为 suppressed exception，禁止仅 warning 后吞掉；
2. 返回或保留可判断的最终结果（例如 `CLEAN` / `RESIDUAL`）。存在 native residual 时，不得把 spawn 当作已完整回滚，也不得立即复用对应名称；应保留可恢复/隔离记录并给出明确严重日志；
3. 补充持续 remove 失败、持续 disconnect 失败、资源释放失败、`isClean()` 自身失败和最终 residual 的 helper 测试；
4. 在 build470 真实 NMS 路径增加受控故障注入，至少覆盖 `placeNewPlayer` 后、listener 构造、player/connection 两侧写入、drain task 调度，以及 PlayerList remove/disconnect 持续失败；
5. 每个真实故障点必须验证：UUID 不在 PlayerList、连接/channel 已关闭、无 drain task、无半安装 listener、无 manager 假人记录、名称/配额/生命周期事务状态与 rollback 结果一致；
6. 只有真实 build470 故障注入和最终不变量全部 PASS，FP-REVIEW-003 才能改为 PASS。

### 10.4 已更正的运行与构建证据

- build470 10 分钟存活不再是 NOT RUN：`LongLatest` 从 19:28:51 到 19:39:18 仍在在线列表和假人列表中，约 627 秒；另一个 `LongLife` 样本从 18:23:35 到 18:34:22 仍在线，约 647 秒；9982ms probe 内增长 199 tick，约 20 tick/s。
- OpenInv 同区客户端证据为 `top=54 bottom=43 count=90`、`generic_9x6`；跨区镜像为 `top=36 bottom=43 count=79`、`generic_9x4`。客户端没有协议解码异常或被踢，最终断开原因为服务器主动停止：`Client disconnected with reason: Server closed`。
- SIMPLE 同区已有开窗证据，但 SIMPLE 跨区客户端开窗以及空/满背包、组件物品、连续开关和全部只读交互模式仍不完整，所以完整客户端矩阵继续为 NOT RUN。
- 本轮已重新执行 JDK 21 六模块 Maven `clean verify`：core 29 tests、v26_1_2 5 tests，全部通过；绿色单测不覆盖上面两个失败边界。
- 当前候选包：`fakeplayer-modern-dist/target/fakeplayer-0.3.19-folia.4.jar`，大小 1553542 bytes，SHA-256：`fdfa6c58f6788991c96604bebd4ec62998ef667d054e9667997af2c6fe531b82`。第 3、9 节中的旧 SHA `a8e3cf3e...` 已失效，不得再用于交付。
- `unzip -tq`：PASS；ServiceLoader 同时包含 `v1_21_11.spi.NMSBridgeImpl` 和 `v26_1_2.spi.NMSBridgeImpl`；JAR 中没有 `com/lishid/`，OpenInv 仍是可选外部依赖。
- `git diff --check`：PASS（仅 CRLF 属性提示）；HEAD 仍为 `fd630aa334bbb72a639158af027c9537bec4d1f1`，描述为 `v0.3.19-folia.3-dirty`，所有本轮修复仍未正式提交。

### 10.5 下一对话的执行顺序与完成门槛

1. 先修复 FP-REVIEW-002 的异步硬屏障并补齐不响应中断的 IO/CPU 测试；不要通过缩短超时或只改测试规避真实竞态；
2. 再修复 FP-REVIEW-003 的异常传播、residual/quarantine 语义和真实 build470 NMS 故障注入；
3. 重新执行 JDK 21 六模块 `clean verify`、`git diff --check`、JAR ZIP/provider/OpenInv/shade 检查，并记录新的唯一 SHA-256；
4. 在无 watchdog 干扰的会话完成 SIMPLE/AUTO、同区/跨区、空/满/组件背包、连续开关和全部只读交互矩阵；
5. 补齐创建者离线、lifespan、低 TPS、跨世界、移动/受击、plugin disable/server stop 及每条生命周期 hook 一次且仅一次的验收；
6. 取得实际生产目标 build 的 `/version`、插件版本、配置和 JVM 参数证据。若目标确为 build638，必须在 build638 重跑，不得把 build470 外推；
7. 只有 FP-REVIEW-001/002/003、完整客户端矩阵、生命周期矩阵、目标服、构建、正式提交、部署与回滚全部 PASS 后，才能宣称修复完成。

### 10.6 2026-09-08 本轮剩余代码修复与复核记录

本轮已继续完成剩余的代码级修复和自动化复核；以下结果不能替代真实 Lophine 目标版本、生产服务器或真人客户端验收。

- **FP-REVIEW-002 异步硬屏障：PASS（自动化验证）**。停服协调器现在把 `STOP_ASYNC` 作为 journal 恢复的硬依赖；IO/CPU 执行器分别停止并等待，不能确认全部终止时不会进入 `RECOVER_JOURNAL`，并保留后续可重试的关闭语义。不响应中断的 IO/CPU 故障注入测试已通过。
- **FP-REVIEW-003 rollback helper、异常传播、residual/quarantine：PASS（自动化验证）**。`NmsPlacementRollback` 具备 `CLEAN` / `RESIDUAL` 结果、持续清理和 invariant 检查；rollback 异常会附加到原始 placement 异常；native residual 会形成 sticky 状态并阻止普通 close 路径重复接管，`FakeplayerManager` 会隔离 residual 名称，避免立即复用。相关 helper 和传播测试已通过。
- **`NameManager` 异步 quarantine：PASS**。自定义名称在 residual 异步清理场景下不会被提前释放或复用；测试夹具已补齐默认名称规则，并移除临时调试输出。
- **定向测试：PASS**。`fakeplayer-core` 36 tests、`fakeplayer-v26_1_2` 15 tests 均为 0 failures、0 errors；其余 reactor/module 构建也成功。
- **正式构建：PASS**。执行 `mvn -B -ntp -Drevision=0.3.19-folia.4 -pl fakeplayer-modern-dist -am clean verify`，六个模块全部成功。
- **候选 JAR：PASS（仅构建产物，不代表已发布）**。文件为 `fakeplayer-modern-dist/target/fakeplayer-0.3.19-folia.4.jar`，大小 1560707 bytes，SHA-256 为 `4c3a2230719d0fb568f44dfcf245a3d10e821003233f404a15f515b29659512e`；`unzip -tq` 通过。
- **ServiceLoader / 依赖边界：PASS**。`META-INF/services/io.github.hello09x.fakeplayer.api.spi.NMSBridge` 同时包含 `v1_21_11` 和 `v26_1_2` provider。`NMSNetwork` 没有独立 ServiceLoader 文件是当前契约的预期结果：网络实现由 `NMSBridge#createNetwork(...)` 创建，不应额外添加 `NMSNetwork` provider。JAR 中不存在 `com/lishid/`；OpenInv 为 Maven `provided` + `optional`，`plugin.yml` 使用 `softdepend`，未被 shade 进主 JAR。
- **工作区检查：PASS**。`git diff --check` 通过（仅保留既有 CRLF 属性提示）；工作区仍保留未提交的源码、测试、计划文档及 `logs/`，本轮没有提交、部署或清理任何既有改动。

以下验收仍为 **NOT RUN**：精确 Lophine 26.1.2 build638 运行验证；build470 真实 NMS 故障注入；生产服务器、部署和回滚；真人客户端完整背包与生命周期矩阵。历史 build470 短测证据不得外推为 build638 或生产验收，因此总体发布结论仍不是“已完成”。

### 10.7 2026-09-09 release 构建记录

- 根 POM、构建文档、README 和 CI workflow 已统一使用 `0.3.19-folia.4`；使用 POM 默认版本执行 `clean verify`，六个模块全部成功。
- 最终 release 候选包为 `fakeplayer-modern-dist/target/fakeplayer-0.3.19-folia.4.jar`，大小 1560707 bytes，SHA-256 为 `01c7baac08b1c779e3cdf6db89619c20da2f345901128ad64704b6cc9b3ff1d5`。
- 最终候选包已通过 `unzip -tq`、ServiceLoader provider、`com/lishid/` 排除和 OpenInv 外部依赖边界检查；该文件将作为本次 release 和 workstation 下载副本的同一来源。

### 10.7 本轮复核补充：代码与构建通过，但发布追溯和现场验收仍未闭环

本轮针对“AI 已表示修复完成”的声明做了独立复核，未修改业务源码。

- **自动化构建：PASS。** 使用 JDK 21 和本机 Maven 3.9.11 执行：
  `/tmp/fakeplayer-maven/apache-maven-3.9.11/bin/mvn -B -ntp -Drevision=0.3.19-folia.4 -pl fakeplayer-modern-dist -am clean verify`。六个 reactor 模块均为 `SUCCESS`；`fakeplayer-core` 36 tests、`fakeplayer-v26_1_2` 15 tests 均为 0 failures、0 errors、0 skipped。
- **候选 JAR 结构：PASS（仅本地构建产物）。** 当前文件为 `fakeplayer-modern-dist/target/fakeplayer-0.3.19-folia.4.jar`，1560707 bytes；`unzip -tq` 通过；`META-INF/services/io.github.hello09x.fakeplayer.api.spi.NMSBridge` 同时包含 `v1_21_11` 与 `v26_1_2` provider；JAR 中没有 `com/lishid/`，OpenInv 未被 shade 进主包。
- **构建可复现性：FAIL（交付追溯缺口）。** 在源码不变的情况下连续执行相同的干净构建，JAR SHA-256 从 `f50d0294be3acef178db8b8edd6c7e81f819a1ab717b0521532277ebf5678158` 变为 `f51af2a4d9314fdf022e89e76121ae1c1631b21bdb0712566c5ef44661911b72`；随后再次打包得到 `c526aded26c7208a4d2aeee5bce2723c987be66f008891b1af48818c55ac97c0`。解压后的文件内容一致，差异来自 ZIP/Maven 生成时间等归档元数据，但这意味着不能把某个旧 SHA 当作永久交付标识。正式发布前需要固定归档时间/实现可复现打包，或至少对最终上传的不可变文件重新计算并保存 SHA、构建输入和 commit。
- **构建入口：FAIL（文档复现缺口）。** 文档推荐的 `./mvnw` 当前因 CRLF shebang 直接失败：`/bin/bash: ./mvnw: /bin/sh^M: bad interpreter: No such file or directory`，退出码 126；系统也没有全局 `mvn`。本轮使用绝对路径 Maven 完成验证，未修改 wrapper。发布前应修复 wrapper 行尾/复现说明并在干净环境验证。
- **仓库交付状态：NOT RUN / 未完成。** 当前 `HEAD=fd630aa334bbb72a639158af027c9537bec4d1f1`，`git describe=v0.3.19-folia.3-dirty`；源码、测试、文档和日志仍有未提交改动，没有正式 commit、tag、上传、部署或回滚记录。
- **现场与真人验收：NOT RUN。** 本轮没有取得精确 Lophine 26.1.2 build638 的 `/version`、插件版本、OpenInv 版本、配置、JVM 参数和服务端日志；没有在 build638 做真实 NMS placement 故障注入；没有完成生产服部署/回滚；没有补齐 SIMPLE/AUTO 的同区/跨区、空/满/组件物品、连续开关、全部只读交互，以及创建者离线 grace、lifespan、低 TPS、跨世界、移动/受击、plugin disable/server stop 一次且仅一次的不变量。

**本轮结论：FAIL（不能宣称修复完成）。** 代码级自动化证据可以标为 PASS，但它不能替代目标 build638 和生产环境验收；在上述 NOT RUN 项完成前，原始“打开背包被踢”和“假人自动退出”问题仍不能被判定为已在用户目标环境中修复。
