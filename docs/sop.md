---
course: COMP41400 Multi-Agent Systems
week: 10-12
type: practical
topic: Manufacturing Mayhem — ASTRA + CArtAgO multi-agent control system（从零开始）
source: "COMP41400 Manufacturing Mayhem Project.pdf"
tags:
  - COMP41400
  - ASTRA
  - CArtAgO
  - ManufacturingMayhem
  - coordination
  - project
created: 2026-11-21
updated: 2026-11-21
---

# Project：Manufacturing Mayhem（从零开始 SOP）

> 来源：`COMP41400 Manufacturing Mayhem Project.pdf`（12 页，Deadline 2026-11-29）｜ 官方仓库：`gitlab.com/astra-language/examples/cartago/manufacturing-mayhem`（2026-11-21 的 `main` 是 `c77c249`，ASTRA 2.7.12）
> 配套笔记：[Project-ManufacturingMayhem-参考代码](参考代码.md) ｜ [Project-ManufacturingMayhem-设计图](设计图.md) ｜ [Project-ManufacturingMayhem-报告草稿](报告草稿.md) ｜ [Project-ManufacturingMayhem-Demo讲解稿](讲解稿.md) ｜ `COMP40040-Project-ManufacturingMayhem-解读`（Obsidian 笔记）
> 相关课程：`Week7-Coordination-CNP与ASTRA实现-逐页讲解`（Obsidian 笔记） ｜ `Week9-Organisations-知识点`（Obsidian 笔记） ｜ `Lab4-VacWorld-SOP`（Obsidian 笔记）
> ⚠️ 预期结果有两类来源：按 PDF 的 timing 规则手推；或者来自参考代码在临时副本里的一次 easy-02 **短跑**（smoke test，没跑到 Summary）。其余步骤没有实际运行。

> ⚠️ 参考代码.md 里的完整代码是修订前的版本（没有 task id，充电固定 400）。实现时以本 SOP 和设计图为准，参考代码只看结构。

**主线**：在官方搭好的 CArtAgO 工厂里，只写 agent（一个 coordinator 加上每个 robot 一个 controller），协调多个 robot 完成「access point 取原料 → workstation 生产 → packaging point → deliver」。评分重点是 **organisation 和 coordination 的设计是否合理、实现和 evaluation 证据是否对得上**；速度只是辅助证据。

**运行**（在项目根目录执行；改了 `.astra` 或 config 之后必须重新 compile）：
```bash
mvn compile astra:deploy
```

**时间线**：week 10 交 design report（40%）→ week 12 交 implementation（40%）+ reflection 和 video（20%）。

---

## Part 0 — 拿到项目，跑通 baseline
**SOP**
1. 从官方仓库 clone，或使用课程 fork（`gitlab.com/ucd-cs-rem/comp41400-2026/manufacturing-mayhem`）。记下起始 commit，README 里要写。
2. 装好 Java 17+ 和 Maven 3，在根目录（有 `pom.xml` 和 `config/` 的那一层）执行 `mvn compile astra:deploy`。
3. 默认配置是 `config/factory.json` + `BasicStrategy`。把 `Main.astra` 里的配置改成 `easy-01-single-line.json` 再跑一次。
4. 读 `src/main/astra/core/Base.astra`，弄清楚每个 `!xxx` / `?xxx` 包装的是哪个 artifact operation。
5. 用 `git commit` 建一个 baseline 点，之后每个阶段各 commit 一次。

**预期结果**：viewer 打开，r1 沿 ap1 → ws1 → pp1 跑完 apple-pie 订单，然后弹出 Simulation Summary，同时生成 `results/run-<time>/events.csv`。

**考察知识点**
- agent 负责决策；artifact 负责执行规则（operation、observable property、linked operation）
- `core.Factory` agent 按 JSON 建好环境，它和 Factory artifact 不是一回事
- evaluation run 期间不能补 stock，也不能改 recipe

## Part 1 — 先做设计（week 10 report）
**SOP**
1. 选 organisation：**central coordinator**（一个 `MyStrategy` 加上 N 个 `MyRobot`）。理由：环境状态集中在 artifact 里，coordinator 能看到全局，task allocation 简单，也能避免重复分配。PDF 明确说 central coordinator 是合法选择，只要说清楚 trade-off。
2. 确定各角色的职责边界：coordinator 管「做什么、派给谁」；MyRobot 管「怎么走、什么时候充电」；artifact 管「什么操作合法」。
3. 确定通信协议：`request transfer(id, from, to)` → `inform delivered(id)`（已卸货）→ `inform transferred(id)`（已回到停车位，可以接新任务）；失败时回 `failure transfer(id, from, to)`。`id` 是 coordinator 生成的唯一 task id（PDF 要求并发设计要用 robot + task id 来配对请求和回复）。
4. 画架构图和 sequence diagram → 见 [Project-ManufacturingMayhem-设计图](设计图.md)。
5. 写约 1000 词的报告 → 见 [Project-ManufacturingMayhem-报告草稿](报告草稿.md)，至少讨论一个 alternative（比如 Contract Net 或 decentralised robots）。

**预期结果**：一份 report，覆盖 PDF 要求的 5 节：organisation、coordination、operational decisions、worked example、rationale 和 evaluation plan。

**考察知识点**
- organisational structure（hierarchy、team、market 之类）以及各自的 trade-off
- 区分哪些职责属于 agent、哪些属于 environment
- 可以评估的设计：要写清楚用哪些 scenario、看哪些 metric

## Part 2 — 连接环境（MyStrategy 骨架）
**SOP**
1. 新建 `src/main/astra/MyStrategy.astra`，写 `agent MyStrategy extends core.Base`。
2. `+!start()` 里的顺序：`cartago.link()` → `!floorplan` / `!warehouse` / `!orders` → `!loadConfig()`。
3. `!loadConfig()`：对 `factory` artifact 执行 `observeProperty`，读出 `robots`、`accessPoints`、`workstations`、`packagingPoints`、`storagePoints`，存成 `configList(name, list)` 形式的 belief。**不要写死 r1、ws1 这些名字。**
4. 对每个名字调用 `!robot` / `!accessPoint` / `!packagingPoint` / `!workstation` 拿到 artifact 引用。读出每个 workstation 的 `output` property，存成 `makes(ws, product)`。
5. 改 `Main.astra` 两处：`configure("config/easy-01-single-line.json")`，以及 `S.createAgent("strategy", "MyStrategy")`。

**预期结果**：console 打印出 `[S] robots = [...] aps = [...] ...` 和 `[S] ws1 makes apple-pie`。

**考察知识点**
- `!` 是 achievement goal，`?` 是 query plan
- plan 的 guard 里绑定的变量**不会**回传给 `!goal` 的输出参数；需要输出结果时用 `?query` plan
- ASTRA 按 variable name 的 Java hash 识别变量，避开 hash 相同的名字（如 `ppId` 和 `r2Id`）；`done` 是 keyword，不能当 belief 名

## Part 3 — Robot controller（MyRobot）
**SOP**
1. 把 supplied `Robot.astra` **复制**成 `MyRobot.astra`，原文件不改。
2. 处理 `request transfer(id, from, to)`：`!go(from)` → `!holding` → `!go(to)` → `!empty` → 回 `delivered(id)` → `!go(<name>-charger)` → `!topUp()` → 回 `transferred(id)`。整段（包括回 charger 和充电）都包在 `try/recover` 里，失败时回 `failure transfer(id, from, to)`。失败后 robot 停在原地，手里的 item 不会恢复，这一点要写进 limitations。
3. `!go(loc)`：调用 `!at` 失败时，`sleep` 250 ms 后重试，最多 40 次。原因是 `?free` 只检查当前状态，不做 reservation。三段 move 都走 `!go`；最常冲突的是**去 ws 的那一段**（两个 robot 同时送料）。
4. `!topUp()`：电量低于 **reserve** 才充满电。reserve 分两种情况：
   - 先 `cartago.lookupArtifact("factory", cartago.ArtifactId fid)`（`core.Base` 没有封装 `!factory`），再   - 启动时用 `cartago.operation(fid, options(boolean td, boolean oa, int io, long mi, boolean bd, double p, long rt))` 读出 `travelDistance`（写法和 `core/Factory.astra` 第 68 行一样）。
   - **td 关闭**（easy-01/02/04）：reserve = 400，也就是从 charger 出发一次 transfer 的最坏消耗（3 次 move × 100 + load/unload 2 × 50，load 和 unload 开始前都要求电量 ≥ 100）。
   - **td 开启**（easy-03、全部 medium、hard）：move 耗电 = 25 × 距离，但 agent **查不到距离**（Floorplan 只提供 `isOccupied`）。所以改为实测：每趟记录「出发电量 − 回到 charger 时的电量」，reserve = min(历史最大值 + 50, maxEnergy)。还没测过任何一趟时，每次都充满再出发。**风险**：如果第一趟恰好是短途（比如耗电 350，reserve 变成 400），剩 450 就会跳过充电；下一趟如果是长途，就可能半路没电。这一点要写进 limitations，也是 reflection 的好素材；可选的缓解办法是让 coordinator 在 request 里附上任务类型（ap→ws 还是 ws→pp），robot 按类型分别记录实测值。
5. 复制一份带说明的 config：
   ```bash
   sed 's/"agent": "Robot"/"agent": "MyRobot"/' config/easy-02-two-robots.json > config/easy-02-two-robots-myrobot.json
   ```
   只改 `agent` 字段，其余资源、stock、seed、options 都保持原样，并在 README 里 disclose。

**预期结果**（travelDistance 关闭时）：一次 transfer 耗时 3 × 4 s + 2 × 2 s = **16 s**，耗电 $3\times100+2\times50=400$。800 电量的 robot 第一趟后剩 400，不充电；第二趟后剩 0，充电。短跑里看到的正是 `energy 0 < 400, charging`。td 开启时，第一趟一定是满电出发，之后每次在 log 里打印出 reserve 的值。

**考察知识点**
- 每个 robot 只受一个 controller 控制（PDF 明确要求）
- 用局部决策处理 location 冲突（等待后重试）
- charging policy 要基于下一趟任务的实际消耗，不能用没解释过的固定阈值

## Part 4 — 先跑通一张订单（串行版）
**SOP**
1. `!assign(pp, string orderId)` → `?outstanding(pp, list needed)`。
2. 对 `needed` 里的每个 item：`?makes(string ws, item)` → 拿到 `?inputsNeeded(ws, list missing)` → 每个 input 执行 `!available(ap, item)`，然后派一个 robot 做 `ap → ws` → `!operate(ws)` → 再派一个 robot 做 `ws → pp`。
3. 最后一件送到之后，**显式调用** `!deliver(pp)`。
4. 先在 easy-01 上跑通，commit 一次，作为「v1 串行版」。这一版也是 evaluation 时的对照组。

**预期结果**：订单状态依次是 ASSIGNED → COMPLETED。串行版在 easy-02 上大约 **56 s 一单**，而且两个 robot 是轮流干活。

**考察知识点**
- production dependency（inputs → output）
- workstation 状态机：idle → ready → active → done → idle/ready
- 一个 packaging point 同一时间只处理一个 order

## Part 5 — 并行与 pipelining（核心 coordination）
**SOP**
1. **robot 分配**：`?freeRobot(string r)` 找第一个不在 `busy(r)` 里的 robot，找不到就 `sleep` 后重试。选中后先 `+busy(r)`，再 `send`；收到 `transferred(id)` **或 `failure`** 时都要 `-busy(sender)`，并删除 `task(id, …)`，否则 robot 失败一次就会永远处于 busy。注意：robot 要回到 charger 才会被释放。在 easy-02 里，先送到 ws1 的 robot 大约 16 s 就回来了，早于 operate 完成（≈20 s），所以派送成品时不用等；只有所有 robot 都还在忙时，`?freeRobot` 才会等待（见设计图 图 2）。另外，`Floorplan.move` 出发时就会占住目标位置，所以两个 robot 同时去 ws1 时，后出发的那个会一直重试到前一个离开。
2. **并行取料**：对每个缺的 input，各分一个不同的 access point（用 `usedAp(ap)` 标记）和一个 robot，所有请求一起发出。然后做 barrier：记下这一批发出的 task id，对每个 id 执行 `wait(arrived(id) | failedT(id))`，完成后删除对应标记（`delivered(id)` handler 里 `+arrived(id)`，failure handler 里 `+failedT(id)`）。
3. **跨订单 pipelining**：每个 packaging point 用 `busyPP(pp)` 标记是否被占用。`ws → pp` 的 transfer **不等它完成**，主循环直接接下一单。在 `delivered` 的 message handler 里，如果 `outstanding` 已经为空，就调用 `!deliver(pp)`，然后 `-busyPP(pp)`。
4. `!operate` 之前加 `!waitReady(ws)`，因为上一件产品可能还没被取走。
5. **task id**：用一个计数器 belief 生成 `t1, t2, …`，发请求时记下 `task(id, robot, from, to)`，回复按 id 处理，处理完删除。不要只靠 `(from, to)` 配对：同一张 2 件的订单（如 easy-02 的 order-4 = 2 × apple-pie）会连续发出两次 `ws1 → pp1`，key 相同；失败重试后，同一路径的旧标记也会残留。

**预期结果**（参考代码短跑，easy-02）：log 里 `r1: ap1 -> ws1` 和 `r2: ap2 -> ws1` 同时发出；pp1 和 pp2 交替接单；4 张订单的 ASSIGNED 时间点分别是 0 s / 20 s / 53 s / 83 s（order-4 是 2 × apple-pie），比串行版快很多。

**考察知识点**
- task allocation 和 mutual exclusion（`busy` / `busyPP` / `usedAp`）
- 用 barrier 同步、用 event-driven handler 推进流程
- 什么情况下 reply correlation 是安全的

## Part 6 — 订单流与终止条件（easy-04）
**SOP**
1. 主循环条件：`outstandingOrders + ordersToArrive + assignedOrders > 0`。
2. 队列为 0 但还有订单没到时，`sleep` 500 ms 再查（polling）。可选的改进是监听 Orders artifact 的 `newOrder` signal。
3. 切换到 `easy-04-steady-arrivals.json`（同样复制一份 myrobot config）。

**预期结果**：新订单到达后会被自动接走，最后才打印 `all orders delivered`，不会提前退出。

**考察知识点**
- polling 和 event-driven 两种方式的对比
- 正确的 termination 条件

## Part 7 — 选一个 medium scenario
**SOP**
1. **注意：所有 medium 都开启了 travelDistance**，所以 Part 3 的两种 reserve 必须都实现，固定 400 在 medium 里会让 robot 半路没电。
2. 推荐选 **medium-09-tight-energy**（`maxEnergy` 650、`chargeRate` 75、travelDistance 开启）：专门用来检验实测 reserve 的效果。预计 reserve 会接近 maxEnergy，也就是几乎每趟都充满；在报告里把这个现象当作证据来解释。备选是 medium-06-bottleneck-oven（检验 `waitReady` 和 pipelining，同样需要实测 reserve）。
3. 不支持的 scenario 如实写进 limitations：medium-07（两级 recipe，需要 storage buffer）、hard-11/12（breakdowns、stock 不足）。medium-08 的三个 product 都是单级 recipe（ws1 apple-pie、ws2 cake、ws3 bread，各 3 个 ap/robot/pp），靠 `makes(ws, product)` **理论上能跑通，但没测过**；它的局限是同一时间只推进一张订单，不会让多台 ws 同时生产（medium-05 也受这个影响）。

**预期结果**：选定的 medium scenario 能跑完并生成 Summary；跑不完也要记录卡在哪里。

**考察知识点**
- 在 scenario 设定的约束下检验设计
- 诚实报告设计的适用范围

## Part 8 — Evaluation 运行与记录
**SOP**
1. 对每个必做 scenario（easy-02、easy-04、所选 medium），分别用 `BasicStrategy`、v1 串行版、v2 并行版各跑一次，**scenario 和 seed 都相同**。
2. 记录：scenario 文件、seed、strategy 版本（git commit）、options、controller 有没有替换。
3. Summary 弹出后点 **Save as JSON / CSV**，存进 `results/`。没跑完的 run 要记录 elapsed time、完成数和卡住的原因。
4. 做一张对比表：makespan、completed、idle time、hand-off delay、load balance、charging、failed operations。

**预期结果**：一张 3 scenario × 2–3 strategy 的表，每个数字都能对应到一份保存下来的 Summary 文件。

**考察知识点**
- 用 metrics 解释 coordination trade-off
- reproducibility

## Part 9 — 提交包、Reflection、Video
**SOP**
1. **README**：prerequisites、build/run 命令、怎么选 strategy 和 scenario、哪些代码是自己写的或改的（`MyStrategy`、`MyRobot`、`*-myrobot.json`、`Main.astra` 那两行）、known limitations、evaluation 摘要。
2. **Reflection 500–750 词**：遇到的挑战（guard 绑定、`done` keyword、location 冲突）、实现和 week 10 设计的差异、evaluation 显示的优缺点、**LLM 的使用方式和你怎么核对它的输出**。
3. **Video 3–5 分钟** → 讲解稿见 [Project-ManufacturingMayhem-Demo讲解稿](讲解稿.md)。

**预期结果**：PDF week 12 checklist 上的每一项都有对应文件。

**考察知识点**
- 设计、实现、evaluation 三者是否一致
- critical reflection 和 LLM 使用的透明度

---

## 易错点

| 错误 | 后果 / 解决 |
|---|---|
| 改了 `.astra` 只执行 `astra:deploy` | 跑的还是旧代码，要执行 `mvn compile astra:deploy` |
| `+!take(string r) : idle(r)` 想靠 guard 带回 r | `Event was not matched to rule`，改用 `?query` plan |
| 用 `done` 当 belief 名 | compile 报 `Identifier expected`，`done` 是 keyword |
| 每次 transfer 都等回复才派下一个 | 多个 robot 只会轮流干活，在 `events.csv` 里看 move 时间段有没有重叠 |
| 把 `?free` 当成 reservation | 两个 robot 抢同一个 location，后到的那个 `!at` 失败，要加等待重试 |
| robot 送完货停在原地 | 挡住 ws/pp，送完要回 charger 停车 |
| 送到最后一件就以为订单完成 | 必须显式调用 `!deliver(pp)` |
| 只看 `outstandingOrders == 0` 就结束 | easy-04 会提前退出，还要加上 `ordersToArrive` 和 `assignedOrders` |
| 原地改 supplied `Robot.astra` 或 scenario | 和 baseline 比就不公平了，改成复制 controller 和 config，并 disclose |
| 在 travelDistance 开启的 scenario 里用固定 400 做充电阈值 | robot 半路没电，操作失败；按 Part 3 改用实测 reserve |
| 回复只按 `(from, to)` 配对 | 2 件订单的两次 `ws1 → pp1` key 相同，失败标记也会残留；改用 task id |
| 新的 `transfer(id, from, to)`、`delivered(id)`、`transferred(id)` 没有在 `types` 里声明 | `core.Base` 只声明了 2 参的 `transfer`/`transferred`，新签名要在 MyStrategy 和 MyRobot **各自**的 `types` 块里声明。同名不同参数个数能否编译没验证过；如果报错，就改成新名字（如 `job(id, from, to)`、`finished(id)`），并同步改图和报告 |
| 跑 BasicStrategy 基线时 `Robot.astra` 已经被改过 | 基线就不干净了。先 `git diff` 确认 `Robot.astra` 和官方一致，对比表里注明每组用的是 `Robot` 还是 `MyRobot` |
| robot 的 failure 被吞掉、不回消息 | coordinator 永远卡在 `wait`，`recover` 里一定要回 `failure` |
| 视频只放 slides | 不算 implementation 演示 |
