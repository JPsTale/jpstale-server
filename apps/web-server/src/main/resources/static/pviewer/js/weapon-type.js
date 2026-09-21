/**
 * weapon-type.js — 武器语义类型提取
 * 从 idcode 高 16 位提取 weaponType，用于替代精确 sItem 索引匹配。
 * 数据来源：sItem[] idcode 前缀（exm 权威表）
 */
import { SITEM_CODE_BY_INDEX } from './sitem-weapon-index.js'

const CROSSBOW_LOWS = new Set([
  0x0200, 0x0300, 0x0400, 0x0800, 0x0900, 0x0A00, 0x0D00, 0x1100, 0x1400
])

/**
 * idcode (32-bit uint) → weaponType 字符串
 * @param {number} idCode  武器 idcode（如 0x01060100）
 * @returns {string|null}  'AXE'|'CLAW'|'HAMMER'|'STAFF'|'SCYTHE'|'BOW'|'CROSSBOW'|'SWORD'|'JAVELIN'|'DAGGER'|null
 */
export function getWeaponTypeFromIdCode(idCode) {
  if (!idCode || idCode === 0) return null
  const prefix = idCode & 0xFFFF0000
  const low = idCode & 0xFFFF
  switch (prefix) {
    case 0x01010000: return 'AXE'
    case 0x01020000: return 'CLAW'
    case 0x01030000: return 'HAMMER'
    case 0x01040000: return 'STAFF'
    case 0x01050000: return 'SCYTHE'
    case 0x01060000: return CROSSBOW_LOWS.has(low) ? 'CROSSBOW' : 'BOW'
    case 0x01070000: return 'SWORD'
    case 0x01080000: return 'JAVELIN'
    case 0x010A0000: return 'DAGGER'
    default: return null
  }
}

/**
 * sItem 索引 → weaponType
 * @param {number} sItemIndex  sItem[] 数组索引（.inx itemCodeList 中的值）
 * @returns {string|null}
 */
export function getWeaponTypeFromSItemIndex(sItemIndex) {
  if (sItemIndex === 0xFF || sItemIndex == null) return 'BARE_HAND'
  const idCode = SITEM_CODE_BY_INDEX[sItemIndex]
  if (idCode == null) return null
  return getWeaponTypeFromIdCode(idCode)
}

/**
 * 从 classItem（API 返回的单双手标识）推导 handType
 * @param {number} classItem  4=单手, 6=双手
 * @returns {string}  '1H'|'2H'|'UNDEFINED'
 */
export function getHandType(classItem) {
  if (classItem === 4) return '1H'
  if (classItem === 6) return '2H'
  return 'UNDEFINED'
}

/**
 * 武器类型的中文名（日志/显示用）
 */
export const WEAPON_TYPE_CN = {
  AXE: '斧', CLAW: '爪', HAMMER: '锤', STAFF: '杖', SCYTHE: '镰',
  BOW: '弓', CROSSBOW: '弩', SWORD: '剑', JAVELIN: '标枪', DAGGER: '匕首',
  BARE_HAND: '空手'
}
