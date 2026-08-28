/**
 * SMD Stage Binary Parser
 * Parses Priston Tale .smd map files (SMD Stage data Ver 0.72)
 * Extracts geometry, materials (texture names), and texlinks (UV coordinates).
 */

export function parseSMD(buffer) {
  const dv = new DataView(buffer);

  // Validate header
  const hdr = [];
  for (let i = 0; i < 24; i++) { const c = dv.getUint8(i); if (c) hdr.push(String.fromCharCode(c)); }
  if (!hdr.join('').startsWith('SMD Stage data')) throw new Error('Invalid SMD: ' + hdr.join(''));

  // Skip header (556)
  let off = 556;

  // smLegacySTAGE3D: Head(4) + StageArea[256][256](262144) + tail fields
  off += 4 + 262144;

  // Read lpOldTexLink from tail (3rd pointer after AreaListCnt)
  // Tail layout: ptrAreaList, AreaListCnt, MemMode, SumCount, CalcSumCount,
  //              ptrVertex, ptrFace, lpOldTexLink, ptrSmLight, ptrSmMaterialGroup, ptrStageObject, ptrSmMaterial
  const lpOldTexLink = dv.getUint32(off + 7 * 4, true);
  off += 48;

  const nVertex  = dv.getInt32(off, true); off += 4;
  const nFace    = dv.getInt32(off, true); off += 4;
  const nTexLink = dv.getInt32(off, true); off += 4;
  const nLight   = dv.getInt32(off, true); off += 4;
  off += 48; // skip rest of tail

  // Material group header: 88 bytes
  const MaterialCount = dv.getInt32(off + 8, true);
  off += 88;

  // Parse materials: 320 bytes each + optional string block
  const materials = new Array(MaterialCount);
  for (let i = 0; i < MaterialCount; i++) {
    const inUse = dv.getUint32(off, true);
    const texCounter = dv.getUint32(off + 4, true);
    // 0x28 TextureStageState[8] + 0x48 TextureFormState[8] (smType.h:591-633)
    const textureStageState = [...Array(8)].map((_, k) => dv.getUint32(off + 40 + k * 4, true));
    const textureFormState = [...Array(8)].map((_, k) => dv.getUint32(off + 72 + k * 4, true));
    const blendType = dv.getUint32(off + 116, true);
    const shade = dv.getUint32(off + 120, true);
    const twoSide = dv.getUint32(off + 124, true);
    const diffuse = [dv.getFloat32(off + 132, true), dv.getFloat32(off + 136, true), dv.getFloat32(off + 140, true)];
    const transparency = dv.getFloat32(off + 144, true);
    const useState = dv.getUint32(off + 164, true);
    const meshState = dv.getUint32(off + 168, true);
    const windMeshBottom = dv.getInt32(off + 172, true);
    const animTexCounter = dv.getUint32(off + 304, true);
    off += 320;

    const tex = []; // diffuse texture name per slot
    const animTextures = []; // animation frame texture names
    if (inUse) {
      if (off + 4 <= buffer.byteLength) {
        const strLen = dv.getInt32(off, true);
        off += 4;
        const strEnd = off + (strLen > 0 && strLen < 100000 ? strLen : 0);
        // Read individual null-terminated strings for each texture slot
        for (let j = 0; j < texCounter && off < strEnd; j++) {
          const name = readCString(dv, off);
          off += name.length + 1;
          tex.push(name);
          // NameA (opacity texture) - skip
          const nameA = readCString(dv, off);
          off += nameA.length + 1;
        }
        // Read animation texture names
        for (let j = 0; j < animTexCounter && off < strEnd; j++) {
          const name = readCString(dv, off);
          off += name.length + 1;
          animTextures.push(name);
          // NameA (opacity texture) - skip
          const nameA = readCString(dv, off);
          off += nameA.length + 1;
        }
        // Ensure we skip to exact end of string block
        off = strEnd;
      }
    }
    materials[i] = { inUse, texCounter, animTexCounter, tex, animTextures, blendType, shade, twoSide, diffuse, transparency, useState, meshState, windMeshBottom, textureStageState, textureFormState };
  }

  // Vertices: smLegacySTAGE_VERTEX is 28 bytes
  // Fields: sum(4), lpRendVertex(4), x(4), y(4), z(4), sDef_Color(4)
  const verts = new Float32Array(nVertex * 3);
  const vertColors = new Uint8Array(nVertex * 4); // ARGB → RGBA
  let minX = Infinity, maxX = -Infinity;
  let minY = Infinity, maxY = -Infinity;
  let minZ = Infinity, maxZ = -Infinity;

  for (let i = 0; i < nVertex; i++) {
    const x = dv.getInt32(off + 8, true);
    const y = dv.getInt32(off + 12, true);
    const z = dv.getInt32(off + 16, true);
    verts[i * 3] = x; verts[i * 3 + 1] = y; verts[i * 3 + 2] = z;
    // sDef_Color: short[4] — BGRA order (SMC_B=0, SMC_G=1, SMC_R=2, SMC_A=3)
    const b = dv.getInt16(off + 20, true);
    const g = dv.getInt16(off + 22, true);
    const r = dv.getInt16(off + 24, true);
    const a = dv.getInt16(off + 26, true);
    vertColors[i * 4]     = Math.max(0, Math.min(255, r));
    vertColors[i * 4 + 1] = Math.max(0, Math.min(255, g));
    vertColors[i * 4 + 2] = Math.max(0, Math.min(255, b));
    vertColors[i * 4 + 3] = Math.max(0, Math.min(255, a));
    if (x < minX) minX = x; if (x > maxX) maxX = x;
    if (y < minY) minY = y; if (y > maxY) maxY = y;
    if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
    off += 28;
  }

  // Faces: smLegacySTAGE_FACE is 28 bytes
  const triIdx = new Uint16Array(nFace * 3);
  const faceMat = new Uint16Array(nFace);
  const faceTexLink = new Int32Array(nFace); // texlink index or -1
  for (let i = 0; i < nFace; i++) {
    triIdx[i * 3]     = dv.getUint16(off + 8, true);
    triIdx[i * 3 + 1] = dv.getUint16(off + 10, true);
    triIdx[i * 3 + 2] = dv.getUint16(off + 12, true);
    faceMat[i]        = dv.getUint16(off + 14, true);
    const lpTexLink   = dv.getUint32(off + 16, true);
    faceTexLink[i]    = lpTexLink !== 0 ? (lpTexLink - lpOldTexLink) / 32 : -1;
    off += 28;
  }

  // TexLinks: smLegacyTEXLINK is 32 bytes — u[3](12) + v[3](12) + hTexture(4) + lpNextTex(4)
  const texUVs = new Float32Array(nTexLink * 6); // [u0,u1,u2, v0,v1,v2] per texlink
  const texNext = new Int32Array(nTexLink);      // NextTex index (-1 if none)
  for (let i = 0; i < nTexLink; i++) {
    texUVs[i * 6]     = dv.getFloat32(off, true);
    texUVs[i * 6 + 1] = dv.getFloat32(off + 4, true);
    texUVs[i * 6 + 2] = dv.getFloat32(off + 8, true);
    texUVs[i * 6 + 3] = dv.getFloat32(off + 12, true);
    texUVs[i * 6 + 4] = dv.getFloat32(off + 16, true);
    texUVs[i * 6 + 5] = dv.getFloat32(off + 20, true);
    const lpNext = dv.getUint32(off + 28, true);
    texNext[i] = lpNext !== 0 ? (lpNext - lpOldTexLink) / 32 : -1;
    off += 32;
  }

  // Build per-face lightmap UV index: follow NextTex chain from face's texlink
  // First texlink = diffuse UVs, second (NextTex) = lightmap UVs
  const faceLightmapUV = new Int32Array(nFace); // lightmap texlink index or -1
  for (let i = 0; i < nFace; i++) faceLightmapUV[i] = -1;
  for (let i = 0; i < nFace; i++) {
    const tl = faceTexLink[i];
    if (tl >= 0 && texNext[tl] >= 0) {
      faceLightmapUV[i] = texNext[tl];
    }
  }

  return {
    nVertex, nFace, nTexLink, nLight,
    verts, vertColors, triIdx, faceMat, faceTexLink, texUVs, faceLightmapUV,
    materials,
    bounds: { minX, maxX, minY, maxY, minZ, maxZ },
  };
}

function readCString(dv, offset) {
  let s = '';
  for (let i = offset; i < dv.byteLength; i++) {
    const c = dv.getUint8(i);
    if (c === 0) break;
    s += String.fromCharCode(c);
  }
  return s;
}
