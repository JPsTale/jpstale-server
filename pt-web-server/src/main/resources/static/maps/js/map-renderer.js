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
    const uv1 = config.hasLM ? new Float32Array(nFaces * 6) : null;

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
          const key = (Math.min(255, Math.max(0, cx)) << 8) | Math.min(255, Math.max(0, cz));
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
    const threeMat = this._buildThreeMaterial(matIdx, mat, config, texMap);

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

  _buildThreeMaterial(matIdx, mat, config, texMap) {
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

    if (config.hasLM && config.lightmapTex) {
      opts.onBeforeCompile = (shader) => {
        shader.uniforms.uLightMap = { value: config.lightmapTex };
        shader.vertexShader = shader.vertexShader.replace(
          '#include <common>',
          '#include <common>\nout vec2 vMyLightMapUv;'
        );
        shader.vertexShader = shader.vertexShader.replace(
          '#include <uv_vertex>',
          '#include <uv_vertex>\nvMyLightMapUv = uv2;'
        );
        shader.fragmentShader = shader.fragmentShader.replace(
          '#include <common>',
          '#include <common>\nuniform sampler2D uLightMap;\nin vec2 vMyLightMapUv;'
        );
        shader.fragmentShader = shader.fragmentShader.replace(
          '#include <color_fragment>',
          '#include <color_fragment>\ndiffuseColor.rgb *= texture2D(uLightMap, vMyLightMapUv).rgb;'
        );
      };
    }

    if (config.isTransparent) {
      opts.transparent = true;
      opts.alphaTest = 60 / 255;
      opts.depthWrite = mat.transparency <= 0.2;
    }

    return new THREE.MeshBasicMaterial(opts);
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
        const cx = Math.floor(cellKey / 256);
        const cz = cellKey % 256;
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
