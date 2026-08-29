/**
 * Map Renderer — setDrawRange architecture
 * Per-material indexed geometry with cell-sorted index buffer.
 * CPU frustum culling at cell level via setDrawRange.
 */
import * as THREE from 'three';

const WORLD_SCALE = 1 / 256;

export class MapRenderer {
  constructor(scene) {
    this.scene = scene;
    this.materials = [];      // MaterialRenderData[]
    this.cellWorldSize = 0;   // world units per StageArea cell
    this.worldMin = [0, 0, 0];
    this.worldMax = [0, 0, 0];
    this.worldWidth = 0;
    this.worldDepth = 0;
    this.visibleCellCount = 0;
    this.drawCallCount = 0;
    this.visibleFaceCount = 0;
    this.totalFaceCount = 0;
    this.lights = [];         // scene lights: { type, wx, wy, wz, range, r, g, b } (world coords)
  }

  /**
   * Build all material geometries from SMD data.
   * @param {Object} smdData - parsed SMD data from smd-parser.js
   * @param {Map} texMap - url → THREE.Texture
   * @param {Function} getMatConfig - (matIdx, mat) → material config
   */
  build(smdData, texMap, getMatConfig) {
    const S = WORLD_SCALE;
    const _t0 = performance.now();

    // Compute world bounds
    const b = smdData.bounds;
    // SMD coordinate transform: wx = -rawZ/256, wy = rawY/256, wz = -rawX/256
    const wx1 = -b.maxZ * S, wx2 = -b.minZ * S;
    const wy1 = b.minY * S, wy2 = b.maxY * S;
    const wz1 = -b.maxX * S, wz2 = -b.minX * S;
    this.worldMin = [Math.min(wx1, wx2), wy1, Math.min(wz1, wz2)];
    this.worldMax = [Math.max(wx1, wx2), wy2, Math.max(wz1, wz2)];
    this.worldWidth = this.worldMax[0] - this.worldMin[0];
    this.worldDepth = this.worldMax[2] - this.worldMin[2];
    this.cellWorldSize = this.worldWidth / 256;

    this.totalFaceCount = smdData.nFace;

    // Scene lights: raw → world (wx=-z/256, wy=y/256, wz=-x/256)
    this.lights = [];
    for (const l of smdData.lights || []) {
      this.lights.push({
        type: l.type,
        wx: -l.z * S, wy: l.y * S, wz: -l.x * S,
        range: l.range, r: l.r, g: l.g, b: l.b,
      });
    }

    // Group faces by material
    const matFaces = new Map();
    for (let i = 0; i < smdData.nFace; i++) {
      const m = smdData.faceMat[i];
      if (!matFaces.has(m)) matFaces.set(m, []);
      matFaces.get(m).push(i);
    }

    // Build geometry for each material
    for (const [matIdx, faceList] of matFaces) {
      const mat = smdData.materials[matIdx];
      const config = getMatConfig(+matIdx, mat);
      if (!config) continue;

      const mrd = this._buildMaterialGeometry(+matIdx, faceList, smdData, config, texMap, getMatConfig);
      if (mrd) {
        this.materials.push(mrd);
        this.scene.add(mrd.mesh);
      }
    }

    // Sort materials: opaque first, then transparent
    this.materials.sort((a, b) => {
      if (a.isTransparent !== b.isTransparent) return a.isTransparent ? 1 : -1;
      return a.matIdx - b.matIdx;
    });
  }

  _buildMaterialGeometry(matIdx, faceList, smdData, config, texMap, getMatConfig) {
    const S = WORLD_SCALE;
    const nFaces = faceList.length;

    // First pass: compute per-face geometry
    const pos2 = new Float32Array(nFaces * 9);
    const nrm2 = new Float32Array(nFaces * 9);
    const col2 = new Float32Array(nFaces * 9);
    const uv0 = config.hasTex ? new Float32Array(nFaces * 6) : null;
    // uv1 = NextTex（tex[1]）UV：lightmap 或第二纹理共用同一数据源
    const uv1 = (config.hasLM || config.hasSecondTex) ? new Float32Array(nFaces * 6) : null;

    const va = new THREE.Vector3(), vb = new THREE.Vector3(), vc = new THREE.Vector3();
    const ab = new THREE.Vector3(), ac = new THREE.Vector3(), fn = new THREE.Vector3();

    let minX = Infinity, maxX = -Infinity;
    let minY = Infinity, maxY = -Infinity;
    let minZ = Infinity, maxZ = -Infinity;

    for (let fi = 0; fi < nFaces; fi++) {
      const i = faceList[fi];
      const a = smdData.triIdx[i * 3], b = smdData.triIdx[i * 3 + 1], c = smdData.triIdx[i * 3 + 2];

      for (let j = 0; j < 3; j++) {
        const vi = [a, b, c][j];
        const wx = -smdData.verts[vi * 3 + 2] * S;
        const wy = smdData.verts[vi * 3 + 1] * S;
        const wz = -smdData.verts[vi * 3] * S;
        pos2[fi * 9 + j * 3] = wx;
        pos2[fi * 9 + j * 3 + 1] = wy;
        pos2[fi * 9 + j * 3 + 2] = wz;
        col2[fi * 9 + j * 3] = smdData.vertColors[vi * 4] / 255;
        col2[fi * 9 + j * 3 + 1] = smdData.vertColors[vi * 4 + 1] / 255;
        col2[fi * 9 + j * 3 + 2] = smdData.vertColors[vi * 4 + 2] / 255;
        if (wx < minX) minX = wx; if (wx > maxX) maxX = wx;
        if (wy < minY) minY = wy; if (wy > maxY) maxY = wy;
        if (wz < minZ) minZ = wz; if (wz > maxZ) maxZ = wz;
      }

      va.set(pos2[fi * 9], pos2[fi * 9 + 1], pos2[fi * 9 + 2]);
      vb.set(pos2[fi * 9 + 3], pos2[fi * 9 + 4], pos2[fi * 9 + 5]);
      vc.set(pos2[fi * 9 + 6], pos2[fi * 9 + 7], pos2[fi * 9 + 8]);
      ab.subVectors(vb, va); ac.subVectors(vc, va);
      fn.crossVectors(ab, ac).normalize();
      for (let j = 0; j < 3; j++) {
        nrm2[fi * 9 + j * 3] = fn.x;
        nrm2[fi * 9 + j * 3 + 1] = fn.y;
        nrm2[fi * 9 + j * 3 + 2] = fn.z;
      }

      if (uv0) {
        const tlIdx = smdData.faceTexLink[i];
        if (tlIdx >= 0) {
          const base = tlIdx * 6;
          if (base + 5 < smdData.texUVs.length) {
            uv0[fi * 6]     = smdData.texUVs[base];
            uv0[fi * 6 + 1] = smdData.texUVs[base + 3];
            uv0[fi * 6 + 2] = smdData.texUVs[base + 1];
            uv0[fi * 6 + 3] = smdData.texUVs[base + 4];
            uv0[fi * 6 + 4] = smdData.texUVs[base + 2];
            uv0[fi * 6 + 5] = smdData.texUVs[base + 5];
          }
        }
      }
      if (uv1) {
        const lmIdx = smdData.faceLightmapUV[i];
        if (lmIdx >= 0) {
          const base = lmIdx * 6;
          if (base + 5 < smdData.texUVs.length) {
            uv1[fi * 6]     = smdData.texUVs[base];
            uv1[fi * 6 + 1] = smdData.texUVs[base + 3];
            uv1[fi * 6 + 2] = smdData.texUVs[base + 1];
            uv1[fi * 6 + 3] = smdData.texUVs[base + 4];
            uv1[fi * 6 + 4] = smdData.texUVs[base + 2];
            uv1[fi * 6 + 5] = smdData.texUVs[base + 5];
          }
        }
      }
    }

    // Second pass: 用 GridMesh 精确三角形-矩形相交分配 cell（参考 jpstale GridMesh）
    // 每个三角形分配到"与其相交的所有 cell"，保证大三角形（如甲板）不漏渲染
    const _tCell0 = performance.now();
    const cellSize = this.cellWorldSize;
    const wmX = this.worldMin[0], wmZ = this.worldMin[2];

    // Build (cellKey, faceIndex) pairs
    const pairs = [];
    for (let fi = 0; fi < nFaces; fi++) {
      const wx0 = pos2[fi * 9], wz0 = pos2[fi * 9 + 2];
      const wx1 = pos2[fi * 9 + 3], wz1 = pos2[fi * 9 + 5];
      const wx2 = pos2[fi * 9 + 6], wz2 = pos2[fi * 9 + 8];

      // 三角形 XZ 包围盒
      let minX = Math.min(wx0, wx1, wx2), maxX = Math.max(wx0, wx1, wx2);
      let minZ = Math.min(wz0, wz1, wz2), maxZ = Math.max(wz0, wz1, wz2);

      // 覆盖的 cell 范围
      const cMinX = Math.floor((minX - wmX) / cellSize);
      const cMaxX = Math.floor((maxX - wmX) / cellSize);
      const cMinZ = Math.floor((minZ - wmZ) / cellSize);
      const cMaxZ = Math.floor((maxZ - wmZ) / cellSize);

      for (let cx = cMinX; cx <= cMaxX; cx++) {
        for (let cz = cMinZ; cz <= cMaxZ; cz++) {
          // 精确相交判断
          if (!this._triCellIntersect(wx0, wz0, wx1, wz1, wx2, wz2, wmX + cx * cellSize, wmZ + cz * cellSize, cellSize)) continue;
          // cellKey 编码：cx*4096 + cz（cz 12bit，支持 0~4095；旧 (cx<<8)|cz 只有 8bit，cz>255 溢出）
          const key = cx * 4096 + cz;
          pairs.push([key, fi]);
        }
      }
    }

    // Sort by cell key
    pairs.sort((a, b) => a[0] - b[0]);

    // Build sorted arrays (may duplicate faces across cells)
    const totalFaces = pairs.length;
    const sortedPos = new Float32Array(totalFaces * 9);
    const sortedNrm = new Float32Array(totalFaces * 9);
    const sortedCol = new Float32Array(totalFaces * 9);
    const sortedUV0 = uv0 ? new Float32Array(totalFaces * 6) : null;
    const sortedUV1 = uv1 ? new Float32Array(totalFaces * 6) : null;
    const sortedCells = new Uint32Array(totalFaces);
    const sortedIndices = new Uint32Array(totalFaces * 3);

    for (let ni = 0; ni < totalFaces; ni++) {
      const fi = pairs[ni][1];
      sortedPos.set(pos2.subarray(fi * 9, fi * 9 + 9), ni * 9);
      sortedNrm.set(nrm2.subarray(fi * 9, fi * 9 + 9), ni * 9);
      sortedCol.set(col2.subarray(fi * 9, fi * 9 + 9), ni * 9);
      if (sortedUV0) sortedUV0.set(uv0.subarray(fi * 6, fi * 6 + 6), ni * 6);
      if (sortedUV1) sortedUV1.set(uv1.subarray(fi * 6, fi * 6 + 6), ni * 6);
      sortedCells[ni] = pairs[ni][0];
      sortedIndices[ni * 3] = ni * 3;
      sortedIndices[ni * 3 + 1] = ni * 3 + 1;
      sortedIndices[ni * 3 + 2] = ni * 3 + 2;
    }

    // Build cell lookup table
    const cellLookup = new Map();
    if (totalFaces > 0) {
      let cellStart = 0;
      let currentCell = sortedCells[0];
      let cellCount = 0;
      for (let i = 0; i < totalFaces; i++) {
        if (sortedCells[i] !== currentCell) {
          cellLookup.set(currentCell, { start: cellStart, count: cellCount });
          currentCell = sortedCells[i];
          cellStart = i * 3;
          cellCount = 0;
        }
        cellCount += 3;
      }
      cellLookup.set(currentCell, { start: cellStart, count: cellCount });
    }
    // 记录 cell 分配耗时（累计所有材质，供 index.html 显示）
    this._cellBuildTimeMs = (this._cellBuildTimeMs || 0) + (performance.now() - _tCell0);
    this.cellLookup = cellLookup;

    // Build BufferGeometry
    const geom = new THREE.BufferGeometry();
    geom.setAttribute('position', new THREE.BufferAttribute(sortedPos, 3));
    geom.setAttribute('normal', new THREE.BufferAttribute(sortedNrm, 3));
    geom.setAttribute('color', new THREE.BufferAttribute(sortedCol, 3));
    if (sortedUV0) geom.setAttribute('uv', new THREE.BufferAttribute(sortedUV0, 2));
    if (sortedUV1) geom.setAttribute('uv2', new THREE.BufferAttribute(sortedUV1, 2));
    geom.setIndex(new THREE.BufferAttribute(sortedIndices, 1));

    // Build Three.js material
    const mat = smdData.materials[matIdx];
    // Wind 类型：0x20=WINDZ1(微),0x40=WINDZ2(大),0x80=WINDX1(微),0x100=WINDX2(大)
    // 0x200=WATER（逐顶点波浪）。BLINK_COLOR 材质风禁用。
    let windKind = 0;
    let waterKind = false;
    if (mat.windMeshBottom && !(mat.useState & 0x4000)) {
      const wc = mat.windMeshBottom & 0x7FF;
      if (wc === 0x20) windKind = 1;       // WINDZ1
      else if (wc === 0x40) windKind = 2;  // WINDZ2
      else if (wc === 0x80) windKind = 3;  // WINDX1
      else if (wc === 0x100) windKind = 4; // WINDX2
      else if (wc === 0x200) waterKind = true; // WATER 逐顶点波浪（smRend3d.cpp:1040-1053）
    }
    const threeMat = this._buildThreeMaterial(matIdx, mat, config, texMap, windKind, minY, maxY, waterKind);

    const mesh = new THREE.Mesh(geom, threeMat);
    mesh.frustumCulled = false; // we do our own frustum culling
    mesh.userData.mapMesh = true;
    if (config.isRendLatter) mesh.renderOrder = 1;

    return {
      matIdx,
      mesh,
      geometry: geom,
      cellLookup,
      cellKeys: sortedCells,
      // CPU 索引重建：保存原始索引静态副本 + 打包用缓冲
      fullIndices: new Uint32Array(sortedIndices),  // 静态原始索引
      packedCount: 0,                                // 当前打包的面数
      aabb: new THREE.Box3(
        new THREE.Vector3(minX, minY, minZ),
        new THREE.Vector3(maxX, maxY, maxZ)
      ),
      faceCount: faceList.length,
      isTransparent: config.isTransparent,
      hasAnimation: config.hasAnimation,
    };
  }

  // ===== GridMesh cell 分配（精确三角形-矩形相交，参考 jpstale GridMesh.java）=====

  /** 点 P 是否在三角形 ABC 内（重心坐标，jpstale pointinTriangle） */
  _pointInTriangle(ax, az, bx, bz, cx, cz, px, pz) {
    const v0x = cx - ax, v0z = cz - az;
    const v1x = bx - ax, v1z = bz - az;
    const v2x = px - ax, v2z = pz - az;
    const dot00 = v0x*v0x + v0z*v0z;
    const dot01 = v0x*v1x + v0z*v1z;
    const dot02 = v0x*v2x + v0z*v2z;
    const dot11 = v1x*v1x + v1z*v1z;
    const dot12 = v1x*v2x + v1z*v2z;
    const denom = dot00*dot11 - dot01*dot01;
    if (Math.abs(denom) < 1e-12) return false;
    const inv = 1 / denom;
    const u = (dot11*dot02 - dot01*dot12) * inv;
    if (u < 0 || u > 1) return false;
    const v = (dot00*dot12 - dot01*dot02) * inv;
    if (v < 0 || v > 1) return false;
    return u + v <= 1;
  }

  /** 线段相交（jpstale lineCross + determinant） */
  _lineCross(ax, ay, bx, by, cx, cy, dx, dy) {
    const delta = (bx-ax)*(cy-dy) - (by-ay)*(cx-dx);
    if (Math.abs(delta) <= 1e-9) return false;
    const namenda = ((cx-ax)*(cy-dy) - (cy-ay)*(cx-dx)) / delta;
    if (namenda > 1 || namenda < 0) return false;
    const miu = ((bx-ax)*(cy-ay) - (by-ay)*(cx-ax)) / delta;
    if (miu > 1 || miu < 0) return false;
    return true;
  }

  /**
   * 判断三角形是否与某个 cell 矩形相交（jpstale GridMesh intersect + pointinTriangle + lineCross）
   * 条件：
   *  1. 三角形任一顶点在 cell 矩形内
   *  2. cell 矩形 4 顶点任一在三角形内
   *  3. 三角形任意边与矩形任意边相交
   */
  _triCellIntersect(ax, az, bx, bz, cx, cz, boxX, boxZ, cellSize) {
    const bx0 = boxX, bz0 = boxZ;
    const bx1 = boxX + cellSize, bz1 = boxZ + cellSize;
    const box = [
      [bx0, bz0], [bx1, bz0], [bx1, bz1], [bx0, bz1],
    ];

    // 1. 三角形顶点在矩形内
    const vInBox = (px, pz) => px >= bx0 && px <= bx1 && pz >= bz0 && pz <= bz1;
    if (vInBox(ax, az) || vInBox(bx, bz) || vInBox(cx, cz)) return true;

    // 2. 矩形顶点在三角形内
    for (const [px, pz] of box) {
      if (this._pointInTriangle(ax, az, bx, bz, cx, cz, px, pz)) return true;
    }

    // 3. 三角形边 与 矩形边 相交
    const triEdges = [[ax,az,bx,bz], [bx,bz,cx,cz], [cx,cz,ax,az]];
    for (const [e1x, e1z, e2x, e2z] of triEdges) {
      for (let k = 0; k < 4; k++) {
        const [bx0, bz0] = box[k];
        const [bx1, bz1] = box[(k+1)%4];
        if (this._lineCross(e1x, e1z, e2x, e2z, bx0, bz0, bx1, bz1)) return true;
      }
    }
    return false;
  }

_buildThreeMaterial(matIdx, mat, config, texMap, windKind, windYMin, windYMax, waterKind) {
    const opts = {
      vertexColors: true,
      side: config.twoSide ? THREE.DoubleSide : THREE.FrontSide,
    };

    if (config.hasTex) {
      opts.map = config.diffuseTex || null;
      opts.color = config.diffuseTex ? 0xffffff : 0xcccccc;
    } else {
      opts.color = 0xcccccc;
    }

    if (config.isTransparent) {
      opts.transparent = true;
      opts.alphaTest = 60 / 255;
      opts.depthWrite = mat.transparency <= 0.2;
      // C++ smRend3d.cpp:3798-3840 BlendType → Three.js blending
      switch (config.blendType) {
        case 2: // COLOR: SRC=SRCCOLOR, DEST=INVSRCCOLOR
          opts.blending = THREE.CustomBlending;
          opts.blendSrc = THREE.SrcColorFactor;
          opts.blendDst = THREE.OneMinusSrcColorFactor;
          break;
        case 3: // SHADOW: SRC=ZERO, DEST=SRCCOLOR（乘法）
          opts.blending = THREE.CustomBlending;
          opts.blendSrc = THREE.ZeroFactor;
          opts.blendDst = THREE.SrcColorFactor;
          break;
        case 4: // LAMP: SRC=SRCALPHA, DEST=ONE（加法）→ 气泡
          opts.blending = THREE.AdditiveBlending;
          break;
        case 5: // ADDCOLOR: SRC=SRCCOLOR, DEST=ONE（加法色）
          opts.blending = THREE.CustomBlending;
          opts.blendSrc = THREE.SrcColorFactor;
          opts.blendDst = THREE.OneFactor;
          break;
        case 6: // INVSHADOW: SRC=ZERO, DEST=INVSRCCOLOR
          opts.blending = THREE.CustomBlending;
          opts.blendSrc = THREE.ZeroFactor;
          opts.blendDst = THREE.OneMinusSrcColorFactor;
          break;
        default: // NONE(0)/ALPHA(1)：标准 alpha
          opts.blending = THREE.NormalBlending;
          break;
      }
    }

    const threeMat = new THREE.MeshBasicMaterial(opts);

    // TextureFormState → UV 滚动参数（SCROLL/SCROLLN/SLOW；REFLEX 地图未用到，遇 fs==5 跳过）
    // scrollSlot: { slot:0|1, kind:'scroll'|'slow', mult, factor }
    const scrollSlot = [];
    const warnReflex = (fs, slot) => console.warn(`[PT] REFLEX(TextureFormState=5) slot${slot} 未实现`, mat.name);
    for (const slot of [0, 1]) {
      const fs = mat.textureFormState ? mat.textureFormState[slot] : 0;
      if (fs === 4) scrollSlot.push({ slot, kind: 'scroll', mult: 1 });                          // SCROLL
      else if (fs >= 6 && fs <= 14) scrollSlot.push({ slot, kind: 'scroll', mult: fs - 4 });      // SCROLL2~10
      else if (fs >= 15 && fs <= 18) scrollSlot.push({ slot, kind: 'slow', factor: 22 - fs });    // SCROLLSLOW1~4
      else if (fs === 5) warnReflex(fs, slot);
    }
    const hasScroll = scrollSlot.length > 0;
    const needLM = !!(config.hasLM && config.lightmapTex);
    const need2Tex = !!(config.hasSecondTex && config.secondTex);
    const scrollU0 = hasScroll && scrollSlot.some(s => s.slot === 0);
    const scrollU1 = hasScroll && scrollSlot.some(s => s.slot === 1);

    // Force unique program per material to prevent GLSL compiler from optimizing away
    // custom uniforms (uScrollU, uWindTime, uWaterTime) when shared program doesn't use them.
    // Must be set BEFORE first render so getParameters() reads it for cache key.
    {
      const _ckParts = [];
      if (hasScroll) _ckParts.push('S' + scrollSlot.map(s => s.slot + s.kind + s.mult).join(''));
      if (windKind) _ckParts.push('W' + windKind);
      if (waterKind) _ckParts.push('A');
      if (needLM) _ckParts.push('L');
      if (need2Tex) _ckParts.push('T');
      if (_ckParts.length > 0) threeMat.customProgramCacheKey = () => _ckParts.join('');
    }

    // Wind 逐顶点摆动（顶点着色器）：方向保持 C++ 的轴语义
    //   WINDZ1/Z2(wk1/2): raw z 平移 → world x 轴摆动
    //   WINDX1/X2(wk3/4): raw x 平移 → world z 轴摆动
    // 幅度：微(1/3)=1.4 世界单位、大(2/4)=2.6，整体再按高度归一化（根不动、尖动）。
    const baseWindMag = windKind ? (windKind === 1 || windKind === 3 ? 1.4 : 2.6) : 0;
    const windAmpScale = (window.__ptWindAmpScale !== undefined ? window.__ptWindAmpScale : 1);
    const baseWindMagScaled = baseWindMag * windAmpScale;
    const vWindDX = (windKind === 1 || windKind === 2) ? baseWindMagScaled : 0;
    const vWindDZ = (windKind === 3 || windKind === 4) ? baseWindMagScaled : 0;

    // C++ SetColorZclip 对所有渲染顶点统一雾化（z>ccDistZMin=1152 world 时向黑衰减），
    // 因此每个材质都必须注入 fog，onBeforeCompile 无条件启用。
    {
      threeMat.userData.scrollSlots = scrollSlot;
      threeMat.onBeforeCompile = (shader) => {
        // ---- vertex: uniform/varying 声明（#include <common> 后追加）----
        let declInline = '#include <common>';
        if (needLM || need2Tex) declInline += '\nout vec2 vMyLightMapUv;';
        if (scrollU0 || scrollU1) declInline += '\nuniform vec2 uScrollU;';
        if (windKind) {
          declInline += '\nuniform float uWindTime;';
          declInline += '\nuniform vec2 uWindMag;';   // 已含方向与幅度（x/z 世界单位）
        }
        if (waterKind) declInline += '\nuniform float uWaterTime;';
        declInline += '\nvarying float vPtFogZ;';
        declInline += '\nvarying vec3 vPtWorldPos;';
        // 昼夜/场景光/火把 uniform（加到顶点色 vColor，等价 C++ sLight = sDef_Color + 光）
        declInline += '\nuniform vec3 uEnvLight;';
        declInline += '\nuniform vec3 uTorchPos;';
        declInline += '\nuniform vec3 uTorchColor;';
        declInline += '\nuniform float uTorchRange;';
        declInline += '\nuniform vec3 uSceneLightPos[8];';
        declInline += '\nuniform vec3 uSceneLightColor[8];';
        declInline += '\nuniform float uSceneLightRange[8];';
        shader.vertexShader = shader.vertexShader.replace('#include <common>', declInline);

        // ---- vertex: UV 变换 ----
        let uvInline = '#include <uv_vertex>';
        if (needLM || need2Tex) {
          uvInline += '\nvMyLightMapUv = uv2;';
          if (scrollU1) uvInline += '\nvMyLightMapUv.x += uScrollU.y;';
        }
        if (scrollU0) {
          // vMapUv 已由 #include <uv_vertex> 计算（vMapUv = (mapTransform*vec3(MAP_UV,1)).xy）
          uvInline += '\nvMapUv.x += uScrollU.x;';
        }
        shader.vertexShader = shader.vertexShader.replace('#include <uv_vertex>', uvInline);

        // ---- vertex: Wind 逐顶点摆动（复刻轴语义，逐点涟漪）----
        // 空间相位在 x/z 上传播（整片网格涟漪，等价 C++ 整网格平移的"风"效果）；
        // 不用"网格整体 y 范围"归一化（大跨度地形网格会稀释幅度到不可见），
        // 仅用局部高度做轻微调制（高处略多动）。
        if (windKind) {
          const windCode =
            '#include <begin_vertex>\n' +
            '  {\n' +
            `    float _ptH = clamp((transformed.y - ${windYMin.toFixed(1)}) / ${(windYMax - windYMin).toFixed(1)}, 0.0, 1.0);\n` +
            '    float _ph = transformed.x * 0.05 + transformed.z * 0.045 + uWindTime * 2.0;\n' +
            '    float _sw = sin(_ph) * 0.6 + sin(_ph * 1.55 + transformed.x * 0.013 + transformed.z * 0.011) * 0.4;\n' +
            '    float _amp = 1.0 + _ptH * 0.5;\n' +
            '    transformed.x += uWindMag.x * _sw * _amp;\n' +
            '    transformed.z += uWindMag.y * _sw * _amp;\n' +
            '  }';
          shader.vertexShader = shader.vertexShader.replace('#include <begin_vertex>', windCode);
        }

        // ---- vertex: Water 逐顶点波浪（smRend3d.cpp:1040-1053 sMATS_SCRIPT_WATER）----
        //   rx = GetSin[((x*8 + time)>>1) & 0xFFF] >> 4;  x += rx  （x,z 为 raw 坐标）
        //   GetSin[ang] = floor(sin(ang*2π/4096)*32768) → shader 用 sin() 等效
        //   raw 位移 = sin*32768>>4 = sin*2048；world 位移 = raw/256 = sin*8
        if (waterKind) {
          const waterCode =
            '#include <begin_vertex>\n' +
            '  {\n' +
            '    float _rx = (-transformed.z * 256.0 * 8.0 + uWaterTime) * 0.5; // raw x 相关 → world z\n' +
            '    float _rz = (-transformed.x * 256.0 * 8.0 + uWaterTime) * 0.5; // raw z 相关 → world x\n' +
            '    float _wa = _rx / 4096.0 * 6.28318530718;\n' +
            '    float _wb = _rz / 4096.0 * 6.28318530718;\n' +
            '    transformed.z += sin(_wa) * 8.0;\n' +
            '    transformed.x += sin(_wb) * 8.0;\n' +
            '  }';
          shader.vertexShader = shader.vertexShader.replace('#include <begin_vertex>', waterCode);
        }

        // ---- vertex: Fog 深度 + 世界坐标 + 昼夜/场景光/火把 ----
        // mvPosition 由 #include <project_vertex> 定义；相机看向 -z，前方深度 = -mvPosition.z
        // 光加到顶点色 vColor（等价 C++ sLight=sDef_Color+光 再 ×texture，而非乘后加）
        {
          const fogCode =
            '#include <project_vertex>\n' +
            '  vPtFogZ = -mvPosition.z;\n' +
            '  vPtWorldPos = (modelMatrix * vec4(transformed, 1.0)).xyz;\n' +
            '  vColor.rgb += uEnvLight;\n' +
            '  { for (int _i = 0; _i < 8; _i++) { if (uSceneLightRange[_i] <= 0.0) continue; float _ld = distance(vPtWorldPos, uSceneLightPos[_i]); if (_ld < uSceneLightRange[_i]) { float _lp = 1.0 - _ld / uSceneLightRange[_i]; vColor.rgb += uSceneLightColor[_i] * _lp; } } }\n' +
            '  { float _td = distance(vPtWorldPos, uTorchPos); if (uTorchRange > 0.0 && _td < uTorchRange) { float _tp = 1.0 - _td / uTorchRange; vColor.rgb += uTorchColor * _tp; } }';
          shader.vertexShader = shader.vertexShader.replace('#include <project_vertex>', fogCode);
        }

        // ---- fragment: 昼夜环境光 + 场景光 + 火把 已在 vertex 加到 vColor（等价 C++ sLight），
        //               fragment 只做 lightmap/第二纹理 与 Fog（伪雾化，smRend3d.cpp:2415-2481）----
        // tex[1] 语义：FSO[1]==0 → lightmap（multiply 固定）；FSO[1]!=0 → 第二纹理（multiply + 滚动）
        {
          shader.fragmentShader = shader.fragmentShader.replace(
            '#include <common>',
            '#include <common>\n' +
            (needLM ? 'uniform sampler2D uLightMap;\nin vec2 vMyLightMapUv;\n' : '') +
            (need2Tex ? 'uniform sampler2D uSecondTex;\nin vec2 vMyLightMapUv;\n' : '') +
            'varying float vPtFogZ;'
          );
          shader.fragmentShader = shader.fragmentShader.replace(
            '#include <color_fragment>',
            '#include <color_fragment>\n' +
            (needLM ? '  diffuseColor.rgb *= texture2D(uLightMap, vMyLightMapUv).rgb;\n' : '') +
            (need2Tex ? '  diffuseColor.rgb *= texture2D(uSecondTex, vMyLightMapUv).rgb;\n' : '') +
            '  { float _z = vPtFogZ; if (_z > 1152.0) { float _dlev = (_z - 1152.0) * 0.5; if (_dlev > 255.0) _dlev = 255.0; diffuseColor.rgb *= 1.0 - _dlev / 256.0; } }'
          );
          if (needLM) shader.uniforms.uLightMap = { value: config.lightmapTex };
          if (need2Tex) shader.uniforms.uSecondTex = { value: config.secondTex };
          shader.uniforms.uEnvLight = { value: new THREE.Vector3(0, 0, 0) };
          shader.uniforms.uTorchPos = { value: new THREE.Vector3(0, 0, 0) };
          shader.uniforms.uTorchColor = { value: new THREE.Vector3(0, 0, 0) };
          shader.uniforms.uTorchRange = { value: 0 };
          shader.uniforms.uSceneLightPos = { value: Array.from({length:8}, () => new THREE.Vector3()) };
          shader.uniforms.uSceneLightColor = { value: Array.from({length:8}, () => new THREE.Vector3()) };
          shader.uniforms.uSceneLightRange = { value: new Float32Array(8) };
        }

        if (scrollU0 || scrollU1) shader.uniforms.uScrollU = { value: new THREE.Vector2(0, 0) };
        if (windKind) {
          shader.uniforms.uWindTime = { value: 0 };
          shader.uniforms.uWindMag = { value: new THREE.Vector2(vWindDX, vWindDZ) };
        }
        if (waterKind) shader.uniforms.uWaterTime = { value: 0 };
        // 保存 shader 引用供每帧更新滚动/风偏移
        threeMat.userData.shader = shader;
      };
    }

    return threeMat;
  }

  /**
   * 每帧更新 TextureFormState 滚动偏移（复刻 smRend3d.cpp:3289-3375）：
   *   SCROLL(fs=4):   u += fwtime
   *   SCROLL2~10:     u += fwtime*(fs-4)
   *   SLOW1~4(fs15-18): factor=22-fs; wtime=(ms>>6)&(0xFFFF>>factor); u += wtime/(0xFFFF>>factor)
   *  基础 fwtime = ((ms>>6)&0xFF)/256，ms=RendStatTime（毫秒）
   * @param {number} animMs 当前毫秒数（t*1000）
   */
  updateScroll(animMs) {
    const ms = animMs | 0;
    const baseW = (ms >>> 6) & 0xFF;
    const baseFw = baseW / 256;
    for (const mrd of this.materials) {
      const mat = mrd.mesh.material;
      const shader = mat.userData.shader;
      const slots = mat.userData.scrollSlots;
      if (!shader || !slots || slots.length === 0) continue;
      const off = shader.uniforms.uScrollU ? shader.uniforms.uScrollU.value : null;
      if (!off) continue;
      for (const s of slots) {
        let v;
        if (s.kind === 'slow') {
          const mask = 0xFFFF >> s.factor;
          v = ((ms >>> 6) & mask) / mask;
        } else {
          v = baseFw * s.mult;
        }
        if (s.slot === 0) off.x = v;
        else off.y = v;
      }
    }
  }

/**
   * 每帧更新 Wind 摆动时间（顶点着色器逐顶点涟漪）。
   * 时间用 1024ms 往返三角波（对齐 C++ ttCnt = ((ms>>2)&0xFF) 反转周期），
   * 再归一化到 [0, 2π] 作为 uWindTime，让摆动节奏与原版一致但更平滑。
   * 位移幅度/轴方向在 onBeforeCompile 时按 windKind 固化进 uniform。
   * @param {number} animMs 当前毫秒数
   */
  updateWind(animMs) {
    const ms = animMs | 0;
    let ttCnt = (ms >>> 2) & 0xFF;
    const ttFlag = (ms >>> 10) & 1;
    if (!ttFlag) ttCnt = 255 - ttCnt;
    const uTime = ttCnt / 255 * Math.PI * 2; // 0 → 2π 往返，往返周期 2048ms
    for (const mrd of this.materials) {
      const shader = mrd.mesh.material.userData.shader;
      if (!shader || !shader.uniforms.uWindTime) continue;
      shader.uniforms.uWindTime.value = uTime;
    }
  }

  /**
   * 每帧更新 Water 波浪时间（smRend3d.cpp:1040-1053，RendStatTime 毫秒）
   * @param {number} animMs 当前毫秒数
   */
  updateWater(animMs) {
    const ms = animMs | 0;
    for (const mrd of this.materials) {
      const shader = mrd.mesh.material.userData.shader;
      if (!shader || !shader.uniforms.uWaterTime) continue;
      shader.uniforms.uWaterTime.value = ms;
    }
  }

  /**
   * 每帧更新昼夜环境光、场景光源与玩家火把（复刻 Winmain.cpp:5394-5403,5517-5535; playmain.cpp:847-885）：
   *   Color_R/G/B = -DarkLevel + BackColor → uEnvLight（加到 diffuseColor）
   *   场景光：仅在夜晚（DarkLevel>0）生效；NIGHT 型（type&1）全亮，其余 rgb×DarkLevel>>8；
   *           片元按距离线性衰减（SetDynamicLight）
   *   火把：DarkLevel>0 时 AddDynamicLight(px, py+32*fONE, pz, ap,ap,ap, 0, DarkLightRange)
   *     ap = DarkLevel×1.25；DarkLightRange = 260 world（非地牢）/ 400 world（地牢）
   *   @param {THREE.Vector3} envLight  环境光偏移（已 ÷255，加到最终色）
   *   @param {{pos:THREE.Vector3,color:THREE.Vector3,range:number}[]} sceneLights 活跃场景光（≤8）
   *   @param {THREE.Vector3} torchPos   火把世界坐标（0 时关闭）
   *   @param {THREE.Vector3} torchColor 火把光色（已 ÷255）
   *   @param {number} torchRange         火把半径（world 单位）
   */
  updateDayNight(envLight, sceneLights, torchPos, torchColor, torchRange) {
    for (const mrd of this.materials) {
      const shader = mrd.mesh.material.userData.shader;
      if (!shader) continue;
      if (shader.uniforms.uEnvLight) shader.uniforms.uEnvLight.value.copy(envLight);
      const up = shader.uniforms.uSceneLightPos, uc = shader.uniforms.uSceneLightColor, ur = shader.uniforms.uSceneLightRange;
      if (up && uc && ur) {
        const n = Math.min(sceneLights.length, 8);
        for (let i = 0; i < 8; i++) {
          if (i < n) {
            up.value[i].copy(sceneLights[i].pos);
            uc.value[i].copy(sceneLights[i].color);
            ur.value[i] = sceneLights[i].range;
          } else {
            up.value[i].set(0, 0, 0);
            uc.value[i].set(0, 0, 0);
            ur.value[i] = 0;
          }
        }
      }
      if (shader.uniforms.uTorchPos) shader.uniforms.uTorchPos.value.copy(torchPos);
      if (shader.uniforms.uTorchColor) shader.uniforms.uTorchColor.value.copy(torchColor);
      if (shader.uniforms.uTorchRange) shader.uniforms.uTorchRange.value = torchRange;
    }
  }

  /**
   * Update setDrawRange for all materials based on camera frustum.
   * Call this BEFORE renderer.render().
   * @param {THREE.Camera} camera - the camera used for frustum culling (dummyCamera)
   * @param {THREE.Camera[]} extraCameras - 附加视锥相机；cell 必须同时被所有视锥命中才可见（交集）
   */
  render(camera, extraCameras = []) {
    const frustums = [];
    for (const cam of [camera, ...extraCameras]) {
      const projScreenMatrix = new THREE.Matrix4();
      projScreenMatrix.multiplyMatrices(cam.projectionMatrix, cam.matrixWorldInverse);
      const f = new THREE.Frustum();
      f.setFromProjectionMatrix(projScreenMatrix);
      frustums.push(f);
    }
    const firstFrustum = frustums[0];

    // DEBUG: expose internals for inspection
    if (this._debug) {
      this._debugLast = {
        camPos: [camera.position.x, camera.position.y, camera.position.z],
        worldMin: this.worldMin,
        worldMax: this.worldMax,
        cellWorldSize: this.cellWorldSize,
        worldWidth: this.worldWidth,
        worldDepth: this.worldDepth,
        materialCount: this.materials.length,
      };
    }

    this.visibleCellCount = 0;
    this.drawCallCount = 0;
    this.visibleFaceCount = 0;

    if (this._debug && !this._dbgMaterials) {
      this._dbgMaterials = [];
    }

    const testFrustums = (box) => {
      for (const f of frustums) {
        if (f.planes && !f.intersectsBox(box)) return false;
      }
      return true;
    };

    for (const mrd of this.materials) {
      // Coarse cull: material AABB vs frustum (must hit all frustums)
      const coarseHit = testFrustums(mrd.aabb);
      if (this._debug && this._dbgMaterials && this._dbgMaterials.length < 8) {
        this._dbgMaterials.push({
          matIdx: mrd.matIdx,
          aabb: [mrd.aabb.min.x, mrd.aabb.min.y, mrd.aabb.min.z, mrd.aabb.max.x, mrd.aabb.max.y, mrd.aabb.max.z].map(v => +v.toFixed(1)),
          coarseHit,
          frustumCount: frustums.length,
        });
      }
      if (!coarseHit) {
        mrd.mesh.visible = false;
        continue;
      }

      // Collect visible cells for this material
      const ranges = [];
      for (const [cellKey, range] of mrd.cellLookup) {
        const cx = Math.floor(cellKey / 4096);
        const cz = cellKey % 4096;
        // Compute cell AABB
        const cellMinX = this.worldMin[0] + cx * this.cellWorldSize;
        const cellMinZ = this.worldMin[2] + cz * this.cellWorldSize;
        const cellMaxX = cellMinX + this.cellWorldSize;
        const cellMaxZ = cellMinZ + this.cellWorldSize;

        const cellAABB = new THREE.Box3(
          new THREE.Vector3(cellMinX, mrd.aabb.min.y, cellMinZ),
          new THREE.Vector3(cellMaxX, mrd.aabb.max.y, cellMaxZ)
        );

        if (this._debug && this._dbgChecked === undefined) {
          this._dbgChecked = true;
          const hits = testFrustums(cellAABB);
          this._debugFirstCell = {
            cellKey, cx, cz, cellMinX, cellMinZ, cellMaxX, cellMaxZ,
            aabbMinY: mrd.aabb.min.y, aabbMaxY: mrd.aabb.max.y,
            intersects: hits,
            projMatrix: Array.from(camera.projectionMatrix.elements),
            mwi: Array.from(camera.matrixWorldInverse.elements),
          };
        }

        if (testFrustums(cellAABB)) {
          ranges.push(range);
          this.visibleCellCount++;
        }
      }

      // CPU 索引重建：把可见 cell 的面索引打包到 index buffer 前部
      // （替代 setDrawRange 连续范围，避免提交中间不可见面）
      // 注意：cellLookup 的 range.start/count 都是"索引单位"（start=i*3, count+=3）
      const idxArr = mrd.geometry.index.array;  // Uint32Array（与 fullIndices 同尺寸）
      const fullIdx = mrd.fullIndices;
      let packed = 0; // 已打包的索引数（不是面数）
      // ranges 收集时已按 cellLookup 顺序，但这里要按索引顺序拷，需排序
      const sortedRanges = ranges.slice().sort((a, b) => a.start - b.start);
      for (const r of sortedRanges) {
        const srcStart = r.start;      // 已是索引单位
        const srcCount = r.count;      // 已是索引单位
        if (srcStart + srcCount > fullIdx.length || packed + srcCount > idxArr.length) {
          if (!this._warnedBounds) { this._warnedBounds = true; console.warn(`[render] 越界 matIdx=${mrd.matIdx} srcStart=${srcStart} srcCount=${srcCount} fullIdx.len=${fullIdx.length} packed=${packed} idxArr.len=${idxArr.length}`); }
          continue;
        }
        idxArr.set(fullIdx.subarray(srcStart, srcStart + srcCount), packed);
        packed += srcCount;
      }
      mrd.packedCount = packed;
      if (packed === 0) {
        mrd.mesh.visible = false;
        continue;
      }
      mrd.geometry.index.needsUpdate = true;
      mrd.geometry.setDrawRange(0, packed);
      mrd.mesh.visible = true;
      this.drawCallCount++;
      this.visibleFaceCount += packed / 3;
    }
  }

  _mergeRanges(ranges, threshold) {
    if (ranges.length === 0) return [];
    const merged = [{ start: ranges[0].start, count: ranges[0].count }];
    for (let i = 1; i < ranges.length; i++) {
      const last = merged[merged.length - 1];
      const gap = ranges[i].start - (last.start + last.count);
      if (gap <= threshold) {
        // Merge: extend last range
        last.count = (ranges[i].start + ranges[i].count) - last.start;
      } else {
        merged.push({ start: ranges[i].start, count: ranges[i].count });
      }
    }
    return merged;
  }

  dispose() {
    for (const mrd of this.materials) {
      mrd.geometry.dispose();
      mrd.mesh.material.dispose();
      this.scene.remove(mrd.mesh);
    }
    this.materials = [];
  }
}
