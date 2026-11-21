---
course: COMP41400 Multi-Agent Systems
type: practical
topic: Manufacturing Mayhem — 参考代码（MyStrategy + MyRobot）
tags:
  - COMP41400
  - ASTRA
  - ManufacturingMayhem
  - project
created: 2026-11-21
updated: 2026-11-21
---

# Manufacturing Mayhem 参考代码

> 配套：[SOP](sop.md)（Part 2–6）｜ [Project-ManufacturingMayhem-设计图](Project-ManufacturingMayhem-设计图.md)
> ⚠️ 只做过 compile 和一次 easy-02 短跑（临时副本，ASTRA 2.7.12，官方 `c77c249`），没有跑到 Summary；只作**参考**，自己实现时要逐段理解、改写，并在 reflection 里说明 LLM 参与的部分。
> ⚠️ 未验证：easy-04、medium、travelDistance 开启的 scenario；`!go` 的 40 次重试上限是经验值。

## 怎么接入
1. 把下面两个文件放进 `src/main/astra/`。
2. 复制 config（只改 `agent` 字段）：
```bash
sed 's/"agent": "Robot"/"agent": "MyRobot"/' config/easy-02-two-robots.json > config/easy-02-two-robots-myrobot.json
```
3. `Main.astra` 改两行：
```
send(request, "factory", configure("config/easy-02-two-robots-myrobot.json"));
S.createAgent("strategy", "MyStrategy");
```
4. 执行 `mvn compile astra:deploy`

## 设计对照

| 机制 | 代码位置 |
|---|---|
| 资源发现（不写死名字） | `!loadConfig`、`makes(ws, product)` |
| 防重复分配 | `busy(r)` + `?freeRobot` |
| 并行取料 + barrier | `!fetchInputs`：`usedAp`、`wait(arrived(..) \| failedT(..))` |
| 跨订单 pipelining | `busyPP(pp)` + `?freePP`；`delivered` handler 里调用 `!deliver` |
| 终止条件 | `waiting + coming + active > 0` |
| location 冲突 | MyRobot `!go`：失败后重试 |
| 按需充电 | MyRobot `!topUp`：电量 < 400 才充 |
| 失败回报 | MyRobot `try/recover` → `failure`；coordinator 收到后加 `failedT` |

## 短跑观察（easy-02）
- `r1: ap1 -> ws1 (apples)` 和 `r2: ap2 -> ws1 (pastry)` 同时发出
- pp1 和 pp2 交替接单；ASSIGNED 时间点分别是 0 / 20 / 53 / 83 s（order-4 = 2 × apple-pie）
- `[r1] energy 0 < 400, charging`：每 2 次 transfer 充一次电

## 已知局限
- 一个 product 只找一台 workstation，不支持两级 recipe（medium-07）和 storage 缓冲
- 一台 ws 同一时间只生产一个 product；多台 ws 时不会并行生产同一订单
- `failedT` 只解开 barrier，不重试，也不处理 robot 手里还拿着的 item
- `?freeRobot` 总是优先选列表里靠前的 robot，load balance 只是部分平衡
- 充电阈值 400 只在 travelDistance 关闭时成立

## MyStrategy.astra
```java
// MyStrategy.astra — central coordinator (COMP41400 Manufacturing Mayhem)
//
// Organisation: one coordinator agent (this file) + one MyRobot agent per robot.
//   coordinator: discovers resources, assigns orders, allocates transfers, tracks robots
//   MyRobot:     executes one transfer at a time, parks, decides its own charging
//   artifacts:   enforce capacity / energy / recipe rules (supplied, unchanged)
//
// Coordination mechanisms:
//   - resource discovery from the factory artifact's observable lists (no fixed names)
//   - busy(r) beliefs: a robot is claimed before a request is sent -> no double allocation
//   - parallel input fetching: one robot per missing input, one access point each
//   - pipelining across orders: packaging points work in parallel; delivery of the last
//     item triggers deliver() in the message handler, so the main loop moves on at once
//   - replies are correlated by (from, to); input transfers always use distinct (ap, ws)
agent MyStrategy extends core.Base {

    types strategy {
        formula configList(string, list);
        formula busy(string);          // robot is executing a request
        formula busyPP(string);        // packaging point holds an undelivered order
        formula packing(string);       // name is a packaging point
        formula makes(string, string); // workstation, output product
        formula usedAp(string);        // access point used in the current batch
        formula arrived(string, string);  // item from -> to has been unloaded
        formula failedT(string, string);
    }

    types mm {
        formula delivered(string, string);
    }

    initial !start();

    // ------------------------------------------------------------------ setup
    plan +!start() {
        cartago.link();
        !floorplan(cartago.ArtifactId _);
        !warehouse(cartago.ArtifactId _);
        !orders(cartago.ArtifactId _);
        !loadConfig();

        ?configList("robots", list robots);
        forall (string r : robots) {
            !robot(r, cartago.ArtifactId _);
        }
        ?configList("accessPoints", list aps);
        forall (string ap : aps) {
            !accessPoint(ap, cartago.ArtifactId _);
        }
        ?configList("packagingPoints", list pps);
        forall (string pp : pps) {
            !packagingPoint(pp, cartago.ArtifactId _);
            +packing(pp);
        }
        // product -> workstation map, read from each workstation's "output" property
        ?configList("workstations", list wss);
        forall (string ws : wss) {
            !workstation(ws, cartago.ArtifactId wid);
            cartago.operation(wid, observeProperty("output", cartago.ArtifactObsProperty op));
            +makes(ws, pe.stringValue(op));
            cartago.println("[S] " + ws + " makes " + pe.stringValue(op));
        }

        !fulfilOrders();
    }

    plan +!loadConfig() {
        cartago.lookupArtifact("factory", cartago.ArtifactId fid);
        cartago.operation(fid, observeProperty("accessPoints", cartago.ArtifactObsProperty p2));
        +configList("accessPoints", pe.listValues(p2));
        cartago.operation(fid, observeProperty("storagePoints", cartago.ArtifactObsProperty p3));
        +configList("storagePoints", pe.listValues(p3));
        cartago.operation(fid, observeProperty("packagingPoints", cartago.ArtifactObsProperty p4));
        +configList("packagingPoints", pe.listValues(p4));
        cartago.operation(fid, observeProperty("workstations", cartago.ArtifactObsProperty p5));
        +configList("workstations", pe.listValues(p5));
        cartago.operation(fid, observeProperty("robots", cartago.ArtifactObsProperty p6));
        +configList("robots", pe.listValues(p6));
        cartago.println("[S] robots = " + pe.listValues(p6) + ", aps = " + pe.listValues(p2)
            + ", pps = " + pe.listValues(p4) + ", wss = " + pe.listValues(p5));
    }

    plan +?configList(string property, list values) : configList(property, values) { }

    // ------------------------------------------------------------- order loop
    // Finished only when nothing is waiting, nothing is still to arrive and nothing is assigned.
    plan +!fulfilOrders() {
        ?outstandingOrders(int waiting);
        ?ordersToArrive(int coming);
        ?assignedOrders(int active);
        while (waiting + coming + active > 0) {
            if (waiting == 0) {
                S.sleep(500);
            } else {
                ?freePP(string pp);
                +busyPP(pp);
                !assign(pp, string orderId);
                cartago.println("[S] " + orderId + " -> " + pp);
                !produceOrder(pp);
            }
            ?outstandingOrders(int w2);
            ?ordersToArrive(int c2);
            ?assignedOrders(int a2);
            waiting = w2;
            coming = c2;
            active = a2;
        }
        cartago.println("[S] all orders delivered");
    }

    // Produce every outstanding item of the order; deliveries are not awaited here.
    plan +!produceOrder(string pp) {
        ?outstanding(pp, list needed);
        forall (string item : needed) {
            ?makes(string ws, item);
            !fetchInputs(ws);
            !waitReady(ws);
            !operate(ws);
            ?freeRobot(string carrier);
            +busy(carrier);
            cartago.println("[S] " + carrier + ": " + ws + " -> " + pp + " (" + item + ")");
            send(request, carrier, transfer(ws, pp));
        }
    }

    // Fetch all missing inputs in parallel: one access point and one robot per input.
    plan +!fetchInputs(string ws) : configList("accessPoints", list aps) {
        ?inputsNeeded(ws, list missing);
        while (P.size(missing) > 0) {
            string chosenAp = "none";
            forall (string item : missing) {
                chosenAp = "none";
                forall (string ap : aps) {
                    if (chosenAp == "none") {
                        if (~usedAp(ap)) {
                            chosenAp = ap;
                        }
                    }
                }
                if (chosenAp ~= "none") {
                    +usedAp(chosenAp);
                    ?freeRobot(string fetcher);
                    +busy(fetcher);
                    !available(chosenAp, item);
                    cartago.println("[S] " + fetcher + ": " + chosenAp + " -> " + ws + " (" + item + ")");
                    send(request, fetcher, transfer(chosenAp, ws));
                }
            }
            // barrier: wait for every transfer of this batch
            forall (string ap2 : aps) {
                if (usedAp(ap2)) {
                    wait(arrived(ap2, ws) | failedT(ap2, ws));
                    -arrived(ap2, ws);
                    -failedT(ap2, ws);
                    -usedAp(ap2);
                }
            }
            ?inputsNeeded(ws, list missing2);
            missing = missing2;
        }
    }

    // The previous output may still be waiting for its carrier.
    plan +!waitReady(string ws) {
        ?state(ws, string st);
        while (st ~= "ready") {
            S.sleep(200);
            ?state(ws, string st2);
            st = st2;
        }
    }

    // ------------------------------------------------------- allocation helpers
    plan +?freeRobot(string chosen) : configList("robots", list robots) {
        string pick = "none";
        while (pick == "none") {
            forall (string r : robots) {
                if (pick == "none") {
                    if (~busy(r)) {
                        pick = r;
                    }
                }
            }
            if (pick == "none") S.sleep(200);
        }
        chosen = pick;
    }

    plan +?freePP(string chosen) : configList("packagingPoints", list pps) {
        string pick = "none";
        while (pick == "none") {
            forall (string p : pps) {
                if (pick == "none") {
                    if (~busyPP(p)) {
                        pick = p;
                    }
                }
            }
            if (pick == "none") S.sleep(200);
        }
        chosen = pick;
    }

    // ------------------------------------------------------------ replies
    // Item unloaded: release barriers, or deliver the order when its last item arrives.
    plan @message(inform, string sender, delivered(string from, string to)) {
        if (packing(to)) {
            ?outstanding(to, list left);
            if (P.size(left) == 0) {
                try {
                    !deliver(to);
                    cartago.println("[S] delivered order at " + to);
                    -busyPP(to);
                } recover {
                    cartago.println("[S] deliver at " + to + " not possible yet");
                }
            }
        } else {
            +arrived(from, to);
        }
    }

    // Robot parked (and charged if needed): it can take new work.
    plan @message(inform, string sender, transferred(string from, string to)) {
        -busy(sender);
    }

    plan @message(failure, string sender, transfer(string from, string to)) {
        cartago.println("[S] " + sender + " FAILED " + from + " -> " + to);
        -busy(sender);
        +failedT(from, to);
    }
}
```

## MyRobot.astra
```java
// MyRobot.astra — robot controller (copy of supplied Robot.astra with three changes)
//   1) reports failure instead of staying silent (the requester would block forever)
//   2) waits and retries when a target location is occupied (?free is only a check)
//   3) charges only when energy cannot cover the next transfer, instead of after every job
//   It also sends delivered(from, to) as soon as the item is unloaded, so the strategy can
//   continue while the robot is still driving back to its charger.
agent MyRobot extends core.Base {
    types mm {
        formula delivered(string, string);
    }

    initial !init();

    plan +!init() {
        cartago.link();
        !floorplan(cartago.ArtifactId _);
        !robot(S.name(), cartago.ArtifactId rid);
        cartago.focus(rid);
        S.sleep(1000);
    }

    plan @message(request, string sender, transfer(string from, string to)) {
        try {
            !go(from);
            !holding(S.name(), string _);
            !go(to);
            !empty(S.name());
            send(inform, sender, delivered(from, to));

            // Park at its own charger so it never blocks a work location
            !go(S.name() + "-charger");
            !topUp();
            send(inform, sender, transferred(from, to));
        } recover {
            cartago.println("[" + S.name() + "] FAILED transfer " + from + " -> " + to);
            send(failure, sender, transfer(from, to));
        }
    }

    // Move with retry: another robot may still be on the target location
    plan +!go(string loc) {
        int tries = 0;
        int moved = 0;
        while (moved == 0) {
            try {
                !at(S.name(), loc);
                moved = 1;
            } recover {
                tries = tries + 1;
                if (tries > 40) S.fail();
                S.sleep(250);
            }
        }
    }

    // Worst-case cost of one transfer starting at the charger (travelDistance off):
    // 3 moves x 100 + load/unload 2 x 50, and load/unload each need >= 100 to start -> 400.
    plan +!topUp() {
        ?chargeLevel(S.name(), int level);
        if (level < 400) {
            cartago.println("[" + S.name() + "] energy " + level + " < 400, charging");
            !charged(S.name());
        }
    }
}
```
