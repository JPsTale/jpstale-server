/*
 * 物品详情页（`/admin/item/{主键id}` → admin-item.html）。
 *
 * 与浮层版的区别：**独立页面**（内容 108 列 × 5 段 ≈ 2100px，浮层里装不下 —— 见怪物册 §十三），
 * 路由传的是 **`itemlist.id` 主键**（不是 `idcode`：一个语义码可能对应多行）。
 *
 * 物品特有的三件在这里：① Spec / Age / Mix 三个**预览**选择器（只影响显示，不写回库）；
 * ② 需求列的**职业修正**、Lv 除数、Spec 价格 +20% 的显示加工（`decorate`）；
 * ③ 「掉落特效候选」勾选块（它就是 `addspecclass1..12` 这 12 列的编辑入口）。
 * 通用的分段渲染 / 编辑控件 / 脏值转换在 admin-detail.js 与 admin-common.js。
 */
(function () {
  var T = window.PTi18n.t;
  var SEC = window.PTItemEffects;

  /** 需求列 → REQ_MOD 里的键（level 不参与职业修正，源实现也只调 str/spr/tal/agi/hp）。 */
  var REQ_KEY = {
    reqstrength: 'str', reqspirit: 'spr', reqtalent: 'tal', reqagility: 'agi', reqhealth: 'hp'
  };

  /** Lv 除数记法的列（源实现用 lvDiv 显示这四列；现在是**整行**按它显示）。 */
  var LV_DIV_COLUMNS = ['addspecatkpowermin', 'addspecatkpowermax',
    'addspecatkratingmin', 'addspecatkratingmax'];

  /** 掉落特效候选列（= 这 12 列的编辑入口）；按列名里的序号排，不硬编码 12。 */
  function poolColumns(ctx) {
    return ctx.columns
      .filter(function (c) { return /^addspecclass\d+$/.test(c.column); })
      .sort(function (a, b) {
        return parseInt(a.column.replace('addspecclass', ''), 10)
          - parseInt(b.column.replace('addspecclass', ''), 10);
      });
  }

  function isPoolColumn(column) { return /^addspecclass\d+$/.test(column); }

  /** 职业位序号 → 显示名（1 Fighter(FS)…11 Brawler(BS)；无名字的槽位只显示序号）。 */
  function jobLabel(n, ctx) {
    var col = ctx.byName.primaryspec;
    var opts = (col && col.options) || [];
    for (var i = 0; i < opts.length; i++) {
      if (Number(opts[i].value) === Number(n)) {
        return T(opts[i].labelKey);
      }
    }
    return T('admin.item.jobSlot', { n: n });
  }

  var view = { spec: '0', age: 0, mixId: '', mixes: [], mixesLoaded: false };

  function currentMix() {
    for (var i = 0; i < view.mixes.length; i++) {
      if (String(view.mixes[i].id) === String(view.mixId)) { return view.mixes[i]; }
    }
    return null;
  }

  function buildSelectors(ctx) {
    var specSel = document.getElementById('specSel');
    specSel.innerHTML = '';
    SEC.specOptions(ctx.row).forEach(function (o) {
      var opt = document.createElement('option');
      opt.value = o.value;
      opt.textContent = o.label;
      specSel.appendChild(opt);
    });
    specSel.value = view.spec;

    var ageSel = document.getElementById('ageSel');
    ageSel.innerHTML = '';
    SEC.ageOptions().forEach(function (o) {
      var opt = document.createElement('option');
      opt.value = String(o.value);
      opt.textContent = o.label;
      ageSel.appendChild(opt);
    });
    ageSel.value = String(view.age);

    var mixSel = document.getElementById('mixSel');
    mixSel.innerHTML = '';
    var none = document.createElement('option');
    none.value = '';
    none.textContent = T(view.mixes.length ? 'admin.item.noMix' : 'admin.item.noMixNone');
    mixSel.appendChild(none);
    view.mixes.forEach(function (m) {
      var opt = document.createElement('option');
      opt.value = String(m.id);
      opt.textContent = PTAdmin.fmt(m.description) || ('Mix #' + m.id);
      mixSel.appendChild(opt);
    });
    mixSel.value = view.mixId;

    var editing = ctx.editing;
    specSel.disabled = editing;
    ageSel.disabled = editing;
    mixSel.disabled = editing;
  }

  /**
   * 一列的显示加工（HTML 片段）：职业需求修正 / Lv 除数 / Spec 价格 +20% / **Mix、Age 染色**。
   * Mix 与 Age 改过的列名由 item-effects.js 的 derive() 回传（mixFields / ageFields，键是列名）。
   */
  function decorate(col, v, ctx) {
    var c = col.column;
    var derived = SEC.derive(ctx.row, { mix: currentMix(), age: view.age });
    var values = derived.values;
    var mixF = derived.mixFields;
    var ageF = derived.ageFields;
    var spec = parseInt(view.spec, 10) || 0;
    var specOn = spec > 0 && SEC.specApplies(ctx.row, spec);

    var value = values[c];
    var cls = [];
    var text = PTAdmin.fmt(value);

    var labelled = PTAdmin.optionLabel(col, value, T);
    if (labelled !== null) {
      text = labelled;
    }
    if (spec > 0 && REQ_KEY[c]) {
      var pct = SEC.REQ_MOD[spec];
      if (pct) {
        var adj = SEC.reqAdjust(value, pct[REQ_KEY[c]]);
        if (adj) {
          text = adj[0] === adj[1] ? String(adj[0]) : adj[0] + ' - ' + adj[1];
          if (adj[0] > v || adj[1] > v) { cls.push('mod-req-up'); }
          if (adj[0] < v || adj[1] < v) { cls.push('mod-req-down'); }
        }
      }
    }
    if (specOn) {
      if (c.indexOf('addspec') === 0) { cls.push('mod-spec'); }
      if (c === 'price') {
        text = String(Math.round((Number(value) || 0) * 1.2));
        cls.push('mod-spec');
      }
    }
    if (mixF && mixF[c]) { cls.push('mod-mix'); }
    if (ageF && ageF[c]) { cls.push('mod-age'); }
    return { text: text, cls: cls.join(' ') };
  }

  /**
   * 「掉落特效候选」勾选块：它就是 `addspecclass1..12` 这 12 列的编辑入口。
   *
   * 为什么要这个块：game-server 掷职业特效时的候选池就是这些列的非零位
   *（`ItemRollService.specJobBits()`），命中（30%）后从池里随机取一个写进该件实例的 `jobCodeMask`。
   * ⇒ **勾上 = 这件掉落时可能随机到该职业；不勾 = 不会。**
   */
  function specPoolBlock(ctx) {
    var box = document.createElement('div');
    box.className = 'item-pool';
    var head = document.createElement('div');
    head.className = 'item-pool-title';
    head.textContent = T('admin.item.poolTitle');
    box.appendChild(head);

    var primary = Number(ctx.row.primaryspec) || 0;
    var fixed = document.createElement('div');
    fixed.className = 'item-pool-fixed';
    fixed.textContent = primary > 0
      ? T('admin.item.selfJobInPool', { job: jobLabel(primary, ctx) })
      : T('admin.item.selfJobNone');
    box.appendChild(fixed);

    var wrap = document.createElement('div');
    wrap.className = 'item-pool-items';
    poolColumns(ctx).forEach(function (col) {
      var n = parseInt(col.column.replace('addspecclass', ''), 10);
      var lab = document.createElement('label');
      var cb = document.createElement('input');
      cb.type = 'checkbox';
      cb.checked = Number(ctx.row[col.column]) !== 0;
      cb.dataset.pool = col.column;
      if (!ctx.editing || !col.editable) {
        cb.disabled = true;
      } else {
        cb.addEventListener('change', function () {
          PTAdmin.markDirty(ctx.dirties, ctx.row, col.column, cb.checked ? 1 : 0);
        });
      }
      lab.appendChild(cb);
      lab.appendChild(document.createTextNode(jobLabel(n, ctx)));
      lab.title = col.column;
      wrap.appendChild(lab);
    });
    box.appendChild(wrap);
    return box;
  }

  PTDetail.boot({
    api: '/api/admin/items',
    listHref: './admin/items',
    editingHint: 'admin.item.editingHint',
    title: function (row) {
      return PTAdmin.fmt(row.name) + '  #' + row.id;
    },
    stats: function (row) {
      return [
        T('admin.item.idcode') + ' ' + PTAdmin.fmt(row.idcode),
        PTAdmin.fmt(row.category),
        T('itemtip.reqLv') + ' ' + PTAdmin.fmt(row.reqlevel),
        T('itemtip.price') + ' ' + PTAdmin.fmt(row.price)
      ].join(' · ');
    },
    render: function (row, ctx, body) {
      // ⚠ 抓 Mix 与绑选择器**只在首次**做：render 会被重渲染反复调用，
      //   写在里面会让监听器层层叠加（一次改动触发 N 次重渲染）。
      if (!view.mixesLoaded) {
        view.mixesLoaded = true;
        PT.request('/api/admin/items/' + row.id + '/mixes', { credentials: 'include' }).then(function (r) {
          view.mixes = (r.ok && r.data) ? r.data : [];
          if (!r.ok) { ctx.msg(T('admin.item.mixLoadFailed'), true); }
          ctx.rerender();
        });
        document.getElementById('specSel').addEventListener('change', function () {
          view.spec = document.getElementById('specSel').value;
          ctx.rerender();
        });
        document.getElementById('ageSel').addEventListener('change', function () {
          view.age = parseInt(document.getElementById('ageSel').value, 10) || 0;
          ctx.rerender();
        });
        document.getElementById('mixSel').addEventListener('change', function () {
          view.mixId = document.getElementById('mixSel').value;
          ctx.rerender();
        });
      }

      buildSelectors(ctx);
      PTDetail.renderSections(ctx, body, decorate, {
        skipColumn: function (col) { return isPoolColumn(col.column); },
        beforeSection: function (sec, container, c) {
          // 这 12 列不再单列成 12 行（只读态是禁用的勾选框、编辑态可点），同一个信息不显示两遍
          if (sec === 'Spec') { container.appendChild(specPoolBlock(c)); }
        }
      });
    }
  });
})();
