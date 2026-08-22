/**
 * PT .smb 骨骼文件解析器
 *
 * 严格依据 exm C++ 源码（非 Java 移植）：
 *  - Legacy/Engine/Graphics/smObj3d.cpp: smPAT3D::LoadFile / smOBJ3D::LoadFile
 *  - Legacy/Engine/Graphics/smObj3d.h: smDFILE_HEADER / smDFILE_OBJINFO / smLegacyOBJ3D
 *  - Legacy/Engine/Graphics/smType.h: smVERTEX / smLegacyFACE / smLegacyTEXLINK / smTM_ROT / smTM_POS / smTM_SCALE / smFRAME_POS
 *
 * 文件布局：
 *   1. smDFILE_HEADER（556B）
 *   2. smDFILE_OBJINFO[ObjCounter]（每条 40B）
 *   3. 可选材质组
 *   4. 每个骨骼对象按 ObjFilePoint 偏移读取
 *
 * smMATRIX 是 int 矩阵（定点 /256），smFMATRIX 是 float 矩阵。
 */

import { readCString } from './utils.js';

/** 读取 smFRAME_POS（16B） */
function readFramePos(dv, o) {
  return {
    startFrame: dv.getInt32(o, true),
    endFrame: dv.getInt32(o + 4, true),
    posNum: dv.getInt32(o + 8, true),
    posCnt: dv.getInt32(o + 12, true),
  };
}

/** 读取 int 矩阵 smMATRIX（64B，定点，元素 /256） */
function readIntMatrix(dv, o) {
  const m = [];
  for (let i = 0; i < 16; i++) m.push(dv.getInt32(o + i * 4, true));
  return m;
}

/** 读取 float 矩阵 smFMATRIX（64B） */
function readFloatMatrix(dv, o) {
  const m = [];
  for (let i = 0; i < 16; i++) m.push(dv.getFloat32(o + i * 4, true));
  return m;
}

/**
 * 解析 .smb 文件
 * @param {ArrayBuffer} buffer
 * @returns {{
 *   header: string, objCount: number,
 *   tmFrame: Array,     // 帧查找表
 *   objects: Array,     // 骨骼对象
 * }}
 */
export function parseSmb(buffer) {
  const dv = new DataView(buffer);

  // ===== smDFILE_HEADER =====
  const header = readCString(dv, 0, 24);
  let o = 24;
  const objCounter = dv.getInt32(o, true); o += 4;
  const matCounter = dv.getInt32(o, true); o += 4;
  const matFilePoint = dv.getInt32(o, true); o += 4;
  const firstObjInfoPoint = dv.getInt32(o, true); o += 4;
  const tmFrameCounter = dv.getInt32(o, true); o += 4;
  const tmFrame = [];
  for (let i = 0; i < 32; i++) { tmFrame.push(readFramePos(dv, o)); o += 16; }

  // ===== smDFILE_OBJINFO[objCounter] =====
  const objInfos = [];
  for (let i = 0; i < objCounter; i++) {
    objInfos.push({
      nodeName: readCString(dv, o, 32),
      length: dv.getInt32(o + 32, true),
      objFilePoint: dv.getInt32(o + 36, true),
    });
    o += 40;
  }

  // ===== 材质组（若 matCounter>0）=====
  // 布局（对照 jpstale loader MaterialGroup.loadData / SmMaterial.loadData）：
  //   smMATERIAL_GROUP 头 88B；每个材质 320B；InUse!=0 时附加 4B strLen + 纹理名段
  const materials = matCounter > 0 ? parseMaterialGroup(dv, matFilePoint) : [];

  // ===== 每个骨骼对象 =====
  const objects = objInfos.map(info => parseObj3D(dv, info));

  return {
    header,
    objCounter,
    matCounter,
    matFilePoint,
    firstObjInfoPoint,
    tmFrameCounter,
    tmFrame,
    objInfos,
    materials,
    objects,
  };
}

/**
 * 解析 smMATERIAL_GROUP（材质组），返回材质数组
 * 每个材质: { texturePaths: [], twoSide, blendType, mapOpacity, textureType, transparency, selfIllum, animTexturePaths, animTexCount }
 * 纹理路径 = 材质里的路径（含原大小写），调用方加载时需转小写
 */
function parseMaterialGroup(dv, matFilePoint) {
  const materials = [];
  let o = matFilePoint;

  // smMATERIAL_GROUP 头
  const head = dv.getUint32(o, true); o += 4;
  const smMaterialPtr = dv.getUint32(o, true); o += 4;
  const materialCount = dv.getInt32(o, true); o += 4;
  o += 12; // reformTexture, maxMaterial, lastSearchMaterial
  o += 64; // lastSearchName[64]

  if (materialCount <= 0 || materialCount > 256) return materials;

  for (let m = 0; m < materialCount; m++) {
    const inUse = dv.getInt32(o, true); o += 4;
    const textureCounter = dv.getInt32(o, true); o += 4;
    o += 32; // smTexture* smTexture[8]
    o += 32; // TextureStageState[8]
    o += 32; // TextureFormState[8]
    o += 4;  // ReformTexture
    const mapOpacity = dv.getInt32(o, true); o += 4;
    const textureType = dv.getInt32(o, true); o += 4;
    const blendType = dv.getInt32(o, true); o += 4;
    o += 4;  // Shade
    const twoSide = dv.getInt32(o, true); o += 4;
    o += 4;  // SerialNum
    o += 12; // Diffuse RGB (float x3)
    const transparency = dv.getFloat32(o, true); o += 4;
    const selfIllum = dv.getFloat32(o, true); o += 4;
    o += 12; // TextureSwap, MatFrame, TextureClip
    o += 12; // UseState, MeshState, WindMeshBottom
    o += 128; // smAnimTexture* smAnimTexture[32]
    const animTexCounter = dv.getInt32(o, true); o += 4;
    o += 8;  // FrameMask, Shift_FrameSpeed
    o += 4;  // AnimationFrame

    const mat = {
      inUse,
      textureCounter,
      texturePaths: [],
      mapOpacity,
      textureType,
      blendType,
      twoSide,
      transparency,
      selfIllum,
      animTexCounter,
      animTexturePaths: [],
    };

    if (inUse !== 0) {
      const strLen = dv.getInt32(o, true); o += 4;
      const strStart = o;
      // 普通纹理：每项 Name + NameA（各为 C 字符串，strlen+1）
      for (let t = 0; t < textureCounter && o < strStart + strLen; t++) {
        const n1 = readCString(dv, o, 260); o += n1.length + 1;
        o += readCString(dv, o, 260).length + 1; // NameA
        if (n1) mat.texturePaths.push(n1);
      }
      // 动画纹理
      for (let t = 0; t < animTexCounter && o < strStart + strLen; t++) {
        const n1 = readCString(dv, o, 260); o += n1.length + 1;
        o += readCString(dv, o, 260).length + 1; // NameA
        if (n1) mat.animTexturePaths.push(n1);
      }
      // 对齐到 strLen
      o = strStart + strLen;
    }

    materials.push(mat);
  }
  return materials;
}

/** 解析单个 smOBJ3D（按 ObjFilePoint 偏移） */
function parseObj3D(dv, info) {
  let o = info.objFilePoint;

  // ===== smLegacyOBJ3D 固定头 =====
  const head = dv.getUint32(o, true); o += 4;
  const vertexPtr = dv.getUint32(o, true); o += 4;   // smVERTEX* Vertex
  const facePtr = dv.getUint32(o, true); o += 4;      // smFACE* Face
  const texLinkPtr = dv.getUint32(o, true); o += 4;   // smTEXLINK* TexLink
  const physiquePtr = dv.getUint32(o, true); o += 4;  // smOBJ3D** Physique
  const hasPhysique = physiquePtr !== 0;

  const zeroVertex = {
    x: dv.getInt32(o, true), y: dv.getInt32(o + 4, true), z: dv.getInt32(o + 8, true),
    nx: dv.getInt32(o + 12, true), ny: dv.getInt32(o + 16, true), nz: dv.getInt32(o + 20, true),
  }; o += 24;

  const maxZ = dv.getInt32(o, true); o += 4;
  const minZ = dv.getInt32(o, true); o += 4;
  const maxY = dv.getInt32(o, true); o += 4;
  const minY = dv.getInt32(o, true); o += 4;
  const maxX = dv.getInt32(o, true); o += 4;
  const minX = dv.getInt32(o, true); o += 4;
  const dBound = dv.getInt32(o, true); o += 4;
  const bound = dv.getInt32(o, true); o += 4;
  const maxVertex = dv.getInt32(o, true); o += 4;
  const maxFace = dv.getInt32(o, true); o += 4;
  const nVertex = dv.getInt32(o, true); o += 4;
  const nFace = dv.getInt32(o, true); o += 4;
  const nTexLink = dv.getInt32(o, true); o += 4;
  const colorEffect = dv.getInt32(o, true); o += 4;
  const clipStates = dv.getUint32(o, true); o += 4;

  // POINT3D Posi, CameraPosi, Angle（各 12B）
  const posi = { x: dv.getInt32(o, true), y: dv.getInt32(o + 4, true), z: dv.getInt32(o + 8, true) }; o += 12;
  const cameraPosi = { x: dv.getInt32(o, true), y: dv.getInt32(o + 4, true), z: dv.getInt32(o + 8, true) }; o += 12;
  const angle = { x: dv.getInt32(o, true), y: dv.getInt32(o + 4, true), z: dv.getInt32(o + 8, true) }; o += 12;

  const trig = [];
  for (let i = 0; i < 8; i++) { trig.push(dv.getInt32(o, true)); o += 4; }

  const nodeName = readCString(dv, o, 32); o += 32;
  const nodeParent = readCString(dv, o, 32); o += 32;
  const pParentPtr = dv.getUint32(o, true); o += 4;   // 指针占位（内存值）

  const tm = readIntMatrix(dv, o); o += 64;
  const tmInvert = readIntMatrix(dv, o); o += 64;
  const tmResult = readFloatMatrix(dv, o); o += 64;
  const tmRotate = readIntMatrix(dv, o); o += 64;
  const mWorld = readIntMatrix(dv, o); o += 64;
  const mLocal = readIntMatrix(dv, o); o += 64;

  const lFrame = dv.getInt32(o, true); o += 4;

  // 绑定姿态
  const qx = dv.getFloat32(o, true); o += 4;
  const qy = dv.getFloat32(o, true); o += 4;
  const qz = dv.getFloat32(o, true); o += 4;
  const qw = dv.getFloat32(o, true); o += 4;
  const sx = dv.getInt32(o, true) / 256; o += 4;
  const sy = dv.getInt32(o, true) / 256; o += 4;
  const sz = dv.getInt32(o, true) / 256; o += 4;
  const px = dv.getInt32(o, true) / 256; o += 4;
  const py = dv.getInt32(o, true) / 256; o += 4;
  const pz = dv.getInt32(o, true) / 256; o += 4;

  // 指针占位
  const tmRotPtr = dv.getUint32(o, true); o += 4;
  const tmPosPtr = dv.getUint32(o, true); o += 4;
  const tmScalePtr = dv.getUint32(o, true); o += 4;
  const tmPrevRotPtr = dv.getUint32(o, true); o += 4;

  const tmRotCnt = dv.getInt32(o, true); o += 4;
  const tmPosCnt = dv.getInt32(o, true); o += 4;
  const tmScaleCnt = dv.getInt32(o, true); o += 4;

  const tmRotFrame = [];
  for (let i = 0; i < 32; i++) { tmRotFrame.push(readFramePos(dv, o)); o += 16; }
  const tmPosFrame = [];
  for (let i = 0; i < 32; i++) { tmPosFrame.push(readFramePos(dv, o)); o += 16; }
  const tmScaleFrame = [];
  for (let i = 0; i < 32; i++) { tmScaleFrame.push(readFramePos(dv, o)); o += 16; }

  const tmFrameCnt = dv.getInt32(o, true); o += 4;

  // ===== 变长数据 =====
  // 顶点 smVERTEX: int x,y,z,nx,ny,nz
  const vertices = [];
  for (let i = 0; i < nVertex; i++) {
    vertices.push({
      x: dv.getInt32(o, true) / 256, y: dv.getInt32(o + 4, true) / 256, z: dv.getInt32(o + 8, true) / 256,
      nx: dv.getInt32(o + 12, true) / 256, ny: dv.getInt32(o + 16, true) / 256, nz: dv.getInt32(o + 20, true) / 256,
    });
    o += 24;
  }

  // 面 smLegacyFACE: WORD v[4], smFTPOINT t[3](float u,v), DWORD lpTexLink
  const faces = [];
  for (let i = 0; i < nFace; i++) {
    faces.push({
      v: [dv.getUint16(o, true), dv.getUint16(o + 2, true), dv.getUint16(o + 4, true), dv.getUint16(o + 6, true)],
      t: [
        { u: dv.getFloat32(o + 8, true), v: dv.getFloat32(o + 12, true) },
        { u: dv.getFloat32(o + 16, true), v: dv.getFloat32(o + 20, true) },
        { u: dv.getFloat32(o + 24, true), v: dv.getFloat32(o + 28, true) },
      ],
      lpTexLink: dv.getUint32(o + 32, true),
    });
    o += 36;
  }

  // 纹理 smLegacyTEXLINK: float u[3],v[3], DWORD hTexture, DWORD NextTex
  const texLinks = [];
  for (let i = 0; i < nTexLink; i++) {
    texLinks.push({
      u: [dv.getFloat32(o, true), dv.getFloat32(o + 4, true), dv.getFloat32(o + 8, true)],
      v: [dv.getFloat32(o + 12, true), dv.getFloat32(o + 16, true), dv.getFloat32(o + 20, true)],
      hTexture: dv.getUint32(o + 24, true),
      nextTex: dv.getUint32(o + 28, true),
    });
    o += 32;
  }

  // 旋转关键帧 smTM_ROT: int frame, float x,y,z,w（标准四元数增量）
  const tmRot = [];
  for (let i = 0; i < tmRotCnt; i++) {
    tmRot.push({
      frame: dv.getInt32(o, true),
      x: dv.getFloat32(o + 4, true),
      y: dv.getFloat32(o + 8, true),
      z: dv.getFloat32(o + 12, true),
      w: dv.getFloat32(o + 16, true),
    });
    o += 20;
  }

  // 位移关键帧 smTM_POS: int frame, float x,y,z
  const tmPos = [];
  for (let i = 0; i < tmPosCnt; i++) {
    tmPos.push({
      frame: dv.getInt32(o, true),
      x: dv.getFloat32(o + 4, true),
      y: dv.getFloat32(o + 8, true),
      z: dv.getFloat32(o + 12, true),
    });
    o += 16;
  }

  // 缩放关键帧 smTM_SCALE: int frame, int x,y,z（定点）
  const tmScale = [];
  for (let i = 0; i < tmScaleCnt; i++) {
    tmScale.push({
      frame: dv.getInt32(o, true),
      x: dv.getInt32(o + 4, true) / 256,
      y: dv.getInt32(o + 8, true) / 256,
      z: dv.getInt32(o + 12, true) / 256,
    });
    o += 16;
  }

  // 每关键帧绝对旋转矩阵 smFMATRIX[TmRotCnt]（float）
  const tmPrevRot = [];
  for (let i = 0; i < tmRotCnt; i++) {
    tmPrevRot.push(readFloatMatrix(dv, o)); o += 64;
  }

  // 每顶点骨骼名（若有 Physique）
  const boneNames = hasPhysique ? [] : null;
  for (let i = 0; i < nVertex && hasPhysique; i++) {
    boneNames.push(readCString(dv, o, 32)); o += 32;
  }

  return {
    info,
    nodeName,
    nodeParent,
    hasPhysique,
    nVertex, nFace, nTexLink,
    texLinkPtr,
    tmFrameCnt,
    vertices, faces, texLinks,
    tmRot, tmPos, tmScale, tmPrevRot,
    tmRotFrame, tmPosFrame, tmScaleFrame,
    boneNames,
    bindQuat: { x: qx, y: qy, z: qz, w: qw },
    bindScale: { x: sx, y: sy, z: sz },
    bindPos: { x: px, y: py, z: pz },
    tm: { m: tm },
    tmInvert: { m: tmInvert },
    tmRotate: { m: tmRotate },
    tmResult: tmResult,
    head, posi, cameraPosi, angle,
  };
}
