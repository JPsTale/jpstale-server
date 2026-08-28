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

    // Second pass: compute per-vertex cell keys, then for each face collect unique cells
    const cellKeyOfVert = new Uint32Array(nFaces * 3);
    for (let fi = 0; fi < nFaces; fi++) {
      for (let j = 0; j < 3; j++) {
        const wx = pos2[fi * 9 + j * 3];
        const wz = pos2[fi * 9 + j * 3 + 2];
        const cx = Math.floor((wx - this.worldMin[0]) / this.cellWorldSize);
        const cz = Math.floor((wz - this.worldMin[2]) / this.cellWorldSize);
        cellKeyOfVert[fi * 3 + j] = (Math.min(255, Math.max(0, cx)) << 8) | Math.min(255, Math.max(0, cz));
      }
    }

    // Build (cellKey, faceIndex) pairs — a face may appear in up to 3 cells
    const pairs = [];
    for (let fi = 0; fi < nFaces; fi++) {
      const k0 = cellKeyOfVert[fi * 3];
      const k1 = cellKeyOfVert[fi * 3 + 1];
      const k2 = cellKeyOfVert[fi * 3 + 2];
      pairs.push([k0, fi]);
      if (k1 !== k0) pairs.push([k1, fi]);
      if (k2 !== k0 && k2 !== k1) pairs.push([k2, fi]);
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
      aabb: new THREE.Box3(
        new THREE.Vector3(minX, minY, minZ),
        new THREE.Vector3(maxX, maxY, maxZ)
      ),
      faceCount: faceList.length,
      isTransparent: config.isTransparent,
      hasAnimation: config.hasAnimation,
    };
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
   */
  render(camera) {
    const projScreenMatrix = new THREE.Matrix4();
    projScreenMatrix.multiplyMatrices(camera.projectionMatrix, camera.matrixWorldInverse);
    const frustum = new THREE.Frustum();
    frustum.setFromProjectionMatrix(projScreenMatrix);

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

    for (const mrd of this.materials) {
      // Coarse cull: material AABB vs frustum
      const coarseHit = frustum.intersectsBox(mrd.aabb);
      if (this._debug && this._dbgMaterials && this._dbgMaterials.length < 8) {
        this._dbgMaterials.push({
          matIdx: mrd.matIdx,
          aabb: [mrd.aabb.min.x, mrd.aabb.min.y, mrd.aabb.min.z, mrd.aabb.max.x, mrd.aabb.max.y, mrd.aabb.max.z].map(v => +v.toFixed(1)),
          coarseHit,
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
          const hits = frustum.intersectsBox(cellAABB);
          this._debugFirstCell = {
            cellKey, cx, cz, cellMinX, cellMinZ, cellMaxX, cellMaxZ,
            aabbMinY: mrd.aabb.min.y, aabbMaxY: mrd.aabb.max.y,
            intersects: hits,
            projMatrix: Array.from(camera.projectionMatrix.elements),
            mwi: Array.from(camera.matrixWorldInverse.elements),
          };
        }

        if (frustum.intersectsBox(cellAABB)) {
          ranges.push(range);
          this.visibleCellCount++;
        }
      }

      if (ranges.length === 0) {
        mrd.mesh.visible = false;
        continue;
      }

      // Merge adjacent ranges, then use full bounding range for setDrawRange
      // (setDrawRange only supports one contiguous range)
      ranges.sort((a, b) => a.start - b.start);
      const merged = this._mergeRanges(ranges, 96);

      // Use the min start and total span across all merged ranges
      const drawStart = merged[0].start;
      const drawEnd = merged[merged.length - 1].start + merged[merged.length - 1].count;

      mrd.mesh.visible = true;
      mrd.geometry.setDrawRange(drawStart, drawEnd - drawStart);
      this.drawCallCount++;
      this.visibleFaceCount += (drawEnd - drawStart) / 3;
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
