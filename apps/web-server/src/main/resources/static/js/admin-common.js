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
   */
  function buildFilterBar(barEl, filters, byName, T) {
    barEl.innerHTML = '';
    filters.forEach(function (g) {
      var box = document.createElement('div');
      box.className = 'item-filter-group';
      var title = document.createElement('span');
      title.className = 'item-filter-title';
      title.textContent = T(g.groupKey);
      box.appendChild(title);
      g.fields.forEach(function (f) {
        if (f.range) {
          box.appendChild(filterField(f.key + '_min', T('admin.common.atLeast', { label: T(f.labelKey) }), f, byName, T));
          box.appendChild(filterField(f.key + '_max', T('admin.common.atMost', { label: T(f.labelKey) }), f, byName, T));
        } else {
          box.appendChild(filterField(f.key, T(f.labelKey), f, byName, T));
        }
      });
      barEl.appendChild(box);
    });
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
    renderItemChips: renderItemChips,
    itemPicker: itemPicker,
    searchPicker: searchPicker,
    resolveNpcNames: resolveNpcNames,
    npcName: npcName
  };
})();
