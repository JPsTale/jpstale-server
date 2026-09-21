/*
 * 物品详情浮层用的三项能力：Spec（职业特效 + 需求修正）/ Mix（合成属性叠加）/ Age（强化倍率）。
 *
 * ⚠ **本文件是从已删除的模拟器页面（static/simulator/index.html）原样搬过来的**，
 *   逐函数照抄，**唯一改动是属性访问名**：那里的对象是驼峰 `ItemDetail`（`d.atkPow1Min`），
 *   这里的数据是管理端下发的**数据库列名**行（`d.atkpow1min`）。逻辑一行都没改。
 *   原文件可从 git 取回对照：
 *     git show HEAD:apps/web-server/src/main/resources/static/simulator/index.html
 *   搬迁时**先抄基准用例再改代码**，改完与原文件逐值对照（见设计文档 §6.2）。
 *
 * 两处如实记下的边界（与源实现一致，不是我们的新问题）：
 *   1. Age **只是倍率**（rate = 1 + level*0.02），不含锻造成功率 —— 源实现也没有引用
 *      gamedb.age_list（那张表存的是 fail_chance/broken_chance 等成功率）。
 *   2. Mix 的 effect.attr 里，源实现的 switch **没有** 'Attack Speed' 与 'Speed' 两个分支
 *      （服务端的 mixAttrName 会产出它们）。照抄 ⇒ 这两类合成属性不参与预览。这是既有缺口。
 *
 * 颜色类的取值（源实现里写在模拟器页面的内联 <style>，一并搬进 css/style.css）：
 *   mod-mix 紫 #c77dff / mod-age 蓝 #6ea8ff / mod-spec 绿 #7cd47c /
 *   mod-req-up 红 #f85149 / mod-req-down 绿 #7cd47c
 */
(function () {
  // ------------------------------------------------------------------
  // Spec：职业与需求修正（原样）
  // ------------------------------------------------------------------

  // EU shared/character.h:1063 saItemRequeriments —— 职业特效的属性需求调整百分比 [min, max]
  // 0:None 1:Fighter 2:Mechanician 3:Archer 4:Pikeman 5:Atalanta 6:Knight 7:Magician 8:Priestess 9:Assassin 10:Shaman 11:Brawler
  // EU itemserver.cpp:2056 CheckAndAdjustItemRequirements —— base + base*percent/100，返回 [min, max]
  var REQ_MOD = {
    1: { str: [20, 25], spr: [-20, -10], tal: [0, 0], agi: [-20, -15], hp: [0, 0] },
    2: { str: [15, 25], spr: [-20, -10], tal: [0, 0], agi: [-20, -15], hp: [0, 0] },
    3: { str: [-25, -15], spr: [-20, -10], tal: [0, 0], agi: [20, 30], hp: [0, 0] },
    4: { str: [20, 25], spr: [-20, -10], tal: [0, 0], agi: [-20, -15], hp: [0, 0] },
    5: { str: [-20, -15], spr: [-20, -10], tal: [0, 0], agi: [20, 30], hp: [0, 0] },
    6: { str: [15, 25], spr: [-15, -10], tal: [5, 10], agi: [-20, -15], hp: [0, 0] },
    7: { str: [-30, -25], spr: [25, 30], tal: [-15, -10], agi: [-20, -15], hp: [0, 0] },
    8: { str: [-30, -25], spr: [25, 30], tal: [-15, -10], agi: [-20, -15], hp: [0, 0] },
    9: { str: [20, 25], spr: [-20, -10], tal: [0, 0], agi: [-20, -15], hp: [0, 0] },
    10: { str: [-30, -25], spr: [25, 30], tal: [-15, -10], agi: [-20, -15], hp: [0, 0] },
    11: { str: [0, 0], spr: [0, 0], tal: [0, 0], agi: [0, 0], hp: [0, 0] }
  };

  var SPEC_JOBS = {
    1: ['Fighter', 'FS'],
    2: ['Mechanician', 'MS'],
    3: ['Archer', 'AS'],
    4: ['Pikeman', 'PS'],
    5: ['Atalanta', 'ATS'],
    6: ['Knight', 'KS'],
    7: ['Magician', 'MGS'],
    8: ['Priestess', 'PRS'],
    9: ['Assassin', 'ASS'],
    10: ['Shaman', 'SS'],
    11: ['Brawler', 'BS']
  };

  function reqAdjust(base, pct) {
    if (!base) return null;
    return [base + Math.floor(base * pct[0] / 100), base + Math.floor(base * pct[1] / 100)];
  }

  // 物品特效适用职业 = Primary Spec（primaryspec）+ Secondary Spec（addspecclass 位）
  function specClassValues(d) {
    var vals = [];
    var push = function (v) { if (v > 0 && vals.indexOf(v) === -1) vals.push(v); };
    push(d.primaryspec);
    [d.addspecclass1, d.addspecclass2, d.addspecclass3, d.addspecclass4,
     d.addspecclass5, d.addspecclass6, d.addspecclass7, d.addspecclass8,
     d.addspecclass9, d.addspecclass10, d.addspecclass11, d.addspecclass12]
      .forEach(function (v, i) { if (v > 0) push(i + 1); });
    return vals;
  }

  // 所选职业是否为该物品特效适用职业（Primary Spec 或 Secondary Spec 位命中）
  function specApplies(d, val) {
    var idx = parseInt(val, 10);
    if (idx <= 0) return false; // No Spec 不显示特效
    return specClassValues(d).indexOf(idx) !== -1;
  }

  function specClassList(d) {
    var names = [];
    [d.addspecclass1, d.addspecclass2, d.addspecclass3, d.addspecclass4,
     d.addspecclass5, d.addspecclass6, d.addspecclass7, d.addspecclass8,
     d.addspecclass9, d.addspecclass10, d.addspecclass11, d.addspecclass12]
      .forEach(function (v, i) {
        var j = SPEC_JOBS[i + 1];
        if (v > 0 && j) names.push(j[1]);
      });
    return names;
  }

  // ------------------------------------------------------------------
  // Mix：合成属性叠加（原样；只有属性名从驼峰改为列名）
  // ------------------------------------------------------------------
  function applyMixEffects(d, mix, touched) {
    if (!mix) return;
    var mark = function () {
      var fs = arguments;
      Array.prototype.forEach.call(fs, function (f) { if (touched) touched[f] = true; });
    };
    mix.effects.forEach(function (e) {
      switch (e.attr) {
        case 'Fire Res': d.firemin = (d.firemin || 0) + e.value; d.firemax = (d.firemax || 0) + e.value; mark('firemin', 'firemax'); break;
        case 'Ice Res': d.frostmin = (d.frostmin || 0) + e.value; d.frostmax = (d.frostmax || 0) + e.value; mark('frostmin', 'frostmax'); break;
        case 'Lightning Res': d.lightningmin = (d.lightningmin || 0) + e.value; d.lightningmax = (d.lightningmax || 0) + e.value; mark('lightningmin', 'lightningmax'); break;
        case 'Poison Res': d.poisonmin = (d.poisonmin || 0) + e.value; d.poisonmax = (d.poisonmax || 0) + e.value; mark('poisonmin', 'poisonmax'); break;
        case 'Organic Res': d.organicmin = (d.organicmin || 0) + e.value; d.organicmax = (d.organicmax || 0) + e.value; mark('organicmin', 'organicmax'); break;
        case 'Critical': d.critical = (d.critical || 0) + e.value; mark('critical'); break;
        case 'Attack Rating': d.atkratingmin = (d.atkratingmin || 0) + e.value; d.atkratingmax = (d.atkratingmax || 0) + e.value; mark('atkratingmin', 'atkratingmax'); break;
        case 'Min DMG': d.atkpow1min = (d.atkpow1min || 0) + e.value; d.atkpow2min = (d.atkpow2min || 0) + e.value; mark('atkpow1min', 'atkpow2min'); break;
        case 'Max DMG': d.atkpow1max = (d.atkpow1max || 0) + e.value; d.atkpow2max = (d.atkpow2max || 0) + e.value; mark('atkpow1max', 'atkpow2max'); break;
        case 'Absorb': d.absorbmin = (d.absorbmin || 0) + e.value; d.absorbmax = (d.absorbmax || 0) + e.value; mark('absorbmin', 'absorbmax'); break;
        case 'Defense': d.defensemin = (d.defensemin || 0) + e.value; d.defensemax = (d.defensemax || 0) + e.value; mark('defensemin', 'defensemax'); break;
        case 'Block': d.blockmin = (d.blockmin || 0) + e.value; d.blockmax = (d.blockmax || 0) + e.value; mark('blockmin', 'blockmax'); break;
        case '+HP': d.addhpmin = (d.addhpmin || 0) + e.value; d.addhpmax = (d.addhpmax || 0) + e.value; mark('addhpmin', 'addhpmax'); break;
        case '+MP': d.addmpmin = (d.addmpmin || 0) + e.value; d.addmpmax = (d.addmpmax || 0) + e.value; mark('addmpmin', 'addmpmax'); break;
        case '+SP': d.addstmmin = (d.addstmmin || 0) + e.value; d.addstmmax = (d.addstmmax || 0) + e.value; mark('addstmmin', 'addstmmax'); break;
        case 'HP Regen': d.regenerationhpmin = (d.regenerationhpmin || 0) + e.value; d.regenerationhpmax = (d.regenerationhpmax || 0) + e.value; mark('regenerationhpmin', 'regenerationhpmax'); break;
        case 'MP Regen': d.regenerationmpmin = (d.regenerationmpmin || 0) + e.value; d.regenerationmpmax = (d.regenerationmpmax || 0) + e.value; mark('regenerationmpmin', 'regenerationmpmax'); break;
        case 'SP Regen': d.regenerationstmmin = (d.regenerationstmmin || 0) + e.value; d.regenerationstmmax = (d.regenerationstmmax || 0) + e.value; mark('regenerationstmmin', 'regenerationstmmax'); break;
        case 'Potion Storage': d.potionspace = (d.potionspace || 0) + e.value; mark('potionspace'); break;
      }
    });
  }

  // ------------------------------------------------------------------
  // Age：强化倍率（原样；scale = 整数列，sr = 小数列 —— 这个区分是源实现里刻意写的）
  // ------------------------------------------------------------------
  function applyAge(d, level, touched) {
    if (level <= 0) return;
    var rate = 1 + level * 0.02;
    var scale = function (a, b) { return [a, b].map(function (x) { return x ? Math.round(x * rate) : x; }); };
    var sr = function (a, b) { return [a, b].map(function (x) { return x ? Math.round(x * rate * 100) / 100 : x; }); };
    var mark = function () {
      var fs = arguments;
      Array.prototype.forEach.call(fs, function (f) { if (touched) touched[f] = true; });
    };
    var p;
    p = scale(d.atkpow1min, d.atkpow1max); d.atkpow1min = p[0]; d.atkpow1max = p[1]; mark('atkpow1min', 'atkpow1max');
    p = scale(d.atkpow2min, d.atkpow2max); d.atkpow2min = p[0]; d.atkpow2max = p[1]; mark('atkpow2min', 'atkpow2max');
    p = scale(d.atkratingmin, d.atkratingmax); d.atkratingmin = p[0]; d.atkratingmax = p[1]; mark('atkratingmin', 'atkratingmax');
    p = scale(d.defensemin, d.defensemax); d.defensemin = p[0]; d.defensemax = p[1]; mark('defensemin', 'defensemax');
    p = sr(d.blockmin, d.blockmax); d.blockmin = p[0]; d.blockmax = p[1]; mark('blockmin', 'blockmax');
    p = sr(d.absorbmin, d.absorbmax); d.absorbmin = p[0]; d.absorbmax = p[1]; mark('absorbmin', 'absorbmax');
    p = sr(d.runspeedmin, d.runspeedmax); d.runspeedmin = p[0]; d.runspeedmax = p[1]; mark('runspeedmin', 'runspeedmax');
    p = scale(d.addhpmin, d.addhpmax); d.addhpmin = p[0]; d.addhpmax = p[1]; mark('addhpmin', 'addhpmax');
    p = scale(d.addmpmin, d.addmpmax); d.addmpmin = p[0]; d.addmpmax = p[1]; mark('addmpmin', 'addmpmax');
    p = scale(d.addstmmin, d.addstmmax); d.addstmmin = p[0]; d.addstmmax = p[1]; mark('addstmmin', 'addstmmax');
    p = sr(d.regenerationhpmin, d.regenerationhpmax); d.regenerationhpmin = p[0]; d.regenerationhpmax = p[1]; mark('regenerationhpmin', 'regenerationhpmax');
    p = sr(d.regenerationmpmin, d.regenerationmpmax); d.regenerationmpmin = p[0]; d.regenerationmpmax = p[1]; mark('regenerationmpmin', 'regenerationmpmax');
    p = sr(d.regenerationstmmin, d.regenerationstmmax); d.regenerationstmmin = p[0]; d.regenerationstmmax = p[1]; mark('regenerationstmmin', 'regenerationstmmax');
    p = scale(d.addspecdefensemin, d.addspecdefensemax); d.addspecdefensemin = p[0]; d.addspecdefensemax = p[1]; mark('addspecdefensemin', 'addspecdefensemax');
    // 注：addspecatkpower / addspecatkrating 是 Lv 除数（玩家等级/除数），age 不改变除数，故不缩放
    p = sr(d.addspecrunspeedmin, d.addspecrunspeedmax); d.addspecrunspeedmin = p[0]; d.addspecrunspeedmax = p[1]; mark('addspecrunspeedmin', 'addspecrunspeedmax');
    p = sr(d.addspecabsorbmin, d.addspecabsorbmax); d.addspecabsorbmin = p[0]; d.addspecabsorbmax = p[1]; mark('addspecabsorbmin', 'addspecabsorbmax');
    p = sr(d.addspecmpregenmin, d.addspecmpregenmax); d.addspecmpregenmin = p[0]; d.addspecmpregenmax = p[1]; mark('addspecmpregenmin', 'addspecmpregenmax');
  }

  // ------------------------------------------------------------------
  // 显示工具（原样）
  // ------------------------------------------------------------------

  function range(a, b) {
    if ((a === null || a === undefined || a === 0) && (b === null || b === undefined || b === 0)) return null;
    if (b === null || b === undefined || b === 0) return a;
    if (a === null || a === undefined || a === 0) a = 0;
    return a + ' - ' + b;
  }

  // Lv 除数显示：值为 Lv/x（玩家等级 / 除数）。单值显示 "Lv/6"，范围显示 "Lv/1 - 3"
  function lvDiv(min, max) {
    if (!min && !max) return null;
    if (!min) return 'Lv/' + max;
    if (!max || min === max) return 'Lv/' + min;
    return 'Lv/' + min + ' - ' + max;
  }

  // 数值单元格包装：有来源颜色时用 span 包裹（mod-mix 紫 / mod-age 蓝 / mod-spec 绿，均加粗）
  function valWrap(v, cls) {
    cls = (cls || '').trim();
    return cls ? '<span class="' + cls + '">' + v + '</span>' : String(v);
  }

  // ------------------------------------------------------------------
  // 组合：源实现写在 renderItemBox 里的那三步（克隆 → Mix → Age），顺序保持一致
  // ------------------------------------------------------------------

  /**
   * 由一行原始数据推导出"带 Mix/Age 叠加"的显示值。
   * @param row   服务端下发的行（数据库列名键）
   * @param opts  { mix: 配方对象|null, age: 0..20 }
   * @returns { values, mixFields, ageFields } —— 后两者记录被改过的列名，供染色
   */
  function derive(row, opts) {
    var d = JSON.parse(JSON.stringify(row));
    var mixFields = {};
    var ageFields = {};
    var o = opts || {};
    applyMixEffects(d, o.mix || null, mixFields);
    applyAge(d, o.age || 0, ageFields);
    return { values: d, mixFields: mixFields, ageFields: ageFields };
  }

  /** Spec 下拉的候选项（源实现 renderSpecSelect 的选项来源）。 */
  function specOptions(d) {
    var out = [{ value: '0', label: 'No Spec (NS)' }];
    specClassValues(d).forEach(function (v) {
      var j = SPEC_JOBS[v];
      if (j) out.push({ value: String(v), label: j[0] + ' Spec (' + j[1] + ')' });
    });
    return out;
  }

  /** Age 下拉的候选项（源实现 renderAgeSelect：No + 1..20）。 */
  function ageOptions() {
    var out = [{ value: 0, label: 'No' }];
    for (var i = 1; i <= 20; i++) out.push({ value: i, label: '+' + i });
    return out;
  }

  window.PTItemEffects = {
    REQ_MOD: REQ_MOD,
    SPEC_JOBS: SPEC_JOBS,
    reqAdjust: reqAdjust,
    specClassValues: specClassValues,
    specApplies: specApplies,
    specClassList: specClassList,
    applyMixEffects: applyMixEffects,
    applyAge: applyAge,
    range: range,
    lvDiv: lvDiv,
    valWrap: valWrap,
    derive: derive,
    specOptions: specOptions,
    ageOptions: ageOptions
  };
})();
