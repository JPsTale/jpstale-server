/*
 * NPC 详情页（`/admin/npc/{npclist.id}` → admin-npc.html）。
 *
 * 三块：
 *   ① 全部列（18 列 4 段，走 /columns 的段序与语义）；
 *   ② **商店清单**：三个商店列（`weaponshop`/`defenseshop`/`miscshop`，空格分隔的物品码）各自一组，
 *      可增删（复用 admin-common 的物品 chip 与选择器），整体保存（顺序即游戏内顺序）；
 *      ⚠ 这三列就是运行时 `NpcShopService` 读的那三列 —— 与怪物掉落是**同一种数据**。
 *   ③ **摆放**（`mapnpc`，只读）：站在哪几张图的哪个点、是否启用、是否仅 GM；地图名可点。
 *
 * 传的是 **`npclist.id` 主键**（全局约定）。
 */
(function () {
  var API = '/api/admin/npcs';
  var T = window.PTi18n.t;

  /** 商店三列：接口键 = 数据库列名。 */
  var SHOP_COLUMNS = [
    { key: 'weaponshop', group: 'weapon', groupLabelKey: 'admin.npc.shopWeapon' },
    { key: 'defenseshop', group: 'defense', groupLabelKey: 'admin.npc.shopDefense' },
    { key: 'miscshop', group: 'misc', groupLabelKey: 'admin.npc.shopMisc' }
  ];

  var state = {
    shops: null, shopsLoaded: false, shopsFailed: false,
    shopEditing: false, shopRows: {},
    places: null, placesLoaded: false, placesFailed: false
  };

  function el(id) { return document.getElementById(id); }

  function note(text, warn) {
    var n = document.createElement('div');
    n.className = 'item-hint' + (warn ? ' item-warn' : '');
    n.textContent = text;
    return n;
  }

  function sectionTitle(text) {
    var t = document.createElement('div');
    t.className = 'item-section-title';
    t.textContent = text;
    return t;
  }

  function button(label, onClick, secondary) {
    var b = document.createElement('button');
    b.type = 'button';
    b.className = 'btn btn-small' + (secondary ? ' btn-secondary' : '');
    b.textContent = label;
    b.addEventListener('click', onClick);
    return b;
  }

  // ------------------------------------------------------------------
  // 商店清单
  // ------------------------------------------------------------------

  function shopBlock(ctx) {
    var box = document.createElement('div');
    box.appendChild(sectionTitle(T('admin.npc.shopsTitle')));

    var bar = document.createElement('div');
    bar.className = 'item-drop-head';
    if (!state.shopEditing) {
      var edit = button(T('admin.npc.editShops'), function () { startShopEditing(ctx); }, true);
      edit.disabled = state.shopsFailed || !state.shops;
      bar.appendChild(edit);
      if (state.shops && !state.shops.isMerchant) {
        bar.appendChild(note(T('admin.npc.notMerchant')));
      }
    }
    box.appendChild(bar);

    if (state.shopsFailed) {
      box.appendChild(note(T('admin.common.loadFailed')));
      return box;
    }
    if (!state.shops) {
      return box;
    }

    SHOP_COLUMNS.forEach(function (col) {
      var group = document.createElement('div');
      group.className = 'item-drop-row';
      var head = document.createElement('div');
      head.className = 'item-drop-head';
      var title = document.createElement('span');
      title.className = 'item-card-name';
      title.textContent = T(col.groupLabelKey);
      head.appendChild(title);
      group.appendChild(head);

      var chips = document.createElement('div');
      if (state.shopEditing) {
        var entries = state.shopRows[col.key] || [];
        PTAdmin.renderItemChips(chips, entries, function (code) {
          state.shopRows[col.key] = entries.filter(function (e) {
            return String(e.code).toLowerCase() !== String(code).toLowerCase();
          });
          ctx.rerender();
        }, T('admin.npc.shopEmptyCol'), T);
        group.appendChild(chips);
        group.appendChild(PTAdmin.itemPicker('shopPick_' + col.key, T('admin.monster.dropPickerPlaceholder'),
          function (it, input, results) { addShopItem(col.key, it, input, results, ctx); }, T));
      } else {
        // 只读态：物品名可点（→ /admin/item/{主键}）；没有 ✕
        PTAdmin.renderItemChips(chips, (state.shops[col.group] || []).map(linkify), null,
          T('admin.npc.shopEmptyCol'), T);
        group.appendChild(chips);
      }
      box.appendChild(group);
    });

    if (state.shopEditing) {
      var footer = document.createElement('div');
      footer.className = 'item-drop-head';
      footer.appendChild(button(T('admin.npc.saveShops'), function () { saveShops(ctx); }));
      footer.appendChild(button(T('admin.common.cancel'), function () {
        state.shopEditing = false;
        state.shopRows = {};
        ctx.rerender();
      }, true));
      box.appendChild(footer);
    }
    (state.shops.warnings || []).forEach(function (w) { box.appendChild(note(w, true)); });
    return box;
  }

  /** 只读态的物品名做成**可点引用** → `/admin/item/{itemlist.id}`（主键）。 */
  function linkify(e) {
    return {
      code: e.code,
      name: e.name,
      itemId: e.itemId,
      href: e.itemId ? './admin/item/' + e.itemId : null
    };
  }

  function startShopEditing(ctx) {
    ctx.setEditing(false);          // 列编辑与商店编辑互斥
    state.shopEditing = true;
    state.shopRows = {};
    SHOP_COLUMNS.forEach(function (col) {
      state.shopRows[col.key] = (state.shops[col.group] || []).slice();
    });
    ctx.msg('');
    ctx.rerender();
  }

  function addShopItem(column, it, input, results, ctx) {
    var list = state.shopRows[column] || (state.shopRows[column] = []);
    var code = String(it.codeimg1 === null || it.codeimg1 === undefined ? '' : it.codeimg1).trim();
    if (!code) return;
    var exists = list.some(function (e) { return String(e.code).toLowerCase() === code.toLowerCase(); });
    if (!exists) {
      list.push({ code: code, itemId: it.id, idcode: it.idcode, name: it.name });
    }
    input.value = '';
    results.classList.add('hidden');
    ctx.rerender();
    var again = el('shopPick_' + column);
    if (again) { again.focus(); }
  }

  function saveShops(ctx) {
    var body = {};
    SHOP_COLUMNS.forEach(function (col) {
      body[col.key] = (state.shopRows[col.key] || []).map(function (e) { return e.code; });
    });
    PT.request(API + '/' + ctx.row.id + '/shops', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    }).then(function (r) {
      if (!r.ok) {
        throw new Error(PT.msgOf(r.code, T('admin.common.requestFailed', { status: r.status })));
      }
      state.shops = r.data;
      state.shopEditing = false;
      state.shopRows = {};
      ctx.rerender();
      ctx.msg(T('admin.npc.shopSaved', { cols: (r.data.touched || []).join(' / ') }), false);
    }).catch(function (e) { ctx.msg(e.message, true); });
  }

  // ------------------------------------------------------------------
  // 摆放（mapnpc，只读）
  // ------------------------------------------------------------------

  function placesBlock(ctx) {
    var box = document.createElement('div');
    box.appendChild(sectionTitle(T('admin.npc.placesTitle')));
    if (state.placesFailed) {
      box.appendChild(note(T('admin.common.loadFailed')));
      return box;
    }
    if (!state.places) {
      return box;
    }
    if (!state.places.count) {
      box.appendChild(note(T('admin.npc.noPlaces')));
      return box;
    }
    box.appendChild(note(T('admin.npc.placesCount', {
      n: state.places.count, maps: state.places.mapCount })));

    state.places.placements.forEach(function (p) {
      var row = document.createElement('div');
      row.className = 'item-row';

      var name = document.createElement('div');
      name.className = 'item-col-name';
      if (p.mapName) {
        var a = document.createElement('a');
        a.className = 'item-ref';
        a.href = './admin/map/' + p.mapId;
        a.textContent = p.mapName;
        name.appendChild(a);
      } else {
        name.textContent = '#' + p.mapId;
      }
      name.title = 'mapnpc.id=' + p.placeId;
      row.appendChild(name);

      var val = document.createElement('div');
      val.className = 'item-col-value';
      val.textContent = '(' + PTAdmin.fmt(p.x) + ', ' + PTAdmin.fmt(p.z) + ')  angle=' + PTAdmin.fmt(p.angle);
      if (!p.enabled) {
        var d = document.createElement('span');
        d.className = 'item-warn';
        d.textContent = '  ' + T('admin.common.disabled');
        val.appendChild(d);
      }
      if (p.onlyGm) {
        var g = document.createElement('span');
        g.className = 'item-warn';
        g.textContent = '  ' + T('admin.common.onlyGm');
        val.appendChild(g);
      }
      row.appendChild(val);
      box.appendChild(row);
    });
    return box;
  }

  // ------------------------------------------------------------------
  // 启动
  // ------------------------------------------------------------------

  PTDetail.boot({
    api: API,
    listHref: './admin/npcs',
    title: function (row) {
      // 显示本地化名（`npclist.name` 是内部键），`#id` 保留主键便于核对
      return PTAdmin.npcName(row.name) + '  #' + row.id;
    },
    stats: function (row) {
      return [
        T('npc.gamefile') + ' ' + PTAdmin.fmt(row.gamefile),
        // §5.1 eventtype 语义标签：收录码→可读名（站桩/商人/仓库/教官/商店/传送），未收录→原数字
        T('npc.eventType') + ' ' + PTAdmin.eventTypeSem(row.eventtype).label,
        // §5.2 teleportid 目的地语义（teleportid=0=无传送 → 不显示该行）
        (row.teleportid && PTAdmin.teleportSem(row.teleportid)) ?
          T('npc.teleportId') + ' ' + PTAdmin.teleportSem(row.teleportid).label : null
      ].filter(Boolean).join(' · ');
    },
    render: function (row, ctx, body) {
      if (!state.shopsLoaded) {
        state.shopsLoaded = true;
        PT.request(API + '/' + row.id + '/shops', { credentials: 'include' }).then(function (r) {
          state.shops = (r.ok && r.data) ? r.data : null;
          state.shopsFailed = !r.ok;
          ctx.rerender();
        }).catch(function () { state.shopsFailed = true; ctx.rerender(); });
      }
      if (!state.placesLoaded) {
        state.placesLoaded = true;
        PT.request(API + '/' + row.id + '/places', { credentials: 'include' }).then(function (r) {
          state.places = (r.ok && r.data) ? r.data : null;
          state.placesFailed = !r.ok;
          ctx.rerender();
        }).catch(function () { state.placesFailed = true; ctx.rerender(); });
      }

      PTDetail.renderSections(ctx, body, function (col, value) {
        // name 列显示本地化名，内名称进 title（可追溯）
        if (col.column === 'name') {
          return { text: PTAdmin.npcName(value), title: PTAdmin.fmt(value) };
        }
        return null;
      });
      body.appendChild(shopBlock(ctx));
      body.appendChild(placesBlock(ctx));
    }
  });
})();
