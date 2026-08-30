# AUDIT.md 核验与整改记录

日期：2026-08-30

本记录逐项回到当前工作区源码核验 `AUDIT.md`，再决定是否修改。结论不是按报告文字机械套用：能确认的缺陷已修复；仍需产品决策、部署样本或发布基础设施的边界保留在 `NEEDS_SOL_REVIEW.md`。

## P1

| 项目 | 当前代码核验 | 本次处理 | 未决边界 |
| --- | --- | --- | --- |
| P1-01 跨区 invsee 快照回写 | 确认存在：假人区域快照会被查看者区域整包写回，期间假人仍可消费、拾取和丢弃 | `AbstractInvseeManager` 的跨区镜像改为只读；点击/拖拽在 HIGHEST 阶段取消，关闭和退出不再回写 | 若要恢复编辑，必须设计假人区域权威、基线版本和逐槽 CAS；见 `NEEDS_SOL_REVIEW.md` |
| P1-02 Bungee PlayerList 未关联响应 | 确认存在：BungeeCord 内建响应无 nonce/签名，仅加 pending 和 TTL 仍无法证明响应归属 | 删除入站 `BungeeCord`/`PlayerList` 注册、解析器和破坏性调用链；BungeeCord 模式的 `follow-quiting` 安全失效为禁用，本地非代理模式仍用权威在线集合和单调宽限清理 | 若未来要恢复跨服跟随，必须另配 nonce/HMAC 代理伴生端；当前安全策略不再是发布阻断 |
| P1-03 生命周期命令和停服清理 | 确认原修复只回收本地资源，无法补偿已执行的 pre-spawn 外部命令；join hook 早于整个 spawn future 提交；禁用时又依赖插件调度任务 | 新增 SQLite 写前日志和逐条 checkpoint；pre-spawn 强制同序号幂等补偿，配对不全拒绝启动；生成任一阶段失败逆序补偿，正常退出释放前置状态，禁用/下次启动同步恢复未完成 finalizer；post/after-spawn 移到 login/teleport 全部成功后；禁用时直接 kick，Folia 由服务端 Connection 队列完成跨线程断开 | 外部命令无法做 exactly-once；配置明确要求 rollback/post-quit/after-quit 都幂等。源码阻断已解除，仍需实服断电恢复验收 |
| P1-04 Action ticker 替换/异常 | 确认存在：旧实现会覆盖 ticker 而不 stop，异常会在每 tick 重试并刷堆栈 | 替换和 stop 先从状态表原子摘除再 stop；ticker 异常立即隔离，按动作/异常类型 60 秒限频；持有并取消 reaper，停服清空状态 | 未做真实 NMS 动作故障注入；需在 Paper/Folia 实服验收 |

## P2

| 项目 | 当前代码核验 | 本次处理 | 未决边界 |
| --- | --- | --- | --- |
| P2-01 权限树/selection | 确认存在：根节点继承 spawn，`selection` 使用了 `select` 权限；README 有不存在的 `fakeplayer.basic` | 根节点去掉 spawn gate，`selection` 改用 `Permission.selection`，删除文档幽灵权限 | 需要非 OP + 权限插件矩阵实测 |
| P2-02 26.x bridge 探测过宽 | 确认存在：原 `isSupported` 接受整个 `26.*`，启动校验只有类名 | 收紧到 `26.1.2`，并校验构造器、方法、字段及关键 NMS 类 | 当前环境只能编译/静态验证，真实 26.1.2 Paper/Folia 启动仍需部署验证 |
| P2-03 common pool 与插件生命周期 | 确认存在：生产代码有无 executor 的 `CompletableFuture.*Async` | 增加插件自有有界 IO/CPU executor、命名线程、拒绝策略；数据库/网络/转换阶段均经可跟踪的提交 API，不再向 `CompletableFuture` 暴露裸 executor；停服取消 pending future 并 shutdown | Bukkit/Folia API 调用仍必须走既有 scheduler；需实服检查 shutdown 时序 |
| P2-04 reload 不切换 invsee 实现 | 确认存在：实现由 Guice singleton 在启动时选择，reload 只重读配置 | `/fp reload` 明确提示 `invsee-implement` 和 OpenInv 变更需要重启，避免伪称热切换 | 动态替换 delegate 属于架构改动，本次不贸然引入 |
| P2-05 更新源和 metadata | 确认存在：updater 与 `plugin.yml` 指向旧归属 | updater 和 plugin website 统一到 `IGNGserver/fakeplayer-folia`，README 下载地址已是该归属 | 其他历史链接是否仍需保留，待发布策略确认 |
| P2-06 数据库坏行/迁移 | 确认存在：UUID 和 `Feature.valueOf` 的坏值可中断查询；现有表初始化没有版本迁移 | skin/user_config 批量读取隔离坏行；profile 坏 UUID 保留为显式错误，普通自动命名会隔离该序号并继续，不再将坏行误当“不存在”后撞唯一约束；不自动删除数据 | 指定到坏名称仍会失败；schema version、迁移/修复/回滚策略需要部署数据和数据库 API 评审 |
| P2-07 legacy FakeChannel 生命周期 | 确认存在：静态 EventLoop、空 close、`isOpen/isActive` 恒真，pipeline close 不代表 channel close | channel 共享引用计数 EventLoop，最后一个 channel 关闭时 shutdown 并允许下次加载重建；close 幂等更新状态，pipeline close 委托 channel；全部活跃 legacy network 接入 close/isConnected | 旧版本全矩阵需用对应 remapped NMS 产物构建和实服测试 |
| P2-08 nearest entity 距离 | 确认存在：distance 初值 0，首次实体会被无条件选中 | 使用正无穷、同世界过滤和平方距离 | 需在实际实体场景做行为烟测 |
| P2-09 依赖/CI/测试不足 | 确认存在：现代 workflow 可构建但旧 NMS 需外部 BuildTools 产物，快照依赖和正则审计不能代替依赖治理 | 未引入未经批准的依赖升级或伪安全扫描；现有现代构建和产物检查保留 | SBOM、漏洞门禁、快照固定和全版本 CI 需要发布/基础设施决策，见 `NEEDS_SOL_REVIEW.md` |
| P2-10 十套 NMS 重复实现 | 确认存在：各版本 adapter 有明显复制 | 未做大规模合并，避免一次重构破坏版本兼容 | 需要支持版本政策和分阶段 adapter 设计，见 `NEEDS_SOL_REVIEW.md` |

## P3

| 项目 | 当前代码核验 | 本次处理 |
| --- | --- | --- |
| P3-01 遗留、隐藏开关和文档不一致 | `Skins`、core `InteractionHand`、`getFeature`、`UsedIdRepository.instance/exists/remove` 确认为无引用；`default-online-skin` 和 `allow-commands` 仍有真实调用链，不能当死代码删除 | 删除确认无引用的类/方法；把 `default-online-skin` 放入模板；保留 `allow-commands` 兼容执行但标为弃用；Placeholder 版本改为插件版本；修正无敌默认值和 `used-uuids.txt` 文档 |
| P3-02 offline map 泄漏 | 确认存在：历史离线创建者计数会留在进程 map | 没有活跃假人或创建者已在线时清除过期计数；达到清理阈值后也立即移除；停服取消 cleanup task 并清空状态 |

## 报告中需要特别保留的判定

- `allow-commands` 不是死功能：`FakeplayerConfig`、`CommandSupports`、`CmdCommand` 仍构成可达链路，本次没有迎合“遗留”标签删除它。
- legacy `FakeChannel` 不是全仓库死代码：现代 provider 不使用它，但旧版本 `FakeConnection` 仍使用，因此只修生命周期，不删除类。
- `default-online-skin` 不是不可达代码：它原本是隐藏/半遗留开关，本次把它纳入模板，而不是删除行为。
- SQL 参数绑定、更新请求的 URL/超时/大小限制、`/fp cmd` 的现有权限和白名单、spawn quota、UUID 原子保存、ServiceLoader fail-closed 均复核未发现本报告所称缺陷，未做无谓改动。

## 验证记录

最终验证以本次任务结束时的命令输出为准：

- core 测试：PASS，21 项，0 失败、0 错误、0 跳过，覆盖 FakeChannel 共享/释放、PluginAsyncExecutor IO/CPU/停服拒绝、SpawnQuota、生命周期命令配对/日志编码/进度不变式、NameSource。
- 生命周期日志 SQL：PASS，用内存 SQLite 执行建表、写入、`SPAWNING → ACTIVE → QUITTING → ROLLING_BACK` checkpoint 和删除，最终无残留行。
- 现代构建：PASS，`git show HEAD:mvnw | sh -s -- -B -ntp -Drevision=0.3.19-folia.2 -pl fakeplayer-modern-dist -am clean verify`，6 个 reactor 模块成功。
- 现代 JAR：PASS，`unzip -tq`；`META-INF/services/io.github.hello09x.fakeplayer.api.spi.NMSBridge` 含 1.21.11/26.1.2 provider；生命周期日志类和 config v20 已打包，Bungee parser 不在产物中；metadata/config 检查通过；最终复核产物 SHA-256 为 `24290f72699cee74e1f29504b978264511508840f8ecedf3e92e3bfce056bfd8`（构建未声明可复现，不能把该值当作源码固定校验和）。
- 26.1.2 NMS 签名核对：PASS，使用本地匹配版本的 Folia server JAR 只读检查了 bridge 所需的类、构造器、方法和字段；未启动服务端，不能替代实服 smoke test。
- legacy 编译：NOT PASS，已尝试 `-pl fakeplayer-dist -am test`，在 `fakeplayer-v1_20_1` 因缺少 `org.spigotmc:spigot:1.20.1-R0.1-SNAPSHOT:remapped-mojang` 制品停止；不是本次修改导致的 Java 编译错误。
- 未宣称通过 Paper/Folia/Bungee 实服、断电日志恢复、客户端 UI、非 OP 权限矩阵和跨区背包竞争；这些边界见 `NEEDS_SOL_REVIEW.md`。

## 最终审查结论

本轮**代码整改可以结束**。P1-01/P1-04 的直接缺陷已修复；P1-02 已通过移除未认证破坏性入口安全关闭；P1-03 已建立明示的幂等补偿、持久化 checkpoint、失败逆序回滚和启停恢复契约，不再只回收本地状态。当前未发现新的源码发布阻断。legacy 依赖制品、真实 Paper/Folia/Bungee 和断电恢复仍是发布验收边界，不能用本次构建 PASS 代替。
