---
course: COMP41400 Multi-Agent Systems
type: video-script
topic: Manufacturing Mayhem — 3–5 minute demo
tags:
  - COMP41400
  - ManufacturingMayhem
  - video
created: 2026-11-21
updated: 2026-11-21
---

# Demo 视频讲解稿（约 4 分钟）

> 配套：[SOP](sop.md) ｜ [Project-ManufacturingMayhem-设计图](Project-ManufacturingMayhem-设计图.md)
> 要求（PDF）：3–5 分钟；必须是**真实运行的系统**，只放 slides 不算；要有旁白或可读字幕；内容包括 organisation + scenario → 一张订单从生产到 deliver → task allocation / 并发 / 资源协调 / 充电决策 → 结尾给一个结果或一个局限。
> 下面的旁白是英文，可以直接念，也可以做成字幕；画面说明是中文。时间是建议值。

## 录制前准备
- `Main.astra` 先设成 `easy-02-two-robots-myrobot.json`，跑一次确认没问题。
- 录屏布局：viewer 占屏幕左边约 2/3，终端 log 放右边（或者用 viewer 底部的 EVENTS 面板）。
- 提前准备好一张设计图（图 1）和一张 evaluation 对比表的截图。
- 每段录完都检查一下：画面上的现象要和旁白说的一致。

## 分镜

| 时间 | 画面 | 旁白（English） |
|---|---|---|
| 0:00–0:30 | 图 1 architecture | "This is my control system for Manufacturing Mayhem. It uses a central coordinator, MyStrategy, and one MyRobot controller per robot. The coordinator decides what is done and by whom; robots decide how to move and when to charge; the CArtAgO artifacts enforce the rules." |
| 0:30–0:50 | 编辑器里的 `Main.astra` 和 config 副本 | "I'm running easy-02: two robots, two access points, one workstation and two packaging points. The only change to the scenario copy is the agent field, which now uses MyRobot." |
| 0:50–1:40 | 启动后，order-1 被 assign 到 pp1；**r1、r2 同时离开 charger** | "Order-1 is assigned to pp1. The pie needs apples and pastry, so the coordinator claims both robots and sends two transfer requests at once: r1 takes apples from ap1, r2 takes pastry from ap2. You can see both robots moving at the same time." |
| 1:40–2:10 | 两个 robot 先后到达 ws1；其中一个等前一个离开 | "Only one robot can stand at ws1. The second robot finds it occupied, waits and retries instead of failing. When both inputs have arrived the barrier opens and the coordinator operates ws1." |
| 2:10–2:50 | ws1 变成 done；一个 robot 把 pie 送往 pp1；同时 sidebar 显示 order-2 被 assign 到 pp2 | "As soon as the pie is ready, a robot carries it to pp1. The coordinator does not wait: it immediately assigns order-2 to pp2 and starts fetching its inputs. That pipelining is why the second order starts about twenty seconds after the first." |
| 2:50–3:10 | pp1 的订单进入 ARCHIVE | "When the last item arrives, the robot's delivered message triggers deliver on pp1. Bringing the item is not enough on its own; delivery is an explicit decision." |
| 3:10–3:35 | robot 回到 charger；log 显示 `energy 0 < 400, charging` | "Robots park at their own charger so they never block a work location. They only charge when energy is below 400, the worst-case cost of one transfer, so each robot charges once every two jobs instead of after every job." |
| 3:35–4:00 | Summary 窗口和 evaluation 对比表 | "All four orders are delivered. Compared with BasicStrategy on the same seed, makespan is [X] versus [Y] seconds and both robots share the work. A limitation: two-stage recipes such as medium-07 are not supported, because the design assumes one workstation per product." |

## 注意
- 方括号里的数字，用实际 Summary 里的值替换，不要编。
- 如果实际运行时第二个 robot 没有出现等待重试的画面，就把这段旁白改成「the retry handles it if…」，或者直接删掉。
- 总时长控制在 3:30–4:30，给剪辑留点余量。
