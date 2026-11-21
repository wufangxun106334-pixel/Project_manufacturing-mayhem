---
course: COMP41400 Multi-Agent Systems
type: report-draft
topic: Manufacturing Mayhem — design report (week 10)
tags:
  - COMP41400
  - ManufacturingMayhem
  - report
created: 2026-11-21
updated: 2026-11-21
---

# Design report 草稿

> 配套：[Project-ManufacturingMayhem-设计图](Project-ManufacturingMayhem-设计图.md)（图 1、图 2 可直接放进报告）｜ [SOP](sop.md)
> 正文约 1000 词（不含标题和表格）。⚠️ 提交前请改成自己的表述，填好 `[ ]` 里的内容，并补上课程讲过的 organisation / Contract Net 文献引用（凭记忆给出的引用要在 Google Scholar 核对）。

---

# Manufacturing Mayhem: Design of a Coordinated Robot Fleet

**Student:** [name, number] **Module:** COMP41400 **Date:** [ ]

## 1. Organisation and responsibilities

The system uses a **central coordinator** organisation with two agent types on top of the supplied CArtAgO environment.

| Agent | Instances | Role | Information maintained |
|---|---|---|---|
| `MyStrategy` (coordinator) | 1 | Discovers resources, assigns orders to packaging points, decides which robot performs which transfer, triggers workstation operation and order delivery | Resource lists, product → workstation map, `busy(robot)`, `busyPP(packagingPoint)`, pending transfers |
| `MyRobot` (controller) | 1 per robot | Executes one transfer at a time, resolves short-term location conflicts, decides when to charge | Its own location, held item and energy (observed from its Robot artifact) |

The supplied `Main` and `core.Factory` agents only start the system and build the environment; they are not modified.

Decisions are split by the information they need. **What** should be done and **by whom** is decided by the coordinator, because only it sees all orders, workstations and robots at once. **How** a transfer is physically carried out, including waiting for an occupied location and charging, is decided by the robot, because the robot can observe its own energy and position directly. The **environment artifacts** decide what is legal. They enforce capacity (one robot per location, one item per robot), energy limits and recipes. Agents never bypass them.

The coordinator obtains information by observing artifact properties: the `factory` artifact's configuration lists, `inputsNeeded` and `state` of workstations, `outstanding` at packaging points, and the order counters. It also relies on messages from robots. No resource names are hard-coded, so the same agents run on any scenario that has single-stage recipes.

## 2. Coordination mechanisms

**Task allocation.** Before sending a request, the coordinator claims a free robot by adding `busy(r)`. The claim is released only when the robot reports `transferred`. This makes double allocation impossible, because a robot is never offered two tasks at once.

**Communication protocol.** Each transfer uses three messages: `request transfer(from, to)`, then `inform delivered(from, to)` once the item has been unloaded, then `inform transferred(from, to)` once the robot has parked and is available again. If any step fails, the robot replies `failure transfer(from, to)`, so the coordinator never blocks on a reply that will not come. Separating *delivered* from *transferred* lets production continue while robots are still driving back.

**Production dependencies.** For each product, the coordinator selects the workstation whose `output` matches and reads its missing inputs. It assigns each missing input to a different access point and a different robot and sends all requests together. It then waits at a **barrier** until every input has arrived, operates the workstation, and dispatches the output to the packaging point.

**Shared resources.** Access points are reserved for the current batch through `usedAp(ap)`. Packaging points are reserved per order through `busyPP(pp)`. Locations are the remaining contention point: `free(location)` is only a check, so a robot that finds its target occupied waits and retries instead of failing. Robots always park at their own charger, so they do not block work locations.

**Concurrent activity.** Orders are pipelined. Once the output of an order has been dispatched, the coordinator immediately assigns the next order to another packaging point. Delivery of the previous order is completed event-driven: when the `delivered` message for its last item arrives, the handler calls `deliver()`.

## 3. Operational decisions

**Incoming orders.** The main loop continues while orders are waiting, still to arrive, or assigned but undelivered. When nothing is waiting it polls every 500 ms, so orders that arrive during the run are picked up.

**Charging.** A robot recharges only when its energy cannot cover another transfer. With `travelDistance` off, the worst case starting from the charger is three moves (300) plus load and unload (100), and both handling steps require at least 100 energy. The threshold is therefore 400. With 800-energy robots this means one charge every two transfers instead of after every transfer.

**Failures and stalls.** A failed robot action is reported, the robot is released, and the coordinator's barrier is unblocked. Because the workstation's `inputsNeeded` is read again afterwards, a missing input is requested again.

**Assumptions and expected limitations.** Each product is made by one workstation in one stage. Two-stage recipes that need storage buffering (medium-07) and mixed-product contention (medium-08) are outside the initial design. Location retries are bounded, and the 400 threshold is not valid when travel distance is enabled. An item still held by a robot after a failure is not recovered.

## 4. Worked interaction example

The example is easy-02: two robots, two access points, one workstation, two packaging points (see Figure 2).

1. The coordinator assigns order-1 to pp1. `outstanding(pp1)` returns `[apple-pie]`; ws1 makes apple-pie and needs `[apples, pastry]`.
2. It retrieves apples at ap1 and pastry at ap2, claims r1 and r2, and sends `transfer(ap1, ws1)` and `transfer(ap2, ws1)` together.
3. Both robots move, load and unload. If one finds ws1 occupied, it retries. Each robot sends `delivered`. After both have arrived, the coordinator calls `operate(ws1)`.
4. When ws1 reaches `done`, the coordinator claims a robot and sends `transfer(ws1, pp1)`, then immediately assigns order-2 to pp2 and starts fetching its inputs.
5. When the pie reaches pp1, the `delivered` handler sees an empty `outstanding` list and calls `deliver(pp1)`, which frees pp1 for a later order.
6. Each robot returns to its charger, charges if it is below 400, and sends `transferred`, which makes it available for the next task.

## 5. Design rationale and evaluation plan

A central coordinator suits this environment. State is already centralised in artifacts, the scenarios contain at most six robots, and the main risks are double allocation and missed dependencies, both of which are easiest to prevent with a single decision point. The trade-offs are a single point of failure and a bottleneck if the fleet grows. Robots keep local autonomy (retrying, charging) to limit that load.

**Alternative considered: Contract Net.** The coordinator would announce each transfer and robots would bid using their distance and energy. This uses local knowledge better and would help when `travelDistance` is enabled. However, it adds a negotiation round to every task and requires bids to be managed while robots are committed elsewhere. For the required scenarios, where travel cost is uniform, the extra messages buy little. It is a candidate extension for easy-03 and hard-10.

**Evaluation plan.** Three versions will be compared on the same scenario files and seeds: `BasicStrategy`, a sequential version of this design, and the parallel, pipelined version.

| Scenario | Question | Metrics |
|---|---|---|
| easy-02-two-robots | Are both robots used concurrently? | makespan, per-robot busy time, load balance |
| easy-04-steady-arrivals | Are new orders picked up promptly? | order waiting time, completed orders, idle time |
| medium-09-tight-energy | Does the charging rule keep robots productive? | charging time, failed operations, makespan |

For each run I will record the scenario, seed, strategy version and controller substitution. I will save the simulator's Summary, and report incomplete runs together with their cause.

---

> 草稿正文约 1000 词。可选项：插入图 1（architecture）和图 2（sequence），争取 +5% bonus。
