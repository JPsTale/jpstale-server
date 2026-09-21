/**
 * PT 模型 → Three.js SkinnedMesh 构建器
 *
 * 严格依据 exm C++ 源码：
 *  - smOBJ3D::WorldForm（smObj3d.cpp:1320）顶点蒙皮变换：
 *      result.x = x*_11 + y*_21 + z*_31
 *      result.y = x*_12 + y*_22 + z*_32
 *      result.z = x*_13 + y*_23 + z*_33
 *      输出 smWorldVertex = (result.x + _41, result.z + _43, result.y + _42)
 *      → 即 three.js 坐标 (x, z, y)：引擎 Z-up → three Y-up
 *  - smMatrixMult（smmatrix.cpp:30）：矩阵为「行主序」存储（_ij 在 m[(i-1)*4+(j-1)]）
 *  - 位移在 _41,_42,_43 = m[12],m[13],m[14]
 *  - 骨骼矩阵为「模型空间绝对矩阵」（Tm）
 *
 * 支持多网格对象：每个有顶点的 GeomObject 独立构建一个 SkinnedMesh，
 * 因为各对象顶点/boneNames/位移独立。
 */
import * as THREE from 'three';
import { loadTexture } from './texture.js';
import { evalSkeleton } from './animation.js';

export function buildSkinnedMesh(smd, smb, meshNames, rawMode, sharedSkel) {
  // ===== 1. 骨骼（可复用 sharedSkel 以支持 LOD 多套网格共用一副骨骼） =====
  const skel = sharedSkel || buildSkeleton(smb, rawMode);
  const { bones, skeleton, skeletonGroup, boneByObj, bindLocalByName, bindWorldByName, boneIndexByName } = skel;
  const objByName = new Map();
  smb.objects.forEach(obj => objByName.set(obj.nodeName, obj));

  // ===== 2. 每个网格对象独立构建 mesh（按 .inx ModelGroup 过滤） =====
  let meshObjs = smd.objects.filter(o => o.nVertex > 0);
  // 若指定了网格名（.inx 的 ModelGroup），只渲染列出的对象
  if (meshNames && meshNames.length > 0) {
    const filtered = meshObjs.filter(o => {
      // C++ _strcmpi 语义：大小写不敏感匹配
      const lower = o.nodeName.toLowerCase();
      return meshNames.some(n => n.toLowerCase() === lower);
    });
    // 匹配不到时回退加载全部（死亡变体等网格名可能不同）
    if (filtered.length > 0) {
      meshObjs = filtered;
    }
  }
  if (meshObjs.length === 0) throw new Error('网格对象无顶点（.inx 指定的网格未找到）');

  const boneMatByName = new Map();
  bones.forEach(b => boneMatByName.set(b.userData.nodeName, b.userData.bindMatrixRowMajor));

  // 每个对象的 Group
  const group = new THREE.Group();
  const meshes = [];
  const texturesToLoad = [];

  // 顶点变换辅助（引擎坐标 → 输出坐标）
  const transformVertex = (rx, ry, rz) => rawMode ? [rx, ry, rz] : [rx, rz, -ry];
  const transformNormal = (fx, fy, fz) => rawMode ? [fx, fy, fz] : [fx, fz, -fy];

  for (const meshObj of meshObjs) {
    // 该对象的材质数 = 所有 face 用到的材质索引范围（含 smd.materials）
    const objMats = (smd.materials || []);
    // 收集该对象用到的材质索引
    const usedMatIdx = new Set();
    for (const f of meshObj.faces) {
      const mi = f.v[3];
      if (mi >= 0 && mi < objMats.length) usedMatIdx.add(mi);
      else usedMatIdx.add(-1); // 无材质 → 默认
    }
    const matIdxs = [...usedMatIdx];

    // 每个材质一个 mesh
    for (const matIdx of matIdxs) {
      const positions = [], normals = [], uvs = [], skinIndices = [], skinWeights = [], indices = [];

      // 遍历面，收集属于该材质的三角形
      let triCount = 0;
      meshObj.faces.forEach((f, fi) => {
        const mi = f.v[3];
        const isThisMat = (mi >= 0 && mi < objMats.length) ? (mi === matIdx) : (matIdx === -1);
        if (!isThisMat) return;

        // TEXLINK 关联：face.lpTexLink 是内存指针，需按 (lpTexLink - texLinkPtr)/32 找索引
        // 对照 jpstale GeomObject.relinkFaceAndTex
        let tl = null;
        if (meshObj.texLinkPtr && f.lpTexLink) {
          const tlIdx = (f.lpTexLink - meshObj.texLinkPtr) / 32;
          if (tlIdx >= 0 && tlIdx < meshObj.texLinks.length) tl = meshObj.texLinks[tlIdx];
        }
        if (!tl) tl = meshObj.texLinks[fi]; // 兜底：指针信息缺失时按 face 索引
        // 非索引：每 face 顶点独立写入（UV 是按面的）
        for (let k = 0; k < 3; k++) {
          const vidx = f.v[k];
          const v = meshObj.vertices[vidx];
          const name = meshObj.boneNames && meshObj.boneNames[vidx] ? meshObj.boneNames[vidx] : '';
          // 顶点预乘 = 骨骼 bindWorld（evalSkeleton frame 0，PrevRot 体系），与骨骼绑定/动画一致
          const m = bindWorldByName.has(name) ? bindWorldByName.get(name) : bindWorldByName.values().next().value;

          const lx = v.x, ly = v.y, lz = v.z;
          const rx = lx*m[0] + ly*m[4] + lz*m[8]  + m[12];
          const ry = lx*m[1] + ly*m[5] + lz*m[9]  + m[13];
          const rz = lx*m[2] + ly*m[6] + lz*m[10] + m[14];
          positions.push(...transformVertex(rx, ry, rz));

          const nx = v.nx, ny = v.ny, nz = v.nz;
          const fx = nx*m[0] + ny*m[4] + nz*m[8];
          const fy = nx*m[1] + ny*m[5] + nz*m[9];
          const fz = nx*m[2] + ny*m[6] + nz*m[10];
          normals.push(...transformNormal(fx, fy, fz));

          if (tl) {
            uvs.push(tl.u[k], 1.0 - tl.v[k]);
          } else {
            uvs.push(0, 0);
          }

          const boneIdx = boneIndexByName.has(name) ? boneIndexByName.get(name) : 0;
          skinIndices.push(boneIdx, 0, 0, 0);
          skinWeights.push(1, 0, 0, 0);
        }
        indices.push(triCount * 3, triCount * 3 + 1, triCount * 3 + 2);
        triCount++;
      });

      if (triCount === 0) continue;

      const geo = new THREE.BufferGeometry();
      geo.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
      geo.setAttribute('normal', new THREE.Float32BufferAttribute(normals, 3));
      geo.setAttribute('uv', new THREE.Float32BufferAttribute(uvs, 2));
      geo.setAttribute('skinIndex', new THREE.Uint16BufferAttribute(skinIndices, 4));
      geo.setAttribute('skinWeight', new THREE.Float32BufferAttribute(skinWeights, 4));
      geo.setIndex(indices);

      // 材质（纹理异步加载，先纯色占位）
      const material = objMats[matIdx];
      const mat = new THREE.MeshPhongMaterial({ color: 0x8899aa, side: THREE.DoubleSide });
      if (matIdx >= 0 && material) {
        if (material.twoSide === 1) mat.side = THREE.DoubleSide;
        else mat.side = THREE.FrontSide;
        // 混合模式（对照 C++ EXERender::SetStateRender + Java setRenderState）：
        // blendType 0=None, 1=Alpha, 2=Color, 3=Shadow, 4=Lamp, 5=AddColor, 6=InvShadow
        if (material.blendType === 4 || material.blendType === 5) {
          mat.transparent = true;
          mat.blending = THREE.AdditiveBlending;
        } else if (material.blendType === 1) {
          mat.transparent = true;
          mat.blending = THREE.NormalBlending;
        }
        // alpha 裁剪（C++ ALPHATEST / Java AlphaDiscardThreshold=0.75）：
        // 纹理加载后若为 tga/png（有 alpha 通道）启用 alphaTest，bmp 靠黑色 colorkey
        if (material.texturePaths && material.texturePaths.length > 0) {
          texturesToLoad.push({ url: material.texturePaths[0], mat, nodeName: meshObj.nodeName });
        }
      }

      const mesh = new THREE.SkinnedMesh(geo, mat);
      mesh.userData.nodeName = meshObj.nodeName;
      mesh.userData.materialIndex = matIdx;
      group.add(mesh);
      meshes.push(mesh);
    }
  }

  // 所有 mesh 绑定到骨架（sharedSkel 时复用同一骨架）
  for (const m of meshes) {
    m.bind(skeleton);
  }
  group.userData.smd = smd;
  group.userData.smb = smb;
  return { group, meshes, skeleton, bones, texturesToLoad, skeletonGroup };
}

/**
 * 构建骨骼（供 LOD 多套网格共用同一副骨骼）
 * 返回 { bones, skeleton, skeletonGroup, boneByObj, bindLocalByName, bindWorldByName, boneIndexByName }
 */
export function buildSkeleton(smb, rawMode) {
  const bones = [];
  const objByName = new Map();
  smb.objects.forEach(obj => objByName.set(obj.nodeName, obj));

  const boneByObj = new Map();
  for (const obj of smb.objects) {
    const bone = new THREE.Bone();
    bone.name = obj.nodeName || ('bone' + bones.length);
    bone.userData.nodeName = obj.nodeName;
    bone.userData.obj = obj;
    bone.userData.bindMatrixRowMajor = intToFloat(obj.tm.m);
    boneByObj.set(obj, bone);
    bones.push(bone);
  }

  // 父子层级
  for (const obj of smb.objects) {
    const bone = boneByObj.get(obj);
    if (obj.nodeParent) {
      const p = objByName.get(obj.nodeParent);
      if (p && boneByObj.has(p)) boneByObj.get(p).add(bone);
    }
  }

  const boneIndexByName = new Map();
  bones.forEach((b, i) => boneIndexByName.set(b.userData.nodeName, i));

  // 绑定基准统一用 evalSkeleton(frame 0)（PrevRot/bindQuat 体系），与动画一致
  const bindSkel = evalSkeleton(smb, 0, true);
  const bindLocalByName = new Map(bindSkel.map(sf => [sf.name, sf.local]));
  const bindWorldByName = new Map();
  for (const sf of bindSkel) {
    bindWorldByName.set(sf.name, sf.world);
  }

  const skeleton = new THREE.Skeleton(bones);

  // 设置骨骼矩阵（rawMode 决定是否 toYup）
  const tmp = new THREE.Matrix4();
  const posV = new THREE.Vector3(), quatQ = new THREE.Quaternion(), sclV = new THREE.Vector3();
  const boneWorld = (rowMajor) => rawMode ? rowMajor : toYup(rowMajor);
  for (const obj of smb.objects) {
    const bone = boneByObj.get(obj);
    const local = bindLocalByName.get(obj.nodeName);
    if (local) {
      tmp.fromArray(boneWorld(local));
      tmp.decompose(posV, quatQ, sclV);
      bone.position.copy(posV);
      bone.quaternion.copy(quatQ);
      bone.scale.copy(sclV);
    }
    bone.matrixWorldNeedsUpdate = true;
  }
  bones.forEach(b => { b.updateMatrixWorld(true); });
  skeleton.calculateInverses();

  const skeletonGroup = new THREE.Group();
  skeletonGroup.add(bones[0]);
  bones.forEach(b => { b.updateMatrixWorld(true); });

  return { bones, skeleton, skeletonGroup, boneByObj, bindLocalByName, bindWorldByName, boneIndexByName };
}

/** int 矩阵（行主序，定点）→ float */
function intToFloat(intM) {
  return intM.map(v => v / 256);
}

/** 引擎 Z-up 矩阵（行主序）→ Y-up 矩阵（行主序）：
 *  WorldForm 输出映射 out=(rx,rz,ry)，列重排：新col1=旧col2(z)，新col2=旧col1(y)
 */
// R = 绕 X 轴 -90°（行主序 4x4）：引擎坐标 (x,y,z) → GL (x, z, -y)
// R·v 用于顶点；骨骼矩阵用相似变换 M' = R·M·R⁻¹（已验证蒙皮不变性：R·(M·v) = (R·M·R⁻¹)·(R·v)）
const ROT_X_NEG90 = [
  1, 0, 0, 0,
  0, 0, 1, 0,
  0, -1, 0, 0,
  0, 0, 0, 1,
];
const ROT_X_NEG90_INV = [
  1, 0, 0, 0,
  0, 0, -1, 0,
  0, 1, 0, 0,
  0, 0, 0, 1,
];

function matMulRow(a, b) {
  const m = new Array(16).fill(0);
  for (let i = 0; i < 4; i++)
    for (let j = 0; j < 4; j++)
      for (let k = 0; k < 4; k++)
        m[j * 4 + i] += a[j * 4 + k] * b[k * 4 + i];
  return m;
}

function toYup(rm) {
  return matMulRow(ROT_X_NEG90, matMulRow(rm, ROT_X_NEG90_INV));
}
