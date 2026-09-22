/*
 * 详情页骨架 —— `admin-item.html` / `admin-monster.html` / `admin-map.html` 共用。
 *
 * 为什么详情是**独立页面**而不是浮层：实测物品详情内容 2102px、怪物详情 2557px（视口 720px，
 * 约 3 屏），浮层里"编辑/保存"会滚出屏幕、掉落编辑器还要跟页面滚动抢位置；
 * 而且后退/刷新/分享/焦点这些浏览器原生行为在浮层里都要手工补。详见怪物册 §十三。
 *
 * 页面只依赖三样东西：
 *   ① 路径里的**数据库主键 id**（`/admin/item/123` —— 传主键不传 idcode：一个语义码可能对应多行）；
 *   ② `/columns` 的列元信息（段序、取值语义、可改性）；
 *   ③ `/{id}` 的行数据。
 *
 * 渲染用的零件（区间行配对、取值语义显示、编辑控件、脏值转换）全在 admin-common.js，三页共用。
 */
(function () {
  var T = window.PTi18n.t;

  function el(id) { return document.getElementById(id); }

  /**
   * 从路径取主键：`/pt/admin/item/123` → 123（最后一段必须是数字）。
   * 取不到返回 null —— 调用方要明说，不能默默显示空页。
   */
  function idFromPath() {
    var m = /\/(\d+)\/?$/.exec(window.location.pathname);
    return m ? parseInt(m[1], 10) : null;
  }

  function showMsg(text, isError) {
    var box = el('msgBox');
    if (!box) return;
    if (!text) { box.classList.add('hidden'); return; }
    box.textContent = text;
    box.className = 'msg ' + (isError ? 'error' : 'success');
  }

  function button(label, onClick, secondary) {
    var b = document.createElement('button');
    b.type = 'button';
    b.className = 'btn btn-small' + (secondary ? ' btn-secondary' : '');
    b.textContent = label;
    b.addEventListener('click', onClick);
    return b;
  }

  /** 一个只读行：`值1 - 值2`（单值行只有 `值1`），语义列显示名字；`decorate` 可再加工。 */
  function viewRow(r, ctx, decorate) {
    var row = document.createElement('div');
    row.className = 'item-row';
    var val = document.createElement('div');
    val.className = 'item-col-value';
    var titles = [];
    var parts = r.cols.map(function (col) {
      var v = ctx.row[col.column];
      var label = PTAdmin.optionLabel(col, v, T);
      var text = label !== null ? label : PTAdmin.fmt(v);
      var cls = '';
      if (decorate) {
        var d = decorate(col, v, ctx);
        if (d) {
          text = d.text;
          cls = d.cls || '';
          if (d.title) { titles.push(d.title); }
        }
      }
      // 带染色类的片段：类名由页面给（受控），文本一律转义
      return cls ? '<span class="' + PTAdmin.esc(cls) + '">' + PTAdmin.esc(text) + '</span>'
                 : PTAdmin.esc(text);
    });
    val.innerHTML = parts.join(' - ');
    if (r.cols[0].unit) {
      val.innerHTML += r.cols[0].unit;
    }
    if (titles.length) { val.title = titles.join(' / '); }
    row.appendChild(PTAdmin.rowNameEl(r));
    row.appendChild(val);
    return row;
  }

  /** 一个编辑行：行内每列一个控件（区间行两个框，用 `-` 隔开）。 */
  function editRow(r, ctx) {
    var row = document.createElement('div');
    row.className = 'item-row';
    var val = document.createElement('div');
    val.className = 'item-col-value';
    r.cols.forEach(function (col, i) {
      if (i > 0) {
        var sep = document.createElement('span');
        sep.className = 'item-col-sep';
        sep.textContent = ' - ';
        val.appendChild(sep);
      }
      val.appendChild(PTAdmin.editControl(col, ctx.row[col.column], function (column, value) {
        PTAdmin.markDirty(ctx.dirties, ctx.row, column, value);
      }, T));
    });
    row.appendChild(PTAdmin.rowNameEl(r));
    row.appendChild(val);
    return row;
  }

  /**
   * 按段渲染**全部列**（段序来自 /columns，不在这里硬编码）。
   *
   * @param decorate 可选：`(col, value, ctx) → {text, cls}` 覆盖显示
   *                 （物品页用它做职业需求修正 / Lv 除数 / Spec 价格 / Mix、Age 染色）
   * @param hooks    可选：`{ beforeSection(sec, container, ctx), skipColumn(col) }` ——
   *                 `beforeSection` 在某段标题后、行之前插一块（物品页用它放「掉落特效候选」勾选块）；
   *                 `skipColumn` 返回 true 的列不渲染成行（那 12 个候选列由勾选块代表，不显示两遍）。
   */
  function renderSections(ctx, container, decorate, hooks) {
    var order = [];
    var bySec = {};
    ctx.columns.forEach(function (c) {
      if (!bySec[c.section]) { bySec[c.section] = []; order.push(c.section); }
      bySec[c.section].push(c);
    });
    order.forEach(function (sec) {
      var visible = bySec[sec].filter(function (c) {
        return !(hooks && hooks.skipColumn && hooks.skipColumn(c));
      });
      var rows = PTAdmin.buildRows(visible, T);
      var title = document.createElement('div');
      title.className = 'item-section-title';
      title.textContent = T(bySec[sec][0].sectionLabelKey);
      title.title = T('admin.common.sectionCountHint', { cols: bySec[sec].length, rows: rows.length });
      container.appendChild(title);
      if (hooks && hooks.beforeSection) {
        hooks.beforeSection(sec, container, ctx);
      }
      rows.forEach(function (r) {
        container.appendChild(ctx.editing ? editRow(r, ctx) : viewRow(r, ctx, decorate));
      });
    });
  }

  /**
   * 启动一个详情页。
   *
   * @param cfg.api        该实体的管理接口根（如 `/api/admin/items`）
   * @param cfg.title      row → 标题文本
   * @param cfg.stats      row → 关键数值一行（可省）
   * @param cfg.listHref   返回列表的目标（如 `./admin/items`，相对 `<base>` 解析）
   * @param cfg.render     (row, ctx, body) → 往 body 里画主体（用 renderSections + 自己的块）
   */
  function boot(cfg) {
    var state = { columns: [], byName: {}, row: null, editing: false, dirties: {} };

    function render() {
      el('pageTitle').textContent = cfg.title(state.row);
      if (el('pageStats')) {
        el('pageStats').textContent = cfg.stats ? (cfg.stats(state.row) || '') : '';
      }
      var body = el('detailBody');
      body.innerHTML = '';
      cfg.render(state.row, ctx, body);
      if (el('editBtn')) { el('editBtn').classList.toggle('hidden', state.editing); }
      if (el('saveBtn')) { el('saveBtn').classList.toggle('hidden', !state.editing); }
      if (el('cancelBtn')) { el('cancelBtn').classList.toggle('hidden', !state.editing); }
      if (state.editing) {
        // 各页可给自己的"编辑中"提示（物品页要额外说明 Spec/Age/Mix 只是预览）
        showMsg(T(cfg.editingHint || 'admin.common.editingHint'), false);
      }
    }

    function save() {
      var changes;
      try {
        changes = PTAdmin.coerceChanges(state.dirties, state.byName, T);
      } catch (e) { showMsg(e.message, true); return; }
      if (!Object.keys(changes).length) { showMsg(T('admin.common.noChanges'), false); return; }
      PT.request(cfg.api + '/' + state.row.id, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(changes)
      }).then(function (r) {
        if (r.status === 401) { window.location.href = './login.html'; return; }
        if (!r.ok) { throw new Error(PT.msgOf(r.code, T('admin.common.requestFailed', { status: r.status }))); }
        state.row = r.data;
        state.editing = false;
        state.dirties = {};
        render();
        showMsg(T('admin.common.saved'), false);
      }).catch(function (e) { showMsg(e.message, true); });
    }

    var ctx = {
      get columns() { return state.columns; },
      get byName() { return state.byName; },
      get row() { return state.row; },
      get editing() { return state.editing; },
      get dirties() { return state.dirties; },
      msg: showMsg,
      button: button,
      rerender: render,
      save: save,
      /** 切换列编辑态（丢掉未保存的改动）。掉落编辑与列编辑互斥，靠它退出。 */
      setEditing: function (editing) {
        state.editing = editing;
        state.dirties = {};
      }
    };

    return window.PTi18n.load().then(function () {
      var id = idFromPath();
      var back = el('backLink');
      if (back && cfg.listHref) {
        back.setAttribute('href', cfg.listHref);
        back.textContent = T('admin.common.backToList');
      }
      if (id === null) {
        showMsg(T('admin.common.badPath'), true);
        return null;
      }
      PTNav.me().then(function (r) {
        if (r.status === 401) { window.location.href = './login.html'; return; }
        if (r.ok && r.data && r.data.accountName) {
          el('adminUserLabel').textContent = r.data.accountName
            + (r.data.webAdmin ? T('admin.common.adminSuffix') : '');
        }
      });
      // 只读页（如地图的"汇聚点"版）不放这三个按钮 —— 缺了就跳过，不当成错误
      if (el('editBtn')) {
        el('editBtn').addEventListener('click', function () {
          state.editing = true;
          state.dirties = {};
          render();
        });
      }
      if (el('cancelBtn')) {
        el('cancelBtn').addEventListener('click', function () {
          state.editing = false;
          state.dirties = {};
          render();
          showMsg('', false);
        });
      }
      if (el('saveBtn')) {
        el('saveBtn').addEventListener('click', save);
      }

      return Promise.all([
        PT.request(cfg.api + '/columns', { credentials: 'include' }).then(function (r) {
          if (r.status === 401) { window.location.href = './login.html'; return; }
          if (r.status === 403) { throw new Error(T('admin.common.notAdmin')); }
          if (!r.ok) { throw new Error(PT.msgOf(r.code, T('admin.common.requestFailed', { status: r.status }))); }
          state.columns = r.data || [];
          state.columns.forEach(function (c) { state.byName[c.column] = c; });
        }),
        PT.request(cfg.api + '/' + id, { credentials: 'include' }).then(function (r) {
          if (r.status === 401) { window.location.href = './login.html'; return; }
          if (r.status === 403) { throw new Error(T('admin.common.notAdmin')); }
          // 404 的 msg 就是 `error.web.{item,monster}NotFound`（api.js 有中文表），直接用
          if (!r.ok) { throw new Error(PT.msgOf(r.code, T('admin.common.requestFailed', { status: r.status }))); }
          state.row = r.data;
        })
      ]).then(function () {
        if (!state.row) return;
        render();
      }).catch(function (e) {
        showMsg(e.message, true);
      });
    });
  }

  window.PTDetail = {
    boot: boot,
    idFromPath: idFromPath,
    renderSections: renderSections,
    showMsg: showMsg,
    button: button
  };
})();
