# SDD Progress Ledger

Branch: feat/server-collision (base 6ad2591)

- Task 1: complete (commits 6ad2591..0554d31, review clean)
- OPEN ITEM (user-confirm, non-blocking): ±15° (session-log C++) vs ±67.5°/±768 (collision.ts) sidestep offset discrepancy. Plan requirement says "对齐客户端 checkNextMove" → we ported ±67.5°. Surfaced to user as follow-up; do not change without decision.
- OPEN ITEM (minor, folded into Task 4): ccdStopsAtWall is a smoke test; real CCD tunneling proof belongs to BridgeTest (Task 4).
- Task 2: complete (commits 0554d31..9303220, review clean)
- Task 3: complete (commits 9303220..01c33d4, review clean)
- Task 4: complete (commit eb0ac79 + fix 324907b, 9/9 tests green)
- CRITICAL FIX (controller, 324907b): frame F = world coords DIRECTLY (worldX,worldY,worldZ), NOT (worldX,worldY,-worldZ). My Task-1 derivation was wrong (X/Z swapped+negated). Correct chain: client smd-parser reads raw unnegated (x@+8,y@+12,z@+16); StageVertex → jME3=(-fz,fy,-fx); MapRegionService.getHeight(-z,-x) → jME3.x=-worldZ, jME3.z=-worldX; so raw/256 = (worldX,worldY,worldZ). Fixed fromMapMesh (x=-v[z], z=-v[x]), removed CollisionSystem z-negation+angle conversion. BridgeTest now uses doc's world coords (4891.50,507.45,-6687.25) + approach-position logic. CCD_MAX_STEP=5.504 validated OK (5.68/16 do not tunnel).
- OPEN ITEM (user-confirm, non-blocking): ±15° (session-log C++) vs ±67.5°/±768 (collision.ts) sidestep offset.
- Task 6: complete (commits 2acaa46 + e710741; baseline walk avg 2.34ms/p95 3.12ms, run CCD avg 5.52ms/p95 6.49ms @300 entities)
- COORD LOCK: CoordinateParityTest (getFloorHeight==getHeight). Correct 100% agree; wrong mapping 0.9% agree (fails decisively). 10/10 tests green.
- Task 5 (visualizer): NOT DONE - dispatch aborted by user interruption.

---

# 计划：怪物掉落 + 掉落物名牌 (2026-09-11)
Branch: feat/item (base 2cb99d3)

- Task S1: complete (commits 2cb99d3..182ef80, review clean)
- Task S3: complete (commit 182ef80..79f90ae, review clean)
- Task S2: complete (commits 79f90ae..f803be5, review clean, 2 tests green)
- Task S4: complete (commits f803be5..9726d02, review clean)
- Task S5: complete (commits 9726d02..0750d77, review clean)
- Task S6: complete (commits 0750d77..3a43015, review clean)
- Task S7: complete (commits 3a43015..236c193, review clean)
- NOTE (S7): getHeight 在未加载地图可能返回 0；/@get 30 散布可能超 AOI 视野，远处需走近可见。
- 服务端 S1–S7 完成。客户端 C1–C3 在 jpstale-client repo 执行。
- Task C1: complete (client commits c5852e9..38d70fa, review clean; tsc pass). A 键 dispatch 由 C2 覆盖。
- Task C2: complete (client commits 38d70fa..5dfbee1, review clean; tsc pass).
- Task C3: complete (client commits 5dfbee1..3d46aac, review clean; tsc pass). 全部实现任务完成。
- MINOR 汇总（供 final review triage）：S2 Locale.ROOT/大小写测试/边界测试；S4 Monster.gold 死字段；S5 ITEMS 隐式分支、ttlMs=0；S6 尾部兜底启发式（预存在）；S7 getHeight 未加载地图返回 0、30 散布超视野；C3 WorldView.ts:2396 过时注释。
- FINAL REVIEW: With fixes. 用户决定"按推荐修"：①拾取 ownerId 校验 ②reload try/catch 保留旧表 ③dropQuantity 允许0（null→1）④/@get ownerId=0（公共）。/@ 授权记录待 GM 系统（本轮不实现）。
- FIX1: complete (commit 236c193..4060ddb, 编译通过). 用户补充：GM 身份字段为 userdb.userinfo.gamemasterlevel / gamemastertype → 追加 GM 授权校验。
- FIX2: complete (commit 4060ddb..f94b209, 编译通过).
- RE-REVIEW: fix1+fix2 全部正确（owner 校验/reload 容错/dropQuantity0/@get 公共/GM 授权），Approved。Minor：/@reloadloot 失败仍回 "reloaded"（反馈失真，非回归）。
- MINOR (S2, deferred to final review): LootService.toLowerCase 未用 Locale.ROOT；大小写不敏感约束无显式测试；加权边界 target==acc 未测。测试命令需 `-DfailIfNoTests=false`（多模块 surefire）。
- NOTE: 执行顺序调整为 S1 → S3(PremiumService stub) → S2(LootService 依赖 PremiumService) → S4..S7 → C1..C3
