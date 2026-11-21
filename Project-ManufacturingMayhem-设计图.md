---
course: COMP41400 Multi-Agent Systems
type: practical
topic: Manufacturing Mayhem — architecture & interaction diagrams
tags:
  - COMP41400
  - ManufacturingMayhem
  - design
  - diagram
created: 2026-11-21
updated: 2026-11-21
---

# Manufacturing Mayhem 设计图

> 配套：[SOP](sop.md) ｜ [Project-ManufacturingMayhem-报告草稿](Project-ManufacturingMayhem-报告草稿.md) ｜ [Project-ManufacturingMayhem-参考代码](Project-ManufacturingMayhem-参考代码.md)
> 用法：Obsidian 会直接渲染 Mermaid。交报告时用截图，或粘到 mermaid.live 导出 PNG/SVG。图里的名字要和报告正文保持一致（architecture diagram bonus 看的是 explanatory value 和 consistency）。

## 图 1 — Architecture（organisation + responsibilities）

```mermaid
flowchart TB
    subgraph ORG["Agent organisation (central coordinator)"]
        MAIN["Main<br/>bootstrap: select scenario, start agents"]
        FAC["core.Factory agent<br/>builds environment from JSON"]
        COORD["MyStrategy (coordinator)<br/>• discover resources<br/>• assign orders to packaging points<br/>• allocate transfers to robots<br/>• track busy robots / pps<br/>• trigger operate & deliver"]
        R1["MyRobot r1<br/>execute transfer<br/>retry occupied locations<br/>decide charging"]
        R2["MyRobot r2 … rN"]
    end

    subgraph ENV["CArtAgO environment (supplied, unchanged)"]
        FA[(Factory artifact<br/>config lists)]
        ORD[(Orders)]
        FP[(Floorplan<br/>occupancy)]
        AP[(Access points)]
        WH[(Warehouse)]
        WS[(Workstations<br/>recipe, state)]
        PP[(Packaging points<br/>outstanding)]
        RB[(Robot artifacts<br/>at, holding, energy)]
        CP[(Charge points)]
    end

    MAIN -- "request configure" --> FAC
    FAC -- "agree / configured" --> MAIN
    MAIN -- "createAgent" --> COORD
    FAC -. "creates" .-> R1 & R2

    COORD -- "request transfer(from,to)" --> R1 & R2
    R1 & R2 -- "inform delivered / transferred<br/>failure transfer" --> COORD

    COORD -- "observe lists" --> FA
    COORD -- "assign, counts" --> ORD
    COORD -- "retrieve" --> AP
    COORD -- "inputsNeeded, operate" --> WS
    COORD -- "outstanding, deliver" --> PP
    R1 -- "move, load, unload, charge" --> RB
    RB -. "linked ops" .-> FP & AP & WS & PP & CP
    AP -. "linked retrieve" .-> WH
```

**图注**：实线 = message 或 operation 调用；虚线 = artifact 之间的 linked operation（agent 不直接调用）。决策都在 agent 里做，规则检查（容量、电量、recipe）都在 artifact 里做。

## 图 2 — Worked example：一张 apple-pie 订单（easy-02）

```mermaid
sequenceDiagram
    autonumber
    participant C as MyStrategy
    participant O as Orders
    participant A1 as ap1 / ap2
    participant R1 as MyRobot r1
    participant R2 as MyRobot r2
    participant W as ws1
    participant P as pp1

    C->>O: assign(pp1) → order-1
    C->>P: ?outstanding → [apple-pie]
    C->>W: ?inputsNeeded → [apples, pastry]
    par parallel fetch
        C->>A1: retrieve(apples) @ap1
        C->>R1: request transfer(ap1, ws1)
    and
        C->>A1: retrieve(pastry) @ap2
        C->>R2: request transfer(ap2, ws1)
    end
    R1->>W: move, load, move, unload
    R1-->>C: inform delivered(ap1, ws1)
    R2->>W: move, load, move (retry if ws1 occupied), unload
    R2-->>C: inform delivered(ap2, ws1)
    Note over C: barrier passed (both arrived)
    C->>W: operate() … state = done
    C->>R2: request transfer(ws1, pp1)
    Note over C: does not wait → assigns next order to pp2
    R2->>P: move, load, move, unload
    R2-->>C: inform delivered(ws1, pp1)
    C->>P: ?outstanding = [] → deliver()
    R1-->>C: inform transferred (parked, charged if < 400)
    R2-->>C: inform transferred
```

## 图 3 — Robot 生命周期（MyRobot）

```mermaid
stateDiagram-v2
    [*] --> Parked
    Parked --> ToSource: request transfer
    ToSource --> Loading: arrived (retry while occupied)
    Loading --> ToTarget
    ToTarget --> Unloading
    Unloading --> Returning: inform delivered
    Returning --> Charging: energy < 400
    Returning --> Parked: energy >= 400 / inform transferred
    Charging --> Parked: full / inform transferred
    ToSource --> Failed: error
    Loading --> Failed
    ToTarget --> Failed
    Unloading --> Failed
    Failed --> Parked: inform failure
```

## 图 4 — Alternative（报告里用来比较）

```mermaid
flowchart LR
    C2["Coordinator<br/>announces task (CFP)"] -- cfp transfer --> RA["Robot A"] & RB2["Robot B"]
    RA -- "propose(cost = distance + charge need)" --> C2
    RB2 -- propose --> C2
    C2 -- "accept-proposal" --> RA
    C2 -- "reject-proposal" --> RB2
```

**图注**：Contract Net 下由 robot 自己报价（考虑位置和电量），coordinator 只负责选标。好处是 robot 的本地信息能被用上；代价是每个任务多一轮通信，并发报价还要处理 commitment。easy 和 medium scenario 规模小、travelDistance 大多关闭，所以选了更简单的 central allocation。
