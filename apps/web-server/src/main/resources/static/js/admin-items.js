/*
 * 物品管理页（admin-items.html）—— **纯列表**：筛选 / 表格 / 网格 / 列显示 / 分页。
 *
 * 详情与编辑在**独立页面** `/admin/item/{itemlist.id}`（admin-item.html）里做 ——
 * 内容 108 列 × 5 段 ≈ 2100px，浮层装不下（见怪物册 §十三）。行/卡片点击 = 跳转。
 *
 * 口径（设计文档 §二/§5）：
 *   · 对外一律用**数据库列名**；行含**全部列**（列集合来自 /columns，不在前端硬编码）。
 *   · 四组筛选条件是**固定**的（下面 FILTERS 与后端 ItemQueryParams 的白名单逐字对应）。
 * 取值语义 / 筛选面板 / 列选择器的共用件在 admin-common.js。
 */
(function () {
  var API = '/api/admin/items';

  /** 文案：**服务端只发 key，这里查表**（表在 static/i18n/{zh,en}.json）。 */
  var T = window.PTi18n.t;

  /** 表格默认列（决策 #4）。其余列由「列显示」勾选。 */
  var DEFAULT_COLUMNS = ['idcode', 'name', 'codeimg1', 'category', 'reqlevel', 'price', 'weight',
    'weaponclass', 'classitem', 'modelposition'];

  /**
   * 四组固定筛选条件。`range: true` → 生成 `{key}_min` / `{key}_max` 两个输入框。
   * ⚠ 键名必须与后端 `ItemQueryParams.parse` 的白名单完全一致。
   */
  var FILTERS = [
    {
      groupKey: 'admin.item.groupIdentity', fields: [
        { key: 'name_like', labelKey: 'admin.item.name', type: 'text', placeholderKey: 'admin.item.namePlaceholder' },
        { key: 'idcode', labelKey: 'admin.item.idcode', type: 'int' },
        { key: 'category', labelKey: 'admin.item.category', type: 'text',
          datalistId: 'categoryList', placeholderKey: 'admin.item.categoryPlaceholder' }
      ]
    },
    {
      groupKey: 'admin.item.groupThreshold', fields: [
        { key: 'reqlevel', labelKey: 'itemtip.reqLv', range: true },
        { key: 'price', labelKey: 'itemtip.price', range: true },
        { key: 'weight', labelKey: 'itemtip.weight', range: true }
      ]
    },
    {
      groupKey: 'admin.item.groupSemantics', fields: [
        { key: 'weaponclass', labelKey: 'weaponclass', type: 'int' },
        { key: 'classitem', labelKey: 'classitem', type: 'int' },
        { key: 'modelposition', labelKey: 'modelposition', type: 'int' }
      ]
    },
    {
      groupKey: 'admin.item.groupDefense', fields: [
        { key: 'defense', labelKey: 'admin.item.defense', range: true },
        { key: 'atkpow1', labelKey: 'atkpow1', range: true },
        { key: 'atkpow2', labelKey: 'atkpow2', range: true },
        { key: 'atkrating', labelKey: 'atkrating', range: true },
        { key: 'absorb', labelKey: 'absorb', range: true },
        { key: 'block', labelKey: 'block', range: true }
      ]
    }
  ];

  var state = {
    columns: [], byName: {},
    visible: DEFAULT_COLUMNS.slice(),
    rows: [], page: 1, size: 50, total: 0, totalPages: 1,
    view: 'table',
    sort: null, order: 'asc'
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

  /** 统一请求：401 → 去登录（返回 null）；403 → 明确报"非管理员"；其余失败抛出带原因的错误。 */
  function call(path, opts) {
    return PT.request(path, opts).then(function (r) {
      if (r.status === 401) { redirectToLogin(); return null; }
      if (r.status === 403) { throw new Error(T('admin.common.notAdmin')); }
      if (!r.ok) { throw new Error(PT.msgOf(r.code, T('admin.common.requestFailed', { status: r.status }))); }
      return r.data;
    });
  }

  /** 打开详情页：路径式路由 + **数据库主键**（相对路径按页面里的 <base> 解析到 context 根）。 */
  function openDetail(id) {
    window.location.href = './admin/item/' + id;
  }

  // ------------------------------------------------------------------
  // 列表
  // ------------------------------------------------------------------

  function load() {
    hideErr();
    var parts = PTAdmin.collectFilters(FILTERS);
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
      renderGrid();
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
      PTAdmin.appendViewCell(tr, './admin/item/' + row.id, T('admin.common.open'));
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

  /** 网格视图：按分类的卡片墙（截图借鉴：分类卡 + 码徽章 + 分类/价格 + 整卡可点）。 */
  function renderGrid() {
    var box = el('gridBody');
    if (!box) return;
    box.innerHTML = '';
    if (state.view !== 'grid') return;
    var grid = document.createElement('div');
    grid.className = 'item-cards';
    state.rows.forEach(function (row) {
      var card = document.createElement('div');
      card.className = 'item-card';

      var head = document.createElement('div');
      head.className = 'item-card-head';
      var nm = document.createElement('span');
      nm.className = 'item-card-name';
      nm.textContent = PTAdmin.fmt(row.name);
      head.appendChild(nm);
      if (row.codeimg1) {
        var code = document.createElement('span');
        code.className = 'item-card-badge';
        code.textContent = PTAdmin.fmt(row.codeimg1);
        head.appendChild(code);
      }
      card.appendChild(head);

      var stats = document.createElement('div');
      stats.className = 'item-card-stats';
      var cat = document.createElement('span');
      cat.textContent = PTAdmin.fmt(row.category);
      stats.appendChild(cat);
      if (row.price !== null && row.price !== undefined) {
        var price = document.createElement('span');
        price.textContent = T('itemtip.price') + ' ' + PTAdmin.fmt(row.price);
        stats.appendChild(price);
      }
      card.appendChild(stats);

      var sub = document.createElement('div');
      sub.className = 'item-card-sub';
      sub.textContent = '#' + row.id + '  ' + PTAdmin.fmt(row.idcode);
      card.appendChild(sub);

      card.addEventListener('click', function () { openDetail(row.id); });
      grid.appendChild(card);
    });
    box.appendChild(grid);
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

  /** 表格 / 网格切换。 */
  function setView(view) {
    if (state.view === view) return;
    state.view = view;
    el('gridBody').classList.toggle('hidden', view !== 'grid');
    el('tableWrapper').classList.toggle('hidden', view === 'grid');
    el('tableBtn').classList.toggle('btn-secondary', view === 'grid');
    el('gridBtn').classList.toggle('btn-secondary', view !== 'grid');
    renderGrid();
  }

  function buildFilterBar() {
    PTAdmin.buildFilterBar(el('filterBar'), FILTERS, state.byName, T, {
      mainKey: 'name_like',
      onInput: function () { state.page = 1; load(); }
    });
    // 分类候选项的容器（/facets 到了再填）
    var dl = document.createElement('datalist');
    dl.id = 'categoryList';
    el('filterBar').appendChild(dl);
  }

  // ------------------------------------------------------------------
  // 事件绑定
  // ------------------------------------------------------------------

  function bind() {
    el('resetBtn').addEventListener('click', function () { PTAdmin.resetFilters(FILTERS); load(); });
    el('refreshBtn').addEventListener('click', function () { load(); });
    el('prevBtn').addEventListener('click', function () { if (state.page > 1) { state.page--; load(); } });
    el('nextBtn').addEventListener('click', function () { if (state.page < state.totalPages) { state.page++; load(); } });
    el('colPickerBtn').addEventListener('click', function () { el('colPicker').classList.toggle('hidden'); });
    el('tableBtn').addEventListener('click', function () { setView('table'); });
    el('gridBtn').addEventListener('click', function () { setView('grid'); });

    // 直达页码：越界自动夹取
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

  // ------------------------------------------------------------------
  // 启动
  // ------------------------------------------------------------------

  function start() {
    // 文案表先到（服务端只发 key）：表没到就渲染会把 key 直接显示出来
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
          // 列语义到了才能决定"哪些筛选项是下拉"，故此处重建一次筛选面板（首次加载、无输入可丢）
          buildFilterBar();
        }),
        call(API + '/facets').then(function (f) {
          if (!f) return;
          var dl = el('categoryList');
          (f.categories || []).forEach(function (c) {
            var opt = document.createElement('option');
            opt.value = c.value;
            opt.textContent = c.value + '（' + c.count + '）';
            dl.appendChild(opt);
          });
          el('hint').textContent = T('admin.item.categoryHint');
        }).catch(function () {
          el('hint').textContent = T('admin.item.categoryHintFailed');
        })
      ]).then(load);
    });
  }

  start();
})();
