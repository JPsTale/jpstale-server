/**
 * 动画匹配器 — 根据 (状态, 武器ID/类型, 职业) 从 .inx 动画条目中筛选适用条目
 *
 * 两种匹配模式:
 *   1. 精确匹配（matchWeapon）: SITEM_CODE_BY_INDEX[idx] === weaponIdCode
 *   2. 类型匹配（matchWeaponByType）: 根据 idcode 前缀推导 weaponType，按类型匹配
 *
 * 精确匹配逻辑严格依据 exm Character.cpp，不做 switch-case 规范化。
 * 类型匹配用于语义化动画系统验证——解决新武器无精确索引的问题。
 */

import { CLASS_FLAG } from './inx-parser.js';
import { SITEM_CODE_BY_INDEX } from './sitem-weapon-index.js';
import { getWeaponTypeFromSItemIndex } from './weapon-type.js';

/**
 * 检查武器 ID 是否在动画条目的白名单中（精确匹配 exm）
 * @param {object} motion
 * @param {number|null} weaponIdCode
 * @returns {boolean}
 */
function matchWeapon(motion, weaponIdCode) {
  const count = motion.itemCodeCount;
  if (count <= 0) return true;

  if (weaponIdCode == null || weaponIdCode === 0) {
    for (let i = 0; i < count && i < 52; i++) {
      if (motion.itemCodeList[i] === 0xFF) return true;
    }
    return false;
  }

  for (let i = 0; i < count && i < 52; i++) {
    const idx = motion.itemCodeList[i];
    if (SITEM_CODE_BY_INDEX[idx] === weaponIdCode) return true;
  }
  return false;
}

/**
 * 按武器类型匹配：动画条目白名单中是否有同类型的武器
 * @param {object} motion          .inx 动画条目
 * @param {string|null} weaponType 武器类型（'BOW'|'CROSSBOW'|'SWORD'|...）
 * @returns {boolean}
 */
function matchWeaponByType(motion, weaponType) {
  const count = motion.itemCodeCount;
  if (count <= 0) return true;

  if (weaponType == null || weaponType === 'BARE_HAND') {
    for (let i = 0; i < count && i < 52; i++) {
      if (motion.itemCodeList[i] === 0xFF) return true;
    }
    return false;
  }

  for (let i = 0; i < count && i < 52; i++) {
    const t = getWeaponTypeFromSItemIndex(motion.itemCodeList[i]);
    if (t === weaponType) return true;
  }
  return false;
}

function matchClass(motion, classId) {
  if (!motion.dwJobCodeBit) return true;
  const classBit = classIdToFlag(classId);
  return (motion.dwJobCodeBit & classBit) !== 0;
}

export function classIdToFlag(classId) {
  const map = {
    1: CLASS_FLAG.Fighter, 2: CLASS_FLAG.Mechanician, 3: CLASS_FLAG.Archer,
    4: CLASS_FLAG.Pikeman, 5: CLASS_FLAG.Atalanta, 6: CLASS_FLAG.Knight,
    7: CLASS_FLAG.Magician, 8: CLASS_FLAG.Priestess, 9: CLASS_FLAG.Assassin,
    10: CLASS_FLAG.Shaman,
  };
  return map[classId] || 0;
}

/**
 * @param {Array} motions
 * @param {number} state
 * @param {number|null} weaponIdCode
 * @param {number} classId
 */
export function findMotions(motions, state, weaponIdCode, classId) {
  const results = [];
  for (let i = 0; i < motions.length; i++) {
    const m = motions[i];
    if (m.state !== state) continue;
    if (!matchClass(m, classId)) continue;
    if (!matchWeapon(m, weaponIdCode)) continue;
    results.push(m);
  }
  return results;
}

/**
 * 按武器类型匹配（语义化）
 * @param {Array} motions
 * @param {number} state
 * @param {string|null} weaponType  武器类型（'BOW'|'SWORD'|...）
 * @param {number} classId
 */
export function findMotionsByType(motions, state, weaponType, classId) {
  const results = [];
  for (let i = 0; i < motions.length; i++) {
    const m = motions[i];
    if (m.state !== state) continue;
    if (!matchClass(m, classId)) continue;
    if (!matchWeaponByType(m, weaponType)) continue;
    results.push(m);
  }
  return results;
}

export function pickMotion(candidates) {
  if (!candidates.length) return null;
  return candidates[Math.floor(Math.random() * candidates.length)];
}
