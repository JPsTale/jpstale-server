/**
 * PT 纸娃娃系统 — 武器加载器
 *
 * 加载武器 DropItem .smd，构建静态 Mesh 挂到骨骼上。
 * 依据 C++ 源码：
 *  - character.cpp:SetTool — sinGetItemInfo → "Image\sinImage\Items\DropItem\it{DorpItem}.ASE"
 *  - character.cpp:RenderD3D — LinkParentObject(AnimPattern, ObjBip) → 父子跟随
 *  - 骨骼名："Bip weapon01"（右手武器），"Bip01 L Hand"（左手），"Bip01 L Forearm"（盾）
 */

import * as THREE from 'three';
import { parseSmb } from './smb-parser.js';

const EXM_RUN = '/pt/exm-run/';
const DROPITEM_DIR = 'image/sinimage/items/dropitem/';

/** fetch + ArrayBuffer */
async function fetchAB(url) {
  const resp = await fetch(url, { cache: 'no-store' });
  if (!resp.ok) throw new Error(`HTTP ${resp.status}: ${url}`);
  return (await resp.arrayBuffer()).slice(0);
}

/**
 * 引擎 Z-up 行主序矩阵 → Y-up 行主序
 * 同 skinned-builder.js 的 toYup
 */
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

/** int 矩阵 → float */
function intToFloat(intM) {
  return intM.map(v => v / 256);
}

/**
 * 加载武器模型（.smd），构建静态 Three.js Group
 *
 * 武器 .smd 格式与角色相同（parseSmb），但无骨骼动画，
 * 顶点直接在武器局部空间（origin = 握把，+Z = 刀刃方向）。
 *
 * @param {string} dorpItem 武器模型代码，如 "WA102"、"WS201"
 * @returns {Promise<{group: THREE.Group, texturesToLoad: Array}>}
 */
export async function loadWeaponModel(dorpItem) {
  const smdName = 'it' + dorpItem.toLowerCase();
  const url = EXM_RUN + DROPITEM_DIR + smdName + '.smd';

  const buf = await fetchAB(url);
  const smd = parseSmb(buf);

  const group = new THREE.Group();
  group.name = 'weapon_' + dorpItem;
  const texturesToLoad = [];

  const meshObjs = smd.objects.filter(o => o.nVertex > 0);
  if (meshObjs.length === 0) {
    throw new Error('武器模型无顶点: ' + dorpItem);
  }

  const objMats = smd.materials || [];

  for (const meshObj of meshObjs) {
    // 收集该对象用到的材质索引
    const usedMatIdx = new Set();
    for (const f of meshObj.faces) {
      const mi = f.v[3];
      if (mi >= 0 && mi < objMats.length) usedMatIdx.add(mi);
      else usedMatIdx.add(-1);
    }
    const matIdxs = [...usedMatIdx];

    for (const matIdx of matIdxs) {
      const positions = [], normals = [], uvs = [], indices = [];
      let triCount = 0;

      meshObj.faces.forEach((f, fi) => {
        const mi = f.v[3];
        const isThisMat = (mi >= 0 && mi < objMats.length) ? (mi === matIdx) : (matIdx === -1);
        if (!isThisMat) return;

        let tl = null;
        if (meshObj.texLinkPtr && f.lpTexLink) {
          const tlIdx = (f.lpTexLink - meshObj.texLinkPtr) / 32;
          if (tlIdx >= 0 && tlIdx < meshObj.texLinks.length) tl = meshObj.texLinks[tlIdx];
        }
        if (!tl) tl = meshObj.texLinks[fi];

        for (let k = 0; k < 3; k++) {
          const vidx = f.v[k];
          const v = meshObj.vertices[vidx];

          // Z-up → Y-up 坐标转换
          positions.push(v.x, v.z, -v.y);
          normals.push(v.nx, v.nz, -v.ny);

          if (tl) {
            uvs.push(tl.u[k], 1.0 - tl.v[k]);
          } else {
            uvs.push(0, 0);
          }
        }
        indices.push(triCount * 3, triCount * 3 + 1, triCount * 3 + 2);
        triCount++;
      });

      if (triCount === 0) continue;

      const geo = new THREE.BufferGeometry();
      geo.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
      geo.setAttribute('normal', new THREE.Float32BufferAttribute(normals, 3));
      geo.setAttribute('uv', new THREE.Float32BufferAttribute(uvs, 2));
      geo.setIndex(indices);

      const material = objMats[matIdx];
      const mat = new THREE.MeshPhongMaterial({ color: 0x8899aa, side: THREE.DoubleSide });
      if (matIdx >= 0 && material) {
        if (material.twoSide === 1) mat.side = THREE.DoubleSide;
        else mat.side = THREE.FrontSide;
        if (material.blendType === 4 || material.blendType === 5) {
          mat.transparent = true;
          mat.blending = THREE.AdditiveBlending;
        } else if (material.blendType === 1) {
          mat.transparent = true;
        }
        if (material.texturePaths && material.texturePaths.length > 0) {
          texturesToLoad.push({ url: material.texturePaths[0], mat, nodeName: meshObj.nodeName });
        }
      }

      const mesh = new THREE.Mesh(geo, mat);
      mesh.name = meshObj.nodeName || 'weapon_part';
      group.add(mesh);
    }
  }

  return { group, texturesToLoad };
}

/**
 * 在骨架骨骼树中查找骨骼 Object3D（大小写不敏感）
 * @param {THREE.Object3D} root 根节点（skeletonGroup 或 bones[0]）
 * @param {string} name 骨骼名
 * @returns {THREE.Object3D|null}
 */
export function findBone(root, name) {
  const lower = name.toLowerCase();
  let found = null;
  root.traverse(obj => {
    if (!found && obj.name && obj.name.toLowerCase() === lower) {
      found = obj;
    }
  });
  return found;
}

/**
 * 武器挂载点名称（与 C++ szBipName_* 对应）
 */
export const WEAPON_BONES = {
  RIGHT_HAND: 'Bip weapon01',
  LEFT_HAND: 'Bip01 L Hand',
  SHIELD: 'Bip01 L Forearm',
  ASSASSIN_LEFT: 'Bip weapon05',
};
