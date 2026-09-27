# 插件移植 fakeplayer（fakeplayer-folia）· 项目 Agent 规范
> 只写本仓库与设备级规范的差异。Git 纪律、worktree、冲突处理见 `~/.qoder/coder-rules/global-rules.md`。

Collaboration: solo（**移植仓库**：改动必须可反复 rebase 到上游）
Default branch: **master**（不是 main，新任务基线是 `origin/master`）
Integration: direct-after-validation
Release: 按仓库既有发布流程（历史有"release: publish Folia 0.3.19-folia.4"）
Worktree: `~/项目/.wt/fakeplayer/<slug>`

## 这是什么
FakePlayer 的 Folia 适配移植，Maven 构建（`pom.xml`）。

## 移植纪律
- 本地改动保持最小可辨，便于反复 rebase 上游；上游合入与本地功能改动不得混在同一提交。
- 上游版本升级时记录基线版本号；冲突按设备级规范做语义合并，禁止选边。

## 验证命令
- `mvn -q clean test package`（Maven 标准生命周期），另有 `ci.yml` 复核。本机缺 Maven 时报告缺口，不得跳过。

## 现状（必须先处理再开发）
- 工作区有 **348 个未提交文件**，本地还有 3 个分支。Agent 进入本仓库先报告，不得 stash / reset / clean / 切分支；新任务从 `origin/master` 另建 worktree。
- 历史身份混用过 `IGNG` 与两个邮箱（已在全局统一）。新提交走全局身份 + `Assisted-by:` trailer。
