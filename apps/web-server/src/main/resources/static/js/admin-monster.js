/*
 * 怪物详情页（`/admin/monster/{主键id}` → admin-monster.html）。
 *
 * 与浮层版的区别：**独立页面**（内容 53 列 × 7 段 + 刷怪地图 + 掉落 ≈ 2560px，浮层里装不下 ——
 * 见怪物册 §十三），路由传的是 **`monsterlist.id` 主键**。
 *
 * 两块跨表视图：
 *   · 刷怪地图（`mapmonster`）：地图名可点 → `/admin/map/{maplist.id}`；
 *   · 掉落（`dropitem`）：物品名可点 → `/admin/item/{itemlist.id}`（**主键**，不是 idcode）；
 *     并带整表替换的编辑器（新增/删除行、物品搜索选择器、百分比随输入实时算）。
 * 通用件（分段渲染、编辑控件、脏值转换）在 admin-detail.js / admin-common.js。
 */
(function () {
  var API = '/api/admin/monsters';
  var ITEM_SEARCH_API = '/api/admin/items/search';
  var T = window.PTi18n.t;

  var state = {
    spawn: null, spawnLoaded: false, spawnFailed: false,
    drops: null, dropsLoaded: false, dropsFailed: false,
    dropEditing: false, dropRows: []
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

  function semanticLabel(column, value, byName) {
    var col = byName[column];
    var label = col ? PTAdmin.optionLabel(col, value, T) : null;
    return label !== null ? label : PTAdmin.fmt(value);
  }

  function button(label, onClick, secondary) {
    var b = document.createElement('button');
    b.type = 'button';
    b.className = 'btn btn-small' + (secondary ? ' btn-secondary' : '');
    b.textContent = label;
    b.addEventListener('click', onClick);
    return b;
  }

  // ------------------------------------------------------------------
  // 刷怪地图（mapmonster，只读；地图名可点）
  // ------------------------------------------------------------------

  function spawnBlock(ctx) {
    var box = document.createElement('div');
    box.appendChild(sectionTitle(T('admin.monster.spawnTitle')));
    if (state.spawnFailed) {
      box.appendChild(note(T('admin.common.loadFailed')));
      return box;
    }
    if (!state.spawn) {
      return box;
    }
    var maps = state.spawn.maps || [];
    if (!maps.length) {
      box.appendChild(note(T('admin.monster.noSpawn')));
    }
    maps.forEach(function (m) { box.appendChild(mapRow(m)); });

    var bossMaps = state.spawn.bossMaps || [];
    if (bossMaps.length) {
      box.appendChild(sectionTitle(T('admin.monster.spawnBossTitle')));
      box.appendChild(note(T('admin.monster.spawnBossUnused')));
      bossMaps.forEach(function (m) { box.appendChild(mapRow(m)); });
    }
    return box;
  }

  function mapRow(m) {
    var row = document.createElement('div');
    row.className = 'item-row';
    var name = document.createElement('div');
    name.className = 'item-col-name';
    if (m.name) {
      var a = document.createElement('a');
      a.className = 'item-ref';
      a.href = './admin/map/' + m.mapId;      // 主键路由
      a.textContent = m.name;
      name.appendChild(a);
    } else {
      name.textContent = '#' + m.mapId;
    }
    name.title = 'maplist.id=' + m.mapId + (m.shortName ? '  shortname=' + m.shortName : '');
    row.appendChild(name);
    return row;
  }

  // ------------------------------------------------------------------
  // 掉落：只读（分布图 + 可点物品名）
  // ------------------------------------------------------------------

  function dropBlock(ctx) {
    var box = document.createElement('div');
    box.appendChild(sectionTitle(T('admin.monster.dropTitle')));

    var bar = document.createElement('div');
    bar.className = 'item-drop-head';
    if (!state.dropEditing) {
      var edit = button(T('admin.monster.editDrops'), function () { startDropEditing(ctx); }, true);
      edit.disabled = state.dropsFailed || !state.drops;
      bar.appendChild(edit);
    }
    box.appendChild(bar);

    if (state.dropsFailed) {
      box.appendChild(note(T('admin.common.loadFailed')));
      return box;
    }
    if (!state.drops) {
      return box;
    }
    if (state.dropEditing) {
      buildDropEditor(box, ctx);
      return box;
    }

    var summary = document.createElement('div');
    summary.className = 'item-row';
    var sName = document.createElement('div');
    sName.className = 'item-col-name';
    sName.textContent = T('admin.monster.dropKey');
    var sVal = document.createElement('div');
    sVal.className = 'item-col-value';
    sVal.textContent = PTAdmin.fmt(state.drops.dropId)
      + '   ' + T('admin.monster.dropTotal') + ' ' + PTAdmin.fmt(state.drops.totalChance);
    summary.appendChild(sName);
    summary.appendChild(sVal);
    box.appendChild(summary);

    var rows = state.drops.rows || [];
    if (!rows.length) {
      box.appendChild(note(T('admin.monster.dropNoRows')));
      return box;
    }
    (state.drops.warnings || []).forEach(function (w) { box.appendChild(note(w, true)); });

    rows.forEach(function (d) { box.appendChild(dropRow(d)); });
    return box;
  }

  function dropRowLabel(d) {
    if (d.kind === 'GOLD') { return T('admin.monster.dropKindGold'); }
    if (d.kind === 'AIR') { return T('admin.monster.dropKindAir'); }
    var entries = d.entries || [];
    if (!entries.length) {
      return d.items + '  ' + T('admin.monster.dropCodeUnknown');
    }
    return entries.map(function (e) {
      return e.name ? e.name : (e.code + '  ' + T('admin.monster.dropCodeUnknown'));
    }).join(' / ');
  }

  /** 物品行里的每个物品都是**可点引用** → `/admin/item/{itemlist.id}`（主键）。 */
  function dropNameCell(d) {
    var name = document.createElement('div');
    name.className = 'item-col-name';
    name.title = d.items;
    if (d.kind === 'GOLD' || d.kind === 'AIR') {
      name.textContent = dropRowLabel(d);
      return name;
    }
    var entries = d.entries || [];
    if (!entries.length) {
      name.textContent = d.items + '  ' + T('admin.monster.dropCodeUnknown');
      return name;
    }
    entries.forEach(function (e, i) {
      if (i > 0) { name.appendChild(document.createTextNode(' / ')); }
      if (e.itemId) {
        var a = document.createElement('a');
        a.className = 'item-ref';
        a.href = './admin/item/' + e.itemId;
        a.textContent = e.name || e.code;
        a.title = e.code;
        name.appendChild(a);
      } else {
        name.appendChild(document.createTextNode(
          (e.name || e.code) + '  ' + T('admin.monster.dropCodeUnknown')));
      }
    });
    return name;
  }

  /** 只读掉落行 → 卡片（左 = 物品名，右 = 概率独立对齐，见 docs/design-webadmin.md §7 D5）。 */
  function dropRow(d) {
    var card = document.createElement('div');
    card.className = 'drop-card';

    var left = document.createElement('div');
    left.className = 'drop-card-name';
    var nameCell = dropNameCell(d);
    nameCell.className = '';   // 卡片布局里不再用 item-col-name 的等宽样式
    left.appendChild(nameCell);
    if (d.skipped) {
      var warn = document.createElement('div');
      warn.className = 'drop-card-warn';
      warn.textContent = T('admin.monster.dropSkipped')
        + '：' + T(d.skipReason === 'chanceZero'
          ? 'admin.monster.dropSkipChanceZero' : 'admin.monster.dropSkipUnknownItem');
      left.appendChild(warn);
    }
    card.appendChild(left);

    var right = document.createElement('div');
    right.className = 'drop-card-chance';
    if (d.kind === 'GOLD') {
      right.appendChild(document.createTextNode(
        T('admin.monster.dropColumnGold') + ' ' + PTAdmin.fmt(d.goldMin) + ' - ' + PTAdmin.fmt(d.goldMax)));
    }
    var pill = document.createElement('span');
    pill.className = 'drop-chance-pill';
    pill.textContent = (d.percent !== null && d.percent !== undefined)
      ? PTAdmin.fmt(d.percent) + '%'
      : PTAdmin.fmt(d.chance);
    pill.title = T('admin.monster.dropColumnChance') + ' ' + PTAdmin.fmt(d.chance);
    right.appendChild(pill);
    card.appendChild(right);
    return card;
  }


  // ------------------------------------------------------------------
  // 掉落：编辑（整表替换；物品用搜索选择；百分比随输入实时算）
  // ------------------------------------------------------------------

  function startDropEditing(ctx) {
    ctx.setEditing(false);          // 列编辑与掉落编辑互斥（保存语义不同）
    state.dropEditing = true;
    state.dropRows = (state.drops && state.drops.rows ? state.drops.rows : []).map(function (x) {
      return {
        id: x.id,
        kind: x.kind,
        codes: x.kind === 'ITEMS' ? String(x.items || '').split(/\s+/).filter(Boolean) : [],
        entries: (x.entries || []).slice(),
        goldMin: x.goldMin,
        goldMax: x.goldMax,
        chance: x.chance
      };
    });
    ctx.msg('');
    ctx.rerender();
  }

  function itemsString(r) {
    if (r.kind === 'GOLD') return 'Gold';
    if (r.kind === 'AIR') return 'Air';
    return r.codes.join(' ');
  }

  /** 编辑期的**预检**（服务端保存时给权威判定，含"物品码认不出"这一条）。 */
  function rowSkippedInEditor(r) {
    return !(Number(r.chance) > 0) || (r.kind === 'ITEMS' && r.codes.length === 0);
  }

  function liveTotal() {
    var t = 0;
    state.dropRows.forEach(function (r) {
      if (!rowSkippedInEditor(r)) { t += Number(r.chance); }
    });
    return t;
  }

  /** 只更新百分比与合计的文本（不重画输入框，否则输入一个数字就丢焦点）。 */
  function updateLiveTotal() {
    var total = liveTotal();
    state.dropRows.forEach(function (r, i) {
      var span = el('dropPct_' + i);
      if (!span) return;
      if (rowSkippedInEditor(r) || total <= 0) {
        span.className = 'item-hint item-warn';
        span.textContent = T('admin.monster.dropSkippedEdit');
      } else {
        span.className = 'item-hint';
        span.textContent = (Math.round(Number(r.chance) * 10000 / total) / 100) + '%';
      }
    });
    var totalEl = el('dropLiveTotal');
    if (totalEl) {
      totalEl.textContent = T('admin.monster.dropRowsCount', { n: state.dropRows.length })
        + ' · ' + T('admin.monster.dropLiveTotal') + ' ' + total;
    }
  }

  function buildDropEditor(box, ctx) {
    var bar = document.createElement('div');
    bar.className = 'item-drop-head';
    bar.appendChild(button(T('admin.monster.addDropRow'), function () {
      state.dropRows.push({ id: null, kind: 'ITEMS', codes: [], entries: [], goldMin: 0, goldMax: 0, chance: 1000 });
      ctx.rerender();
    }, true));
    bar.appendChild(button(T('admin.monster.saveDrops'), function () { saveDrops(ctx); }));
    bar.appendChild(button(T('admin.common.cancel'), function () {
      state.dropEditing = false;
      state.dropRows = [];
      ctx.rerender();
    }, true));
    var total = document.createElement('span');
    total.className = 'item-hint';
    total.id = 'dropLiveTotal';
    bar.appendChild(total);
    box.appendChild(bar);

    if (!state.dropRows.length) {
      box.appendChild(note(T('admin.monster.dropNoRows')));
    }
    state.dropRows.forEach(function (r, i) { box.appendChild(dropEditRow(r, i, ctx)); });
    // 百分比在 render 末尾（挂载之后）刷新，见本文件 cfg.render
  }

  function dropEditRow(r, i, ctx) {
    var row = document.createElement('div');
    row.className = 'item-drop-row';

    var head = document.createElement('div');
    head.className = 'item-drop-head';

    var typeSel = document.createElement('select');
    typeSel.className = 'admin-input';
    [['ITEMS', 'admin.monster.dropTypeItems'], ['GOLD', 'admin.monster.dropTypeGold'], ['AIR', 'admin.monster.dropTypeAir']]
      .forEach(function (pair) {
        var o = document.createElement('option');
        o.value = pair[0];
        o.textContent = T(pair[1]);
        typeSel.appendChild(o);
      });
    typeSel.value = r.kind;
    typeSel.title = T('admin.monster.dropType');
    typeSel.addEventListener('change', function () {
      r.kind = typeSel.value;
      ctx.rerender();
    });
    head.appendChild(typeSel);

    var chance = document.createElement('input');
    chance.className = 'admin-input';
    chance.type = 'number';
    chance.min = '0';
    chance.id = 'dropChance_' + i;
    chance.value = r.chance === null || r.chance === undefined ? '' : String(r.chance);
    chance.title = T('admin.monster.dropColumnChance');
    chance.addEventListener('input', function () {
      r.chance = chance.value;
      chance.classList.remove('item-input-bad');
      updateLiveTotal();
    });
    head.appendChild(chance);

    var pct = document.createElement('span');
    pct.className = 'item-hint';
    pct.id = 'dropPct_' + i;
    head.appendChild(pct);

    head.appendChild(button(T('admin.monster.dropDeleteRow'), function () {
      state.dropRows.splice(i, 1);
      ctx.rerender();
    }, true));
    row.appendChild(head);

    if (r.kind === 'ITEMS') {
      var chips = document.createElement('div');
      PTAdmin.renderItemChips(chips, r.entries, function (code) {
        r.codes = r.codes.filter(function (c) { return String(c).toLowerCase() !== String(code).toLowerCase(); });
        r.entries = r.entries.filter(function (y) { return String(y.code).toLowerCase() !== String(code).toLowerCase(); });
        ctx.rerender();
      }, T('admin.monster.dropNoItemYet'), T);
      row.appendChild(chips);
      row.appendChild(PTAdmin.itemPicker('dropPick_' + i, T('admin.monster.dropPickerPlaceholder'),
        function (it, input, results) { addItem(i, it, input, results, ctx); }, T));
    } else if (r.kind === 'GOLD') {
      var gold = document.createElement('div');
      gold.className = 'item-drop-head';
      [['goldMin', T('admin.monster.dropGoldMin')], ['goldMax', T('admin.monster.dropGoldMax')]]
        .forEach(function (pair) {
          var label = document.createElement('label');
          label.textContent = pair[1];
          var input = document.createElement('input');
          input.className = 'admin-input';
          input.type = 'number';
          input.min = '0';
          input.value = r[pair[0]] === null || r[pair[0]] === undefined ? '0' : String(r[pair[0]]);
          input.addEventListener('input', function () { r[pair[0]] = input.value; });
          label.appendChild(input);
          gold.appendChild(label);
        });
      row.appendChild(gold);
    } else {
      row.appendChild(note(T('admin.monster.dropAirHint')));
    }
    return row;
  }

  function addItem(rowIndex, it, input, results, ctx) {
    var r = state.dropRows[rowIndex];
    if (!r) return;
    var code = String(it.codeimg1 === null || it.codeimg1 === undefined ? '' : it.codeimg1).trim();
    if (!code) return;
    var exists = r.codes.some(function (c) { return String(c).toLowerCase() === code.toLowerCase(); });
    if (!exists) {
      r.codes.push(code);
      r.entries.push({ code: code, itemId: it.id, idcode: it.idcode, name: it.name });
    }
    input.value = '';
    results.classList.add('hidden');
    ctx.rerender();
    // 重渲染后把焦点还给同一个输入框（连着加几件物品时不用重新点）
    var again = el('dropPick_' + rowIndex);
    if (again) { again.focus(); }
  }

  function saveDrops(ctx) {
    var payload = [];
    for (var i = 0; i < state.dropRows.length; i++) {
      var r = state.dropRows[i];
      var chance = Number(r.chance);
      if (r.chance === '' || r.chance === null || isNaN(chance) || chance < 0) {
        var input = el('dropChance_' + i);
        if (input) { input.classList.add('item-input-bad'); }
        ctx.msg(T('admin.monster.dropNeedNumber', { n: i + 1 }), true);
        return;
      }
      if (r.kind === 'ITEMS' && !r.codes.length) {
        ctx.msg(T('admin.monster.dropNeedItems', { n: i + 1 }), true);
        return;
      }
      var row = { id: r.id, items: itemsString(r), chance: chance };
      if (r.kind === 'GOLD') {
        row.goldMin = Number(r.goldMin) || 0;
        row.goldMax = Number(r.goldMax) || 0;
      }
      payload.push(row);
    }
    PT.request(API + '/' + ctx.row.id + '/drops', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ rows: payload })
    }).then(function (res) {
      if (!res.ok) {
        throw new Error(PT.msgOf(res.code, T('admin.common.requestFailed', { status: res.status })));
      }
      state.drops = res.data;
      state.dropEditing = false;
      state.dropRows = [];
      ctx.rerender();
      ctx.msg(T('admin.monster.dropSavedCounts', {
        a: res.data.added, u: res.data.updated, r: res.data.removed }), false);
    }).catch(function (e) { ctx.msg(e.message, true); });
  }

  // ------------------------------------------------------------------
  // 启动
  // ------------------------------------------------------------------

  PTDetail.boot({
    api: API,
    listHref: './admin/monsters',
    title: function (row) {
      return PTAdmin.fmt(row.name) + '  #' + row.id;
    },
    stats: function (row) {
      var parts = [
        T('monster.level') + ' ' + PTAdmin.fmt(row.level),
        T('monster.hp') + ' ' + PTAdmin.fmt(row.hp),
        T('monster.exp') + ' ' + PTAdmin.fmt(row.exp)
      ];
      if (row.atkpowmin !== null && row.atkpowmin !== undefined) {
        parts.push(T('monster.atk') + ' ' + PTAdmin.fmt(row.atkpowmin) + ' - ' + PTAdmin.fmt(row.atkpowmax));
      }
      return parts.join(' · ');
    },
    render: function (row, ctx, body) {
      // ⚠ 这两块只在首次抓（render 会被重渲染反复调用）
      if (!state.spawnLoaded) {
        state.spawnLoaded = true;
        PT.request(API + '/' + row.id + '/spawn', { credentials: 'include' }).then(function (r) {
          state.spawn = (r.ok && r.data) ? r.data : null;
          state.spawnFailed = !r.ok;
          ctx.rerender();
        }).catch(function () { state.spawnFailed = true; ctx.rerender(); });
      }
      if (!state.dropsLoaded) {
        state.dropsLoaded = true;
        PT.request(API + '/' + row.id + '/drops', { credentials: 'include' }).then(function (r) {
          state.drops = (r.ok && r.data) ? r.data : null;
          state.dropsFailed = !r.ok;
          ctx.rerender();
        }).catch(function () { state.dropsFailed = true; ctx.rerender(); });
      }

      PTDetail.renderSections(ctx, body);
      body.appendChild(spawnBlock(ctx));
      body.appendChild(dropBlock(ctx));
      // 掉落编辑态的百分比/合计：**挂载之后**才能按 id 找到（这块是先建好再 append 的）
      if (state.dropEditing) {
        updateLiveTotal();
      }
    }
  });
})();
