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
