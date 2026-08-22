/**
 * PT .inx 解析器
 *
 * 严格依据 exm C++ 源码（非 Java 移植）：
 *  - Legacy/IO/fileread.cpp: smModelDecode / ModelKeyWordDecode / MotionKeyWordDecode / GetSpeedSum
 *  - Legacy/Engine/Graphics/smType.h: smMODELINFO / smMOTIONINFO / _MODELGROUP
 *  - Legacy/Game/Character/character.h: CHRMOTION_EXT
 *
 * .inx 是加密的：动画条目的 StartFrame/EndFrame 与 MotionKeyWord_1/2 异或混编，
 * 必须先用 MotionKeyWordDecode 还原。文件大小 == sizeof(smMODELINFO)。
 */

// ===== 常量 =====
const CHRMOTION_EXT = 10;

/** 动画状态码（character.h） */
const CHRMOTION_STATE = {
  0x40: 'STAND', 0x50: 'WALK', 0x60: 'RUN', 0x80: 'FALLDOWN',
  0x100: 'ATTACK', 0x110: 'DAMAGE', 0x120: 'DEAD', 0x130: 'SOMETIME',
  0x140: 'EAT', 0x150: 'SKILL', 0x170: 'FALLSTAND', 0x180: 'FALLDAMAGE',
  0x200: 'RESTART', 0x210: 'WARP', 0x220: 'YAHOO',
};

/** 从 DataView 读取定长字符串（按字节，遇 \0 截断） */
export function readCString(dv, offset, len) {
  const bytes = new Uint8Array(dv.buffer, dv.byteOffset + offset, len);
  let end = 0;
  while (end < len && bytes[end] !== 0) end++;
  return new TextDecoder('latin1').decode(bytes.subarray(0, end));
}

/**
 * GetSpeedSum（文件名校验和，character.cpp:430）
 * 大写化：小写字母转大写后参与累加
 */
export function getSpeedSum(name) {
  let sum2 = 0, dwSum = 0, cnt = 0;
  for (let i = 0; i < name.length; i++) {
    let ch = name.charCodeAt(i) & 0xff;
    if (ch === 0) break;
    if (ch >= 'a'.charCodeAt(0) && ch <= 'z'.charCodeAt(0)) {
      ch -= 0x20;
      sum2 += ch * (cnt + 1);
      dwSum += ch * (cnt * cnt);
    } else {
      sum2 += ch * (cnt + 1);
      dwSum += ch * (cnt * cnt);
    }
    cnt++;
  }
  return ((dwSum << 24) | (cnt << 16) | sum2) >>> 0;
}

/** 读取一个 _MODELGROUP（smType.h:397）：int ModelNameCnt + char[4][16] */
function readModelGroup(dv, offset) {
  const modelNameCnt = dv.getInt32(offset, true);
  const names = [];
  // C++ _strcmpi 语义：名字是连续内存 char[4][16]，无 \0 时跨边界连续读直到 \0。
  // 超过 16 字符的名字（如 maracuja_bear_Body=18字符）会溢出到下一槽，需连续读取。
  // 只读前 modelNameCnt 个槽（C++ ListCnt 只遍历前 cnt 个）
  let pos = offset + 4;
  for (let i = 0; i < modelNameCnt && i < 4; i++) {
    names.push(readCString(dv, pos, 64)); // 跨边界读到 \0
    pos += 16;
  }
  return { modelNameCnt, modelNames: names, size: 4 + 4 * 16 };
}

/** 读取一个 smMOTIONINFO（smType.h:374），不解密。sizeof=172（含对齐） */
function readRawMotionInfo(dv, offset) {
  const state = dv.getUint32(offset, true);
  const motionKeyWord1 = dv.getUint32(offset + 4, true);
  const startFrame = dv.getUint32(offset + 8, true);
  const motionKeyWord2 = dv.getUint32(offset + 12, true);
  const endFrame = dv.getUint32(offset + 16, true);
  const eventFrame = [
    dv.getUint32(offset + 20, true),
    dv.getUint32(offset + 24, true),
    dv.getUint32(offset + 28, true),
    dv.getUint32(offset + 32, true),
  ];
  const itemCodeCount = dv.getInt32(offset + 36, true);
  const itemCodeList = new Uint16Array(dv.buffer, dv.byteOffset + offset + 40, 52);
  const dwJobCodeBit = dv.getUint32(offset + 144, true);
  const skillCodeList = new Uint8Array(dv.buffer, dv.byteOffset + offset + 148, 8);
  const mapPosition = dv.getInt32(offset + 156, true);
  const repeat = dv.getUint32(offset + 160, true);
  const keyCode = dv.getUint8(offset + 164);
  // offset 165..167 对齐填充
  const motionFrame = dv.getInt32(offset + 168, true);

  return {
    state,
    motionKeyWord1,
    startFrame,
    motionKeyWord2,
    endFrame,
    eventFrame,
    itemCodeCount,
    itemCodeList,
    dwJobCodeBit,
    skillCodeList,
    mapPosition,
    repeat,
    keyCode,
    motionFrame,
    size: 172,
  };
}

/**
 * MotionKeyWordDecode（fileread.cpp:5785）
 * 还原 StartFrame/EndFrame，MotionKeyWord_1/2 清零
 */
function motionKeyWordDecode(mi) {
  // StartFrame 还原
  if (mi.motionKeyWord1 || mi.startFrame) {
    const keyWord = ((mi.motionKeyWord1 & 0xff000000) >>> 0) |
      ((mi.motionKeyWord1 & 0x0000ff00) << 8) |
      (mi.startFrame & 0x0000ff00) |
      ((mi.startFrame & 0xff000000) >>> 24);
    const frame = ((mi.startFrame & 0x000000ff) << 24) |
      (mi.startFrame & 0x00ff0000) |
      ((mi.motionKeyWord1 & 0x00ff0000) >>> 8) |
      (mi.motionKeyWord1 & 0x000000ff);
    mi.motionKeyWord1 = 0;
    mi.startFrame = frame >>> 0;
  }
  // EndFrame 还原
  if (mi.motionKeyWord2 || mi.endFrame) {
    const keyWord = ((mi.endFrame & 0x0000ff00) << 16) |
      ((mi.endFrame & 0xff000000) >>> 8) |
      (mi.motionKeyWord2 & 0x0000ff00) |
      ((mi.motionKeyWord2 & 0xff000000) >>> 24);
    const frame = ((mi.motionKeyWord2 & 0x00ff0000) << 8) |
      ((mi.motionKeyWord2 & 0x000000ff) << 16) |
      ((mi.endFrame & 0x00ff0000) >>> 8) |
      (mi.endFrame & 0x000000ff);
    mi.motionKeyWord2 = 0;
    mi.endFrame = frame >>> 0;
  }
}

/**
 * 解析 .inx 文件二进制
 * @param {ArrayBuffer} buffer
 * @returns 解析后的 smMODELINFO
 */
export function parseInx(buffer) {
  const dv = new DataView(buffer);
  let o = 0;

  const modelFile = readCString(dv, 0, 64);
  const motionFile = readCString(dv, 64, 64);
  const subModelFile = readCString(dv, 128, 64);
  o = 192;

  const highModel = readModelGroup(dv, o); o += highModel.size;
  const defaultModel = readModelGroup(dv, o); o += defaultModel.size;
  const lowModel = readModelGroup(dv, o); o += lowModel.size;

  // MotionInfo[MOTION_INFO_MAX=512]，每个 172 字节（sizeof(smMOTIONINFO) 含对齐）
  const rawMotions = [];
  for (let i = 0; i < 512; i++) {
    const mi = readRawMotionInfo(dv, o);
    mi.index = i;
    rawMotions.push(mi);
    o += mi.size;
  }

  const motionCount = dv.getInt32(o, true); o += 4;
  const fileTypeKeyWord = dv.getUint32(o, true); o += 4;
  const linkFileKeyWord = dv.getUint32(o, true); o += 4;
  const szLinkFile = readCString(dv, o, 64); o += 64;

  const talkLinkFile = readCString(dv, o, 64); o += 64;
  const talkMotionFile = readCString(dv, o, 64); o += 64;

  // 解密动画条目（CHRMOTION_EXT=10 起）
  const motions = rawMotions.map(mi => ({ ...mi }));
  for (let i = CHRMOTION_EXT; i < Math.min(motionCount, 512); i++) {
    motionKeyWordDecode(motions[i]);
  }

  return {
    modelFile,
    motionFile,
    subModelFile,
    highModel,
    defaultModel,
    lowModel,
    motions,
    motionCount,
    fileTypeKeyWord,
    linkFileKeyWord,
    szLinkFile,
    talkLinkFile,
    talkMotionFile,
  };
}

/** 便捷：状态码 → 名称 */
export function motionStateName(state) {
  return CHRMOTION_STATE[state] || ('0x' + state.toString(16));
}
