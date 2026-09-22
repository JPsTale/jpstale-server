/*
 * 地图详情页（`/admin/map/{maplist.id}` → admin-map.html）—— **四表汇聚点**，其中两段可编辑。
 *
 *   ① maplist 全部列（走 /columns 的段序与语义）—— **可编辑**（等级门槛 / 地形类型 / PvP / 名称 / 场景文件…）；
 *   ② **刷怪配置**（`mapmonster` 一行）—— **可编辑**：12 个普通怪槽位（怪物 + 数量）、上限/间隔、
 *      3 个 Boss 槽位 + 3 个副怪槽位；保存是**整行替换**（空槽位写 NULL）；
 *   ③ **NPC**（`mapnpc`）—— 只读，名字可点 → `/admin/npc/{npclist.id}`；
 *   ④ **怪物刷新点**（`mapspawnpoint`，**不是玩家出生点**——那是 `fields.json` 的 `startPoints`）—— 只读，x/z + description。
 *
 * 跨表引用一律传**主键**；怪物名同时给"在 monsterlist 里查不到"的标红（刷怪代码按名字查模板，会跳过它）。
 *
 * ⚠ 刷怪配置的改动**要等 game-server 重启才生效**（`MapManager` 只在启动时读一次 `mapmonster`）。
 */
(function () {
  var API = '/api/admin/maps';
  var T = window.PTi18n.t;

  /** `mapmonster` 的槽位数（与后端 AdminMapService 的两个常量一致）。 */
  var WAVE_SLOTS = 12;
  var BOSS_SLOTS = 3;

  var state = {
    spawn: null, spawnLoaded: false, spawnFailed: false,
    npcs: null, npcsLoaded: false, npcsFailed: false,
    points: null, pointsLoaded: false, pointsFailed: false,
    // 刷怪配置编辑态：槽位数组（slot 下标 1..12 / 1..3）
    spawnEditing: false, editWaves: null, editBoss: null, editSub: null,
    editMax: 0, editInterval: 0
  };

  function el(id) { return document.getElementById(id); }

  function note(text, warn) {
    var n = document.createElement('div');
    n.className = 'item-hint' + (warn ? ' item-warn' : '');
    n.textContent = text;
    return n;
  }

  function sectionTitle(text) {
    var t = document.createElement('div');
    t.className = 'item-section-title';
    t.textContent = text;
    return t;
  }

  function button(label, onClick, secondary) {
    var b = document.createElement('button');
    b.type = 'button';
    b.className = 'btn btn-small' + (secondary ? ' btn-secondary' : '');
    b.textContent = label;
    b.addEventListener('click', onClick);
    return b;
  }

  function row(leftEl, valueText, title) {
    var r = document.createElement('div');
    r.className = 'item-row';
    var name = document.createElement('div');
    name.className = 'item-col-name';
    if (leftEl) { name.appendChild(leftEl); }
    if (title) { name.title = title; }
    var val = document.createElement('div');
    val.className = 'item-col-value';
    val.textContent = valueText;
    r.appendChild(name);
    r.appendChild(val);
    return r;
  }

  /** 怪物名 → 可点引用；名字在 monsterlist 里找不到时标红（刷怪代码会跳过它）。 */
  function monsterLink(name, monsterId) {
    var span = document.createElement('span');
    if (monsterId) {
      var a = document.createElement('a');
      a.className = 'item-ref';
      a.href = './admin/monster/' + monsterId;   // 主键路由
      a.textContent = name;
      span.appendChild(a);
    } else {
      span.className = 'item-warn';
      span.textContent = name;
      span.title = T('admin.map.monsterUnmatched');
    }
    return span;
  }

  /** 怪物选择器：按名字搜 `/api/admin/monsters`（`mapmonster` 的槽位存的就是**名字**）。 */
  function monsterPicker(inputId, onPick) {
    return PTAdmin.searchPicker(inputId, T('admin.map.monsterPickerPlaceholder'), function (q) {
      // 空查询 = **列出本图刷怪配置里已经用到的怪**（与 NPC 选择器同一个理由：不必先猜关键词）
      if (!q) {
        var seen = {};
        var out = [];
        ((state.spawn && state.spawn.waves) || []).forEach(function (w) {
          if (!w.monsterName || seen[w.monsterName]) { return; }
          seen[w.monsterName] = true;
          out.push({ value: { name: w.monsterName, level: null }, label: w.monsterName });
        });
        return out;
      }
      return PT.request('/api/admin/monsters?size=20&name_like=' + encodeURIComponent(q),
        { credentials: 'include' }).then(function (r) {
        var list = (r.ok && r.data && r.data.items) ? r.data.items : [];
        return list.map(function (m) {
          return {
            value: m,
            label: PTAdmin.fmt(m.name) + '   Lv' + PTAdmin.fmt(m.level) + '   #' + m.id
          };
        });
      });
    }, function (m, input, results) { onPick(m, input, results); }, T, { showInitial: true });
  }

  // ------------------------------------------------------------------
  // ② 刷怪配置（可编辑）
  // ------------------------------------------------------------------

  function spawnBlock(ctx) {
    var box = document.createElement('div');
    box.appendChild(sectionTitle(T('admin.map.spawnTitle')));

    var bar = document.createElement('div');
    bar.className = 'item-drop-head';
    if (!state.spawnEditing) {
      var edit = button(T('admin.map.editSpawn'), function () { startSpawnEditing(ctx); }, true);
      edit.disabled = state.spawnFailed;
      bar.appendChild(edit);
      bar.appendChild(note(T('admin.map.spawnRestartHint')));
    }
    box.appendChild(bar);

    if (state.spawnFailed) {
      box.appendChild(note(T('admin.common.loadFailed')));
      return box;
    }
    if (!state.spawn) {
      return box;
    }
    if (state.spawnEditing) {
      buildSpawnEditor(box, ctx);
      return box;
    }

    if (!state.spawn.configured) {
      box.appendChild(note(T('admin.map.noSpawn')));
      return box;
    }
    box.appendChild(note(T('admin.map.spawnLimits', {
      max: PTAdmin.fmt(state.spawn.maxMonsters), interval: PTAdmin.fmt(state.spawn.interval) })));
    (state.spawn.warnings || []).forEach(function (w) { box.appendChild(note(w, true)); });

    (state.spawn.waves || []).forEach(function (w) {
      box.appendChild(row(monsterLink(w.monsterName, w.monsterId),
        T('admin.map.waveCount', { n: PTAdmin.fmt(w.count) }),
        'monster' + w.slot + ' / count' + w.slot));
    });

    var boss = (state.spawn.bossWaves || []);
    if (boss.length) {
      box.appendChild(sectionTitle(T('admin.map.bossWavesTitle')));
      box.appendChild(note(T('admin.map.bossWavesUnused')));
      boss.forEach(function (w) {
        box.appendChild(row(monsterLink(w.monsterName, w.monsterId),
          (w.kind === 'boss' ? 'bossmonster' : 'submonster') + w.slot));
      });
    }
    return box;
  }

  function startSpawnEditing(ctx) {
    ctx.setEditing(false);            // 列编辑与刷怪编辑互斥
    state.spawnEditing = true;
    var sp = state.spawn || { waves: [], bossWaves: [], maxMonsters: 0, interval: 0 };
    state.editWaves = new Array(WAVE_SLOTS).fill(null);
    state.editBoss = new Array(BOSS_SLOTS).fill(null);
    state.editSub = new Array(BOSS_SLOTS).fill(null);
    (sp.waves || []).forEach(function (w) {
      if (w.slot >= 1 && w.slot <= WAVE_SLOTS) {
        state.editWaves[w.slot - 1] = { monster: w.monsterName, count: w.count };
      }
    });
    (sp.bossWaves || []).forEach(function (w) {
      if (w.slot >= 1 && w.slot <= BOSS_SLOTS) {
        (w.kind === 'boss' ? state.editBoss : state.editSub)[w.slot - 1] = { monster: w.monsterName };
      }
    });
    state.editMax = sp.maxMonsters === null || sp.maxMonsters === undefined ? 0 : sp.maxMonsters;
    state.editInterval = sp.interval === null || sp.interval === undefined ? 0 : sp.interval;
    ctx.msg('');
    ctx.rerender();
  }

  function buildSpawnEditor(box, ctx) {
    // 上限 / 间隔
    var limits = document.createElement('div');
    limits.className = 'item-drop-head';
    [['max', T('admin.map.maxMonsters'), state.editMax], ['interval', T('admin.map.interval'), state.editInterval]]
      .forEach(function (pair) {
        var label = document.createElement('label');
        label.textContent = pair[1];
        var input = document.createElement('input');
        input.className = 'admin-input';
        input.type = 'number';
        input.min = '0';
        input.value = String(pair[2]);
        input.addEventListener('input', function () {
          if (pair[0] === 'max') { state.editMax = input.value; } else { state.editInterval = input.value; }
        });
        label.appendChild(input);
        limits.appendChild(label);
      });
    box.appendChild(limits);

    // 12 个普通怪槽位：有怪物 → 名字 + 数量 + 清空；空槽位 → 选择器（槽位是固定的，不是"新增行"）
    for (var i = 0; i < WAVE_SLOTS; i++) {
      box.appendChild(waveSlotRow(i, ctx));
    }

    // Boss / 副怪槽位（只编辑名字；hoursbossmonster*/countsub* 我们不碰）
    box.appendChild(sectionTitle(T('admin.map.bossWavesTitle')));
    box.appendChild(note(T('admin.map.bossWavesUnused')));
    for (var b = 0; b < BOSS_SLOTS; b++) {
      box.appendChild(bossSlotRow('boss', b, ctx));
      box.appendChild(bossSlotRow('sub', b, ctx));
    }

    var footer = document.createElement('div');
    footer.className = 'item-drop-head';
    footer.appendChild(button(T('admin.map.saveSpawn'), function () { saveSpawn(ctx); }));
    footer.appendChild(button(T('admin.common.cancel'), function () {
      state.spawnEditing = false;
      ctx.rerender();
    }, true));
    box.appendChild(footer);
  }

  function waveSlotRow(i, ctx) {
    var rowEl = document.createElement('div');
    rowEl.className = 'item-drop-row';
    var head = document.createElement('div');
    head.className = 'item-drop-head';
    var label = document.createElement('span');
    label.className = 'item-card-name';
    label.textContent = 'monster' + (i + 1);
    label.title = 'monster' + (i + 1) + ' / count' + (i + 1);
    head.appendChild(label);

    var cur = state.editWaves[i];
    if (cur) {
      var name = document.createElement('span');
      name.textContent = cur.monster;
      name.title = cur.monster;
      head.appendChild(name);

      var count = document.createElement('input');
      count.className = 'admin-input';
      count.type = 'number';
      count.min = '0';
      count.id = 'waveCount_' + i;
      count.value = cur.count === null || cur.count === undefined ? '0' : String(cur.count);
      count.title = T('admin.map.waveCountLabel');
      count.addEventListener('input', function () { cur.count = count.value; });
      head.appendChild(count);

      head.appendChild(button(T('admin.common.remove'), function () {
        state.editWaves[i] = null;
        ctx.rerender();
      }, true));
    } else {
      head.appendChild(monsterPicker('wavePick_' + i, function (m, input, results) {
        state.editWaves[i] = { monster: m.name, count: 1 };
        input.value = '';
        results.classList.add('hidden');
        ctx.rerender();
        var again = el('wavePick_' + i);
        if (again) { again.focus(); }
      }));
    }
    rowEl.appendChild(head);
    return rowEl;
  }

  function bossSlotRow(kind, i, ctx) {
    var rowEl = document.createElement('div');
    rowEl.className = 'item-drop-row';
    var head = document.createElement('div');
    head.className = 'item-drop-head';
    var label = document.createElement('span');
    label.className = 'item-card-name';
    label.textContent = (kind === 'boss' ? 'bossmonster' : 'submonster') + (i + 1);
    head.appendChild(label);

    var arr = kind === 'boss' ? state.editBoss : state.editSub;
    var cur = arr[i];
    if (cur) {
      var name = document.createElement('span');
      name.textContent = cur.monster;
      head.appendChild(name);
      head.appendChild(button(T('admin.common.remove'), function () {
        arr[i] = null;
        ctx.rerender();
      }, true));
    } else {
      head.appendChild(monsterPicker(kind + 'Pick_' + i, function (m, input, results) {
        arr[i] = { monster: m.name };
        input.value = '';
        results.classList.add('hidden');
        ctx.rerender();
        var again = el(kind + 'Pick_' + i);
        if (again) { again.focus(); }
      }));
    }
    rowEl.appendChild(head);
    return rowEl;
  }

  function saveSpawn(ctx) {
    var waves = [];
    for (var i = 0; i < WAVE_SLOTS; i++) {
      var w = state.editWaves[i];
      if (!w) { continue; }
      var count = Number(w.count);
      if (w.count === '' || isNaN(count) || count < 0) {
        var input = el('waveCount_' + i);
        if (input) { input.classList.add('item-input-bad'); }
        ctx.msg(T('admin.map.waveCountBad', { slot: i + 1 }), true);
        return;
      }
      waves.push({ slot: i + 1, monster: w.monster, count: count });
    }
    var bossWaves = [];
    for (var b = 0; b < BOSS_SLOTS; b++) {
      if (state.editBoss[b]) { bossWaves.push({ kind: 'boss', slot: b + 1, monster: state.editBoss[b].monster }); }
      if (state.editSub[b]) { bossWaves.push({ kind: 'sub', slot: b + 1, monster: state.editSub[b].monster }); }
    }
    var max = Number(state.editMax);
    var interval = Number(state.editInterval);
    if (isNaN(max) || max < 0 || isNaN(interval) || interval < 0) {
      ctx.msg(T('admin.map.limitsBad'), true);
      return;
    }
    PT.request(API + '/' + ctx.row.id + '/spawn', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ maxmonsters: max, interval: interval, waves: waves, bossWaves: bossWaves })
    }).then(function (r) {
      if (!r.ok) {
        throw new Error(PT.msgOf(r.code, T('admin.common.requestFailed', { status: r.status })));
      }
      state.spawn = r.data;
      state.spawnEditing = false;
      ctx.rerender();
      ctx.msg(T('admin.map.spawnSaved', {
        waves: (r.data.waves || []).length, created: r.data.created ? T('admin.map.spawnCreated') : '' }), false);
    }).catch(function (e) { ctx.msg(e.message, true); });
  }

  // ------------------------------------------------------------------
  // ③ NPC（只读）—— 汇总面板：名字 + 徽标 + 朝向，坐标移入 title；悬停行点亮画布标记
  // ------------------------------------------------------------------

  function chip(text, kind) {
    var s = document.createElement('span');
    s.className = 'badge badge-' + (kind || 'warn');
    s.textContent = text;
    return s;
  }

  /** 汇总行悬停 ↔ 画布标记联动（`setSelected` 就是画布的高亮环）。 */
  function hoverLink(row, markerId) {
    row.addEventListener('mouseenter', function () {
      if (viewCanvas) { viewCanvas.setSelected(markerId); }
    });
    row.addEventListener('mouseleave', function () {
      if (viewCanvas) { viewCanvas.setSelected(null); }
    });
  }

  function npcSummaryRow(n) {
    var row = document.createElement('div');
    row.className = 'map-summary-row';
    var name = document.createElement('span');
    name.className = 'map-summary-name';
    if (n.npcId) {
      var a = document.createElement('a');
      a.className = 'item-ref';
      a.href = './admin/npc/' + n.npcId;      // 主键路由
      a.textContent = PTAdmin.npcName(n.npcName);
      name.appendChild(a);
    } else {
      name.textContent = PTAdmin.fmt(n.npcName);
    }
    row.appendChild(name);

    var tags = document.createElement('span');
    tags.className = 'map-summary-tags';
    if (!n.enabled) { tags.appendChild(chip(T('admin.common.disabled'), 'warn')); }
    if (n.onlyGm) { tags.appendChild(chip(T('admin.common.onlyGm'), 'gm')); }
    row.appendChild(tags);

    var coord = document.createElement('span');
    coord.className = 'map-summary-coord';
    coord.textContent = '◤ angle ' + PTAdmin.fmt(n.angle);
    row.appendChild(coord);
    row.title = 'mapnpc.id=' + n.placeId + '  (' + PTAdmin.fmt(n.x) + ', ' + PTAdmin.fmt(n.z) + ')';
    hoverLink(row, 'npc:' + n.placeId);
    return row;
  }

  function npcBlock() {
    var box = document.createElement('div');
    box.appendChild(sectionTitle(T('admin.map.npcTitle')));
    if (state.npcsFailed) {
      box.appendChild(note(T('admin.common.loadFailed')));
      return box;
    }
    if (!state.npcs) {
      return box;
    }
    if (!state.npcs.count) {
      box.appendChild(note(T('admin.map.noNpc')));
      return box;
    }
    var list = document.createElement('div');
    list.className = 'map-summary';
    state.npcs.npcs.forEach(function (n) { list.appendChild(npcSummaryRow(n)); });
    box.appendChild(list);
    return box;
  }

  // ------------------------------------------------------------------
  // ④ 怪物刷新点（只读）—— 汇总面板：描述 + 坐标（小字），行悬停点亮画布
  // ------------------------------------------------------------------

  function pointSummaryRow(p) {
    var row = document.createElement('div');
    row.className = 'map-summary-row';
    var name = document.createElement('span');
    name.className = 'map-summary-name';
    name.textContent = p.description ? PTAdmin.fmt(p.description) : '#' + PTAdmin.fmt(p.pointId);
    row.appendChild(name);
    var coord = document.createElement('span');
    coord.className = 'map-summary-coord';
    coord.textContent = '(' + PTAdmin.fmt(p.x) + ', ' + PTAdmin.fmt(p.z) + ')';
    row.appendChild(coord);
    row.title = 'mapspawnpoint.id=' + p.pointId;
    hoverLink(row, 'point:' + p.pointId);
    return row;
  }

  function pointsBlock() {
    var box = document.createElement('div');
    box.appendChild(sectionTitle(T('admin.map.pointsTitle')));
    if (state.pointsFailed) {
      box.appendChild(note(T('admin.common.loadFailed')));
      return box;
    }
    if (!state.points) {
      return box;
    }
    if (!state.points.count) {
      box.appendChild(note(T('admin.map.noPoints')));
      return box;
    }
    box.appendChild(note(T('admin.map.pointsCount', { n: state.points.count })));
    var list = document.createElement('div');
    list.className = 'map-summary';
    state.points.points.forEach(function (p) { list.appendChild(pointSummaryRow(p)); });
    box.appendChild(list);
    return box;
  }

  // ------------------------------------------------------------------
  // ⑤ 位置可视化（只读画布 + 编辑器入口）
  // ------------------------------------------------------------------

  var viewCanvas = null;

  function mapBlock(ctx) {
    var box = document.createElement('div');
    box.appendChild(sectionTitle(T('admin.map.viewTitle')));
    var bar = document.createElement('div');
    bar.className = 'item-drop-head';
    bar.appendChild(button(T('admin.map.editVisual'), function () {
      window.location.href = './admin/map/' + ctx.row.id + '/edit';
    }, true));
    bar.appendChild(note(T('admin.map.viewHint')));
    box.appendChild(bar);
    // 图例（颜色与画布一致：NPC 青绿点 / 刷新点红点）
    var legend = document.createElement('div');
    legend.className = 'map-legend';
    [['#8bf08b', T('admin.map.legendNpc')], ['#ff5252', T('admin.map.legendPoint')]]
      .forEach(function (pair) {
        var item = document.createElement('span');
        item.className = 'map-legend-item';
        var sw = document.createElement('span');
        sw.className = 'map-legend-swatch';
        sw.style.background = pair[0];
        item.appendChild(sw);
        item.appendChild(document.createTextNode(pair[1]));
        legend.appendChild(item);
      });
    box.appendChild(legend);
    var host = document.createElement('div');
    host.className = 'map-view';
    host.id = 'mapViewHost';
    box.appendChild(host);
    return box;
  }

  /**
   * 画布要在**挂载之后**建：`#mapViewHost` 挂着才有尺寸（与掉落百分比同一条教训 ——
   * 游离节点上 `clientWidth` 是 0，量出来的视野是错的）。
   */
  function mountViewCanvas() {
    var host = el('mapViewHost');
    if (!host) { return; }
    if (viewCanvas) { viewCanvas.destroy(); viewCanvas = null; }
    viewCanvas = window.PTMapCanvas.create(host, {
      mapId: ctxRowId(),
      onHover: function () {}     // 只读视图不要读数
    });
    var list = [];
    ((state.npcs && state.npcs.npcs) || []).forEach(function (n) {
      list.push({ id: 'npc:' + n.placeId, kind: 'npc', x: n.x, z: n.z, angle: n.angle });
    });
    ((state.points && state.points.points) || []).forEach(function (p) {
      list.push({ id: 'point:' + p.pointId, kind: 'point', x: p.x, z: p.z });
    });
    viewCanvas.setMarkers(list);
  }

  var currentRowId = null;
  function ctxRowId() { return currentRowId; }

  // ------------------------------------------------------------------
  // 启动
  // ------------------------------------------------------------------

  PTDetail.boot({
    api: API,
    listHref: './admin/maps',
    title: function (r) { return PTAdmin.fmt(r.name) + '  #' + r.id; },
    stats: function (r) {
      var parts = [PTAdmin.fmt(r.typemap), T('itemtip.reqLv') + ' ' + PTAdmin.fmt(r.levelreq)];
      if (Number(r.pvp) !== 0) { parts.push('PvP'); }
      if (state.spawn && state.spawn.configured) {
        parts.push(T('admin.map.statWaves', { n: (state.spawn.waves || []).length }));
      }
      if (state.npcs) { parts.push(T('admin.map.statNpcs', { n: state.npcs.count })); }
      if (state.points) { parts.push(T('admin.map.statPoints', { n: state.points.count })); }
      return parts.join(' · ');
    },
    render: function (r, ctx, body) {
      if (!state.spawnLoaded) {
        state.spawnLoaded = true;
        PT.request(API + '/' + r.id + '/spawn', { credentials: 'include' }).then(function (res) {
          state.spawn = (res.ok && res.data) ? res.data : null;
          state.spawnFailed = !res.ok;
          ctx.rerender();
        }).catch(function () { state.spawnFailed = true; ctx.rerender(); });
      }
      if (!state.npcsLoaded) {
        state.npcsLoaded = true;
        PT.request(API + '/' + r.id + '/npcs', { credentials: 'include' }).then(function (res) {
          state.npcs = (res.ok && res.data) ? res.data : null;
          state.npcsFailed = !res.ok;
          ctx.rerender();
        }).catch(function () { state.npcsFailed = true; ctx.rerender(); });
      }
      if (!state.pointsLoaded) {
        state.pointsLoaded = true;
        PT.request(API + '/' + r.id + '/points', { credentials: 'include' }).then(function (res) {
          state.points = (res.ok && res.data) ? res.data : null;
          state.pointsFailed = !res.ok;
          ctx.rerender();
        }).catch(function () { state.pointsFailed = true; ctx.rerender(); });
      }

      currentRowId = r.id;
      // ⚠ **可视化块放最前**（用户 2026-09-22：原先它在 121 行列段 + 42 个 NPC + 刷新点之后，
      //   想看/想编辑得先翻很久）。顺序：地图 → 刷怪配置 → NPC → 怪物刷新点 → 全部列。
      body.appendChild(mapBlock(ctx));
      body.appendChild(spawnBlock(ctx));
      body.appendChild(npcBlock());
      body.appendChild(pointsBlock());
      PTDetail.renderSections(ctx, body);
      // 挂载之后再建画布（见 mountViewCanvas 的注释）
      mountViewCanvas();
    }
  });
})();
