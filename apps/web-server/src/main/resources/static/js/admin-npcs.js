/*
 * NPC 管理页（admin-npcs.html）—— **纯列表**：筛选 / 表格 / 列显示 / 分页。
 *
 * 详情与编辑在独立页面 `/admin/npc/{npclist.id}`（admin-npc.html）：那里有全部列、
 * **商店清单**（三个商店列的编辑，复用物品选择器）与**摆放**（mapnpc）。
 *
 * 四组固定筛选：名字 / 所在地图 / 是否商人 / 事件类型。
 * ⚠ 后两组不是本表的列：`map` 经 `mapnpc` 反查；`merchant` = "三个商店列任一非空"
 *（与运行时 `NpcShopService.isMerchant` 同一条判据，见后端 AdminNpcService 的注释）。
 */
(function () {
  var API = '/api/admin/npcs';
  var T = window.PTi18n.t;

  var DEFAULT_COLUMNS = ['name', 'gamefile', 'eventtype', 'teleportid', 'weaponshop', 'miscshop'];

  /** 键名必须与后端 `NpcQueryParams.parse` 的白名单完全一致。 */
  var FILTERS = [
    {
      groupKey: 'admin.npc.groupIdentity', fields: [
        { key: 'name_like', labelKey: 'admin.npc.name', type: 'text',
          placeholderKey: 'admin.npc.namePlaceholder' }
      ]
    },
    {
      groupKey: 'admin.npc.groupMap', fields: [
        { key: 'map', labelKey: 'admin.npc.map', type: 'select' }
      ]
    },
    {
      groupKey: 'admin.npc.groupMerchant', fields: [
        { key: 'merchant', labelKey: 'admin.npc.merchant', type: 'select' }
      ]
    },
    {
      groupKey: 'admin.npc.groupEvent', fields: [
        { key: 'eventtype', labelKey: 'npc.eventType', type: 'select' }
      ]
    }
  ];

  var state = {
    columns: [], byName: {},
    visible: DEFAULT_COLUMNS.slice(),
    rows: [], page: 1, size: 50, total: 0, totalPages: 1,
    sort: null, order: 'asc'
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
  function openDetail(id) { window.location.href = './admin/npc/' + id; }

  function load() {
    hideErr();
    var parts = PTAdmin.collectFilters(FILTERS);
    var typed = el('f_name_like') ? el('f_name_like').value.trim() : '';
    if (typed) {
      var matched = PTAdmin.resolveNpcNames(typed);
      if (matched.length) {
        // 命中本地化名：改成"内名称精确列表"交给服务端（服务端不需要知道任何显示名）
        parts = parts.filter(function (p) { return p.indexOf('name_like=') !== 0; });
        parts.push('names=' + encodeURIComponent(matched.slice(0, 200).join(',')));
        el('hint').textContent = T('admin.npc.matchedByName', { n: matched.length });
      } else {
        el('hint').textContent = '';
      }
    } else {
      el('hint').textContent = '';
    }
    if (state.sort) { parts.push('sort=' + encodeURIComponent(state.sort), 'order=' + state.order); }
    var q = parts.concat(['page=' + state.page, 'size=' + state.size]).join('&');
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
    PTAdmin.buildHead(el('tableHead'), state.visible, state.byName, T, {
      sort: state.sort, order: state.order,
      onSort: function (c) {
        if (state.sort === c) {
          state.order = state.order === 'asc' ? 'desc' : 'asc';
        } else {
          state.sort = c;
          state.order = 'asc';
        }
        state.page = 1;
        load();
      }
    });
  }

  function renderTable() {
    var tb = el('tableBody');
    tb.innerHTML = '';
    state.rows.forEach(function (row) {
      var tr = document.createElement('tr');
      PTAdmin.appendViewCell(tr, './admin/npc/' + row.id, T('admin.common.open'));
      state.visible.forEach(function (c) {
        var td = document.createElement('td');
        var col = state.byName[c];
        if (c === 'name') {
          // 显示**本地化名**（`npclist.name` 是内部键），内名称放 title 便于追溯
          td.textContent = PTAdmin.npcName(row.name);
          td.title = row.name;
        } else if (c === 'eventtype') {
          // §5.1 eventtype 语义标签：裸数字 → 可读语义（站桩/商人/仓库/教官/武器店…），原数字放 title
          var ev = PTAdmin.eventTypeSem(row[c]);
          td.textContent = ev.label;
          td.title = T('admin.npc.eventtypeCode', { code: ev.value });
        } else if (c === 'teleportid' && (row[c] === 0 || row[c] === null || row[c] === undefined)) {
          // §5.2 teleportid=0 = 无传送，直接画破折号，不显示裸 0
          td.textContent = '\u2014';
        } else if (c === 'teleportid') {
          // §5.2 传送目的地语义：静态表 → 目的地名，原码放 title
          var tp = PTAdmin.teleportSem(row[c]);
          td.textContent = tp ? tp.label : PTAdmin.fmt(row[c]);
          td.title = T('admin.npc.teleportCode', { code: row[c] });
        } else if (c === 'eventtype') {
          // §5.1 eventtype 语义标签：裸数字 → 可读语义（站桩/商人/仓库/教官/武器店/防具店/传送），原数字放 title
          var ev = PTAdmin.eventTypeSem(row[c]);
          td.textContent = ev.label;
          td.title = T('admin.npc.eventtypeCode', { code: ev.value });
        } else if (c === 'teleportid') {
          // §5.2 teleportid 目的地语义：>0 但要按目的地读（1=5目的地 / 2=城堡 / 3=无尽塔），0=无传送画破折号
          if (row[c] === 0 || row[c] === null || row[c] === undefined) {
            td.textContent = '\u2014';
          } else {
            var tp = PTAdmin.teleportSem(row[c]);
            td.textContent = tp ? tp.label : PTAdmin.fmt(row[c]);
            td.title = T('admin.npc.teleportidCode', { code: row[c] });
          }
        } else if (c === 'eventtype') {
          // §5.1 eventtype 语义标签：可读语义名显示，原数字进 title
          var ev = PTAdmin.eventTypeSem(row[c]);
          td.textContent = ev.label;
          td.title = T('admin.npc.eventtypeCode', { code: ev.value });
        } else if (c === 'teleportid') {
          // §5.2 teleportid 目的地语义：teleportid=0=无传送画破折号；其余显示目的地语义标签
          if (row[c] === 0 || row[c] === null || row[c] === undefined) {
            td.textContent = '\u2014';
          } else {
            var tp = PTAdmin.teleportSem(row[c]);
            td.textContent = tp ? tp.label : PTAdmin.fmt(row[c]);
            td.title = T('admin.npc.teleportidCode', { code: row[c] });
          }
        } else {
          var label = col ? PTAdmin.optionLabel(col, row[c], T) : null;
          td.textContent = label !== null ? label : PTAdmin.fmt(row[c]);
        }
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
    if (state.pagerJump) { state.pagerJump.sync(state.page, state.totalPages); }
  }

  function onColToggle(column, checked) {
    var i = state.visible.indexOf(column);
    if (checked && i === -1) { state.visible.push(column); }
    if (!checked && i !== -1) { state.visible.splice(i, 1); }
    renderHead();
    renderTable();
  }

  function buildFilterBar() {
    PTAdmin.buildFilterBar(el('filterBar'), FILTERS, state.byName, T, {
      mainKey: 'name_like',
      onInput: function () { state.page = 1; load(); }
    });
  }

  /** 三个 select 型筛选的候选：地图、是否商人、事件类型（都由 /facets 给）。 */
  function fillSelects(f) {
    PTAdmin.fillSelect('map', (f.maps || []).map(function (m) {
      return { value: m.value, label: m.name + '（' + m.count + '）' };
    }));
    PTAdmin.fillSelect('merchant', [
      { value: 'true', label: T('admin.common.yes') },
      { value: 'false', label: T('admin.common.no') }
    ]);
    PTAdmin.fillSelect('eventtype', (f.eventTypes || []).map(function (e) {
      return { value: e.value, label: PTAdmin.fmt(e.value) + '（' + e.count + '）' };
    }));
  }

  function bind() {
    el('resetBtn').addEventListener('click', function () { PTAdmin.resetFilters(FILTERS); load(); });
    el('refreshBtn').addEventListener('click', function () { load(); });
    el('prevBtn').addEventListener('click', function () { if (state.page > 1) { state.page--; load(); } });
    el('nextBtn').addEventListener('click', function () { if (state.page < state.totalPages) { state.page++; load(); } });
    el('colPickerBtn').addEventListener('click', function () { el('colPicker').classList.toggle('hidden'); });

    state.pagerJump = PTAdmin.buildPagerJump(document.querySelector('.item-pager'), T, function (n) {
      var max = state.totalPages || 1;
      var p = Math.min(Math.max(n, 1), max);
      if (p !== state.page) { state.page = p; load(); }
    });

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
