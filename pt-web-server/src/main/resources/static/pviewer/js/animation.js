/**
 * PT 骨骼动画求值器
 *
 * 严格依据 exm C++ 源码：
 *  - smOBJ3D::GetRotFrame（smObj3d.cpp:800）：
 *      Slerp(零四元数→下一帧四元数B, alpha) × PrevRot[cnt]
 *  - smOBJ3D::GetPosFrame / GetScaleFrame（smObj3d.cpp:851/881）：线性插值
 *  - smOBJ3D::TmAnimation（smObj3d.cpp:1021）：
 *      TmResult = qmat × pParent->TmResult（层级累积）
 *  - smOBJ3D::GetTmFrameRot（smObj3d.cpp:945）：TmRotFrame 查找表
 *
 * 矩阵布局（数据验证确认）：
 *  - smFMATRIX 行主序：_ij 在 m[(i-1)*4+(j-1)]，位移 _41,_42,_43 在 m[12],m[13],m[14]
 *  - PrevRot[cnt] = 关键帧 cnt 的绝对旋转矩阵
 *  - PrevRot[cnt+1] = quat[cnt+1]矩阵(行主序) × PrevRot[cnt]
 *  - 帧号是 160 的倍数（1tick=160帧）
 */

/** 四元数 → 旋转矩阵（行主序 float），与 C++ smFMatrixFromQuaternion 一致 */
export function quatToMatrixRow(x, y, z, w) {
  const xx = x*x, yy = y*y, zz = z*z;
  const xy = x*y, xz = x*z, yz = y*z;
  const wx = w*x, wy = w*y, wz = w*z;
  return [
    1-2*(yy+zz), 2*(xy-wz), 2*(xz+wy), 0,
    2*(xy+wz), 1-2*(xx+zz), 2*(yz-wx), 0,
    2*(xz-wy), 2*(yz+wx), 1-2*(xx+yy), 0,
    0, 0, 0, 1,
  ];
}

/** 四元数 Slerp（C++ D3DMath_QuaternionSlerp，A=零四元数） */
function quatSlerpFromZero(bx, by, bz, bw, alpha) {
  // A = (0,0,0,0)，A·B = 0 → cosθ = 0 → θ = π/2
  const fTheta = Math.PI / 2;
  const fSinTheta = 1;
  const fScale1 = Math.sin(fTheta * (1.0 - alpha)) / fSinTheta;
  const fScale2 = Math.sin(fTheta * alpha) / fSinTheta;
  // 结果 = A×scale1 + B×scale2 = B×scale2（A 是零四元数）
  return { x: bx * fScale2, y: by * fScale2, z: bz * fScale2, w: bw * fScale2 };
}

/** 行主序矩阵乘法 m = a × b（C++ smFMatrixMult） */
export function matMulRow(a, b) {
  const m = new Array(16).fill(0);
  for (let i = 0; i < 4; i++)
    for (let j = 0; j < 4; j++)
      for (let k = 0; k < 4; k++)
        m[j*4+i] += a[j*4+k] * b[k*4+i];
  return m;
}

/** GetTmFrameRot（smObj3d.cpp:945）：用 tmRotFrame 查找表定位 posNum */
function getTmFrameRot(obj, frame) {
  if (obj.tmFrameCnt > 0 && obj.tmRotFrame) {
    for (let i = 0; i < obj.tmRotFrame.length; i++) {
      const f = obj.tmRotFrame[i];
      if (f.posCnt > 0 && f.startFrame <= frame && f.endFrame > frame) {
        return f.posNum;
      }
    }
  }
  return -1;
}

/**
 * 计算骨骼在指定帧的旋转矩阵（行主序 float，绝对）
 * 严格按 GetRotFrame：Slerp(0→B) × PrevRot[cnt]
 */
function getRotMatrix(obj, frame) {
  const tmRot = obj.tmRot;
  const tmPrevRot = obj.tmPrevRot;
  if (!tmRot || tmRot.length === 0 || !tmPrevRot || tmPrevRot.length === 0) {
    // 无旋转动画：用 TmRotate（绑定旋转矩阵，C++ smObj3d.cpp:1094 smFMatrixFromMatrix(qmat, TmRotate)）
    // 注意：不是 Tm（绝对绑定矩阵），TmRotate 才是局部绑定旋转
    const m = obj.tmRotate.m;
    return [m[0]/256, m[1]/256, m[2]/256, 0, m[4]/256, m[5]/256, m[6]/256, 0, m[8]/256, m[9]/256, m[10]/256, 0, 0, 0, 0, 1];
  }

  // 找 posNum
  let num = getTmFrameRot(obj, frame);
  if (num < 0) num = 0;

  // 在 tmRot[num..] 找 s<=frame<e
  let cnt = num;
  if (tmRot[cnt].frame > frame) {
    return tmPrevRot[0].slice();
  }
  let s, e;
  while (true) {
    if (cnt + 1 >= tmRot.length) break;
    s = tmRot[cnt].frame;
    e = tmRot[cnt + 1].frame;
    if (s <= frame && e > frame) break;
    cnt++;
  }
  if (cnt + 1 >= tmRot.length) {
    return tmPrevRot[tmPrevRot.length - 1].slice();
  }

  const ch = e - s;
  const sh = frame - s;
  const alpha = ch > 0 ? sh / ch : 0;

  // Slerp(0 → B)，B = tmRot[cnt+1]（.smb 里已是四元数：w=cos(θ/2)）
  const b = tmRot[cnt + 1];
  const q = quatSlerpFromZero(b.x, b.y, b.z, b.w, alpha);
  const qMat = quatToMatrixRow(q.x, q.y, q.z, q.w);

  // gmat = PrevRot[cnt] × qMat
  // C++ smObj3d.cpp:846 smFMatrixMult(gmat, PrevRot[cnt], gmat) —— PrevRot 在左
  return matMulRow(tmPrevRot[cnt], qMat);
}

/** 位移插值（GetPosFrame，引擎坐标 x,y,z） */
function getPos(obj, frame) {
  const tmPos = obj.tmPos;
  if (!tmPos || tmPos.length === 0) {
    return { x: obj.bindPos.x, y: obj.bindPos.y, z: obj.bindPos.z };
  }
  if (tmPos[0].frame > frame) return { x: tmPos[0].x, y: tmPos[0].y, z: tmPos[0].z };
  let cnt = 0, s, e;
  while (true) {
    if (cnt + 1 >= tmPos.length) break;
    s = tmPos[cnt].frame;
    e = tmPos[cnt + 1].frame;
    if (s <= frame && e > frame) break;
    cnt++;
  }
  if (cnt + 1 >= tmPos.length) {
    const last = tmPos[tmPos.length - 1];
    return { x: last.x, y: last.y, z: last.z };
  }
  const alpha = (frame - s) / (e - s);
  return {
    x: tmPos[cnt].x + (tmPos[cnt+1].x - tmPos[cnt].x) * alpha,
    y: tmPos[cnt].y + (tmPos[cnt+1].y - tmPos[cnt].y) * alpha,
    z: tmPos[cnt].z + (tmPos[cnt+1].z - tmPos[cnt].z) * alpha,
  };
}

/**
 * 计算整副骨骼在指定帧的矩阵（行主序 float）
 * rawMode=false: 转 Y-up（旋转 toYupRow），用于 GL 显示
 * rawMode=true:  保持引擎坐标（Z-up），不转换
 * 返回: [{ name, local, world, pos }]
 */
export function evalSkeleton(smb, frame, rawMode) {
  const byName = new Map(smb.objects.map(o => [o.nodeName, o]));
  const result = new Map();

  function calc(obj) {
    if (result.has(obj.nodeName)) return result.get(obj.nodeName);

    // 旋转（Z-up 行主序）
    const rotZup = getRotMatrix(obj, frame);
    const pos = getPos(obj, frame);

    let localMat;
    if (rawMode) {
      // 引擎坐标：旋转 + 位移（tmPos 原始）
      localMat = rotZup.slice();
      localMat[12] = pos.x;
      localMat[13] = pos.y;
      localMat[14] = pos.z;
    } else {
      // GL 坐标：完整相似变换 M' = R·M·R⁻¹（含位移，等价于顶点 R·v = (x,z,-y)）
      const engineMat = rotZup.slice();
      engineMat[12] = pos.x;
      engineMat[13] = pos.y;
      engineMat[14] = pos.z;
      localMat = toYupRow(engineMat);
    }

    let worldMat;
    if (obj.nodeParent && byName.has(obj.nodeParent)) {
      const parent = calc(byName.get(obj.nodeParent));
      // C++ smObj3d.cpp:1138: smFMatrixMult(TmResult, qmat, pParent->TmResult) = TmResult = qmat × parent
      // world = 子局部 × 父world（子在前）——顺序不能反，否则层级错乱（转向/颤抖）
      worldMat = matMulRow(localMat, parent.world);
    } else {
      worldMat = localMat;
    }

    const entry = { local: localMat, world: worldMat, pos };
    result.set(obj.nodeName, entry);
    return entry;
  }

  // 遍历所有对象，对未计算的调用 calc（自动向上递归父）
  for (const obj of smb.objects) {
    calc(obj);
  }

  return smb.objects.map(o => ({ name: o.nodeName, ...result.get(o.nodeName) }));
}

// R = 绕 X 轴 -90°（行主序 4x4）：引擎坐标 (x,y,z) → GL (x, z, -y)
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

/**
 * 引擎 Z-up 行主序矩阵 → GL（绕 X -90° 相似变换 M' = R·M·R⁻¹，含位移）
 * 与 skinned-builder.toYup 一致
 */
export function toYupRow(rm) {
  return matMulRow(ROT_X_NEG90, matMulRow(rm, ROT_X_NEG90_INV));
}

/**
 * 把 evalSkeleton 的局部矩阵应用到 three.js 骨骼
 * 每骨骼: 局部矩阵(引擎行主序) → Y-up → three.js Matrix4 → decompose → position/quaternion
 * @param {THREE.Bone[]} bones three.js 骨骼（按 smb.objects 顺序）
 * @param {Array} skelFrames evalSkeleton 的返回值
 * @param {THREE.Matrix4} tmp 临时矩阵
 * @param {THREE.Vector3} posV
 * @param {THREE.Quaternion} quatQ
 * @param {THREE.Vector3} sclV
 */
export function applyToBones(bones, skelFrames, tmp, posV, quatQ, sclV) {
  const byName = new Map(bones.map(b => [b.userData.nodeName, b]));
  for (const sf of skelFrames) {
    const bone = byName.get(sf.name);
    if (!bone) continue;
    // sf.local 行主序数组：fromArray（列主序读取）位移落在 [12..14] 正确，
    // 旋转部分实测正确（acero/armbeetle 骨骼位置、朝向均正确），无需额外转置
    tmp.fromArray(sf.local);
    tmp.decompose(posV, quatQ, sclV);
    bone.position.copy(posV);
    bone.quaternion.copy(quatQ);
    bone.scale.copy(sclV);
    bone.matrixWorldNeedsUpdate = true;
  }
}
