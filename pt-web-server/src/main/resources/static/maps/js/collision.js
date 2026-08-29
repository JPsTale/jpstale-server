/**
 * Collision detection — replicates smStage3d.cpp CheckNextMove / GetPolyHeight / smMakeTLine
 *
 * All coordinates in raw SMD integers (fONE = 256 per game unit).
 * Caller must convert world ↔ SMD coords.
 *
 * Original C++ flow (smStage3d.cpp:569):
 *   CheckNextMove(x,y,z,angle,dist)
 *     → MakeAreaFaceList: StageArea[256][256] 网格定位附近面（128 游戏单位范围）
 *     → for ccnt in [0,1,2] (主方向, 左偏-768, 右偏+768):
 *         → smMakeTLine 生成 4 条 T 形扫掠线
 *         → GetPolyHeight(ep) 高度收集（只限向上爬 hy < StepHeight）
 *         → GetTriangleImact 检测 T 线是否撞墙
 *         → 成功则移动；ccnt==0 失败时 dist>>=1（距离减半重试）
 *     → 全失败返回 NULL（原地不动）
 */
import { sdGetSin, sdGetCos } from './sm-sin.js?v=1';

const ANGLE_360 = 4096;
const FLOATNS = 8;
const fONE = 256;
const STEP_HEIGHT = 10 * fONE;
// StageArea cell：64 游戏单位 = 64*fONE 定点（smType.h:31 SizeMAPCELL=64, ShiftMAPCELL_MULT=6）
const CELL_SIZE = 64 * fONE;
const CELL_SHIFT = 14; // log2(64*256) = log2(16384)
// MakeAreaFaceList 附近范围：±64 游戏单位（smStage3d.cpp:615 fONE*64）
const AREA_RADIUS = 64 * fONE;

class CollisionMesh {
  constructor() {
    this.triangles = [];
    // StageArea 网格：cellKey → triangle 索引数组
    this.cellMap = new Map();
    this.minY = 0;
    this.maxY = 0;
  }

  /**
   * Build collision mesh from SMD data in RAW SMD coordinates.
   * Only faces where (meshState & 1) != 0 are solid.
   */
  buildFromSMD(smdData) {
    this.triangles = [];
    this.cellMap = new Map();
    const pos = smdData.verts;
    const triIdx = smdData.triIdx;
    const materials = smdData.materials;
    let minY = Infinity, maxY = -Infinity;

    for (let fi = 0; fi < smdData.nFace; fi++) {
      const matIdx = smdData.faceMat[fi];
      const mat = materials[matIdx];
      if (!mat) continue;
      const meshState = mat.meshState || 0;
      if ((meshState & 1) === 0) continue;

      const i0 = triIdx[fi * 3];
      const i1 = triIdx[fi * 3 + 1];
      const i2 = triIdx[fi * 3 + 2];

      // Raw SMD coordinates (no scaling)
      const x1 = pos[i0 * 3], y1 = pos[i0 * 3 + 1], z1 = pos[i0 * 3 + 2];
      const x2 = pos[i1 * 3], y2 = pos[i1 * 3 + 1], z2 = pos[i1 * 3 + 2];
      const x3 = pos[i2 * 3], y3 = pos[i2 * 3 + 1], z3 = pos[i2 * 3 + 2];

      if (y1 < minY) minY = y1; if (y1 > maxY) maxY = y1;

      const tri = {
        x1, y1, z1, x2, y2, z2, x3, y3, z3, matIdx,
        minX: Math.min(x1, x2, x3), maxX: Math.max(x1, x2, x3),
        minY: Math.min(y1, y2, y3), maxY: Math.max(y1, y2, y3),
        minZ: Math.min(z1, z2, z3), maxZ: Math.max(z1, z2, z3),
        // 法线（未归一化）——用于区分垂直墙(ny小)与地面/斜坡(ny大)
        nx: 0, ny: 0, nz: 0, nyNorm: 1,
      };
      // 法线 = (B-A)×(C-A)
      const ux = x2 - x1, uy = y2 - y1, uz = z2 - z1;
      const vx = x3 - x1, vy = y3 - y1, vz = z3 - z1;
      tri.nx = uy * vz - uz * vy;
      tri.ny = uz * vx - ux * vz;
      tri.nz = ux * vy - uy * vx;
      const nlen = Math.hypot(tri.nx, tri.ny, tri.nz);
      if (nlen > 1) tri.nyNorm = Math.abs(tri.ny) / nlen;
      this.triangles.push(tri);
    }
    this.minY = minY;
    this.maxY = maxY;

    // 构建 StageArea 网格：每个三角形分配到其覆盖的所有 cell
    for (let i = 0; i < this.triangles.length; i++) {
      const tri = this.triangles[i];
      const cMinX = Math.floor(tri.minX / CELL_SIZE);
      const cMaxX = Math.floor(tri.maxX / CELL_SIZE);
      const cMinZ = Math.floor(tri.minZ / CELL_SIZE);
      const cMaxZ = Math.floor(tri.maxZ / CELL_SIZE);
      for (let cx = cMinX; cx <= cMaxX; cx++) {
        for (let cz = cMinZ; cz <= cMaxZ; cz++) {
          const key = cx * 65536 + cz;
          let arr = this.cellMap.get(key);
          if (!arr) { arr = []; this.cellMap.set(key, arr); }
          arr.push(i);
        }
      }
    }
  }

  /** 获取 (x,z) 所在 cell 的三角形索引列表（含相邻 cell，范围 ±AREA_RADIUS） */
  _nearbyTriangleIdx(x, z) {
    const cx0 = Math.floor((x - AREA_RADIUS) / CELL_SIZE);
    const cx1 = Math.floor((x + AREA_RADIUS) / CELL_SIZE);
    const cz0 = Math.floor((z - AREA_RADIUS) / CELL_SIZE);
    const cz1 = Math.floor((z + AREA_RADIUS) / CELL_SIZE);
    const out = [];
    for (let cx = cx0; cx <= cx1; cx++) {
      for (let cz = cz0; cz <= cz1; cz++) {
        const arr = this.cellMap.get(cx * 65536 + cz);
        if (arr) out.push(...arr);
      }
    }
    return out;
  }

  /**
   * GetPolyHeight — find triangle height at (x, z) using barycentric test.
   * 只查询附近 cell 的面（性能：对齐 C++ MakeAreaFaceList 网格定位）
   */
  getPolyHeight(x, z) {
    let bestY = null;
    const idxList = this._nearbyTriangleIdx(x, z);
    for (const i of idxList) {
      const tri = this.triangles[i];
      const denom = (tri.z2 - tri.z3) * (tri.x1 - tri.x3) + (tri.x3 - tri.x2) * (tri.z1 - tri.z3);
      if (Math.abs(denom) < 1) continue;
      const a = ((tri.z2 - tri.z3) * (x - tri.x3) + (tri.x3 - tri.x2) * (z - tri.z3)) / denom;
      const b = ((tri.z3 - tri.z1) * (x - tri.x3) + (tri.x1 - tri.x3) * (z - tri.z3)) / denom;
      const c = 1 - a - b;
      if (a >= -0.01 && b >= -0.01 && c >= -0.01) {
        const y = a * tri.y1 + b * tri.y2 + c * tri.y3;
        if (bestY === null || y > bestY) bestY = y;
      }
    }
    return bestY !== null ? { height: bestY, found: true } : { height: 0, found: false };
  }

  /**
   * GetFloorHeight — 返回 (x,z) 处"可站立"的地面高度（复刻 C++ smStage3d.cpp:720）
   * 只考虑"上升 < 步高"的面（即从 currentY 能走上去的面），
   * 忽略高处结构（梁/屋顶/帆），避免误判"要爬很高"。
   * 返回 { height, found }：最高且可站立的面高度；无则 found=false
   */
  getFloorHeight(x, z, currentY) {
    let bestY = null;
    const idxList = this._nearbyTriangleIdx(x, z);
    for (const i of idxList) {
      const tri = this.triangles[i];
      const denom = (tri.z2 - tri.z3) * (tri.x1 - tri.x3) + (tri.x3 - tri.x2) * (tri.z1 - tri.z3);
      if (Math.abs(denom) < 1) continue;
      const a = ((tri.z2 - tri.z3) * (x - tri.x3) + (tri.x3 - tri.x2) * (z - tri.z3)) / denom;
      const b = ((tri.z3 - tri.z1) * (x - tri.x3) + (tri.x1 - tri.x3) * (z - tri.z3)) / denom;
      const c = 1 - a - b;
      if (a >= -0.01 && b >= -0.01 && c >= -0.01) {
        const y = a * tri.y1 + b * tri.y2 + c * tri.y3;
        const rise = y - currentY;
        // C++: hy = he - ep.y; if (hy < Stage_StepHeight) 才记录为地面
        if (rise < STEP_HEIGHT) {
          if (bestY === null || y > bestY) bestY = y;
        }
      }
    }
    return bestY !== null ? { height: bestY, found: true } : { height: 0, found: false };
  }

  /**
   * 点 p 相对三角形平面 (p1,p2,p3) 的有向距离符号
   * 复刻 C++ smGetPlaneProduct（smStage3d.cpp:21）
   * 返回：>0 在法线侧，<0 另一侧，≈0 在平面上
   */
  _smPlaneProduct(p1, p2, p3, p) {
    const ux = p2[0] - p1[0], uy = p2[1] - p1[1], uz = p2[2] - p1[2];
    const vx = p3[0] - p1[0], vy = p3[1] - p1[1], vz = p3[2] - p1[2];
    // 法线 = u × v
    const nx = uy * vz - uz * vy;
    const ny = uz * vx - ux * vz;
    const nz = ux * vy - uy * vx;
    const dx = p[0] - p1[0], dy = p[1] - p1[1], dz = p[2] - p1[2];
    return nx * dx + ny * dy + nz * dz;
  }

  /**
   * 线段 sp→ep 与三角形相交检测 —— 精确复刻 C++ smGetTriangleImact（smStage3d.cpp:149）
   * 关键差异 vs 标准射线相交：
   *  - C++ 用"三角形沿方向 v 平移后，sp 是否落在三角形内"判断
   *  - v 方向：c1<=0 时 = (ep-sp)，c1>0 时 = (sp-ep) 且 vy=vz=0（C++ 原样）
   *  - 不要求交点在 [sp,ep] 线段内
   */
  _triangleImact(tri, sp, ep) {
    const p1 = [tri.x1, tri.y1, tri.z1];
    const p2 = [tri.x2, tri.y2, tri.z2];
    const p3 = [tri.x3, tri.y3, tri.z3];

    // C++ smStage3d.cpp:330-334 —— Y 剔除
    const spBelow = sp[1] < p1[1] && sp[1] < p2[1] && sp[1] < p3[1];
    const spAbove = sp[1] > p1[1] && sp[1] > p2[1] && sp[1] > p3[1];
    const epBelow = ep[1] < p1[1] && ep[1] < p2[1] && ep[1] < p3[1];
    const epAbove = ep[1] > p1[1] && ep[1] > p2[1] && ep[1] > p3[1];
    if ((spBelow || spAbove) && (epBelow || epAbove)) return false;

    // C++ 336-340 —— 平面异侧测试
    let c1 = this._smPlaneProduct(p1, p2, p3, sp);
    let c2 = this._smPlaneProduct(p1, p2, p3, ep);
    if ((c1 <= 0 && c2 <= 0) || (c1 > 0 && c2 > 0)) return false;

    // C++ 355-364 —— 方向 v（×16 对应 C++ <<4，但 JS 用未缩放，符号一致）
    let vx, vy, vz;
    if (c1 <= 0) {
      vx = ep[0] - sp[0];
      vy = ep[1] - sp[1];
      vz = ep[2] - sp[2];
    } else {
      // C++ 原样：反向且 vy=vz=0
      vx = sp[0] - ep[0];
      vy = 0;
      vz = 0;
    }

    // C++ 369-419 —— 三条边平移测试
    // 边 (p1,p2)
    const cp1 = [p1[0] + vx, p1[1] + vy, p1[2] + vz];
    if (this._smPlaneProduct(p1, p2, cp1, sp) > 0) return false;
    // 边 (p2,p3)
    const cp2 = [p2[0] + vx, p2[1] + vy, p2[2] + vz];
    if (this._smPlaneProduct(p2, p3, cp2, sp) > 0) return false;
    // 边 (p3,p1)
    const cp3 = [p3[0] + vx, p3[1] + vy, p3[2] + vz];
    if (this._smPlaneProduct(p3, p1, cp3, sp) > 0) return false;

    return true;
  }

  /**
   * 用 T 形线检测移动路径是否被垂直面（墙/树/空气墙）阻挡
   * 对应 C++ smMakeTLine + GetTriangleImact
   * 只检测垂直面（nyNorm < 0.3），避免把地面/斜坡误判为墙
   */
  _wallBlocked(x, y, z, dx, dz, bodyWidth, bodyHeight) {
    const bw = (bodyWidth * fONE) >> 2;   // C++ width = ObjWidth>>2
    // C++ smMakeTLine（smStage3d.cpp:436-437）：
    //   PosiMinY = fONE*12 → 脚底线 = y + 12
    //   PosiMaxY = ObjHeight - (ObjHeight>>2) → 胸口线 = y + 0.75*ObjHeight
    const footY = y + fONE * 12;          // 脚底线
    const chestY = y + bodyHeight * fONE - ((bodyHeight * fONE) >> 2); // 胸口线

    // C++ smMakeTLine: dist2 = dist + fONE*12（探测前方 步长+12 游戏单位）
    // 步长 |(dx,dz)|，探测距离 = |(dx,dz)| + 12*fONE
    const dLen = Math.hypot(dx, dz) || 1;
    const probeLen = dLen + 12 * fONE;
    // 探测方向 = 移动方向（单位化）
    const ux = dx / dLen, uz = dz / dLen;
    const px = ux * probeLen, pz = uz * probeLen; // 探测点偏移

    // 4 条 T 形线（C++ smMakeTLine: Line0/1 前进线到 dist2, Line2/3 前端横线）
    const lines = [
      { sp: [x, footY, z], ep: [x + px, footY, z + pz] },
      { sp: [x, chestY, z], ep: [x + px, chestY, z + pz] },
      { sp: [x + px - bw, footY, z + pz], ep: [x + px + bw, footY, z + pz] },
      { sp: [x + px - bw, chestY, z + pz], ep: [x + px + bw, chestY, z + pz] },
    ];

    // 路径整体范围（探测范围）
    const pathMinX = Math.min(x, x + px) - bw;
    const pathMaxX = Math.max(x, x + px) + bw;
    const pathMinZ = Math.min(z, z + pz) - bw;
    const pathMaxZ = Math.max(z, z + pz) + bw;

    // 用 cell 网格定位附近三角形（同 getPolyHeight）
    const idxList = this._nearbyTriangleIdx((x + x + px) / 2, (z + z + pz) / 2);
    for (const i of idxList) {
      const tri = this.triangles[i];
      // 只检测垂直面（墙/树），跳过地面/斜坡（法线近竖直）
      if (tri.nyNorm >= 0.3) continue;
      if (tri.maxX < pathMinX || tri.minX > pathMaxX) continue;
      if (tri.maxZ < pathMinZ || tri.minZ > pathMaxZ) continue;
      for (const l of lines) {
        const lMinY = Math.min(l.sp[1], l.ep[1]);
        const lMaxY = Math.max(l.sp[1], l.ep[1]);
        if (tri.maxY < lMinY || tri.minY > lMaxY) continue; // Y 无重叠
        if (this._triangleImact(tri, l.sp, l.ep)) return true;
      }
    }
    return false;
  }

  /**
   * CheckNextMove — replicates smSTAGE3D::CheckNextMove (smStage3d.cpp:569)
   * Input: raw SMD coords (x,y,z), engine angle, distance in fONE units.
   * Returns { x, y, z, collision } in raw SMD coords.
   */
  checkNextMove(x, y, z, angle, dist, bodyWidth = 11) {
    const prevX = x, prevY = y, prevZ = z;
    const stepMaxUp = STEP_HEIGHT;
    const bodyHeight = 21;

    let curDist = dist;
    // ccnt: 0=主方向, 1=左偏-768, 2=右偏+768（C++ smStage3d.cpp:595-601,624）
    for (let ccnt = 0; ccnt < 3; ccnt++) {
      const offset = ccnt === 0 ? 0 : (ccnt === 1 ? -768 : 768);
      const testAngle = (angle + offset) & (ANGLE_360 - 1);
      const sinVal = sdGetSin[testAngle] || 0;
      const cosVal = sdGetCos[testAngle] || 0;

      const fdx = (sinVal * curDist) >> 15;
      const fdz = (cosVal * curDist) >> 15;
      // 距离过小 → 无法推进，视为碰撞
      if (fdx === 0 && fdz === 0) { if (ccnt === 0) curDist >>= 1; continue; }

      // 墙壁检测（C++ GetTriangleImact）
      if (this._wallBlocked(x, y, z, fdx, fdz, bodyWidth, bodyHeight)) {
        if (ccnt === 0) curDist >>= 1; // C++: 主方向失败距离减半重试
        continue;
      }

      // 高度检测：ep 单点（C++ GetPolyHeight(face, ep.x, ep.z), smStage3d.cpp:634）
      // 用 getFloorHeight 只考虑"上升 < 步高"的可站立面，忽略高处结构（梁/屋顶）
      const newX = x + fdx;
      const newZ = z + fdz;
      const h = this.getFloorHeight(newX, newZ, y);
      if (h.found) {
        // 只限制向上爬（hy = he - ep.y < StepHeight, smStage3d.cpp:637）
        const rise = h.height - y;
        if (rise > stepMaxUp) {
          if (ccnt === 0) curDist >>= 1;
          continue;
        }
      }

      // 成功：移动到 ep，y = 地面高度
      return { x: newX, y: h.found ? h.height : y, z: newZ, collision: false };
    }

    return { x: prevX, y: prevY, z: prevZ, collision: true };
  }
}

export { CollisionMesh };
