/*
 * 管理端页面的**共用零件**（物品页 admin-items.js 与怪物页 admin-monsters.js 都用这一份）。
 *
 * 为什么要抽出来：这两页的"取值语义显示 / 区间行配对 / 脏值类型转换 / 筛选面板 / 列选择器"
 * 是**同一套判定**。抄第二份的下场是两边慢慢漂 —— 例如同一列在一个页面上显示成职业名、
 * 在另一个页面上显示成数字，而没人会发现（AGENTS #15）。
 *
 * 页面自己只负责：状态、请求、模态框里各段的**内容**（哪些段、哪些额外块）。
 *
 * 文案：服务端只发 key，这里一律用 `T(key)` 查 static/i18n/{zh,en}.json。
 */
(function () {
  /** 走 `value` 的显示形态；null/undefined 一律空串（不显示 "null"）。 */
  function fmt(v) {
    return v === null || v === undefined ? '' : String(v);
  }

  function esc(s) {
    return String(s).replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }

  // ------------------------------------------------------------------
  // 取值语义：ENUM / BOOL / 位掩码 → 显示名
  // ------------------------------------------------------------------

  /**
   * 有语义的列 → 显示名；没有（NUMBER/TEXT，或取值不在候选里）返回 null。
   *
   * 候选来自服务端 `/columns`（职业名、weaponclass、怪物的本性/属性…），
   * 前端**不自己写一份**取值表 —— 那是"同一个判定两处实现"的老路。
   */
  function optionLabel(col, value, T) {
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
    // 文本枚举（怪物表的 monstertype / propertymon）：值是字符串，逐字比对
    var textOpts = col.textOptions || [];
    for (var j = 0; j < textOpts.length; j++) {
      if (String(textOpts[j].value) === String(value)) {
        return T(textOpts[j].labelKey);
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
    return null;   // 取值不在候选里 → 保持原值显示，由列名/原值提示
  }

  // ------------------------------------------------------------------
  // 列头文案：rowLabelKey → 常用列名直查 → 大小写扫描 → 原列名
  // ------------------------------------------------------------------

  /** 常用列名的手工映射（命名空间扫描覆盖不到的少数明确列）。 */
  var COLUMN_LABEL_OVERRIDES = {
    id: 'ID',
    reqlevel: 'itemtip.reqLv',
    levelreq: 'itemtip.reqLv',
    hp: 'monster.hp',
    exp: 'monster.exp'
  };

  /** 大小写不敏感索引：列名 → 文案 key（每语言一份，惰性建一次）。 */
  var lowerIndex = null;
  var lowerIndexLocale = null;
  function labelIndex() {
    var loc = window.PTi18n.getLocale();
    if (lowerIndex && lowerIndexLocale === loc) {
      return lowerIndex;
    }
    lowerIndex = {};
    lowerIndexLocale = loc;
    // 只扫这些"可能含列名"的命名空间（段名/操作类 key 不在此列，避免误伤）
    ['itemtip', 'monster', 'map', 'npc', 'admin.item', 'admin.monster', 'admin.npc', 'admin.map']
      .forEach(function (ns) {
        window.PTi18n.keys(ns).forEach(function (k) {
          var low = String(k).toLowerCase();
          if (lowerIndex[low] === undefined) {
            lowerIndex[low] = ns + '.' + k;
          }
        });
      });
    return lowerIndex;
  }

  /**
   * 表头显示的列名：
   *   ① 有 `rowLabelKey`（语义行名，最准确）用它；
   *   ② 否则查文案表（手工映射 → 精确 key → 大小写扫描）；
   *   ③ 兜底显示数据库列名（title 始终是原始列名，方便对接入库）。
   * 不依赖 `T()` 的兜底，因为查不到时 `T()` 会 console.warn 刷屏。
   */
  function columnLabel(col, T) {
    if (!col) {
      return '';
    }
    var Tfn = T || window.PTi18n.t;
    if (col.rowLabelKey && window.PTi18n.has(col.rowLabelKey)) {
      return Tfn(col.rowLabelKey);
    }
    var c = col.column;
    if (COLUMN_LABEL_OVERRIDES[c]) {
      return Tfn(COLUMN_LABEL_OVERRIDES[c]);
    }
    var exact = ['itemtip', 'monster', 'map', 'npc',
      'admin.item', 'admin.monster', 'admin.npc', 'admin.map'];
    for (var i = 0; i < exact.length; i++) {
      if (window.PTi18n.has(exact[i] + '.' + c)) {
        return Tfn(exact[i] + '.' + c);
      }
    }
    var hit = labelIndex()[String(c).toLowerCase()];
    if (hit) {
      return Tfn(hit);
    }
    return c;
  }

  // ------------------------------------------------------------------
  // 区间行：按 rowLabelKey 把列配成一行
  // ------------------------------------------------------------------

  /**
   * 按**行**分组：`rowLabelKey` 相同、`rowPart` 为 1/2 的列合成一行（渲染成 `值1 - 值2`）。
   *
   * 配对不一定是"同一列的 min/max"：物品的 `atkpow1min` 与 `atkpow2min` 才是同一行（攻击力(小)），
   * 所以这里只按 `rowLabelKey` 归并、按 `rowPart` 排序，不假设配法。无 `rowLabelKey` 的列各自一行，
   * 行名回退成数据库列名。
   */
  function buildRows(cols, T) {
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

  // ------------------------------------------------------------------
  // 编辑：控件 / 脏值 / 类型转换
  // ------------------------------------------------------------------

  /**
   * 一个列的编辑控件：不可改 → 文本；`BOOL` → 勾选；`ENUM` → 下拉；其余 → 文本框。
   *
   * @param onDirty 记脏回调 `(column, value)`
   */
  function editControl(col, raw, onDirty, T) {
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
      cb.addEventListener('change', function () { onDirty(col.column, cb.checked ? 1 : 0); });
      return cb;
    }
    if (col.kind === 'ENUM') {
      var sel = document.createElement('select');
      sel.dataset.column = col.column;
      sel.title = col.column;
      var candidates = [];
      (col.options || []).forEach(function (o) {
        candidates.push({ value: o.value, label: T(o.labelKey) });
      });
      (col.textOptions || []).forEach(function (o) {
        candidates.push({ value: o.value, label: T(o.labelKey) });
      });
      var known = false;
      candidates.forEach(function (o) { if (String(o.value) === String(raw)) { known = true; } });
      if (!known && raw !== null && raw !== undefined) {
        // 取值不在候选里（如怪物表里那条小写 good）——照样可编辑，但标明它不在候选内
        candidates.unshift({ value: raw, label: T('admin.common.optionNotListed') });
      }
      candidates.forEach(function (o) {
        var opt = document.createElement('option');
        opt.value = String(o.value);
        opt.textContent = o.label;
        sel.appendChild(opt);
      });
      sel.value = String(raw);
      var numeric = (col.javaType === 'Integer' || col.javaType === 'Double');
      sel.addEventListener('change', function () {
        onDirty(col.column, numeric ? Number(sel.value) : sel.value);
      });
      return sel;
    }
    var input = document.createElement('input');
    input.className = 'admin-input';
    input.value = fmt(raw);
    input.dataset.column = col.column;
    input.title = col.column;
    input.addEventListener('input', function () {
      onDirty(col.column, input.value);
      input.classList.remove('item-input-bad');
    });
    return input;
  }

  /** 记脏：与库中原值相同则清除（否则会把"改回原值"也当成改动发出去）。 */
  function markDirty(dirties, row, column, value) {
    var raw = row[column];
    var same = (typeof value === 'number')
      ? Number(raw) === value
      : String(raw) === String(value);
    if (same) {
      delete dirties[column];
    } else {
      dirties[column] = value;
    }
  }

  /**
   * 按列类型把脏值转成 JSON 值；数字列不合法就标红并中止（不静默发脏值）。
   *
   * ⚠ 脏值有两种来源，类型不同：**输入框给字符串**（`input.value`）、**勾选/下拉给数字**。
   * 这里先统一成字符串再判，否则数字走 `.trim()` 会抛 `s.trim is not a function`
   * —— 那个错会表现为"点保存没反应，只在提示里冒一句 TypeError"。
   */
  function coerceChanges(dirties, byName, T) {
    var out = {};
    var bad = null;
    Object.keys(dirties).forEach(function (c) {
      var col = byName[c];
      var raw = String(dirties[c]).trim();
      if (col.javaType === 'Integer' || col.javaType === 'Double') {
        if (raw === '' || isNaN(Number(raw))) { bad = c; return; }
        out[c] = Number(raw);
      } else if (col.javaType === 'Boolean') {
        if (raw === 'true' || raw === '1') { out[c] = true; }
        else if (raw === 'false' || raw === '0') { out[c] = false; }
        else { bad = c; }
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

  // ------------------------------------------------------------------
  // 筛选面板（按各页的 FILTERS 定义生成，避免手写几十个输入框写错）
  // ------------------------------------------------------------------

  /**
   * 生成筛选面板。
   *
   * FILTERS 条目：`{ groupKey, fields: [ {key, labelKey, range?, placeholderKey?, type?} ] }`，
   * `type` ∈ `text`（默认）/ `int` / `select`（候选项由页面事后用 fillSelect 填）。
   * ⚠ 键名必须与后端 `*QueryParams.parse` 的白名单完全一致 —— 多一个后端就 400。
   *
   * 控件类型优先按**该列的语义**定（`byName[key].kind === 'ENUM'` → 下拉），
   * 于是 `weaponclass=2` 这种"手填数字"就不存在了 —— 与详情页/列表用同一份 `options`。
   *
   * opts（重设计 v0.2 引入，见 docs/design-webadmin.md §4）：
   *   - `mainKey`：主搜索框绑定的筛选键（如 `name_like`），高级面板里同名控件不重复渲染；
   *   - `onInput`：任意筛选控件变更后的回调（防抖 300ms，页面里负责 `page=1` + 重查）。
   * 不给 opts 时退回旧行为（平铺全部条件，无搜索框/折叠）——目前没有调用方走这条，只保兼容。
   */
  function buildFilterBar(barEl, filters, byName, T, opts) {
    barEl.innerHTML = '';
    var isMain = function (f) {
      if (!opts || !opts.mainKey) { return false; }
      return f.range
        ? (f.key + '_min' === opts.mainKey || f.key + '_max' === opts.mainKey)
        : f.key === opts.mainKey;
    };

    if (!opts) {
      filters.forEach(function (g) { barEl.appendChild(filterGroupEl(g, byName, T, null)); });
      return;
    }

    // 顶行：主搜索框（= mainKey 字段）＋「筛选」开关
    var quick = document.createElement('div');
    quick.className = 'filter-quick';
    if (opts.mainKey) {
      var q = document.createElement('input');
      q.id = 'f_' + opts.mainKey;
      q.className = 'admin-input';
      q.setAttribute('placeholder', T('admin.common.quickSearchPlaceholder'));
      q.setAttribute('aria-label', T('admin.common.quickSearchPlaceholder'));
      quick.appendChild(q);
    }

    var adv = document.createElement('div');
    adv.className = 'filter-advanced hidden';
    filters.forEach(function (g) { adv.appendChild(filterGroupEl(g, byName, T, isMain)); });

    var toggle = document.createElement('button');
    toggle.type = 'button';
    toggle.className = 'btn btn-small btn-secondary filter-toggle';
    toggle.textContent = T('admin.common.filterToggle');
    toggle.setAttribute('aria-expanded', 'false');
    toggle.addEventListener('click', function () {
      var open = adv.classList.toggle('hidden') === false;
      toggle.classList.toggle('on', open);
      toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
      toggle.textContent = T(open ? 'admin.common.filterHide' : 'admin.common.filterToggle');
    });
    quick.appendChild(toggle);
    barEl.appendChild(quick);
    barEl.appendChild(adv);

    // 即时搜索：所有控件的 input/change 都防抖触发 onInput（事件冒泡，无需逐个绑定）
    var timer = null;
    function schedule() {
      if (!opts.onInput) { return; }
      if (timer) { clearTimeout(timer); }
      timer = setTimeout(opts.onInput, 300);
    }
    barEl.addEventListener('input', schedule);
    barEl.addEventListener('change', schedule);
  }

  function filterGroupEl(g, byName, T, isMain) {
    var box = document.createElement('div');
    box.className = 'item-filter-group';
    var title = document.createElement('span');
    title.className = 'item-filter-title';
    title.textContent = T(g.groupKey);
    box.appendChild(title);
    g.fields.forEach(function (f) {
      if (isMain && isMain(f)) { return; }
      if (f.range) {
        box.appendChild(filterField(f.key + '_min', T('admin.common.atLeast', { label: T(f.labelKey) }), f, byName, T));
        box.appendChild(filterField(f.key + '_max', T('admin.common.atMost', { label: T(f.labelKey) }), f, byName, T));
      } else {
        box.appendChild(filterField(f.key, T(f.labelKey), f, byName, T));
      }
    });
    return box;
  }

  function filterField(id, label, spec, byName, T) {
    var wrap = document.createElement('label');
    wrap.textContent = label;
    var col = byName[spec.key];
    var input;
    if (spec.type === 'select' || (col && col.kind === 'ENUM')) {
      input = document.createElement('select');
      input.id = 'f_' + id;
      input.className = 'admin-input';
      var any = document.createElement('option');
      any.value = '';
      any.textContent = T('admin.common.any');
      input.appendChild(any);
      if (col && col.kind === 'ENUM') {
        (col.options || []).forEach(function (o) {
          var opt = document.createElement('option');
          opt.value = String(o.value);
          opt.textContent = T(o.labelKey);
          input.appendChild(opt);
        });
        (col.textOptions || []).forEach(function (o) {
          var opt = document.createElement('option');
          opt.value = String(o.value);
          opt.textContent = T(o.labelKey);
          input.appendChild(opt);
        });
      }
    } else {
      input = document.createElement('input');
      input.id = 'f_' + id;
      input.className = 'admin-input';
      if (spec.type === 'int') {
        input.type = 'number';
      } else {
        if (spec.datalistId) {
          input.setAttribute('list', spec.datalistId);
        }
        input.placeholder = spec.placeholderKey ? T(spec.placeholderKey) : '';
      }
    }
    wrap.appendChild(input);
    return wrap;
  }

  /** 往一个 `select` 筛选项里填候选（value/label 由页面给）。 */
  function fillSelect(id, items) {
    var sel = document.getElementById('f_' + id);
    if (!sel) {
      return;
    }
    items.forEach(function (it) {
      var opt = document.createElement('option');
      opt.value = String(it.value);
      opt.textContent = it.label;
      sel.appendChild(opt);
    });
  }

  function collectFilters(filters) {
    var parts = [];
    filters.forEach(function (g) {
      g.fields.forEach(function (f) {
        var keys = f.range ? [f.key + '_min', f.key + '_max'] : [f.key];
        keys.forEach(function (k) {
          var node = document.getElementById('f_' + k);
          var v = node ? node.value : '';
          if (v !== null && v !== undefined && String(v).trim() !== '') {
            parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(String(v).trim()));
          }
        });
      });
    });
    return parts;
  }

  function resetFilters(filters) {
    filters.forEach(function (g) {
      g.fields.forEach(function (f) {
        var keys = f.range ? [f.key + '_min', f.key + '_max'] : [f.key];
        keys.forEach(function (k) {
          var node = document.getElementById('f_' + k);
          if (node) { node.value = ''; }
        });
      });
    });
  }

  // ------------------------------------------------------------------
  // 列选择器（按段分组）
  // ------------------------------------------------------------------

  /** 按段分组生成勾选框；`onChange(column, checked)` 由页面负责重画表头/表体。 */
  function buildColumnPicker(boxEl, columns, visible, onChange, T) {
    var t = T || window.PTi18n.t;
    boxEl.innerHTML = '';
    var sections = {};
    columns.forEach(function (c) { (sections[c.section] = sections[c.section] || []).push(c); });
    Object.keys(sections).forEach(function (s) {
      var head = document.createElement('div');
      head.className = 'item-colpicker-section';
      head.textContent = t(sections[s][0].sectionLabelKey);
      boxEl.appendChild(head);
      sections[s].forEach(function (c) {
        var lab = document.createElement('label');
        var cb = document.createElement('input');
        cb.type = 'checkbox';
        cb.checked = visible.indexOf(c.column) !== -1;
        cb.addEventListener('change', function () { onChange(c.column, cb.checked); });
        lab.appendChild(cb);
        lab.appendChild(document.createTextNode(c.column));
        boxEl.appendChild(lab);
      });
    });
  }


  // ------------------------------------------------------------------
  // 表格骨架：语义化可排序表头 / 行「查看」入口 / 页码跳转
  // ------------------------------------------------------------------

  /**
   * 渲染表头（4 个列表页共用）：
   *   ① 首列空槽 —— 放每行的「查看」按钮（与表体首列对齐，见 appendViewCell）；
   *   ② 每列表头 = semanticLabel（见 columnLabel），title 保留原始列名；
   *   ③ 全部可排序（后端 sort= 对列注册表白名单校验，所有列都能排）：
   *      点击 = 升→降循环，当前排序列加箭头 + aria-sort。
   *
   * opts：`{ sort, order, onSort(col) }`；不给 onSort 就只显示标签、不可点。
   */
  function buildHead(tr, columns, byName, T, opts) {
    tr.innerHTML = '';
    var slot = document.createElement('th');
    slot.className = 'view-th';
    slot.setAttribute('aria-label', T('admin.common.open'));
    tr.appendChild(slot);
    columns.forEach(function (c) {
      var col = byName[c];
      var th = document.createElement('th');
      th.textContent = col ? columnLabel(col, T) : c;
      th.title = c;   // 原始列名始终可见（hover）
      if (opts && opts.onSort) {
        th.classList.add('sortable');
        if (opts.sort === c) {
          th.classList.add(opts.order === 'asc' ? 'sort-asc' : 'sort-desc');
          th.setAttribute('aria-sort', opts.order === 'asc' ? 'ascending' : 'descending');
        } else {
          th.setAttribute('aria-sort', 'none');
        }
        (function (colName) {
          th.addEventListener('click', function () { opts.onSort(colName); });
        })(c);
      }
      tr.appendChild(th);
    });
  }

  /**
   * 表体首列：可点「查看」链接（键盘可达）。点它只跳详情，不触发整行点击。
   * 行本身还保留整行点击跳转（页面自己挂）。
   */
  function appendViewCell(tr, href, label) {
    var td = document.createElement('td');
    td.className = 'view-cell';
    var a = document.createElement('a');
    a.className = 'row-view-btn';
    a.href = href;
    a.tabIndex = 0;
    a.textContent = label;
    a.addEventListener('click', function (ev) { ev.stopPropagation(); });
    td.appendChild(a);
    tr.appendChild(td);
    return td;
  }

  /**
   * 分页区的「跳至第 N 页」直达框（输入数字回车/失焦即跳，越界自动夹取）。
   * 返回 `sync(page, totalPages)`：每次加载后调它同步 max/当前值（正在输入时不打扰）。
   */
  function buildPagerJump(pagerEl, T, onGo) {
    var sep = document.createElement('span');
    sep.className = 'item-hint';
    sep.textContent = T('admin.common.pageJump');
    var inp = document.createElement('input');
    inp.type = 'number';
    inp.min = '1';
    inp.step = '1';
    inp.className = 'page-jump';
    inp.setAttribute('aria-label', T('admin.common.pageJump'));

    function go() {
      var n = parseInt(inp.value, 10);
      if (isNaN(n)) {
        inp.value = inp.last || '';
        return;
      }
      onGo(n);
    }
    inp.addEventListener('keydown', function (ev) {
      if (ev.key === 'Enter' || ev.key === 'Escape') { go(); }
    });
    inp.addEventListener('change', go);

    pagerEl.appendChild(sep);
    pagerEl.appendChild(inp);
    return {
      sync: function (page, totalPages) {
        inp.max = totalPages || 1;
        if (document.activeElement !== inp) {
          inp.value = page;
        }
      }
    };
  }

  // ------------------------------------------------------------------
  // 物品 chip 与选择器（怪物掉落行、NPC 商店列共用 —— 同一种交互只写一份）
  // ------------------------------------------------------------------

  /**
   * 渲染一组物品 chip：`名字 码 ✕`。`onRemove(code)` 由调用方处理移除。
   * `emptyText` 在没有任何条目时显示一行提示。
   */
  function renderItemChips(container, entries, onRemove, emptyText, T) {
    container.innerHTML = '';
    var list = entries || [];
    if (!list.length && emptyText) {
      var hint = document.createElement('div');
      hint.className = 'item-hint';
      hint.textContent = emptyText;
      container.appendChild(hint);
      return;
    }
    list.forEach(function (e) {
      var chip = document.createElement('span');
      chip.className = 'item-chip';
      chip.title = e.code;
      if (e.href) {
        // 只读态：物品名做成**可点引用**（跨页跳转用主键，见各页的 ?id 路由）
        var a = document.createElement('a');
        a.className = 'item-ref';
        a.href = e.href;
        a.textContent = e.name || e.code;
        chip.appendChild(a);
      } else {
        chip.appendChild(document.createTextNode((e.name ? e.name + ' ' : '') + e.code));
      }
      if (onRemove) {
        var x = document.createElement('button');
        x.type = 'button';
        x.textContent = '✕';
        x.title = T('admin.common.remove');
        x.addEventListener('click', function () { onRemove(e.code); });
        chip.appendChild(x);
      }
      container.appendChild(chip);
    });
  }

  /**
   * 通用**搜索选择器**：输入 → 防抖 220ms → `searchFn(q) → Promise<[{value,label}]>` → 结果列表；
   * 点一条就交给 `onPick(value, input, results)`。
   *
   * 为什么要有它：物品选择器（掉落行 / NPC 商店）与怪物选择器（地图刷怪槽位）是**同一种交互**，
   * 只有"查什么"不同 —— 这里只留一份防抖 + 结果渲染，各自传自己的 `searchFn`。
   * `inputId` 由调用方给：重渲染后要把焦点还给它（连着加几条时不用重新点输入框）。
   */
  function searchPicker(inputId, placeholder, searchFn, onPick, T, opts) {
    var wrap = document.createElement('div');
    wrap.className = 'item-picker';
    var input = document.createElement('input');
    input.className = 'admin-input';
    input.id = inputId;
    input.placeholder = placeholder;
    var results = document.createElement('div');
    results.className = 'item-picker-results hidden';
    var timer = null;

    function runSearch(q) {
      Promise.resolve(searchFn(q)).then(function (list) {
          results.innerHTML = '';
          if (!list || !list.length) {
            var hint = document.createElement('div');
            hint.className = 'item-hint';
            hint.textContent = T('admin.common.searchEmpty');
            results.appendChild(hint);
            results.classList.remove('hidden');
            return;
          }
          list.forEach(function (it) {
            var b = document.createElement('button');
            b.type = 'button';
            b.textContent = it.label;
            b.addEventListener('click', function () { onPick(it.value, input, results); });
            results.appendChild(b);
          });
          results.classList.remove('hidden');
        }).catch(function () { results.classList.add('hidden'); });
    }

    input.addEventListener('input', function () {
      if (timer) { clearTimeout(timer); }
      timer = setTimeout(function () { runSearch(input.value.trim()); }, 220);
    });
    // `showInitial`：建好就搜一次（空查询）—— 否则"下拉里什么都没有"看起来像坏了（用户 2026-09-22 报过）
    if (opts && opts.showInitial) {
      runSearch('');
    }
    wrap.appendChild(input);
    wrap.appendChild(results);
    return wrap;
  }

  /** 物品选择器（`/api/admin/items/search`）：掉落行、NPC 商店列用。 */
  function itemPicker(inputId, placeholder, onPick, T) {
    return searchPicker(inputId, placeholder, function (q) {
      return window.PT.request('/api/admin/items/search?limit=10&q=' + encodeURIComponent(q),
        { credentials: 'include' }).then(function (r) {
        var list = (r.ok && r.data) ? r.data : [];
        return list.map(function (it) {
          return {
            value: it,
            label: fmt(it.codeimg1) + '  ' + fmt(it.name)
              + (it.price === null || it.price === undefined ? '' : '   ' + it.price + ' G')
          };
        });
      });
    }, onPick, T, { showInitial: true });   // 打开就有候选（空查询给前 10 件），不必先猜关键词
  }

  /**
   * NPC 的**本地化名**：`npclist.name` 是内部键（`blacksmith_drol`），
   * 客户端把显示名放在 `npc.*`（我们镜像成 `npcName.*`，键就是内名称）。
   * 查不到就回退内名称 —— 不静默，也不假装有译文（表缺失时会原样显示内名称）。
   */
  function npcName(name) {
    if (!name) {
      return '';
    }
    var key = 'npcName.' + name;
    return window.PTi18n.has(key) ? window.PTi18n.t(key) : name;
  }

  /**
   * **eventtype 语义表**（静态，来自分析文档 §5.1 已确证分布）：
   * `npc.Column` eventtype 是裸数字码，前端补可读语义；未收录码不臆造，保底显示原数字。
   * 0=站桩 14=商人 30=仓库 4=教官 9=武器店 8=防具店 13=传送。
   */
  var EVENT_TYPE_SEMANTICS = {
    0: 'admin.npc.eventtype0',
    14: 'admin.npc.eventtype14',
    30: 'admin.npc.eventtype30',
    4: 'admin.npc.eventtype4',
    9: 'admin.npc.eventtype9',
    8: 'admin.npc.eventtype8',
    13: 'admin.npc.eventtype13'
  };

  /**
   * **teleportid 目的地语义**（静态，来自分析文档 §5.2）：
   * 1=5 目的地传送（费用 100/100/500/1000+税，Lv84 门槛）
   * 2=城堡传送（费用 = Lv×500 + 税）  3=无尽塔传送（免费、无尽 1F）
   * 其余码不臆造，保底显示「传送码 {id}」。
   */
  var TELEPORT_SEMANTICS = {
    1: { dests: ['admin.npc.tpMushroom', 'admin.npc.tpBeehive', 'admin.npc.tpPrison', 'admin.npc.tpRailway', 'admin.npc.tpPerum'],
         costs: ['100', '100', '500', '1000'], gate: 'admin.npc.tpGate', label: 'admin.npc.teleport1' },
    2: { label: 'admin.npc.teleport2' },
    3: { label: 'admin.npc.teleport3' }
  };

  /** eventtype 语义标签：已收录码 → 可读名，未收录 → 原数字。返回 { label, value }。 */
  function eventTypeSem(value) {
    var v = (value === null || value === undefined) ? '' : String(value);
    var key = EVENT_TYPE_SEMANTICS[v];
    return key ? { label: window.PTi18n.t(key), value: v } : { label: v, value: v };
  }

  /** teleportid 目的地语义：返回 { label, dests, costs, gate, note } 或 null（=0，无传送）。 */
  function teleportSem(id) {
    var v = (id === null || id === undefined) ? 0 : Number(id);
    if (!v) { return null; }
    var def = TELEPORT_SEMANTICS[v];
    if (!def) { return { label: 'admin.npc.tpUnknown', code: String(v), note: null }; }
    var out = { label: def.label, code: String(v), note: null };
    if (def.dests) {
      out.dests = def.dests.map(function (k) { return window.PTi18n.t(k); });
      out.costs = def.costs;
      out.gate = window.PTi18n.t(def.gate);
    } else if (def.label === 'admin.npc.teleport2') {
      out.note = window.PTi18n.t('admin.npc.teleport2Note');
    } else if (def.label === 'admin.npc.teleport3') {
      out.note = window.PTi18n.t('admin.npc.teleport3Note');
    }
    return out;
  }

  /**
   * **本地化名 → 内名称**（NPC）：界面显示的是本地化名（`npcName.*`，键是内名称），
   * 而服务端只认 `npclist.name` —— 搜索/选择器都先在这里反查，再把内名称交给服务端
   *（列表用 `names=` 筛选，选择器同理）。查不到返回空数组，调用方自行回退。
   */
  function resolveNpcNames(query) {
    var q = String(query || '').toLowerCase();
    if (!q) {
      return [];
    }
    var out = [];
    window.PTi18n.keys('npcName').forEach(function (internal) {
      var label = String(window.PTi18n.t('npcName.' + internal)).toLowerCase();
      if (label.indexOf(q) !== -1) {
        out.push(internal);
      }
    });
    return out;
  }

  window.PTAdmin = {
    fmt: fmt,
    esc: esc,
    optionLabel: optionLabel,
    buildRows: buildRows,
    rowNameEl: rowNameEl,
    editControl: editControl,
    markDirty: markDirty,
    coerceChanges: coerceChanges,
    buildFilterBar: buildFilterBar,
    fillSelect: fillSelect,
    collectFilters: collectFilters,
    resetFilters: resetFilters,
    buildColumnPicker: buildColumnPicker,
    columnLabel: columnLabel,
    buildHead: buildHead,
    appendViewCell: appendViewCell,
    buildPagerJump: buildPagerJump,
    renderItemChips: renderItemChips,
    itemPicker: itemPicker,
    searchPicker: searchPicker,
    resolveNpcNames: resolveNpcNames,
    npcName: npcName,
    eventTypeSem: eventTypeSem,
    teleportSem: teleportSem
  };
})();
