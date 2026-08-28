/**
 * PT 纸娃娃系统 — 角色模型加载器
 *
 * 加载角色 body + head 两层网格，共享同一副骨骼。
 * 依据 C++ 源码：
 *  - character.cpp:smPATTERN::LoadCharactor — body .inx → model .smd
 *  - character.cpp:SetPattern — head Pattern2 共享骨骼
 *  - fileread.cpp:smModelDecode — linkFile 递归加载动画数据
 */

import { parseInx } from './inx-parser.js';
import { parseSmb } from './smb-parser.js';
import { buildSkinnedMesh, buildSkeleton } from './skinned-builder.js';
import { JOB_DATA, getHeadInxPath, getBodyInxPath } from './job-data.js';

const EXM_RUN = '/pt/exm-run/';

/** fetch + ArrayBuffer */
async function fetchAB(url) {
  const resp = await fetch(url, { cache: 'no-store' });
  if (!resp.ok) throw new Error(`HTTP ${resp.status}: ${url}`);
  return (await resp.arrayBuffer()).slice(0);
}

/**
 * 解析 .inx 的 modelFile → 实际 .smd 文件名（去扩展名，转小写）
 * 例: char\tmABCD\tmbB01.ASE → char/tmabcd/tmbb01
 */
function resolveModelBase(inxInfo) {
  if (!inxInfo || !inxInfo.modelFile) return null;
  const mf = inxInfo.modelFile.replace(/\\/g, '/').toLowerCase();
  const slash = mf.lastIndexOf('/');
  const name = mf.substring(slash + 1).replace(/\.[^.]+$/, '');
  const dir = slash >= 0 ? mf.substring(0, slash) : '';
  return dir + '/' + name;
}

/**
 * 解析 .inx 的 motionFile → 实际 .smb 文件名
 * 例: char\tmABCD\m1.ase → char/tmabcd/m1.smb
 */
function resolveMotionBase(inxInfo) {
  if (!inxInfo || !inxInfo.motionFile) return null;
  const mf = inxInfo.motionFile.replace(/\\/g, '/').toLowerCase();
  const slash = mf.lastIndexOf('/');
  const name = mf.substring(slash + 1).replace(/\.[^.]+$/, '');
  const dir = slash >= 0 ? mf.substring(0, slash) : '';
  return dir + '/' + name;
}

/**
 * 加载单个网格文件（.smd），按 meshNames 过滤对象
 */
async function loadSmd(basePath, meshNames) {
  const url = EXM_RUN + basePath + '.smd';
  const buf = await fetchAB(url);
  const smd = parseSmb(buf);
  let objs = smd.objects.filter(o => o.nVertex > 0);
  if (meshNames && meshNames.length > 0) {
    const filtered = objs.filter(o => {
      const lower = o.nodeName.toLowerCase();
      return meshNames.some(n => n.toLowerCase() === lower);
    });
    if (filtered.length > 0) objs = filtered;
  }
  return { smd, objs };
}

/**
 * 加载角色完整模型（body + head + skeleton）
 *
 * @param {number} jobId 职业ID（1-10）
 * @param {number} faceNum 头型编号（0-2）
 * @param {number} tier 转职阶层（0-4，默认0=基础）
 * @param {object} lodKey LOD 键（'high'/'medium'/'low'，默认 'high'）
 * @returns {Promise<{bodyGroup, headGroup, skeleton, bones, skeletonGroup, animSmb, bodyInxInfo, headInxInfo, errs}>}
 */
export async function loadCharacterModel(jobId, faceNum = 0, tier = 0, lodKey = 'high', armorNum = 1, bodyInxOverride = null) {
  const job = JOB_DATA[jobId];
  if (!job) throw new Error('未知职业ID: ' + jobId);

  const errs = [];
  const bodyInxPath = bodyInxOverride || getBodyInxPath(jobId, armorNum) || job.bodyInx;
  const headInxPath = getHeadInxPath(jobId, faceNum, tier);

  // 1. 解析三个 .inx（body, head, bip）
  let bodyInxInfo = null, headInxInfo = null, bipInxInfo = null;
  try {
    [bodyInxInfo, headInxInfo, bipInxInfo] = await Promise.all([
      fetchAB(EXM_RUN + bodyInxPath).then(buf => parseInx(buf)),
      fetchAB(EXM_RUN + headInxPath).then(buf => parseInx(buf)),
      fetchAB(EXM_RUN + job.bipInx).then(buf => parseInx(buf)),
    ]);
  } catch (e) {
    errs.push('inx 解析失败: ' + e.message);
    throw e;
  }

  // 2. 获取 .smd 文件路径
  const bodyModelBase = resolveModelBase(bodyInxInfo);
  const headModelBase = resolveModelBase(headInxInfo);
  if (!bodyModelBase) throw new Error('body modelFile 为空');
  if (!headModelBase) throw new Error('head modelFile 为空');

  // 3. 获取 .smb 骨骼文件（从 bip 的 motionFile）
  const bipMotionBase = resolveMotionBase(bipInxInfo);
  if (!bipMotionBase) throw new Error('bip motionFile 为空');
  // bipMotionBase = "char/tmabcd/m1" → smbUrl = "/pt/exm-run/char/tmabcd/m1.smb"
  const smbUrl = EXM_RUN + bipMotionBase + '.smb';

  // 4. 加载骨骼 .smb
  let smb;
  try {
    smb = parseSmb(await fetchAB(smbUrl));
  } catch (e) {
    throw new Error('骨骼 .smb 加载失败: ' + smbUrl + ' — ' + e.message);
  }

  // 5. 获取 meshNames（从 .inx 的 HighModel）
  const bodyHighNames = bodyInxInfo.highModel.modelNames.filter(Boolean);
  const headHighNames = headInxInfo.highModel.modelNames.filter(Boolean);

  // 如果 head high names 为空，加载全部 head mesh
  const headMeshNames = headHighNames.length > 0 ? headHighNames : null;

  // 6. 加载 body + head 的 .smd 网格数据
  const [bodySmdResult, headSmdResult] = await Promise.all([
    loadSmd(bodyModelBase, bodyHighNames),
    loadSmd(headModelBase, headMeshNames),
  ]);

  // 7. 构建骨骼
  const skel = buildSkeleton(smb, false);

  // 8. 构建 body SkinnedMesh（用 sharedSkel）
  const bodyResult = buildSkinnedMesh(bodySmdResult.smd, smb, bodyHighNames, false, skel);

  // 9. 构建 head SkinnedMesh（用同一副骨骼）
  const headResult = buildSkinnedMesh(headSmdResult.smd, smb, headMeshNames, false, skel);

  return {
    bodyGroup: bodyResult.group,
    headGroup: headResult.group,
    bodyMeshes: bodyResult.meshes,
    headMeshes: headResult.meshes,
    skeleton: skel.skeleton,
    bones: skel.bones,
    skeletonGroup: skel.skeletonGroup,
    animSmb: smb,
    bipInxInfo,
    bodyInxInfo,
    headInxInfo,
    bodyTextures: bodyResult.texturesToLoad,
    headTextures: headResult.texturesToLoad,
    errs,
  };
}
