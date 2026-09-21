/*
 * 物品管理页（admin-items.html）。
 *
 * 口径（设计文档 §二/§5/§6）：
 *   · 对外一律用**数据库列名**；行含**全部列**（列集合来自 /columns，不在前端硬编码）。
 *   · 四组筛选条件是**固定**的（下面 FILTERS 与后端 ItemQueryParams 的白名单逐字对应）——
 *     多加一个参数后端会 400，所以这里不能凭感觉加。
 *   · 详情默认**只读**，点「编辑」才切成可改；编辑态显示的是**数据库原值**
 *     （Spec/Mix/Age 是预览，不能把预览值写回库）。留空 = 不改（不支持清空）。
 *   · Mix / Spec / Age 三项能力来自 item-effects.js（原样搬运自已删除的模拟器页面）。
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
   * `labelKey` / `placeholderKey` 是文案 key（**不是文案**）。
   */
  var FILTERS = [
    {
      groupKey: 'admin.item.groupIdentity', fields: [
        { key: 'name_like', labelKey: 'admin.item.name', type: 'text', placeholderKey: 'admin.item.namePlaceholder' },
        { key: 'idcode', labelKey: 'admin.item.idcode', type: 'int' },
        { key: 'category', labelKey: 'admin.item.category', type: 'category' }
      ]
    },
    {
      groupKey: 'admin.item.groupThreshold', fields: [
        { key: 'reqlevel', labelKey: 'itemtip.reqLv', range: true },
        { key: 'price', labelKey: 'admin.item.price', range: true },
        { key: 'weight', labelKey: 'admin.item.weight', range: true }
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

  /** 需求列 → REQ_MOD 里的键（level 不参与职业修正，源实现也只调 str/spr/tal/agi/hp）。 */
  var REQ_KEY = {
    reqstrength: 'str', reqspirit: 'spr', reqtalent: 'tal', reqagility: 'agi', reqhealth: 'hp'
  };

  /** Lv 除数记法的列（源实现用 lvDiv 显示这四列；现在是**整行**按它显示）。 */
  var LV_DIV_COLUMNS = ['addspecatkpowermin', 'addspecatkpowermax',
    'addspecatkratingmin', 'addspecatkratingmax'];

  var SEC = window.PTItemEffects;

  var state = {
    columns: [], byName: {},
    visible: DEFAULT_COLUMNS.slice(),
    rows: [], page: 1, size: 50, total: 0, totalPages: 1,
    row: null, editing: false, dirties: {},
    mixes: [], spec: '0', age: 0, mixId: ''
  };

  function el(id) { return document.getElementById(id); }

  function hideMsg(node) { node.classList.add('hidden'); }
  function showMsg(node, text, isError) {
    node.textContent = text;
    node.className = 'msg ' + (isError ? 'error' : 'success');
  }
  function showErr(text) { showMsg(el('errBox'), text, true); }
  function hideErr() { hideMsg(el('errBox')); }
  function modalMsg(text, isError) {
    if (text) { showMsg(el('modalMsg'), text, isError); } else { hideMsg(el('modalMsg')); }
  }

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

  function fmt(v) { return v === null || v === undefined ? '' : String(v); }

  // ------------------------------------------------------------------
  // 筛选面板（按 FILTERS 生成，避免手写 24 个输入框写错）
  // ------------------------------------------------------------------

  function buildFilterBar() {
    var bar = el('filterBar');
    bar.innerHTML = '';
    FILTERS.forEach(function (g) {
      var box = document.createElement('div');
      box.className = 'item-filter-group';
      var title = document.createElement('span');
      title.className = 'item-filter-title';
      title.textContent = T(g.groupKey);
      box.appendChild(title);
      g.fields.forEach(function (f) {
        if (f.range) {
          box.appendChild(field(f.key + '_min', T('admin.item.atLeast', { label: T(f.labelKey) }), f));
          box.appendChild(field(f.key + '_max', T('admin.item.atMost', { label: T(f.labelKey) }), f));
        } else {
          box.appendChild(field(f.key, T(f.labelKey), f));
        }
      });
      bar.appendChild(box);
    });
    // 分类候选项的容器（/facets 到了再填）
    var dl = document.createElement('datalist');
    dl.id = 'categoryList';
    bar.appendChild(dl);
  }

  /**
   * 一个筛选项的控件。
   *
   * 控件类型按该列的**语义**定（`state.byName[key].kind`）：枚举列给**下拉**（含"（不限）"），
   * 其余按 `spec.type` 给文本框/数字框。这样 `weaponclass=2` 这种"手填数字"就不存在了
   * —— 与详情页/列表用的是服务端同一份 `options`。
   */
  function field(id, label, spec) {
    var wrap = document.createElement('label');
    wrap.textContent = label;
    var col = state.byName[spec.key];
    var input;
    if (col && col.kind === 'ENUM') {
      input = document.createElement('select');
      input.id = 'f_' + id;
      input.className = 'admin-input';
      var any = document.createElement('option');
      any.value = '';
      any.textContent = T('admin.item.any');
      input.appendChild(any);
      (col.options || []).forEach(function (o) {
        var opt = document.createElement('option');
        opt.value = String(o.value);
        opt.textContent = T(o.labelKey);
        input.appendChild(opt);
      });
    } else {
      input = document.createElement('input');
      input.id = 'f_' + id;
      input.className = 'admin-input';
      if (spec.type === 'category') {
        input.setAttribute('list', 'categoryList');
        input.placeholder = T('admin.item.categoryPlaceholder');
      } else if (spec.type !== 'text') {
        input.type = 'number';
      } else {
        input.placeholder = spec.placeholderKey ? T(spec.placeholderKey) : '';
      }
    }
    wrap.appendChild(input);
    return wrap;
  }

  function collectFilters() {
    var parts = [];
    FILTERS.forEach(function (g) {
      g.fields.forEach(function (f) {
        var keys = f.range ? [f.key + '_min', f.key + '_max'] : [f.key];
        keys.forEach(function (k) {
          var v = el('f_' + k).value;
          if (v !== null && v !== undefined && String(v).trim() !== '') {
            parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(String(v).trim()));
          }
        });
      });
    });
    return parts;
  }

  function resetFilters() {
    FILTERS.forEach(function (g) {
      g.fields.forEach(function (f) {
        var keys = f.range ? [f.key + '_min', f.key + '_max'] : [f.key];
        keys.forEach(function (k) { el('f_' + k).value = ''; });
      });
    });
    state.page = 1;
  }

  // ------------------------------------------------------------------
  // 列表
  // ------------------------------------------------------------------

  function load() {
    hideErr();
    var q = collectFilters().concat(['page=' + state.page, 'size=' + state.size]).join('&');
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
        // 有语义的列在列表里也显示名字（不是裸数字）——和详情页同一套 optionLabel
        var label = col ? optionLabel(col, row[c]) : null;
        td.textContent = label !== null ? label : fmt(row[c]);
        tr.appendChild(td);
      });
      tr.addEventListener('click', function () { openDetail(row.id); });
      tb.appendChild(tr);
    });
  }

  function renderPager() {
    el('pageInfo').textContent = T('admin.item.pager', {
      page: state.page, pages: state.totalPages, total: state.total, size: state.size });
    el('prevBtn').disabled = state.page <= 1;
    el('nextBtn').disabled = state.page >= state.totalPages;
  }

  function buildColumnPicker() {
    var box = el('colPicker');
    box.innerHTML = '';
    var sections = {};
    state.columns.forEach(function (c) { (sections[c.section] = sections[c.section] || []).push(c); });
    Object.keys(sections).forEach(function (s) {
      var head = document.createElement('div');
      head.className = 'item-colpicker-section';
      head.textContent = sections[s][0].sectionLabel;
      box.appendChild(head);
      sections[s].forEach(function (c) {
        var lab = document.createElement('label');
        var cb = document.createElement('input');
        cb.type = 'checkbox';
        cb.checked = state.visible.indexOf(c.column) !== -1;
        cb.addEventListener('change', function () {
          var i = state.visible.indexOf(c.column);
          if (cb.checked && i === -1) { state.visible.push(c.column); }
          if (!cb.checked && i !== -1) { state.visible.splice(i, 1); }
          renderHead();
          renderTable();
        });
        lab.appendChild(cb);
        lab.appendChild(document.createTextNode(c.column));
        box.appendChild(lab);
      });
    });
  }

  // ------------------------------------------------------------------
  // 详情浮层
  // ------------------------------------------------------------------

  function openDetail(id) {
    state.editing = false;
    state.dirties = {};
    modalMsg('');
    call(API + '/' + id).then(function (row) {
      if (!row) return;
      state.row = row;
      state.spec = '0';
      state.age = 0;
      state.mixId = '';
      state.mixes = [];
      el('modalTitle').textContent = fmt(row.name) + '  #' + row.id;
      el('modal').classList.remove('hidden');
      buildSelectors();
      // Mix 配方拿不到不该挡住详情（浏览器端只提示，不静默：会写进 modalMsg）
      call(API + '/' + id + '/mixes').then(function (m) {
        state.mixes = m || [];
      }).catch(function () {
        state.mixes = [];
        modalMsg(T('admin.item.mixLoadFailed'), true);
      }).then(function () {
        buildSelectors();
        renderModal();
      });
    }).catch(function (e) { showErr(e.message); });
  }

  function buildSelectors() {
    var specSel = el('specSel');
    specSel.innerHTML = '';
    SEC.specOptions(state.row).forEach(function (o) {
      var opt = document.createElement('option');
      opt.value = o.value;
      opt.textContent = o.label;
      specSel.appendChild(opt);
    });
    specSel.value = state.spec;

    var ageSel = el('ageSel');
    ageSel.innerHTML = '';
    SEC.ageOptions().forEach(function (o) {
      var opt = document.createElement('option');
      opt.value = String(o.value);
      opt.textContent = o.label;
      ageSel.appendChild(opt);
    });
    ageSel.value = String(state.age);

    var mixSel = el('mixSel');
    mixSel.innerHTML = '';
    var none = document.createElement('option');
    none.value = '';
    none.textContent = T(state.mixes.length ? 'admin.item.noMix' : 'admin.item.noMixNone');
    mixSel.appendChild(none);
    state.mixes.forEach(function (m) {
      var opt = document.createElement('option');
      opt.value = String(m.id);
      opt.textContent = fmt(m.description) || ('Mix #' + m.id);
      mixSel.appendChild(opt);
    });
    mixSel.value = state.mixId;
  }

  /** 取当前选中的 Mix 配方对象。 */
  function currentMix() {
    if (!state.mixId) return null;
    for (var i = 0; i < state.mixes.length; i++) {
      if (String(state.mixes[i].id) === String(state.mixId)) return state.mixes[i];
    }
    return null;
  }

  function renderModal() {
    var body = el('modalBody');
    body.innerHTML = '';
    var editing = state.editing;

    var derived = SEC.derive(state.row, { mix: currentMix(), age: state.age });
    var values = derived.values;
    var mixF = derived.mixFields;
    var ageF = derived.ageFields;
    var spec = parseInt(state.spec, 10) || 0;
    var specOn = spec > 0 && SEC.specApplies(state.row, spec);

    // 段顺序与列顺序都来自 /columns（不在这里硬编码）
    var order = [];
    var bySec = {};
    state.columns.forEach(function (c) {
      if (!bySec[c.section]) { bySec[c.section] = []; order.push(c.section); }
      bySec[c.section].push(c);
    });

    order.forEach(function (sec) {
      var cols = bySec[sec].filter(function (col) {
        // 这 12 个候选位由「掉落特效候选」勾选组件展示与编辑 —— **两种模式下都不再列成 12 行是/否**
        //（只读态是禁用的勾选框、编辑态可点）。同一件事显示两遍既啰嗦、又容易看出不一致。
        return !isPoolColumn(col.column);
      });
      var rows = buildRows(cols);
      var title = document.createElement('div');
      title.className = 'item-section-title';
      // 列数与行数都写出来：合并区间后行数会少于列数，读者才不会以为漏了
      title.textContent = T(bySec[sec][0].sectionLabelKey);
      title.title = T('admin.item.sectionCountHint', { cols: bySec[sec].length, rows: rows.length });
      body.appendChild(title);
      // 「职业特效」段先放勾选组件：它就是掉落候选池（addspecclass1..12）本身
      if (sec === 'Spec') {
        body.appendChild(specPoolBlock(editing));
      }
      rows.forEach(function (r) {
        body.appendChild(editing
          ? editGroupRow(r)
          : viewGroupRow(r, values, mixF, ageF, spec, specOn));
      });
    });

    el('mixSel').disabled = editing;
    el('ageSel').disabled = editing;
    el('specSel').disabled = editing;
    el('editBtn').classList.toggle('hidden', editing);
    el('saveBtn').classList.toggle('hidden', !editing);
    el('cancelBtn').classList.toggle('hidden', !editing);
    if (editing) {
      modalMsg(T('admin.item.editingHint'), false);
      el('modalMsg').className = 'msg';
    }
  }

  /**
   * 按**行**分组：`rowLabel` 相同、`rowPart` 为 1/2 的列合成一行（渲染成 `值1 - 值2` 的区间）。
   *
   * <p>
   * 配对不一定是"同一列的 min/max"：`atkpow1min` 与 `atkpow2min` 才是同一行（攻击力(小)），
   * 所以这里只按 `rowLabel` 归并、按 `rowPart` 排序，不假设配法。无 `rowLabel` 的列各自一行，
   * 行名回退成数据库列名。
   */
  function buildRows(cols) {
    var rows = [];
    var indexOfLabel = {};
    cols.forEach(function (col) {
      if (!col.rowLabelKey || !col.rowPart) {
        rows.push({ label: col.rowLabelKey ? T(col.rowLabelKey) : col.column, cols: [col] });
        return;
      }
      if (indexOfLabel[col.rowLabelKey] === undefined) {
        indexOfLabel[col.rowLabelKey] = rows.length;
        rows.push({ label: T(col.rowLabelKey), cols: [col] });
        return;
      }
      rows[indexOfLabel[col.rowLabelKey]].cols.push(col);
    });
    rows.forEach(function (r) {
      r.cols.sort(function (a, b) { return (a.rowPart || 0) - (b.rowPart || 0); });
    });
    return rows;
  }

  /** 行名 + 数据口径：把该行涉及的数据库列名放进 title，便于追溯（显示的是名字，数据仍是列）。 */
  function rowNameEl(r) {
    var name = document.createElement('div');
    name.className = 'item-col-name';
    name.textContent = r.label;
    name.title = r.cols.map(function (c) { return c.column; }).join(' / ');
    return name;
  }

  /** 只读一行：`值1 - 值2`（单值行就只有 `值1`）。 */
  function viewGroupRow(r, values, mixF, ageF, spec, specOn) {
    var row = document.createElement('div');
    row.className = 'item-row';
    var val = document.createElement('div');
    val.className = 'item-col-value';

    if (LV_DIV_COLUMNS.indexOf(r.cols[0].column) !== -1) {
      // Lv 除数那两对：整行显示成 `Lv/x`，两端不同则是 `Lv/a - b`
      //（源实现的 lvDiv 本来就是"范围"记法，此前我只按单列调用、白丢了它的一半设计）
      var a = values[r.cols[0].column];
      var b = r.cols.length > 1 ? values[r.cols[1].column] : a;
      var lv = SEC.lvDiv(a, b);
      val.textContent = lv !== null ? lv : fmt(a);
    } else {
      var parts = [];
      r.cols.forEach(function (col) {
        parts.push(showedValue(col, values, mixF, ageF, spec, specOn));
      });
      val.innerHTML = parts.join(' - ');
      if (r.cols[0].unit) {
        val.innerHTML += r.cols[0].unit;   // 单位接一次（区间行不写成 6% - 10%）
      }
    }
    row.appendChild(rowNameEl(r));
    row.appendChild(val);
    return row;
  }

  /** 编辑一行：行内每个列各一个控件（区间行就是两个数字框，用 `-` 隔开表示区间）。 */
  function editGroupRow(r) {
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
      val.appendChild(editControl(col));
    });
    row.appendChild(rowNameEl(r));
    row.appendChild(val);
    return row;
  }

  /** 一个列的编辑控件：不可改 → 文本；`BOOL` → 勾选；`ENUM` → 下拉；其余 → 文本框。 */
  function editControl(col) {
    var raw = state.row[col.column];
    if (!col.editable) {
      var ro = document.createElement('span');
      ro.textContent = fmt(raw) + T('admin.common.notEditable');
      return ro;
    }
    if (col.kind === 'BOOL') {
      var cb = document.createElement('input');
      cb.type = 'checkbox';
      cb.checked = Number(raw) !== 0;
      cb.dataset.column = col.column;
      cb.title = col.column;
      cb.addEventListener('change', function () { markDirty(col.column, cb.checked ? 1 : 0); });
      return cb;
    }
    if (col.kind === 'ENUM') {
      var sel = document.createElement('select');
      sel.dataset.column = col.column;
      sel.title = col.column;
      var opts = (col.options || []).slice();
      var known = false;
      opts.forEach(function (o) { if (Number(o.value) === Number(raw)) { known = true; } });
      if (!known && raw !== null && raw !== undefined) {
        opts.unshift({ value: raw, label: T('admin.common.optionNotListed') });
      }
      opts.forEach(function (o) {
        var opt = document.createElement('option');
        opt.value = String(o.value);
        opt.textContent = o.label;
        sel.appendChild(opt);
      });
      sel.value = String(raw);
      sel.addEventListener('change', function () { markDirty(col.column, Number(sel.value)); });
      return sel;
    }
    var input = document.createElement('input');
    input.className = 'admin-input';
    input.value = fmt(raw);
    input.dataset.column = col.column;
    input.title = col.column;
    input.addEventListener('input', function () {
      markDirty(col.column, input.value);
      input.classList.remove('item-input-bad');
    });
    return input;
  }

  /**
   * 一列的显示值（HTML 片段）：职业需求修正 / Lv 除数 / Spec 价格 / **Mix、Age 染色**。
   *
   * Mix 与 Age 改过的列名由 item-effects.js 的 derive() 回传（mixFields / ageFields，键是列名），
   * 在这里按来源套颜色 —— 数字变了却不标色，等于让人看不出"这个值是算出来的"。
   */
  function showedValue(col, values, mixF, ageF, spec, specOn) {
    var c = col.column;
    var v = values[c];
    var cls = [];
    var text = fmt(v);

    // 有语义的列：显示名字而不是裸数字（原值随后由"（库中 X）"带出来）
    var labelled = optionLabel(col, v);
    if (labelled !== null) {
      text = labelled;
    }
    // 需求列：按所选职业修正（源实现只调 str/spr/tal/agi/hp，level 不调）
    if (spec > 0 && REQ_KEY[c]) {
      var pct = SEC.REQ_MOD[spec];
      if (pct) {
        var adj = SEC.reqAdjust(v, pct[REQ_KEY[c]]);
        if (adj) {
          text = adj[0] === adj[1] ? String(adj[0]) : adj[0] + ' - ' + adj[1];
          if (adj[0] > v || adj[1] > v) cls.push('mod-req-up');
          if (adj[0] < v || adj[1] < v) cls.push('mod-req-down');
        }
      }
    }
    // 职业特效命中：spec 列标绿；价格显示 +20%（源实现 renderMisc 的算法）
    if (specOn) {
      if (c.indexOf('addspec') === 0) cls.push('mod-spec');
      if (c === 'price') {
        text = String(Math.round((Number(v) || 0) * 1.2));
        cls.push('mod-spec');
      }
    }
    // Mix / Age 改过的列：按来源染色
    if (mixF && mixF[c]) cls.push('mod-mix');
    if (ageF && ageF[c]) cls.push('mod-age');
    return SEC.valWrap(text, cls.join(' '));
  }

  // ------------------------------------------------------------------
  // 取值语义（ENUM / BOOL）：显示翻名字、编辑给下拉或勾选
  // ------------------------------------------------------------------

  /**
   * 有语义的列 → 显示名；没有（NUMBER/TEXT，或取值不在候选里）返回 null。
   *
   * 候选来自服务端 `/columns` 的 `options`（职业名、weaponclass、classitem 位掩码…），
   * 前端**不自己写一份**取值表 —— 那是"同一个判定两处实现"的老路。
   */
  function optionLabel(col, value) {
    if (col.kind === 'BOOL') {
      return Number(value) !== 0 ? T('admin.common.yes') : T('admin.common.no');
    }
    if (col.kind !== 'ENUM') {
      return null;
    }
    var opts = col.options || [];
    for (var i = 0; i < opts.length; i++) {
      if (Number(opts[i].value) === Number(value)) {
        return T(opts[i].labelKey);
      }
    }
    // 不在候选里：位掩码列按**位表**拼出来（服务端不拼串；拼法与文案都在客户端）
    if (col.bits && col.bits.length) {
      var v = Number(value);
      if (!v) {
        return null;
      }
      var parts = [];
      col.bits.forEach(function (b) {
        if ((v & b.value) !== 0) {
          parts.push(T(b.labelKey));
          v &= ~b.value;
        }
      });
      if (v !== 0) {
        parts.push(T('admin.item.unknownBits', { hex: v.toString(16).toUpperCase() }));
      }
      return parts.join(' + ');
    }
    return null;   // 取值不在候选里 → 保持原数字，并由"（库中 X）"提示
  }

  /** 记脏：与库中原值相同则清除（否则会把"改回原值"也当成改动发出去）。三处编辑入口共用。 */
  function markDirty(column, value) {
    var raw = state.row[column];
    var same = (typeof value === 'number')
      ? Number(raw) === value
      : String(raw) === String(value);
    if (same) {
      delete state.dirties[column];
    } else {
      state.dirties[column] = value;
    }
  }

  // ------------------------------------------------------------------
  // 掉落特效候选（职业勾选）
  // ------------------------------------------------------------------

  /** 候选列 = `addspecclass1..12`。按列名里的序号排，不硬编码 12（列集合由 /columns 给）。 */
  function poolColumns() {
    return state.columns
      .filter(function (c) { return /^addspecclass\d+$/.test(c.column); })
      .sort(function (a, b) {
        return parseInt(a.column.replace('addspecclass', ''), 10)
          - parseInt(b.column.replace('addspecclass', ''), 10);
      });
  }

  function isPoolColumn(column) { return /^addspecclass\d+$/.test(column); }

  /** 职业位序号 → 显示名（1 Fighter(FS)…11 Brawler(BS)；无名字的槽位只显示序号）。 */
  function jobLabel(n) {
    var col = state.byName.primaryspec;
    var opts = (col && col.options) || [];
    for (var i = 0; i < opts.length; i++) {
      if (Number(opts[i].value) === Number(n)) {
        return T(opts[i].labelKey);
      }
    }
    return T('admin.item.jobSlot', { n: n });
  }

  /**
   * 掉落特效候选的勾选组件。
   *
   * 为什么是这个含义：game-server 掷职业特效时，候选池就是这些列的非零位
   * （`ItemRollService.specJobBits()`：`addspecclassN != 0` → 第 N 个职业位），
   * 命中（30%）后从池里随机取一个写进该件实例的 `jobCodeMask`。
   * ⇒ **勾上 = 这件掉落时可能随机到该职业；不勾 = 不会。**
   *
   * 编辑态下这个组件就是这 12 列的编辑入口（列本身不再单列输入框）。
   * 写入值用 1/0：库里这些列实测只有 0 和 1（是标志位，不是数量）。
   */
  function specPoolBlock(editing) {
    var box = document.createElement('div');
    box.className = 'item-pool';

    var head = document.createElement('div');
    head.className = 'item-pool-title';
    head.textContent = T('admin.item.poolTitle');
    box.appendChild(head);

    // 自身职业（primaryspec）也在掉落池里 —— 不写出来的话，会以为它只是个没用的展示字段
    var primary = Number(state.row.primaryspec) || 0;
    var fixed = document.createElement('div');
    fixed.className = 'item-pool-fixed';
    fixed.textContent = primary > 0
      ? T('admin.item.selfJobInPool', { job: jobLabel(primary) })
      : T('admin.item.selfJobNone');
    box.appendChild(fixed);

    var wrap = document.createElement('div');
    wrap.className = 'item-pool-items';

    poolColumns().forEach(function (col) {
      var n = parseInt(col.column.replace('addspecclass', ''), 10);
      var lab = document.createElement('label');
      var cb = document.createElement('input');
      cb.type = 'checkbox';
      cb.checked = Number(state.row[col.column]) !== 0;
      cb.dataset.pool = col.column;
      if (!editing || !col.editable) {
        cb.disabled = true;
      } else {
        cb.addEventListener('change', function () {
          markDirty(col.column, cb.checked ? 1 : 0);
        });
      }
      lab.appendChild(cb);
      lab.appendChild(document.createTextNode(jobLabel(n)));
      lab.title = col.column;
      wrap.appendChild(lab);
    });

    box.appendChild(wrap);
    return box;
  }

  /**
   * 按列类型把脏值转成 JSON 值；数字列不合法就标红并中止（不静默发脏值）。
   *
   * ⚠ 脏值有两种来源，类型不同：**输入框给字符串**（`input.value`）、**勾选组件给数字**（1/0）。
   * 这里先统一成字符串再判，否则数字走 `.trim()` 会抛 `s.trim is not a function`
   * —— 那个错会表现为"点保存没反应，只在提示里冒一句 TypeError"。
   */
  function coerceChanges() {
    var out = {};
    var bad = null;
    Object.keys(state.dirties).forEach(function (c) {
      var col = state.byName[c];
      var raw = String(state.dirties[c]).trim();
      if (col.javaType === 'Integer' || col.javaType === 'Double') {
        if (raw === '' || isNaN(Number(raw))) { bad = c; return; }
        out[c] = Number(raw);
      } else {
        out[c] = raw;
      }
    });
    if (bad) {
      var input = document.querySelector('#modalBody input[data-column="' + bad + '"]');
      if (input) { input.classList.add('item-input-bad'); }
      throw new Error(T('admin.item.needNumber', { column: bad }));
    }
    return out;
  }

  function save() {
    var changes;
    try { changes = coerceChanges(); } catch (e) { modalMsg(e.message, true); return; }
    if (!Object.keys(changes).length) { modalMsg(T('admin.common.noChanges'), false); return; }
    call(API + '/' + state.row.id, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(changes)
    }).then(function (row) {
      if (!row) return;
      state.row = row;
      state.editing = false;
      state.dirties = {};
      modalMsg(T('admin.common.saved'), false);
      renderModal();
      // 列表里那一行也要跟着变（否则界面留着旧值）
      for (var i = 0; i < state.rows.length; i++) {
        if (state.rows[i].id === row.id) { state.rows[i] = row; break; }
      }
      renderTable();
    }).catch(function (e) { modalMsg(e.message, true); });
  }

  // ------------------------------------------------------------------
  // 事件绑定
  // ------------------------------------------------------------------

  function bind() {
    el('searchBtn').addEventListener('click', function () { state.page = 1; load(); });
    el('resetBtn').addEventListener('click', function () { resetFilters(); load(); });
    el('refreshBtn').addEventListener('click', function () { load(); });
    el('prevBtn').addEventListener('click', function () { if (state.page > 1) { state.page--; load(); } });
    el('nextBtn').addEventListener('click', function () { if (state.page < state.totalPages) { state.page++; load(); } });
    el('colPickerBtn').addEventListener('click', function () { el('colPicker').classList.toggle('hidden'); });

    el('modalClose').addEventListener('click', function () { el('modal').classList.add('hidden'); });
    el('modal').addEventListener('click', function (e) {
      if (e.target === el('modal')) { el('modal').classList.add('hidden'); }
    });
    el('specSel').addEventListener('change', function () { state.spec = el('specSel').value; renderModal(); });
    el('ageSel').addEventListener('change', function () { state.age = parseInt(el('ageSel').value, 10) || 0; renderModal(); });
    el('mixSel').addEventListener('change', function () { state.mixId = el('mixSel').value; renderModal(); });
    el('editBtn').addEventListener('click', function () { state.editing = true; state.dirties = {}; renderModal(); });
    el('cancelBtn').addEventListener('click', function () { state.editing = false; state.dirties = {}; renderModal(); });
    el('saveBtn').addEventListener('click', save);

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
        el('adminUserLabel').textContent = r.data.accountName + (r.data.webAdmin ? T('admin.common.adminSuffix') : '');
      }
    });

    return Promise.all([
      call(API + '/columns').then(function (cols) {
        if (!cols) return;
        state.columns = cols;
        cols.forEach(function (c) { state.byName[c.column] = c; });
        buildColumnPicker();
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
        // 候选取值拿不到不影响筛选（仍是普通输入框），但要说出来
        el('hint').textContent = T('admin.item.categoryHintFailed');
      })
    ]).then(load);
    });
  }

  start();
})();
