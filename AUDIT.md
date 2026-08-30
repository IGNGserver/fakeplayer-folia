# FakePlayer 全代码库安全与架构审计

- 审计日期：2026-08-27
- 审计对象：`8ebb4abd62cb68a76bce6ca60920178f0e213b33`（`v0.3.19-folia.2`）
- 审计范围：当前仓库的 22 个 Maven 模块、275 个生产 Java 文件（23,865 行）、资源、构建脚本、测试、发行制品和当前 Git 历史。没有使用仓库外的历史记录，也没有修改业务代码。

## 结论摘要

未发现可以仅靠普通远程玩家、无需既有权限就直接取得服务器任意代码执行的 P0 问题；但当前版本不宜在修复前把“跨区背包编辑”“BungeeCord 跟随下线清理”和“生命周期控制台命令”视为强一致、可安全依赖的能力。

本次确认 4 项 P1、10 项 P2、2 组 P3 问题。最需要优先处理的是：

1. Folia 跨区 `invsee` 用无版本号的整包快照反复覆盖假人实时背包，可恢复已消耗/丢弃物品，也可覆盖后来获得的物品，具备物品复制和丢失风险。
2. BungeeCord `PlayerList` 响应没有请求关联、时效或发送者校验；任意两个可解析的空/缺人响应可立即触发删除，并不真的需要等待代码声称的两个清理周期。
3. `pre-spawn`、`post/after-quit` 控制台命令不是事务：生成失败没有补偿；停服时延迟清理命令无法可靠执行，可能遗留白名单、权限组或其他控制台侧状态。
4. 动作 ticker 抛出异常后仍每 tick 重试并打印完整堆栈；替换/停止动作又会丢弃旧 ticker 的内部状态而不调用其 `stop()`，挖掘状态尤其会残留。

这里的 P1 表示“下一次发布前应完成或先禁用相关入口”，P2 表示“随后一个修复周期内完成”，P3 表示“纳入清理和重构批次”。

## 审计方法与验证边界

本次不是按漏洞清单做字符串扫描，而是从真实入口向下追踪：

`Main` / CommandAPI / Bukkit 事件 / 插件消息 → `FakeplayerManager` → 名称与配额 → SQLite 配置与皮肤 → `Fakeplayer` 登录流水线 → NMS bridge / synthetic network → Folia/Paper 调度器 → 退出与补偿钩子。

同时对配置、权限声明、README、发行仓库、ServiceLoader 制品和 Git 变更历史做了反向核对，以区分仍在使用的兼容逻辑、真正不可达的死代码和“声明仍在但产品入口已断”的遗留链。

验证结果：

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 现代发行版 clean verify | PASS | Maven 3.9.11 等价执行 `mvn -B -ntp -Drevision=0.3.19-folia.2 -pl fakeplayer-modern-dist -am clean verify`；6 个 reactor 模块成功 |
| 单元测试 | PASS，但覆盖不足 | 共 7 项：`SpawnQuota` 4、Bungee 消息解析 2、`NameSource` 1；0 失败、0 跳过 |
| 现代 JAR 完整性 | PASS | `unzip -t` 无错误；SHA-256 `86ffe0eded4e45736857b4cb83116da160156d0686bdc9c48621c9e3e38840df` |
| ServiceLoader | PASS | 现代 JAR 包含 1.21.11 与 26.1.2 两个 bridge provider |
| 遗留全版本发行版 | NOT RUN | `BUILD.md:100-110` 明确要求为所有旧模块预装对应 BuildTools remapped NMS；当前验证环境没有这组运行时制品 |
| Paper/Folia/Bungee 实服行为 | NOT RUN | 仓库没有可启动的服务端测试夹具；本报告对跨区、停服、代理注入路径的结论来自可到达代码流，外部代理是否额外过滤消息需在部署环境复测 |

## 详细发现

### P1-01：跨区背包镜像会用过期的整背包快照覆盖实时状态

**证据链**

- `fakeplayer-core/src/main/java/io/github/hello09x/fakeplayer/core/manager/invsee/AbstractInvseeManager.java:96-128` 在假人所属区域读取一次完整背包，然后在查看者区域创建镜像。
- 点击和拖拽事件在 `AbstractInvseeManager.java:198-226` 触发同步；关闭和退出又在 `AbstractInvseeManager.java:229-247` 再同步一次。
- 每次同步都在 `AbstractInvseeManager.java:148-158` 复制镜像的全部内容，并直接调用 `setContents(contents)` 覆盖假人的全部实时库存。session 只有假人 UUID 和镜像引用（`AbstractInvseeManager.java:250-253`），没有 revision、基线哈希、锁、CAS 或按槽合并。
- 假人打开镜像期间仍可执行丢弃、使用、挖掘、拾取、自动补货等动作；代码没有暂停这些写入者。

**可达影响**

确定性时序为：镜像读到物品 A → 假人在自身区域消耗/丢弃 A → 查看者只改其他槽或直接关闭 → 旧镜像整包回写 → A 被恢复。反向时序会把假人后来拾取的物品覆盖掉。攻击者需要拥有该假人的管理/背包能力，但在生存服、经济服中这仍是直接的物品完整性漏洞。

**修复建议**

短期先把跨区镜像设为只读，或在 Folia 上默认关闭编辑。长期把假人区域作为唯一写入权威：查看者操作转换成带 `baseRevision` 的逐槽命令，在假人区域验证原槽仍等于基线后原子应用；关闭界面不得再无条件整包回写。至少加入“假人同时消耗/拾取 + 查看者编辑/关闭”的跨区域集成测试。

### P1-02：BungeeCord 在线列表没有来源/请求关联，却能驱动破坏性删除

**证据链**

- `Main.java:55-59` 无条件注册 `BungeeCord` incoming channel。
- `WildFakeplayerManager.java:56-87` 对所有同 channel 消息直接解析；传入的 `Player player` 未验证为本次查询的载体、未验证为真实玩家，也没有 pending request、nonce、generation 或 TTL。
- 解析器只检查长度、`PlayerList` 和 `ALL`（`BungeePlayerListParser.java:27-47`），并不提供真实性或新鲜度。
- 正常查询仅每 6000 tick 发送一次（`WildFakeplayerManager.java:143-177`），但接收路径可在任意时间、任意频率调用 `cleanup0`。
- `cleanup0` 对缺失的创建者每收到一次列表就递增；到 2 次后立即逐个调用 `manager.remove`（`WildFakeplayerManager.java:103-137`）。因此两条紧邻的空列表就足够，日志中“离线超过 12000 tick”的说法并不成立。

**可达影响**

过期、乱序、重放的合法代理响应本身就可能误删假人；若客户端、代理插件或同服插件能够向该 incoming channel 注入有效 payload，则可主动触发删除。仓库代码不能证明外部代理一定允许普通客户端伪造，因此不把“任意远程玩家必然可利用”写成既定事实；但产品层没有建立自己的信任边界是确定的。

**修复建议**

删除动作只能消费一个当前 pending 查询的响应：记录查询 generation、选定真实载体 UUID、发送时间和消费状态，拒绝 unsolicited、重复、过期或载体不符的响应。Bungee 内建 `PlayerList` 本身不能携带自定义 nonce 时，应改为代理侧配套的认证自定义 channel，或至少把结果作为软信号并等待本地退出事件/多轮按时间采样后再删除。测试必须覆盖重放、乱序、双响应、假人载体和两个查询交叉返回。

### P1-03：生命周期控制台命令缺少失败补偿，停服清理也不可靠

**证据链**

- 配置明确把 `pre-spawn-commands` 推荐为加入白名单的办法（`fakeplayer-core/src/main/resources/config.yml:159-174`），这些命令以控制台身份执行（`FakeplayerManager.java:533-542,614-633`）。
- 生成流程先把假人提交到注册表，再运行 `pre-spawn`，之后才读 SQLite 配置并进入 NMS 登录（`FakeplayerManager.java:126-157`）。这些后续步骤任一失败都会走 `rollbackSpawn`。
- `rollbackSpawn` 仅移除内存注册、名称、command chain、网络并 kick（`FakeplayerManager.java:680-708`），从不运行补偿命令。因此已成功执行的白名单/权限/外部插件写入永久遗留。
- 正常退出的 `after-quit` 固定延迟 20 tick（`FakeplayerLifecycleListener.java:90-102`）。停服清理由插件自己的 `PluginDisableEvent` listener 间接触发（`FakeplayerListener.java:170-175`），`onDisable` 先 kick/removeAll 后立即清空 command chain（`FakeplayerManager.java:673-677`）；Folia kick 本身还是提交到 entity scheduler 的异步任务（`FakeplayerManager.java:290-300`）。插件禁用过程中，新的延迟任务和实体任务都不能成为可靠的最终化机制。
- `Main.onDisable` 只注销 plugin channel（`Main.java:119-128`），没有直接、可等待的 manager shutdown 顺序。

**可达影响**

一次正常的 DB/NMS/登录插件失败就能留下白名单、权限组、AuthMe 或其他外部状态；停服/热重载时，配置为回收这些状态的 `after-quit` 尤其不会可靠运行。因为执行身份是控制台，这不是普通的提示丢失，而是特权副作用的事务边界错误。

**修复建议**

把生命周期建模为有状态事务（reserved → pre-applied → joined → post-applied → quitting → compensated）。每个已执行的前置 hook 必须有幂等补偿，生成失败时同步/全局执行并等待完成。禁用流程应由 `Main.onDisable` 直接调用一个不可再调度 20-tick 任务的同步 shutdown：先禁止新生成，快照所有记录，执行配置好的 shutdown/补偿 hook，关闭动作和网络，最后清表。不要依靠插件自己的 `PluginDisableEvent` 来启动核心清理。

### P1-04：动作异常形成永久每-tick 重试/日志洪泛，替换动作未停止旧状态

**证据链**

- 新动作直接 `manager.put` 覆盖旧 ticker（`ActionManager.java:83-93`），没有对返回的旧 ticker 调用 `stop()`。
- `/fp stop` 不是停止旧 ticker，而是为每个动作新建一个 `ActionSetting.stop()` ticker 替换旧值（`ActionManager.java:113-123`）。
- 挖掘动作的 `stop()` 依赖旧实例保存的 `current.pos/progress` 才能发送 ABORT 和清除破坏进度（例如 `fakeplayer-v1_21_9/src/main/java/io/github/hello09x/fakeplayer/v1_21_9/action/MineAction.java:115-138`；26.x 同样依赖 `mine.pos`，`ReflectiveAction.java:399-412`）。新建的 stop ticker 没有这些状态。
- ticker 抛出任何 `Throwable` 时，`ActionManager.java:155-165` 只打印完整堆栈，不移除动作、不调用 stop、不退避；该动作的每-tick timer 保持运行。
- `BaseActionTicker.java:38-68` 只有正常完成才会消耗次数/结束；异常不会改变 `remains`。

**可达影响**

一次持续性 NMS 映射错误、插件事件异常或动作边界异常，就能让每个受影响假人每秒约 20 次打印堆栈并占用区域线程；多个假人可放大为 CPU/磁盘拒绝服务。重复设置或停止挖掘还可能留下客户端破坏动画和服务器端交互状态。触发通常需要动作权限，但也可能由版本漂移自然触发。

**修复建议**

用原子 swap：安装新 ticker 前对旧 ticker 调用 `stop()`，失败也必须从 map 移除。`stopOnEntity` 应直接停止并删除原实例，而不是创建替代实例。tick 异常后立即隔离该动作，按“动作类型+异常摘要”限频日志，并向创建者报告一次。增加抛异常 ticker、连续覆盖 MINE、stop MINE 和多假人日志上限测试。

### P2-01：根命令权限破坏最小授权；`selection` 权限是一条已断的产品链

**证据链**

- 根 `/fakeplayer` 节点绑定 `fakeplayer.command.spawn`（`CommandRegistry.java:86-98`），所有子命令还各自绑定独立权限。按 CommandAPI/Brigadier 父节点 requirement 语义，只有 `fakeplayer.tp`、`fakeplayer.action`、`fakeplayer.exp` 或单个子命令权限的玩家仍无法遍历根节点。
- `plugin.yml:116-148` 把 tp、exp、action 声明为可独立授予的权限组；README 也如此宣传（`README_zh.md:97-104`）。实际管理员只能额外授予 spawn 或 `fakeplayer.*` 才能让这些组可用，扩大了权限面。
- `Permission.selection` 常量存在（`Permission.java:9-10`），`plugin.yml:28-29,104-105` 声明并纳入权限组，README 指定 `/fp selection` 应使用它（`README_zh.md:68-70`），但命令实际误用 `Permission.select`（`CommandRegistry.java:105-109`）。全仓精确引用搜索确认 `Permission.selection` 没有调用者。
- README 还列出 `fakeplayer.basic`（`README_zh.md:101-105`），而 `plugin.yml` 和代码完全没有该权限。

**影响**

权限模型的产品契约与运行时不一致，管理员容易为恢复功能而过度授权 spawn 或通配权限；`selection` 和 `basic` 则属于“声明/文档仍在、实际功能入口不存在”的明确遗留链。

**修复建议**

移除根节点的 spawn 权限，根仅负责命令命名空间，每个子命令自行授权；把 `selection` 绑定到 `Permission.selection`；决定是实现 `fakeplayer.basic` 聚合权限还是从文档删除。新增基于非 OP sender 的权限矩阵测试，逐一证明每个组只开放声明的命令。

### P2-02：26.x bridge 对整个主版本乐观放行，启动自检只验证类名

`fakeplayer-v26_1_2/src/main/java/io/github/hello09x/fakeplayer/v26_1_2/spi/NMSBridgeImpl.java:43-67` 对所有 `26.*` 返回 supported，但 `verifyRuntime()` 只加载 11 个类。真实生成随后要求特定 `ServerPlayer` 构造器（`NMSServerImpl.java:38-52`）、`Connection` 字段和协议方法（`NMSNetworkImpl.java:259-302`），动作/网络模块有 56 处 `invokeOptional`、`setFieldIfPresent` 或反射成员查找。类名不变而字段、构造器或签名变化时，插件会通过 enable 自检，直到生成或动作过程中才失败，并与 P1-04 的永久重试组合放大。

1.21.11 provider 也直接委托这套反射实现（`fakeplayer-v1_21_11/src/main/java/io/github/hello09x/fakeplayer/v1_21_11/spi/NMSBridgeImpl.java:16-22,55-61`），只是版本匹配本身是精确的。README 仅声称实测 26.1.2（`README_zh.md:15-20`），代码却把未知未来 26.x 当成已支持。

建议默认只精确放行实测版本；若要兼容一个版本族，enable 时验证所有强制构造器、字段、协议方法并执行不注册玩家的自检。把动作做成能力表，缺能力则禁用单项而不是运行中静默返回 null。

### P2-03：异步工作统一落入 JVM common pool，没有所有权、背压或禁用取消

更新检查、SQLite 查询/写入、UUID 解析、皮肤缓存和 Mojang profile 完成都大量使用无 executor 的 `CompletableFuture.runAsync/supplyAsync/then*Async`，例如：

- `Main.java:88-116`；
- `FakeplayerFeatureManager.java:79-89,143-162`；
- `FakeplayerSkinManager.java:67-97,197-249`；
- `NameManager.java:383-447`；
- `SpawnCommand.java:70-98`。

这些任务共享 JVM `ForkJoinPool.commonPool`，没有插件级队列上限、按创建者速率限制、统一超时或取消 token。`Main.onDisable` 也不等待或取消它们。慢 SQLite/Mojang 请求和大量命令补全/生成请求可相互饥饿；禁用后完成的 future 仍可能尝试向已经禁用的插件调度 Bukkit/Folia 工作，并保留旧 classloader 引用。

建议使用两个插件所有的有界 executor（短 CPU/DB 与有限网络 I/O 分离），设置队列、超时、拒绝策略和每创建者配额；所有 continuation 在回到调度器前检查 plugin generation/enabled token。shutdown 时停止接单、取消 future、限时 drain，再关闭数据源。

### P2-04：`/fp reload` 报成功，但不会重新选择 `invsee` 实现

`ReloadCommand.java:27-33` 只调用 `config.reload()`；`InvseeManager` 则在 Guice 初始化时根据配置创建一次且为 `@Singleton`（`FakeplayerModule.java:34-47`），之后该实例作为 listener 注册（`Main.java:61-69`）。所以修改 `invsee-implement`、安装/移除 OpenInv 后执行 README 推荐的 `/fp reload`（`README_zh.md:39-40`），命令会报告成功，但产品行为直到完整重启都不会变化。

建议区分“可热重载”和“需重启”配置；更好的实现是稳定注册一个 delegating listener，在 reload 时安全关闭 session 并原子替换 delegate。增加 SIMPLE ↔ AUTO reload 测试。

### P2-05：发行版更新检查和官网仍指向已经迁移前的旧产品仓库

当前 Git remote、徽章、下载和 Issue 均为 `IGNGserver/fakeplayer-folia`（`README_zh.md:5-6,35`），但运行时固定查询 `tanyaofei/minecraft-fakeplayer`（`Main.java:88-104`），打包后的 `plugin.yml:7` 也把下载地址指向旧上游。Git blame/`git log -S` 显示这两处从首个 Folia port 提交 `f5c1509` 保留至今，而文档后来已经切换到 fork。

这是一条完整的遗留功能链：检查器、metadata、日志互相仍能工作，但对应的实际发行产品已经迁移。结果是 fork 用户可能错过安全更新、收到不可直接替换的上游版本提示，点击日志地址也到错误制品。

建议把 release owner/repository/website 统一为一个构建属性并在制品测试中断言为当前仓库；若仍要提示上游更新，应作为单独的 upstream advisory，而不是本产品下载地址。

### P2-06：SQLite 没有 schema 版本/迁移或坏行隔离，历史数据可阻断生成

三张表都以 `create table if not exists` 初始化，没有 schema version 或 migration ledger（例如 `FakeplayerProfileRepository.java:48-66`、`UserConfigRepository.java:52-68`）。读取时直接执行 `UUID.fromString`（`FakeplayerProfileRepository.java:39-45`）和 `Feature.valueOf`（`UserConfigRowMapper.java:20-26`）。一条损坏 UUID、或未来删除/重命名 Feature 后留下的历史 row，会让整次查询抛异常；`getFeaturesAsync` 又位于每次生成的关键路径（`FakeplayerManager.java:136-151`），因此单个历史值可以阻断该创建者的所有假人生成。

建议引入递增 schema version、事务迁移和启动前备份；为 enum 存稳定外部 key 而非 Java 名称。读路径对未知 key 隔离并告警，不能让一行破坏整批。增加旧版本数据库、未知 key、非法 UUID 和部分迁移失败测试。

### P2-07：遗留发行版的 synthetic Netty channel 从不真正关闭

`FakeChannel` 被 1.20.1 至 1.21.9 的多个 legacy `FakeConnection` 实际实例化。它拥有静态 `DefaultEventLoop`（`FakeChannel.java:11-14`），但仓库没有 `shutdownGracefully`；`doClose()` 为空，`isActive()` / `isOpen()` 永远 true（`FakeChannel.java:36-38,55-68`）。自定义 pipeline 的 `close()` 只返回成功 promise（`FakeChannelPipeline.java:247-255,280-290`），并未改变 channel 生命周期。

这会让调用者收到“关闭成功”的虚假状态，并可能在热重载/停服后保留 Netty 线程和插件 classloader。现代 1.21.11/26 provider 不调用它，但该类仍被打入现代 JAR；风险真正作用于 full legacy distribution，而该发行版本轮未能构建/实服验证。

建议 legacy adapter 改用可确定关闭的 `EmbeddedChannel` 或实现真实 channel state；event loop 必须有 plugin-owned 生命周期和 `shutdownGracefully`。用线程快照和重复 enable/disable 集成测试验证无残留线程。

### P2-08：`LOOK_AT_NEAREST_ENTITY` 实际只看集合中的第一个实体

`LookAtEntityAction.java:53-68` 把 `distance` 初始化为 0。第一次循环只设置 `nearest`，不设置 distance；以后所有真实距离都非负，不可能满足 `d < 0`，所以永远不会更新 nearest。该功能从生成选项可达（`Fakeplayer.java:169-171`），名称和文档却明确声称“最近实体”。

建议初始化为 `Double.POSITIVE_INFINITY`，或第一次赋值时同时计算距离；使用 squared distance，并加入实体顺序打乱、首项非最近、跨世界保护测试。

### P2-09：构建供应链不可复现，CI 的“安全审计”不能覆盖真实风险

- `pom.xml:43,90-105` 将会被 shade 进发行 JAR 的三个 devtools 依赖固定为可变的 `0.1.7-SNAPSHOT`；Paper API 也为 snapshot（`pom.xml:75-79`）。同一 Git commit 在不同日期可能解析到不同字节。
- 运行时 dependency tree 确认 modern distribution 实际携带 devtools snapshot、Guice 7.0.0、HikariCP 5.1.0 与 SLF4J 1.7.36。
- CI 所谓 Static security audit 只是拒绝五个 API 名称的正则（`.github/workflows/build.yml:26-31`），没有依赖漏洞分析、secret scan、SAST 或制品 SBOM。
- CI 只构建 modern distribution（`build.yml:12-42`）；文档仍声明支持的 1.20.x–1.21.10 full legacy 模块没有持续编译，更没有运行测试。
- 275 个生产 Java 文件只有 3 个测试类/7 个测试，核心的权限、生命周期、invsee、动作、SQLite migration、NMS 和 reload 均为零覆盖。

建议发布依赖全部改为不可变 release 版本并记录校验和；CI 生成 CycloneDX/SPDX SBOM，加入依赖审计和 secret scanning，并按受支持版本矩阵构建。无法持续构建的 legacy 版本应从“支持”降级为 archived/best-effort，而不是继续作为正式产品承诺。

### P2-10：十套手工复制 NMS 实现形成 11,036 行安全修复分叉

1.20.1、1.20.2、1.20.4、1.20.6、1.21、1.21.3、1.21.4、1.21.5、1.21.6、1.21.9 十个完整版本模块合计 11,036 行 Java，类结构高度重复；另有若干版本只做 bridge 委托。这不是单纯的代码体积问题：连接关闭、动作 stop、异常处理或权限相关修复需要手工同步到多套实现，当前 CI 又不构建这些模块，极易产生只修现代版或只修某个旧版的安全分叉。

建议抽出稳定 SPI 模板和共享 action/network 状态机，版本模块只提供显式差异；无法共享的部分用生成器和 golden diff 管理。每个正式支持版本至少做编译、provider 选择、spawn/quit/action smoke matrix。

### P3-01：已确认的死代码、半废弃入口和文档漂移

全仓精确引用搜索与最终 JAR 清单确认：

- `core/util/Skins.java` 没有任何调用者；现行皮肤逻辑已在 `FakeplayerSkinManager` 重复实现，但 `Skins.class` 仍进入现代 JAR。
- `core/constant/InteractionHand.java` 没有调用者；各 NMS 模块使用的是 `net.minecraft.world.InteractionHand`，同名短字符串命中不能算引用；该 class 同样进入现代 JAR。
- `UsedIdRepository.instance`（`UsedIdRepository.java:25-36`）从未被引用，却会在 Guice 构造真正的 injected singleton 前额外构造并读取一次文件。注意：`UsedIdRepository` 的迁移和登录拦截本身仍有调用者，不应整类删除；应只删除静态 singleton 和未使用的 `remove/exists`，待迁移策略另行退役。
- `FakeplayerFeatureManager.getFeature`（`FakeplayerFeatureManager.java:40-50`）没有调用者，现行路径使用批量 `getFeaturesAsync`。
- `default-online-skin` 只在代码中读取（`FakeplayerConfig.java:226`），配置模板与 README 都没有入口，属于隐藏但仍可手工激活的半遗留功能。
- `allow-commands` 仍从配置、命令 requirement、执行器一路可达（`FakeplayerConfig.java:218-244`、`CommandSupports.java:201-203`、`CmdCommand.java:40-57`），因此不是死功能；但代码宣告 0.4 将删除，当前模板和 README 却继续主动推荐，迁移状态自相矛盾。
- README 声称默认无敌（`README_zh.md:145-150`），模板实际为 false（`config.yml:280-288`）；README 指向 `used-uuid.txt`（`README_zh.md:157-159`），代码实际使用 `used-uuids.txt`（`UsedIdRepository.java:66-67,92-100`）。
- Placeholder expansion 版本固定返回 `1.0`（`FakeplayerPlaceholderExpansionImpl.java:40-48`），与插件发行版本永久脱节。

建议先为每个兼容/隐藏功能确定 owner、删除版本和迁移说明，再删除确认不可达的 class/method；README、默认模板、plugin metadata 应从同一结构化源生成或由制品测试交叉断言。

### P3-02：Bungee offline 计数会随历史离线创建者永久增长

`WildFakeplayerManager` 的 `offline` 是进程级 map（`WildFakeplayerManager.java:42-45`）。离线创建者达到阈值并删除全部假人后，其名字不再出现在后续 `group`，也不会出现在 `online` 集合；唯一清理逻辑只删除 online 名字（`WildFakeplayerManager.java:121-137`）。长期有不同创建者离线并被清理时，map 会单调保留历史名字。

修复时在完成该创建者的删除后立即移除计数，并给计数加最后更新时间/TTL；也应在 manager shutdown 时取消 `cleanupTask` 并清表。

## 遗留功能链判定

为避免把“旧”误当成“死”，本次判定如下：

| 链路 | 判定 | 理由 |
| --- | --- | --- |
| `fakeplayer.command.selection` 常量 → plugin.yml → 权限组 → README | **断链/产品不可用** | 实际 command 使用 `Permission.select`，声明权限无消费者 |
| IGNG fork release → update checker → plugin website | **指向已不存在的产品归属** | 产品已迁移到 IGNG，运行时仍查询/链接旧上游 |
| `fakeplayer.basic` → README | **文档幽灵入口** | 代码和 plugin.yml 从未定义 |
| `Skins`、core `InteractionHand`、同步 `getFeature` | **死代码** | 仅有声明，无生产/测试引用；前两者仍被打包 |
| `default-online-skin` | **隐藏/半遗留** | 代码可达，但模板、命令、文档均无入口 |
| `allow-commands` | **仍在使用，不是死代码** | 完整执行链可达，只是删除计划与当前文档冲突 |
| `preparing-commands` / `destroy-commands` | **有意兼容链** | reload 时仍迁移到新 hook 并告警（`FakeplayerConfig.java:246-255`） |
| `UsedIdRepository` | **迁移链仍活跃** | NameManager 和登录拦截仍使用；只有静态 `instance` 与部分方法为死代码 |
| legacy `FakeChannel` | **legacy 活跃、modern 不可达** | 旧版本 FakeConnection 调用；现代 provider 不调用但仍被 shade |

## 已验证的正向控制

以下部分经过代码和构建核对，不应误报为漏洞：

- 仓库内 SQL 均使用 `?` 参数绑定，没有把玩家输入拼接进 SQL。
- 更新请求固定到 GitHub HTTPS，已有 5 秒连接超时、10 秒请求超时和 256 KiB 响应上限（`UpdateChecker.java:17-29,51-75`）；问题是仓库归属和 executor 生命周期，不是任意 URL SSRF。
- Bungee parser 有 32 KiB 上限，畸形消息日志有一分钟限频（`BungeePlayerListParser.java:17-29`、`WildFakeplayerManager.java:89-95`）；问题是有效消息的信任与关联，而不是无界解析。
- `/fp cmd` 有 2048 字符上限、allow-list/权限检查、fake 自身命令权限检查，并阻止非 OP 通过它调用本插件根命令（`CmdCommand.java:24-75`）。该能力仍然高风险，但没有发现绕过这些检查的确定路径。
- spawn quota 在异步名称/DB/NMS 工作前预留，并在结束时释放（`FakeplayerManager.java:98-168,636-661`）；相关 4 个并发测试通过。
- `used-uuids.txt` 保存使用临时文件和原子 rename，包含不支持原子移动时的 fallback（`UsedIdRepository.java:92-127`）。
- ServiceLoader 在选择 bridge 时会跳过 `isSupported=false` 或 `verifyRuntime` 失败的 provider（`FakeplayerModule.java:50-71`）；P2-02 是验证深度不足，而非完全没有 fail-closed 入口。

## 建议修复顺序与验收标准

### 第一批：发布阻断项（P1）

1. 暂时禁止跨区可写 invsee；完成 revision/CAS 或只读设计后再开放。
2. Bungee 清理只消费当前 pending、未过期、未消费的响应；在没有可信代理配套前，不得用单一 plugin message 直接删除。
3. 把 spawn/quit hook 改成可补偿事务，并实现不依赖延迟 scheduler 的同步 shutdown。
4. 动作替换先 stop 旧实例；异常一次即隔离并限频日志。

验收必须在真实 Folia + Bungee 测试环境完成：跨区域并发修改背包无复制/丢失；重放/乱序列表不删除；强制 DB/NMS 生成失败后白名单/权限无残留；停服后 hook 完成；故障 ticker 不再下一 tick 重试。

### 第二批：边界与可维护性（P2）

1. 修正根权限和 `selection`，以非 OP 权限矩阵验收。
2. 收紧 26.x 版本范围并做成员级 capability probe。
3. 引入插件所有的有界 executor 和可等待 shutdown。
4. 让 reload 真正替换 invsee delegate，或明确要求重启。
5. 统一 fork 的更新源、metadata 和下载地址。
6. 引入数据库版本迁移/坏行隔离。
7. 修复 legacy channel 生命周期与 nearest-entity 算法。
8. 固定依赖、生成 SBOM，并让 CI 持续构建所有仍宣称支持的版本。
9. 逐步把十套 NMS 复制实现收敛为共享状态机和版本差异层。

### 第三批：删除与文档收口（P3）

删除确认不可达的类和静态 singleton；明确 `allow-commands`、隐藏皮肤开关及旧配置别名的退役版本；修正文档中的权限、默认值、UUID 文件名和 Placeholder 版本。每次删除前用制品级引用/启动测试确认 legacy distribution 没有隐式反射依赖。

## 最终判断

现代模块目前“能编译、7 个现有单测能过、JAR 结构完整”，但这只证明构建链可用，不证明跨区一致性、代理信任、停服补偿或权限契约正确。当前最危险的不是传统的 SQL 注入或命令执行原语，而是多个控制面把“不带版本的快照”“未关联的代理消息”和“不可等待的异步 hook”当成权威事实。先修复这三类信任/事务边界，再扩展版本支持和清理技术债，才能把 Folia 移植从“路径可跑”提升为“状态不会被错误恢复、删除或遗留”。
