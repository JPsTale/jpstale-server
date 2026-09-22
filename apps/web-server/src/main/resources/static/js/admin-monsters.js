/*
 * 怪物管理页（admin-monsters.html）—— **纯列表**：筛选 / 表格 / 卡片 / 列显示 / 分页。
 *
 * 详情与编辑在**独立页面** `/admin/monster/{monsterlist.id}`（admin-monster.html）里做
 *（内容 53 列 × 7 段 + 刷怪地图 + 掉落 ≈ 2560px，浮层装不下 —— 见怪物册 §十三）。
 * 行/卡片点击 = 跳转；掉落里的物品名、刷怪地图名也都在详情页里点。
 *
 * 口径：
 *   · 四组筛选条件是**固定**的（下面 FILTERS 与后端 MonsterQueryParams 的白名单逐字对应）。
 *   · BOSS 徽章的名单来自 `/bosses`（在 mapmonster 的 boss/submonster 列里被声明的怪名），
 *     徽章的 title 如实说明"这几列当前刷怪代码不读"。
 * 取值语义 / 筛选面板 / 列选择器的共用件在 admin-common.js。
 */
(function () {
  var API = '/api/admin/monsters';

  var T = window.PTi18n.t;

  /** 表格默认列（用户点名的"名字、等级、攻防属性、经验值" + id 与两类语义列）。 */
  var DEFAULT_COLUMNS = ['monsterid', 'name', 'level', 'hp', 'exp',
    'atkpowmin', 'atkpowmax', 'defense', 'monstertype', 'propertymon'];

  /** 四组固定筛选条件（键名必须与后端 `MonsterQueryParams.parse` 的白名单完全一致）。 */
  var FILTERS = [
    {
      groupKey: 'admin.monster.groupIdentity', fields: [
        { key: 'name_like', labelKey: 'admin.monster.name', type: 'text',
          placeholderKey: 'admin.monster.namePlaceholder' }
      ]
    },
    {
      groupKey: 'admin.monster.groupLevel', fields: [
        { key: 'level', labelKey: 'monster.level', range: true }
      ]
    },
    {
      groupKey: 'admin.monster.groupType', fields: [
        { key: 'monstertype', labelKey: 'monster.typeLabel' },
        { key: 'propertymon', labelKey: 'monster.propertyLabel' }
      ]
    },
    {
      groupKey: 'admin.monster.groupMap', fields: [
        { key: 'map', labelKey: 'admin.monster.map', type: 'select' }
      ]
    }
  ];

  /** 卡片视图的等级分段：每 10 级一段（最后一段兜到 140，覆盖实测最高 135）。 */
  var LEVEL_BANDS = [];
  for (var bandFrom = 1; bandFrom <= 131; bandFrom += 10) {
    LEVEL_BANDS.push([bandFrom, bandFrom + 9]);
  }

  /** 卡片视图一次拉多少条（表格视图用 50）。 */
  var CARD_PAGE_SIZE = 200;

  var state = {
    columns: [], byName: {},
    visible: DEFAULT_COLUMNS.slice(),
    rows: [], page: 1, size: 50, total: 0, totalPages: 1,
    view: 'table',
    bossNames: {}
  };

  function el(id) { return document.getElementById(id); }

  function hideMsg(node) { node.classList.add('hidden'); }
  function showMsg(node, text, isError) {
    node.textContent = text;
    node.className = 'msg ' + (isError ? 'error' : 'success');
  }
  function showErr(text) { showMsg(el('errBox'), text, true); }
  function hideErr() { hideMsg(el('errBox')); }

  function redirectToLogin() { window.location.href = './login.html'; }

  function call(path, opts) {
    return PT.request(path, opts).then(function (r) {
      if (r.status === 401) { redirectToLogin(); return null; }
      if (r.status === 403) { throw new Error(T('admin.common.notAdmin')); }
      if (!r.ok) { throw new Error(PT.msgOf(r.code, T('admin.common.requestFailed', { status: r.status }))); }
      return r.data;
    });
  }

  /** 打开详情页：路径式路由 + **数据库主键**。 */
  function openDetail(id) {
    window.location.href = './admin/monster/' + id;
  }

  // ------------------------------------------------------------------
  // 列表（表格 / 卡片）
  // ------------------------------------------------------------------

  function load() {
    hideErr();
    var q = PTAdmin.collectFilters(FILTERS).concat(['page=' + state.page, 'size=' + state.size]).join('&');
    return call(API + '?' + q).then(function (data) {
      if (!data) return;
      state.rows = data.items || [];
      state.total = data.total;
      state.totalPages = data.totalPages;
      state.page = data.page;
      state.size = data.size;
      renderHead();
      renderTable();
      renderCards();
      renderPager();
    }).catch(function (e) { showErr(e.message); });
  }

  function renderHead() {
    var tr = el('tableHead');
    tr.innerHTML = '';
    state.visible.forEach(function (c) {
      var th = document.createElement('th');
      th.textContent = c;
      tr.appendChild(th);
    });
  }

  function renderTable() {
    var tb = el('tableBody');
    tb.innerHTML = '';
    state.rows.forEach(function (row) {
      var tr = document.createElement('tr');
      state.visible.forEach(function (c) {
        var td = document.createElement('td');
        var col = state.byName[c];
        var label = col ? PTAdmin.optionLabel(col, row[c], T) : null;
        td.textContent = label !== null ? label : PTAdmin.fmt(row[c]);
        tr.appendChild(td);
      });
      tr.addEventListener('click', function () { openDetail(row.id); });
      tb.appendChild(tr);
    });
  }

  /** 等级落在哪一段（无匹配时归入最后一段 —— 实测最高 135，段表覆盖到 140）。 */
  function bandOf(level) {
    var lv = Number(level);
    if (!isFinite(lv)) {
      return LEVEL_BANDS[0];
    }
    for (var i = 0; i < LEVEL_BANDS.length; i++) {
      if (lv >= LEVEL_BANDS[i][0] && lv <= LEVEL_BANDS[i][1]) {
        return LEVEL_BANDS[i];
      }
    }
    return LEVEL_BANDS[LEVEL_BANDS.length - 1];
  }

  /** 卡片视图：按**等级分段**铺卡片（分段顺序按等级升序，不是"数据里先遇到谁"）。 */
  function renderCards() {
    var box = el('cardBody');
    if (!box) return;
    box.innerHTML = '';
    if (state.view !== 'card') return;

    var byBand = {};
    state.rows.forEach(function (r) {
      var band = bandOf(r.level);
      var key = band[0] + '-' + band[1];
      if (!byBand[key]) { byBand[key] = { band: band, rows: [] }; }
      byBand[key].rows.push(r);
    });
    LEVEL_BANDS.map(function (b) { return b[0] + '-' + b[1]; }).forEach(function (key) {
      var g = byBand[key];
      if (!g) return;
      var head = document.createElement('div');
      head.className = 'item-band';
      head.textContent = T('admin.monster.band', { from: g.band[0], to: g.band[1] })
        + ' · ' + T('admin.monster.bandCount', { n: g.rows.length });
      box.appendChild(head);
      var grid = document.createElement('div');
      grid.className = 'item-cards';
      g.rows.forEach(function (r) { grid.appendChild(monsterCard(r)); });
      box.appendChild(grid);
    });
  }

  function monsterCard(r) {
    var card = document.createElement('div');
    card.className = 'item-card';

    var head = document.createElement('div');
    head.className = 'item-card-head';
    var nm = document.createElement('span');
    nm.className = 'item-card-name';
    nm.textContent = PTAdmin.fmt(r.name);
    head.appendChild(nm);
    if (state.bossNames[r.name]) {
      var badge = document.createElement('span');
      badge.className = 'item-card-badge';
      badge.textContent = T('admin.monster.bossBadge');
      badge.title = T('admin.monster.bossHint');
      head.appendChild(badge);
    }
    card.appendChild(head);

    var stats = document.createElement('div');
    stats.className = 'item-card-stats';
    [[T('monster.level'), r.level], [T('monster.hp'), r.hp], [T('monster.exp'), r.exp]]
      .forEach(function (pair) {
        var s = document.createElement('span');
        s.textContent = pair[0] + ' ' + PTAdmin.fmt(pair[1]);
        stats.appendChild(s);
      });
    card.appendChild(stats);

    var sub = document.createElement('div');
    sub.className = 'item-card-sub';
    sub.textContent = PTAdmin.fmt(r.monsterid) + ' · '
      + semanticLabel('monstertype', r.monstertype) + ' · ' + semanticLabel('propertymon', r.propertymon);
    card.appendChild(sub);

    card.addEventListener('click', function () { openDetail(r.id); });
    return card;
  }

  function semanticLabel(column, value) {
    var col = state.byName[column];
    var label = col ? PTAdmin.optionLabel(col, value, T) : null;
    return label !== null ? label : PTAdmin.fmt(value);
  }

  function renderPager() {
    el('pageInfo').textContent = T('admin.common.pager', {
      page: state.page, pages: state.totalPages, total: state.total, size: state.size });
    el('prevBtn').disabled = state.page <= 1;
    el('nextBtn').disabled = state.page >= state.totalPages;
  }

  function onColToggle(column, checked) {
    var i = state.visible.indexOf(column);
    if (checked && i === -1) { state.visible.push(column); }
    if (!checked && i !== -1) { state.visible.splice(i, 1); }
    renderHead();
    renderTable();
  }

  /** 表格 / 卡片切换：卡片视图每页 200 条（分段铺开需要更多数据）。 */
  function setView(view) {
    if (state.view === view) return;
    state.view = view;
    state.size = view === 'card' ? CARD_PAGE_SIZE : 50;
    state.page = 1;
    el('cardBody').classList.toggle('hidden', view !== 'card');
    el('tableWrapper').classList.toggle('hidden', view === 'card');
    el('tableBtn').classList.toggle('btn-secondary', view === 'card');
    el('cardBtn').classList.toggle('btn-secondary', view !== 'card');
    load();
  }

  function buildFilterBar() {
    PTAdmin.buildFilterBar(el('filterBar'), FILTERS, state.byName, T);
  }

  // ------------------------------------------------------------------
  // 事件绑定
  // ------------------------------------------------------------------

  function bind() {
    el('searchBtn').addEventListener('click', function () { state.page = 1; load(); });
    el('resetBtn').addEventListener('click', function () { PTAdmin.resetFilters(FILTERS); load(); });
    el('refreshBtn').addEventListener('click', function () { load(); });
    el('prevBtn').addEventListener('click', function () { if (state.page > 1) { state.page--; load(); } });
    el('nextBtn').addEventListener('click', function () { if (state.page < state.totalPages) { state.page++; load(); } });
    el('colPickerBtn').addEventListener('click', function () { el('colPicker').classList.toggle('hidden'); });
    el('tableBtn').addEventListener('click', function () { setView('table'); });
    el('cardBtn').addEventListener('click', function () { setView('card'); });

    el('logoutBtn').addEventListener('click', function () {
      fetch(PT.apiUrl('/api/user/logout'), { method: 'POST', credentials: 'include' })
        .finally(function () { redirectToLogin(); });
    });
  }

  // ------------------------------------------------------------------
  // 启动
  // ------------------------------------------------------------------

  function start() {
    return window.PTi18n.load().then(function () {
      buildFilterBar();
      bind();

      PTNav.me().then(function (r) {
        if (r.status === 401) { redirectToLogin(); return; }
        if (r.ok && r.data && r.data.accountName) {
          el('adminUserLabel').textContent = r.data.accountName
            + (r.data.webAdmin ? T('admin.common.adminSuffix') : '');
        }
      });

      return Promise.all([
        call(API + '/columns').then(function (cols) {
          if (!cols) return;
          state.columns = cols;
          cols.forEach(function (c) { state.byName[c.column] = c; });
          PTAdmin.buildColumnPicker(el('colPicker'), state.columns, state.visible, onColToggle, T);
          renderHead();
          buildFilterBar();
        }),
        call(API + '/facets').then(function (f) {
          if (!f) return;
          PTAdmin.fillSelect('map', (f.maps || []).map(function (m) {
            return { value: m.value, label: m.name };
          }));
        }).catch(function () {
          el('hint').textContent = T('admin.common.loadFailed');
        }),
        // BOSS 徽章名单：拿不到就不显示徽章（不静默：写进 hint）
        call(API + '/bosses').then(function (b) {
          (b && b.names ? b.names : []).forEach(function (n) { state.bossNames[n] = true; });
        }).catch(function () {
          el('hint').textContent = T('admin.common.loadFailed');
        })
      ]).then(load);
    });
  }

  start();
})();
