/**
 * PT 纸娃娃系统 — 职业/种族/头型数据
 *
 * 严格依据 C++ 源码：
 *  - NewSourcePT-2023/SrcGame/src/HoBaram/HoLogin.cpp（建号 UI + 头文件表）
 *  - PristonTale-EU-main/shared/packets.h（ECharacterClass 枚举）
 *  - PristonTale-EU-main/shared/character.cpp:GetCharacterRace（种族分组）
 *  - NewSourcePT-2023/SrcGame/src/playsub.cpp:ResetHairModel（头文件命名规则）
 *
 * 头文件命名：{raceGender}{classLetter}{faceNum}[{tier}].inx
 *   raceGender: tmh = Tempskron-male, tfh = Tempskron-female, mmh = Morion-male, mfh = Morion-female
 *   classLetter: a=Mechanician, b=Fighter, c=Pikeman, d=Archer, e=Assassin, A=Knight, B=Atalanta, C=Priestess, D=Magician, E=Shaman
 *   tier: 无=基础, a=1阶转职, b=2阶, c=3阶, d=4阶
 *
 * 身体文件命名：{prefix}{NNN}.inx（如 a001.inx = Mechanician 基础铠甲）
 */

/** 种族ID */
export const RACE_TEMPSKRON = 0;
export const RACE_MORION = 1;

/** 职业ID → 种族映射 */
export const CLASS_RACE = {
  1: RACE_TEMPSKRON, 2: RACE_TEMPSKRON, 3: RACE_TEMPSKRON, 4: RACE_TEMPSKRON, 9: RACE_TEMPSKRON,
  5: RACE_MORION, 6: RACE_MORION, 7: RACE_MORION, 8: RACE_MORION, 10: RACE_MORION,
};

/** 种族 → 职业列表 */
export const RACE_CLASSES = {
  [RACE_TEMPSKRON]: [1, 2, 3, 4, 9],
  [RACE_MORION]: [5, 6, 7, 8, 10],
};

/** 职业中文名 */
export const JOB_NAMES_CN = {
  1: '战士', 2: '机械师', 3: '弓箭手', 4: '枪骑士', 5: '女武神',
  6: '骑士', 7: '魔法师', 8: '祭司', 9: '刺客', 10: '萨满',
};

/** 职业英文名 */
export const JOB_NAMES_EN = {
  1: 'Fighter', 2: 'Mechanician', 3: 'Archer', 4: 'Pikeman', 5: 'Atalanta',
  6: 'Knight', 7: 'Magician', 8: 'Priestess', 9: 'Assassin', 10: 'Shaman',
};

/**
 * 职业完整数据
 * bodyInx: 基础身体 .inx 路径（铠甲编号 001 = 基础）
 * headPrefix: 头部 .inx 文件名前缀（大小写敏感，磁盘上全小写）
 * headLetter: 头部文件中的职业字母
 * bipInx: 共享骨骼 .inx 路径（linkFile 目标）
 * bipSmb: 骨骼动画 .smb 文件名（motionFile 解析结果）
 * gender: 'm'=男 Tempskron/Morion-male, 'f'=女 Tempskron-female/Morion-female
 */
export const JOB_DATA = {
  // ===== 坦普族（Tempskron）=====
  // Fighter(1) — 身体 b001.inx, 头 tmh-b0[1-3], bip M1Bip.inx → m1.smb
  1: {
    bodyInx: 'char/tmabcd/b001.inx',
    headPrefix: 'tmh-b',
    headLetter: 'b',
    bipInx: 'char/tmabcd/m1bip.inx',
    bipSmb: 'm1.smb',
    gender: 'm',
    bipMeshPrefix: 'tmb',
  },
  // Mechanician(2) — 身体 a001.inx, 头 tmh-a0[1-3], bip M1Bip.inx → m1.smb
  2: {
    bodyInx: 'char/tmabcd/a001.inx',
    headPrefix: 'tmh-a',
    headLetter: 'a',
    bipInx: 'char/tmabcd/m1bip.inx',
    bipSmb: 'm1.smb',
    gender: 'm',
    bipMeshPrefix: 'tmb',
  },
  // Archer(3) — 身体 d001.inx, 头 tfh-d0[1-3], bip M2Bip.inx → m2.smb
  3: {
    bodyInx: 'char/tmabcd/d001.inx',
    headPrefix: 'tfh-d',
    headLetter: 'd',
    bipInx: 'char/tmabcd/m2bip.inx',
    bipSmb: 'm2.smb',
    gender: 'f',
    bipMeshPrefix: 'tfb',
  },
  // Pikeman(4) — 身体 c001.inx, 头 tmh-c0[1-3], bip M4Bip.inx → m4.smb
  4: {
    bodyInx: 'char/tmabcd/c001.inx',
    headPrefix: 'tmh-c',
    headLetter: 'c',
    bipInx: 'char/tmabcd/m4bip.inx',
    bipSmb: 'm4.smb',
    gender: 'm',
    bipMeshPrefix: 'tmb',
  },
  // Assassin(9) — 身体 e001.inx, 头 tfh-e0[1-3], bip M6Bip.inx → m6.smb
  9: {
    bodyInx: 'char/tmabcd/e001.inx',
    headPrefix: 'tfh-e',
    headLetter: 'e',
    bipInx: 'char/tmabcd/m6bip.inx',
    bipSmb: 'm6.smb',
    gender: 'f',
    bipMeshPrefix: 'tfb',
  },

  // ===== 魔灵族（Morion）=====
  // Knight(6) — 身体 ma001.inx, 头 mmh-a0[1-3], bip M1Bip.inx → m1.smb
  6: {
    bodyInx: 'char/tmabcd/ma001.inx',
    headPrefix: 'mmh-a',
    headLetter: 'a',
    bipInx: 'char/tmabcd/m1bip.inx',
    bipSmb: 'm1.smb',
    gender: 'm',
    bipMeshPrefix: 'mmb',
  },
  // Atalanta(5) — 身体 mb001.inx, 头 mfh-b0[1-3], bip M2Bip.inx → m2.smb
  5: {
    bodyInx: 'char/tmabcd/mb001.inx',
    headPrefix: 'mfh-b',
    headLetter: 'b',
    bipInx: 'char/tmabcd/m2bip.inx',
    bipSmb: 'm2.smb',
    gender: 'f',
    bipMeshPrefix: 'mfb',
  },
  // Magician(7) — 身体 md001.inx, 头 mmh-d0[1-3], bip M3Bip.inx → m3.smb
  7: {
    bodyInx: 'char/tmabcd/md001.inx',
    headPrefix: 'mmh-d',
    headLetter: 'd',
    bipInx: 'char/tmabcd/m3bip.inx',
    bipSmb: 'm3.smb',
    gender: 'm',
    bipMeshPrefix: 'mmb',
  },
  // Priestess(8) — 身体 mc001.inx, 头 mfh-c0[1-3], bip M5Bip.inx → m5.smb
  8: {
    bodyInx: 'char/tmabcd/mc001.inx',
    headPrefix: 'mfh-c',
    headLetter: 'c',
    bipInx: 'char/tmabcd/m5bip.inx',
    bipSmb: 'm5.smb',
    gender: 'f',
    bipMeshPrefix: 'mfb',
  },
  // Shaman(10) — 身体 me001.inx, 头 mmh-e0[1-3], bip M7Bip.inx → m7.smb
  10: {
    bodyInx: 'char/tmabcd/me001.inx',
    headPrefix: 'mmh-e',
    headLetter: 'e',
    bipInx: 'char/tmabcd/m7bip.inx',
    bipSmb: 'm7.smb',
    gender: 'm',
    bipMeshPrefix: 'mmb',
  },
};

/**
 * 转职阶层后缀
 * 0 = 基础（无后缀）, 1 = a, 2 = b, 3 = c
 * 注意：tier 2 使用下划线 _ 代替连字符 -（C++ ResetHairModel: cl[0]='_' when r==2）
 * '-d' 后缀是 Q版/漫画头，不是转职阶层
 */
export const TIER_SUFFIXES = ['', 'a', 'b', 'c'];
export const TIER_NAMES_CN = ['基础', '一转', '二转', '三转'];
export const TIER_NAMES_EN = ['Base', '1st', '2nd', '3rd'];

/**
 * 构建头部 .inx 文件路径
 * @param {number} jobId 职业ID
 * @param {number} faceNum 面型编号（0, 1, 2）
 * @param {number} tier 转职阶层（0-3）
 * @returns {string} 头部 .inx 相对路径（如 char/tmabcd/tmh-b01a.inx）
 */
export function getHeadInxPath(jobId, faceNum, tier = 0) {
  const job = JOB_DATA[jobId];
  if (!job) return null;
  const face = String(faceNum + 1).padStart(2, '0'); // 0→'01', 1→'02', 2→'03'
  const suffix = TIER_SUFFIXES[tier] || '';
  // C++ ResetHairModel: tier 2 用下划线 _ 代替连字符 -
  // headPrefix = "tmh-b" → tier2 时 "tmh-b01b" → "tmh_b01b"
  if (tier === 2) {
    const lastDash = job.headPrefix.lastIndexOf('-');
    if (lastDash >= 0) {
      const fixed = job.headPrefix.substring(0, lastDash) + '_' + job.headPrefix.substring(lastDash + 1);
      return `char/tmabcd/${fixed}${face}${suffix}.inx`;
    }
  }
  return `char/tmabcd/${job.headPrefix}${face}${suffix}.inx`;
}

/**
 * 构建身体 .inx 文件路径（不同铠甲编号）
 * @param {number} jobId 职业ID
 * @param {number} armorNum 铠甲编号（1-34）
 * @returns {string} 身体 .inx 相对路径
 */
export function getBodyInxPath(jobId, armorNum = 1) {
  const job = JOB_DATA[jobId];
  if (!job) return null;
  const prefix = job.bodyInx.match(/\/([^/]+?)(\d+)\.inx$/)?.[0]?.replace(/\d+\.inx$/, '') || '';
  const baseName = job.bodyInx.split('/').pop().replace(/\d+\.inx$/, '');
  const num = String(armorNum).padStart(3, '0');
  return `char/tmabcd/${baseName}${num}.inx`;
}
