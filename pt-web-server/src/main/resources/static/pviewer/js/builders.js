/**
 * 调试用构建器：分别验证 mesh 和骨骼
 *
 *  - buildRawMesh: 加载 .smd，不蒙皮，直接用原始顶点渲染
 *  - buildBoneLines: 加载 .smb，用骨骼绝对矩阵的位移画线条
 */
import * as THREE from 'three';

/**
 * 无蒙皮原始网格：直接用 .smd 顶点渲染（用于验证 mesh 数据本身）
 *  - 先用线框(LineSegments)展示，避免法线/材质干扰
 *  - 顶点按 C++ 引擎坐标 (x,y,z) → three (x, z, -y)（绕 X -90°）
 */
export function buildRawMesh(smd) {
  const meshObj = smd.objects.find(o => o.nVertex > 0);
  if (!meshObj) throw new Error('网格对象无顶点');

  const positions = [];
  for (const v of meshObj.vertices) {
    positions.push(v.x, v.z, -v.y); // 绕 X -90°
  }

  const indices = [];
  for (const f of meshObj.faces) {
    indices.push(f.v[0], f.v[1], f.v[2]); // v[3] 是材质号，不是顶点
  }

  const geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
  geo.setIndex(indices);

  // 线框
  const edges = new THREE.EdgesGeometry(geo);
  const lines = new THREE.LineSegments(edges, new THREE.LineBasicMaterial({
    color: 0x88ccff,
    linewidth: 1,
  }));

  // 顶点点云（可选，便于看顶点位置）
  const points = new THREE.Points(
    geo,
    new THREE.PointsMaterial({ color: 0xffaa44, size: 0.3 })
  );

  const group = new THREE.Group();
  group.add(lines);
  group.add(points);
  group.userData.meshObj = meshObj;

  // 包围盒（用于相机对准）
  geo.computeBoundingBox();
  return { group, boundingBox: geo.boundingBox };
}

/**
 * 骨骼线条：用每个骨骼的绝对矩阵（Tm）的位移，画父→子的连线
 *  - 每个骨骼一个关节球 + 父子连线
 *  - C++ 确认：.smb 里每个骨骼的 Tm 是「模型空间绝对矩阵」
 *  - 矩阵为「列主序」：_ij 在 m[(j-1)*4+(i-1)]，位移 _41,_42,_43 在 m[12],m[13],m[14]
 */
export function buildBoneLines(smb) {
  const objByName = new Map();
  smb.objects.forEach(obj => objByName.set(obj.nodeName, obj));

  // 关节位置 = 绝对矩阵 Tm 的位移（列主序 m[12],m[13],m[14]）
  // 绕 X -90°（R·v = (x,z,-y)）：引擎 Z-up → GL Y-up
  const joints = [];
  for (const obj of smb.objects) {
    const m = intToFloat(obj.tm.m);
    const tx = m[12], ty = m[13], tz = m[14];
    joints.push({
      name: obj.nodeName,
      pos: [tx, tz, -ty], // R·v = (x, z, -y)
      obj,
    });
  }

  // 骨骼球（每关节一个小球，尺寸按模型比例）
  const spheres = joints.map(j => {
    const s = new THREE.Mesh(
      new THREE.SphereGeometry(1.2, 10, 10),
      new THREE.MeshBasicMaterial({ color: 0xff4444 })
    );
    s.position.set(...j.pos);
    return s;
  });

  // 父子连线
  const linePositions = [];
  const jointByName = new Map();
  joints.forEach(j => jointByName.set(j.name, j));
  for (const j of joints) {
    if (j.obj.nodeParent && jointByName.has(j.obj.nodeParent)) {
      const p = jointByName.get(j.obj.nodeParent);
      linePositions.push(...p.pos, ...j.pos);
    }
  }

  const lineGeo = new THREE.BufferGeometry();
  lineGeo.setAttribute('position', new THREE.Float32BufferAttribute(linePositions, 3));
  const lines = new THREE.LineSegments(
    lineGeo,
    new THREE.LineBasicMaterial({ color: 0xff8844, linewidth: 2 })
  );

  const group = new THREE.Group();
  spheres.forEach(s => group.add(s));
  group.add(lines);
  group.userData.joints = joints;

  return { group, joints };
}

function intToFloat(intM) {
  return intM.map(v => v / 256);
}
