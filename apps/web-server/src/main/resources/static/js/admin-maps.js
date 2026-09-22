/*
 * 地图管理页（admin-maps.html）—— **纯列表**：筛选 / 表格 / 列显示 / 分页。
 *
 * 详情（四表汇聚点 + 列编辑 + **刷怪配置编辑**）在 `/admin/map/{maplist.id}`（admin-map.html）。
 *
 * 五组固定筛选：名字（同时搜 name 与 shortname）· 地形类型 · 等级要求区间 · PvP · **是否有刷怪配置**。
 * ⚠ 后两组里，"是否有刷怪配置"不是本表的列 —— 它是"`mapmonster` 里有没有这张图的行"（48/63 张有），
 * 判据与详情页的 `spawn.configured` 同源。
 */
(function () {
  var API = '/api/admin/maps';
  var T = window.PTi18n.t;

  /** 只有 7 列，默认就把它们都摆出来。 */
  var DEFAULT_COLUMNS = ['id', 'name', 'shortname', 'typemap', 'levelreq', 'pvp', 'stagefile'];

  /** 键名必须与后端 `MapQueryParams.parse` 的白名单完全一致。 */
  var FILTERS = [
    {
      groupKey: 'admin.map.groupIdentity', fields: [
        { key: 'name_like', labelKey: 'admin.map.name', type: 'text',
          placeholderKey: 'admin.map.namePlaceholder' }
      ]
    },
    {
      groupKey: 'admin.map.groupRules', fields: [
        { key: 'typemap', labelKey: 'map.typeMap', type: 'select' },
        { key: 'levelreq', labelKey: 'itemtip.reqLv', range: true }
      ]
    },
    {
      groupKey: 'admin.map.groupFlags', fields: [
        { key: 'pvp', labelKey: 'admin.map.pvp', type: 'select' },
        { key: 'has_spawn', labelKey: 'admin.map.hasSpawn', type: 'select' }
      ]
    }
  ];

  var state = {
    columns: [], byName: {},
    visible: DEFAULT_COLUMNS.slice(),
    rows: [], page: 1, size: 50, total: 0, totalPages: 1
  };

  function el(id) { return document.getElementById(id); }

  function showErr(text) {
    var box = el('errBox');
    box.textContent = text;
    box.className = 'msg error';
  }
  function hideErr() { el('errBox').classList.add('hidden'); }

  function redirectToLogin() { window.location.href = './login.html'; }

  function call(path, opts) {
    return PT.request(path, opts).then(function (r) {
      if (r.status === 401) { redirectToLogin(); return null; }
      if (r.status === 403) { throw new Error(T('admin.common.notAdmin')); }
      if (!r.ok) { throw new Error(PT.msgOf(r.code, T('admin.common.requestFailed', { status: r.status }))); }
      return r.data;
    });
  }

  /** 详情页（路径式路由 + 主键）。 */
  function openDetail(id) { window.location.href = './admin/map/' + id; }

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
        // null 显示空、0 显示 0（levelreq/pvp 都有真实的 0）
        td.textContent = label !== null ? label : PTAdmin.fmt(row[c]);
        tr.appendChild(td);
      });
      tr.addEventListener('click', function () { openDetail(row.id); });
      tb.appendChild(tr);
    });
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

  function buildFilterBar() {
    PTAdmin.buildFilterBar(el('filterBar'), FILTERS, state.byName, T);
  }

  /** 三个 select 的候选：地形类型（原文+计数）、PvP（0/1）、是否有刷怪配置（true/false）。 */
  function fillSelects(f) {
    PTAdmin.fillSelect('typemap', (f.typeMaps || []).map(function (t) {
      return { value: t.value, label: PTAdmin.fmt(t.value) + '（' + t.count + '）' };
    }));
    PTAdmin.fillSelect('pvp', [
      { value: '0', label: T('admin.common.no') },
      { value: '1', label: T('admin.common.yes') }
    ]);
    PTAdmin.fillSelect('has_spawn', [
      { value: 'true', label: T('admin.common.yes') },
      { value: 'false', label: T('admin.common.no') }
    ]);
  }

  function bind() {
    el('searchBtn').addEventListener('click', function () { state.page = 1; load(); });
    el('resetBtn').addEventListener('click', function () { PTAdmin.resetFilters(FILTERS); load(); });
    el('refreshBtn').addEventListener('click', function () { load(); });
    el('prevBtn').addEventListener('click', function () { if (state.page > 1) { state.page--; load(); } });
    el('nextBtn').addEventListener('click', function () { if (state.page < state.totalPages) { state.page++; load(); } });
    el('colPickerBtn').addEventListener('click', function () { el('colPicker').classList.toggle('hidden'); });
    el('logoutBtn').addEventListener('click', function () {
      fetch(PT.apiUrl('/api/user/logout'), { method: 'POST', credentials: 'include' })
        .finally(function () { redirectToLogin(); });
    });
  }

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
          fillSelects(f);
        }).catch(function () {
          el('hint').textContent = T('admin.common.loadFailed');
        })
      ]).then(load);
    });
  }

  start();
})();
