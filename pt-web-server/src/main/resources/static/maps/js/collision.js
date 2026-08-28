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
   * 线段 sp→ep 与三角形相交检测
   * 复刻 C++ smGetTriangleImact（smStage3d.cpp:149）语义：
   *   - sp、ep 在三角形平面两侧（异号）
   *   - 三角形沿线段方向平移后，3 条边的平面测试都指向 sp
   * 注意：C++ 不要求交点在 [sp,ep] 线段内——只要移动方向穿过三角形即算撞墙，
   * 所以这里不做 t∈[0,1] 严格限制（否则靠近墙边缘时 t 会略超界导致穿透）。
   */
  _triangleImact(tri, sp, ep) {
    const Ax = tri.x1, Ay = tri.y1, Az = tri.z1;
    const Bx = tri.x2, By = tri.y2, Bz = tri.z2;
    const Cx = tri.x3, Cy = tri.y3, Cz = tri.z3;
    const Px = sp[0], Py = sp[1], Pz = sp[2];
    const Qx = ep[0], Qy = ep[1], Qz = ep[2];

    const Dx = Qx - Px, Dy = Qy - Py, Dz = Qz - Pz;
    const len2 = Dx*Dx + Dy*Dy + Dz*Dz;
    if (len2 < 1) return false;

    const E1x = Bx - Ax, E1y = By - Ay, E1z = Bz - Az;
    const E2x = Cx - Ax, E2y = Cy - Ay, E2z = Cz - Az;
    const Tx = Px - Ax, Ty = Py - Ay, Tz = Pz - Az;

    const PvecX = Dy * E2z - Dz * E2y;
    const PvecY = Dz * E2x - Dx * E2z;
    const PvecZ = Dx * E2y - Dy * E2x;
    const det = E1x * PvecX + E1y * PvecY + E1z * PvecZ;
    if (det > -1e-6 && det < 1e-6) return false;

    const invDet = 1 / det;
    const u = (Tx * PvecX + Ty * PvecY + Tz * PvecZ) * invDet;
    if (u < -1e-6 || u > 1 + 1e-6) return false;

    const QvecX = Ty * E1z - Tz * E1y;
    const QvecY = Tz * E1x - Tx * E1z;
    const QvecZ = Tx * E1y - Ty * E1x;
    const v = (Dx * QvecX + Dy * QvecY + Dz * QvecZ) * invDet;
    if (v < -1e-6 || u + v > 1 + 1e-6) return false;

    // t 不严格限制在 [0,1]：C++ 语义下，只要方向穿过三角形且 sp/ep 异侧即算撞墙
    // （放宽下限防止靠墙时 t 略 <0 或略 >1 造成穿透）
    const t = (E2x * QvecX + E2y * QvecY + E2z * QvecZ) * invDet;
    if (t < -1e-3) return false;

    return true;
  }

  /**
   * 用 T 形线检测移动路径是否被垂直面（墙/树/空气墙）阻挡
   * 对应 C++ smMakeTLine + GetTriangleImact
   * 只检测垂直面（nyNorm < 0.3），避免把地面/斜坡误判为墙
   */
  _wallBlocked(x, y, z, dx, dz, bodyWidth, bodyHeight) {
    const bw = (bodyWidth * fONE) >> 2;   // C++ width = ObjWidth>>2
    const minY = y + fONE * 12;           // 脚底
    const maxY = y + (bodyHeight * fONE) - ((bodyHeight * fONE) >> 2); // 胸口

    // 4 条 T 形线（C++ smMakeTLine: Line0/1 前进线, Line2/3 前端横线）
    const lines = [
      { sp: [x, minY, z], ep: [x + dx, minY, z + dz] },
      { sp: [x, maxY, z], ep: [x + dx, maxY, z + dz] },
      { sp: [x + dx - bw, minY, z + dz], ep: [x + dx + bw, minY, z + dz] },
      { sp: [x + dx - bw, maxY, z + dz], ep: [x + dx + bw, maxY, z + dz] },
    ];

    // 路径整体范围
    const pathMinX = Math.min(x, x + dx) - bw;
    const pathMaxX = Math.max(x, x + dx) + bw;
    const pathMinZ = Math.min(z, z + dz) - bw;
    const pathMaxZ = Math.max(z, z + dz) + bw;

    // 用 cell 网格定位附近三角形（同 getPolyHeight）
    const idxList = this._nearbyTriangleIdx((x + x + dx) / 2, (z + z + dz) / 2);
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
      const newX = x + fdx;
      const newZ = z + fdz;
      const h = this.getPolyHeight(newX, newZ);
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
