/**
 * Animation State Machine — manages state transitions, weapon matching, combo attacks
 *
 * Core logic based on EU source:
 * - EXE.cpp:3195-3209 — animation end → return to Idle
 * - CharacterGame.cpp:1432-1467 — weapon type overrides animation
 * - EXE.cpp:3240-3260 — distance-driven Walk/Run transitions
 *
 * State transitions:
 *   STAND → WALK/RUN (distance) → STAND (close)
 *   STAND → ATTACK (attack) → STAND (attack ends)
 *   STAND → SKILL (skill) → STAND (skill ends)
 *   Any non-looping state ends → return to STAND
 */

import { findMotions, findMotionsByType, pickMotion, classIdToFlag } from './anim-match.js';

const STATE = {
  STAND:    0x0040,
  WALK:     0x0050,
  RUN:      0x0060,
  SPRINT:   0x0070,
  FALLDOWN: 0x0080,
  ATTACK:   0x0100,
  DAMAGE:   0x0110,
  DEAD:     0x0120,
  EAT:      0x0140,
  SKILL:    0x0150,
  YAHOO:    0x0220,
  TAUNT:    0x0230,
};

const CHRMOTION_EXT_MIN = 10;

/**
 * Create animation state machine instance
 * @param {object} opts
 * @param {Function} opts.getMotions   - returns bipInx.motions array (base+ext)
 * @param {Function} opts.getClassId   - returns current class ID (1-10)
 * @param {Function} opts.getWeaponIdCode - returns current weapon idCode or null
 * @param {Function} opts.getWeaponType   - returns current weapon type string or null (e.g. 'BOW')
 * @param {Function} opts.onMotionChange - callback when animation changes (motion) => void
 * @param {Function} opts.log           - log callback
 */
export function createAnimStateMachine(opts) {
  const { getMotions, getClassId, getWeaponIdCode, getWeaponType, onMotionChange, log: logFn } = opts;
  const log2 = logFn || (() => {});

  let currentState = STATE.STAND;
  let currentMotion = null;

  function getBipMotions() {
    const all = getMotions();
    if (!all) return [];
    return all;
  }

  function findMotionForState(state, excludeCurrent) {
    const motions = getBipMotions();
    const classId = getClassId();
    const weaponId = getWeaponIdCode();
    let candidates = findMotions(motions, state, weaponId, classId);
    // 精确匹配无结果时，回退到类型匹配（语义化验证）
    if (!candidates.length && getWeaponType) {
      const weaponType = getWeaponType();
      if (weaponType) candidates = findMotionsByType(motions, state, weaponType, classId);
    }
    if (excludeCurrent && currentMotion && candidates.length > 1) {
      candidates = candidates.filter(m => m !== currentMotion);
    }
    return pickMotion(candidates);
  }

  function applyMotion(motion) {
    if (!motion) return false;
    currentMotion = motion;
    if (onMotionChange) onMotionChange(motion);
    return true;
  }

  function triggerAttack() {
    const motion = findMotionForState(STATE.ATTACK, true);
    if (!motion) { log2('No matching attack animation', 'warn'); return false; }
    currentState = STATE.ATTACK;
    applyMotion(motion);
    log2('Attack: ' + motion.state.toString(16) + ' [' + motion.startFrame + ',' + motion.endFrame + ']');
    return true;
  }

  function triggerSkill() {
    const motion = findMotionForState(STATE.SKILL, true);
    if (!motion) { log2('No matching skill animation', 'warn'); return false; }
    currentState = STATE.SKILL;
    applyMotion(motion);
    log2('Skill: ' + motion.state.toString(16) + ' [' + motion.startFrame + ',' + motion.endFrame + ']');
    return true;
  }

  function triggerWalk() {
    const motion = findMotionForState(STATE.WALK, false);
    if (!motion) return false;
    currentState = STATE.WALK;
    applyMotion(motion);
    return true;
  }

  function triggerRun() {
    const motion = findMotionForState(STATE.RUN, false);
    if (!motion) return false;
    currentState = STATE.RUN;
    applyMotion(motion);
    return true;
  }

  function triggerIdle() {
    const motion = findMotionForState(STATE.STAND, false);
    if (!motion) return false;
    currentState = STATE.STAND;
    applyMotion(motion);
    return true;
  }

  function triggerTaunt() {
    const motion = findMotionForState(STATE.TAUNT, false);
    if (!motion) { log2('No matching taunt animation', 'warn'); return false; }
    currentState = STATE.TAUNT;
    applyMotion(motion);
    return true;
  }

  function triggerYahoo() {
    const motion = findMotionForState(STATE.YAHOO, false);
    if (!motion) { log2('No matching yahoo animation', 'warn'); return false; }
    currentState = STATE.YAHOO;
    applyMotion(motion);
    return true;
  }

  /**
   * Called when current animation reaches end frame.
   * Returns the motion to transition to (or null to stop).
   */
  function onAnimationEnd() {
    if (currentState === STATE.ATTACK || currentState === STATE.SKILL ||
        currentState === STATE.TAUNT || currentState === STATE.YAHOO) {
      // Non-looping states → return to idle
      return triggerIdle() ? currentMotion : null;
    }
    return null;
  }

  function getCurrentState() { return currentState; }
  function getCurrentMotion() { return currentMotion; }
  function getWeaponMatchSummary() {
    const motions = getBipMotions();
    const classId = getClassId();
    const weaponId = getWeaponIdCode();
    const counts = {};
    for (const m of motions) {
      const key = '0x' + m.state.toString(16);
      if (!counts[key]) counts[key] = { total: 0, matched: 0 };
      counts[key].total++;
      const matched = findMotions([m], m.state, weaponId, classId);
      if (matched.length > 0) counts[key].matched++;
    }
    return counts;
  }

  return {
    STATE,
    triggerAttack,
    triggerSkill,
    triggerWalk,
    triggerRun,
    triggerIdle,
    triggerTaunt,
    triggerYahoo,
    onAnimationEnd,
    getCurrentState,
    getCurrentMotion,
    getWeaponMatchSummary,
  };
}
