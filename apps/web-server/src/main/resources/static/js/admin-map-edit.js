/*
 * 地图可视化编辑页（`/admin/map/{id}/edit` → admin-map-edit.html）。
 *
 * 用户 2026-09-22 定的三条口径：
 *   ① **不复用客户端地图组件源码，但要复现其地图可视化逻辑** → 见 `js/map-canvas.js`（按 WorldMap.ts 的算法重写）；
 *   ② **独立整页**编辑（详情页只做可视化展示）；
 *   ③ **攒着点保存**（拖拽/放置都先改本地，点保存一次提交；离开前有未保存提醒）。
 *
 * 编辑两类东西（都在这张图上）：
 *   · **NPC 摆放**（`mapnpc`）：三角 + 朝向（`angle`），另有 `y`（高度）与 `enabled`/`onlyGm`；
 *   · **怪物刷新点**（`mapspawnpoint`，**不是玩家出生点**）：圆点 + `description`。
 *
 * 保存走两个"整表替换"接口（`POST /{id}/npcs` / `POST /{id}/points`），只提交**脏了的那一边**。
 */
(function () {
  var API = '/api/admin/maps';
  var T = window.PTi18n.t;

  var state = {
    mapId: null, map: null,
    npcs: [],          // [{placeId|null, tempId, npcId, npcName, x, y, z, angle, enabled, onlyGm}]
    points: [],        // [{pointId|null, tempId, x, z, description}]
    dirtyNpcs: false, dirtyPoints: false,
    tool: 'select',
    chosenNpc: null,   // 放置 NPC 前先选中的那只
    selectedKey: null,
    seq: 0             // 新标记的临时 id
  };

  var canvas = null;

  function el(id) { return document.getElementById(id); }

  function msg(text, isError) {
    var box = el('msgBox');
    if (!text) { box.classList.add('hidden'); return; }
    box.textContent = text;
    box.className = 'msg ' + (isError ? 'error' : '');
  }

  function keyOfNpc(n) { return 'npc:' + (n.placeId !== null && n.placeId !== undefined ? n.placeId : 'new' + n.tempId); }
  function keyOfPoint(p) { return 'point:' + (p.pointId !== null && p.pointId !== undefined ? p.pointId : 'new' + p.tempId); }

  function isDirty() { return state.dirtyNpcs || state.dirtyPoints; }

  function touch(which) {
    if (which === 'npcs') { state.dirtyNpcs = true; } else { state.dirtyPoints = true; }
    el('dirtyBadge').textContent = T('admin.map.unsaved');
    el('dirtyBadge').className = 'item-hint map-dirty';
  }

  function clearDirty() {
    state.dirtyNpcs = false;
    state.dirtyPoints = false;
    el('dirtyBadge').textContent = '';
    el('dirtyBadge').className = 'item-hint';
  }

  // ------------------------------------------------------------------
  // 标记（画布用的那份）
  // ------------------------------------------------------------------

  function markers() {
    var out = [];
    state.npcs.forEach(function (n) {
      out.push({
        id: keyOfNpc(n),
        kind: 'npc',
        x: n.x, z: n.z, angle: n.angle,
        label: PTAdmin.npcName(n.npcName)
      });
    });
    state.points.forEach(function (p) {
      out.push({ id: keyOfPoint(p), kind: 'point', x: p.x, z: p.z, label: p.description || '' });
    });
    return out;
  }

  function syncCanvas() {
    if (canvas) {
      canvas.setMarkers(markers());
      canvas.setSelected(state.selectedKey);
    }
  }

  // ------------------------------------------------------------------
  // 右栏：选中项属性
  // ------------------------------------------------------------------

  function findSelected() {
    var key = state.selectedKey;
    if (!key) { return null; }
    if (key.indexOf('npc:') === 0) {
      for (var i = 0; i < state.npcs.length; i++) {
        if (keyOfNpc(state.npcs[i]) === key) { return { kind: 'npc', item: state.npcs[i] }; }
      }
    } else {
      for (var j = 0; j < state.points.length; j++) {
        if (keyOfPoint(state.points[j]) === key) { return { kind: 'point', item: state.points[j] }; }
      }
    }
    return null;
  }

  function numberField(labelText, value, onChange) {
    var label = document.createElement('label');
    label.textContent = labelText;
    var input = document.createElement('input');
    input.className = 'admin-input';
    input.type = 'number';
    input.value = value === null || value === undefined ? '' : String(value);
    input.addEventListener('input', function () {
      var v = Number(input.value);
      if (input.value === '' || isNaN(v)) { return; }
      onChange(Math.round(v));
    });
    label.appendChild(input);
    return label;
  }

  function renderPanel() {
    var box = el('panelBody');
    box.innerHTML = '';
    var sel = findSelected();
    if (!sel) {
      var hint = document.createElement('div');
      hint.className = 'item-hint';
      hint.textContent = T('admin.map.panelEmpty');
      box.appendChild(hint);
      return;
    }
    var head = document.createElement('div');
    head.className = 'item-section-title';
    head.textContent = sel.kind === 'npc' ? T('admin.map.markerNpc') : T('admin.map.markerPoint');
    box.appendChild(head);

    if (sel.kind === 'npc') {
      var n = sel.item;
      var name = document.createElement('div');
      name.className = 'item-hint';
      name.textContent = PTAdmin.npcName(n.npcName) + '  #' + n.npcId;
      box.appendChild(name);
      box.appendChild(numberField('x', n.x, function (v) { n.x = v; syncCanvas(); touch('npcs'); }));
      box.appendChild(numberField(T('admin.map.fieldY'), n.y, function (v) { n.y = v; touch('npcs'); }));
      box.appendChild(numberField('z', n.z, function (v) { n.z = v; syncCanvas(); touch('npcs'); }));
      box.appendChild(numberField(T('admin.map.fieldAngle'), n.angle, function (v) { n.angle = v; syncCanvas(); touch('npcs'); }));
      box.appendChild(flagField(T('admin.common.disabled'), n.enabled, function (v) { n.enabled = v; touch('npcs'); }, 1));
      box.appendChild(flagField(T('admin.common.onlyGm'), n.onlyGm, function (v) { n.onlyGm = v; touch('npcs'); }, 0));
      box.appendChild(removeButton(function () {
        state.npcs = state.npcs.filter(function (x) { return x !== n; });
        state.selectedKey = null;
        touch('npcs');
        syncCanvas();
        renderPanel();
      }));
    } else {
      var p = sel.item;
      box.appendChild(numberField('x', p.x, function (v) { p.x = v; syncCanvas(); touch('points'); }));
      box.appendChild(numberField('z', p.z, function (v) { p.z = v; syncCanvas(); touch('points'); }));
      var dl = document.createElement('label');
      dl.textContent = T('admin.map.fieldDescription');
      var di = document.createElement('input');
      di.className = 'admin-input';
      di.value = p.description === null || p.description === undefined ? '' : p.description;
      di.addEventListener('input', function () { p.description = di.value; touch('points'); });
      dl.appendChild(di);
      box.appendChild(dl);
      box.appendChild(removeButton(function () {
        state.points = state.points.filter(function (x) { return x !== p; });
        state.selectedKey = null;
        touch('points');
        syncCanvas();
        renderPanel();
      }));
    }
  }

  /** 0/1 开关（`enabled` 缺省 1、`onlyGm` 缺省 0 —— 与语料一致）。 */
  function flagField(labelText, value, onChange, defaultOn) {
    var label = document.createElement('label');
    label.textContent = labelText;
    var cb = document.createElement('input');
    cb.type = 'checkbox';
    cb.checked = (value === null || value === undefined ? defaultOn : value) !== 0;
    cb.addEventListener('change', function () { onChange(cb.checked ? 1 : 0); });
    label.appendChild(cb);
    return label;
  }

  function removeButton(onClick) {
    var b = document.createElement('button');
    b.type = 'button';
    b.className = 'btn btn-small btn-secondary';
    b.textContent = T('admin.map.removeMarker');
    b.style.marginTop = '0.5rem';
    b.addEventListener('click', onClick);
    return b;
  }

  // ------------------------------------------------------------------
  // 工具栏
  // ------------------------------------------------------------------

  function setTool(tool) {
    state.tool = tool;
    [['toolSelect', 'select'], ['toolNpc', 'place-npc'], ['toolPoint', 'place-point'], ['toolDelete', 'delete']]
      .forEach(function (pair) {
        el(pair[0]).classList.toggle('map-tool-active', pair[1] === tool);
      });
    el('placePanel').classList.toggle('hidden', tool !== 'place-npc');
    if (canvas) { canvas.setMode(tool); }
  }

  function buildNpcPicker() {
    var host = el('npcPickHost');
    host.innerHTML = '';
    host.appendChild(PTAdmin.searchPicker('npcPick', T('admin.npc.namePlaceholder'), function (q) {
      // ⚠ **空查询 = 列出本图已有的 NPC**（用户 2026-09-22："没法从全部 NPC 中选择"）：
      //   摆放时最可能选的就是这几只，而且不必先猜关键词 —— 空着什么都不显示会像坏了。
      if (!q) {
        var seen = {};
        var here = [];
        state.npcs.forEach(function (n) {
          if (!n.npcId || seen[n.npcId]) { return; }
          seen[n.npcId] = true;
          here.push({ value: { id: n.npcId, name: n.npcName },
            label: PTAdmin.npcName(n.npcName) + '   #' + n.npcId });
        });
        return here;
      }
      // 先按**本地化名**反查内名称（界面显示的是本地化名），命中就用 `names=`；否则按内名称子串搜
      var matched = PTAdmin.resolveNpcNames(q);
      var url = matched.length
        ? '/api/admin/npcs?size=20&names=' + encodeURIComponent(matched.slice(0, 200).join(','))
        : '/api/admin/npcs?size=20&name_like=' + encodeURIComponent(q);
      return PT.request(url, { credentials: 'include' }).then(function (r) {
        var list = (r.ok && r.data && r.data.items) ? r.data.items : [];
        return list.map(function (n) {
          return { value: n, label: PTAdmin.npcName(n.name) + '   #' + n.id };
        });
      });
    }, function (n, input, results) {
      state.chosenNpc = n;
      input.value = '';
      results.classList.add('hidden');
      el('npcChosen').textContent = T('admin.map.npcChosen', { name: PTAdmin.npcName(n.name) });
      setTool('place-npc');
    }, T, { showInitial: true }));
  }

  // ------------------------------------------------------------------
  // 画布回调
  // ------------------------------------------------------------------

  function pickNpcY(x, z) {
    // 新放的 NPC 默认高度取**最近的既有 NPC** 的 y（地图有起伏，0 会埋进地里）；一只都没有就 0
    var best = null;
    var bestD = Infinity;
    state.npcs.forEach(function (n) {
      var d = (n.x - x) * (n.x - x) + (n.z - z) * (n.z - z);
      if (d < bestD) { bestD = d; best = n; }
    });
    return best ? best.y : 0;
  }

  function wireCanvas() {
    canvas = window.PTMapCanvas.create(el('mapHost'), {
      mapId: state.mapId,
      onHover: function (h) {
        el('cursorReadout').textContent = h ? ('x ' + h.x + ', z ' + h.z) : '';
      },
      onSelect: function (m) {
        state.selectedKey = m.id;
        renderPanel();
      },
      onPick: function (hit) {
        if (state.tool === 'delete') {
          if (!hit.marker) { return; }
          removeByKey(hit.marker.id);
          return;
        }
        if (state.tool === 'place-npc') {
          if (!state.chosenNpc) {
            msg(T('admin.map.needNpcFirst'), true);
            return;
          }
          state.npcs.push({
            placeId: null, tempId: ++state.seq,
            npcId: state.chosenNpc.id, npcName: state.chosenNpc.name,
            x: hit.x, y: pickNpcY(hit.x, hit.z), z: hit.z, angle: 0, enabled: 1, onlyGm: 0
          });
          touch('npcs');
          syncCanvas();
          return;
        }
        if (state.tool === 'place-point') {
          state.points.push({
            pointId: null, tempId: ++state.seq,
            x: hit.x, z: hit.z,
            description: String(state.points.length + 1)   // 语料里就是 '1'/'2' 这种序号
          });
          touch('points');
          syncCanvas();
          return;
        }
        // 选择：点标记选中、点空白取消
        state.selectedKey = hit.marker ? hit.marker.id : null;
        if (canvas) { canvas.setSelected(state.selectedKey); }
        renderPanel();
      },
      onMarkerMove: function (m) {
        // 拖拽中：同步面板里的坐标读数（拖完再算脏；onMarkerMoved 在松手时给一次最终值）
        var sel = findSelected();
        if (sel && ((m.kind === 'npc' && keyOfNpc(sel.item) === m.id) || (m.kind === 'point' && keyOfPoint(sel.item) === m.id))) {
          renderPanel();
        }
      },
      onMarkerMoved: function (m) {
        if (!m) { return; }
        touch(m.kind === 'npc' ? 'npcs' : 'points');
        renderPanel();
      }
    });
  }

  function removeByKey(key) {
    if (key.indexOf('npc:') === 0) {
      state.npcs = state.npcs.filter(function (n) { return keyOfNpc(n) !== key; });
      touch('npcs');
    } else {
      state.points = state.points.filter(function (p) { return keyOfPoint(p) !== key; });
      touch('points');
    }
    if (state.selectedKey === key) { state.selectedKey = null; }
    syncCanvas();
    renderPanel();
  }

  // ------------------------------------------------------------------
  // 载入与保存
  // ------------------------------------------------------------------

  function load() {
    var id = state.mapId;
    return Promise.all([
      PT.request(API + '/' + id, { credentials: 'include' }).then(function (r) {
        if (r.status === 401) { window.location.href = './login.html'; throw new Error('401'); }
        if (!r.ok) { throw new Error(PT.msgOf(r.code, T('admin.common.requestFailed', { status: r.status }))); }
        state.map = r.data;
      }),
      PT.request(API + '/' + id + '/npcs', { credentials: 'include' }).then(function (r) {
        if (!r.ok) { throw new Error(PT.msgOf(r.code, T('admin.common.requestFailed', { status: r.status }))); }
        state.npcs = (r.data.npcs || []).map(function (n) {
          return {
            placeId: n.placeId, tempId: ++state.seq,
            npcId: n.npcId, npcName: n.npcName,
            x: n.x, y: n.y, z: n.z, angle: n.angle, enabled: n.enabled, onlyGm: n.onlyGm
          };
        });
      }),
      PT.request(API + '/' + id + '/points', { credentials: 'include' }).then(function (r) {
        if (!r.ok) { throw new Error(PT.msgOf(r.code, T('admin.common.requestFailed', { status: r.status }))); }
        state.points = (r.data.points || []).map(function (p) {
          return { pointId: p.pointId, tempId: ++state.seq, x: p.x, z: p.z, description: p.description };
        });
      })
    ]);
  }

  function save() {
    if (!isDirty()) {
      msg(T('admin.common.noChanges'), false);
      return;
    }
    var jobs = [];
    if (state.dirtyNpcs) {
      jobs.push(PT.request(API + '/' + state.mapId + '/npcs', {
        method: 'POST', credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          npcs: state.npcs.map(function (n) {
            return {
              placeId: n.placeId, npcId: n.npcId,
              x: n.x, y: n.y, z: n.z, angle: n.angle, enabled: n.enabled, onlyGm: n.onlyGm
            };
          })
        })
      }));
    }
    if (state.dirtyPoints) {
      jobs.push(PT.request(API + '/' + state.mapId + '/points', {
        method: 'POST', credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          points: state.points.map(function (p) {
            return { pointId: p.pointId, x: p.x, z: p.z, description: p.description };
          })
        })
      }));
    }
    return Promise.all(jobs).then(function (rs) {
      rs.forEach(function (r) {
        if (!r.ok) {
          throw new Error(PT.msgOf(r.code, T('admin.common.requestFailed', { status: r.status })));
        }
      });
      var summary = rs.map(function (r) {
        return T('admin.map.savedCounts', {
          a: r.data.added, u: r.data.updated, r: r.data.removed });
      }).join(' · ');
      clearDirty();
      state.selectedKey = null;
      // 重新拉一次：新增的行已经拿到数据库主键，标记的 key 要跟着换（否则第二次保存会当成"新增"）
      return load().then(function () {
        syncCanvas();
        renderPanel();
        msg(T('admin.map.saved') + '：' + summary, false);
      });
    }).catch(function (e) { msg(e.message, true); });
  }

  // ------------------------------------------------------------------
  // 启动
  // ------------------------------------------------------------------

  function boot() {
    var m = /\/admin\/map\/(\d+)\/edit\/?$/.exec(window.location.pathname);
    if (!m) {
      msg(T('admin.common.badPath'), true);
      return;
    }
    state.mapId = parseInt(m[1], 10);
    window.PTi18n.load().then(function () {
      PTNav.me().then(function (r) {
        if (r.status === 401) { window.location.href = './login.html'; return; }
        if (r.ok && r.data && r.data.accountName) {
          el('adminUserLabel').textContent = r.data.accountName
            + (r.data.webAdmin ? T('admin.common.adminSuffix') : '');
        }
      });
      el('backLink').textContent = T('admin.common.backToList');
      el('backLink').setAttribute('href', './admin/map/' + state.mapId);
      el('saveBtn').addEventListener('click', save);
      el('revertBtn').addEventListener('click', function () {
        if (!isDirty() || window.confirm(T('admin.map.confirmRevert'))) {
          load().then(function () {
            clearDirty();
            state.selectedKey = null;
            syncCanvas();
            renderPanel();
            msg(T('admin.map.reverted'), false);
          }).catch(function (e) { msg(e.message, true); });
        }
      });
      el('fitBtn').addEventListener('click', function () { if (canvas) { canvas.fit(); } });
      [['toolSelect', 'select'], ['toolNpc', 'place-npc'], ['toolPoint', 'place-point'], ['toolDelete', 'delete']]
        .forEach(function (pair) {
          el(pair[0]).addEventListener('click', function () {
            if (pair[1] === 'place-npc' && !state.chosenNpc) {
              msg(T('admin.map.needNpcFirst'), true);
            }
            setTool(pair[1]);
          });
        });
      // ⚠ NPC 选择器必须在 `load()` **之后**建：它的"空查询 = 列本图已有 NPC"要读 `state.npcs`，
      //   建早了那一刻还是空的 ⇒ 列表空着、而 `showInitial` 又只跑一次（踩过）。
      // 离开前提醒未保存（用户定的"攒着点保存"配套）
      window.addEventListener('beforeunload', function (e) {
        if (isDirty()) {
          e.preventDefault();
          e.returnValue = '';
        }
      });
      load().then(function () {
        el('mapLabel').textContent = PTAdmin.fmt(state.map.name) + '  #' + state.map.id
          + (state.map.shortname ? '  (' + state.map.shortname + ')' : '');
        buildNpcPicker();      // 数据到位后再建（见上）
        setTool('select');
        wireCanvas();
        // ⚠ 画布建好之后必须**立刻把标记灌进去**：`PTMapCanvas.create` 不带标记，
        //   漏了这一步的表现是"地图在、标记全无"，直到第一次编辑才出现（踩过）。
        syncCanvas();
        renderPanel();
      }).catch(function (e) { msg(e.message, true); });
    });
  }

  boot();
})();
