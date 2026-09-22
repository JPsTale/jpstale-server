/*
 * 地图画布（管理端自己实现）—— **复现**客户端 `jpstale-client/src/ui/WorldMap.ts` 的地图可视化逻辑。
 *
 * 为什么不直接复用那份源码（用户 2026-09-22 定："**可以不复用地图组件源码，但要复现其地图可视化逻辑**"）：
 * 它是 TS + Vite 构建（import `.json`/`.css`）且绑定游戏运行时（视野/玩家/图层分组），跨仓库打包会带来
 * "改了组件忘了重新打包"的滞后风险。所以这里按它的**算法**重写一份纯 JS 版（零依赖，静态页直接用）：
 *
 *   · 视图 = 中心 `(cx, cz)` + `unit`（**每个屏幕像素多少世界单位**），夹在 [ZOOM_MIN, ZOOM_MAX] = [8, 128]（与它同值）
 *   · `toScreen(x,z) = [w/2 + (x-cx)/unit, h/2 + (z-cz)/unit]`，`toWorld` 是其逆（逐行同法）
 *   · `fitTo(box, pad=0.06)`：`unit = max(bw,bh) / (min(cw,ch) * (1 - 2*pad))`（同它）
 *   · 滚轮缩放**以指针为锚**：先记指针下的世界点，缩放后再把它平移回指针下 —— 与它 `zoom()` 的
 *     "`bx - nx` 补偿"逐行同法（不这么写，缩放会往画布中心跑）
 *   · 平面图：`/res/image/planemap/index.json` 给每图 AABB，`/res/image/planemap/<id>.<format>` 是图本身；
 *     绘制 = `drawImage(img, …boxOnScreen(aabb))` —— **按 AABB 拉伸**，所以不需要 index 里的 `scale`
 *   · 标记（用户 2026-09-22 定）：
 *       **NPC = 绿色圆点 + 朝向线**、**怪物刷新点 = 红色圆点**（两种形状/颜色一眼分得开）。
 *     颜色只是**我们挑的两个对比色**（绿 `#8bf08b` / 红 `#ff5252`，取自客户端地图标记的既有色调），
 *     配深色描边 —— 沙漠/草地/雪地上都看得清。
 *
 *     ⚠ **两个"点"别混**（用户 2026-09-22 指出我混过）：
 *       · **玩家出生点** = `fields.json` 的 `startPoints`（EU `MapGame.cpp` 生成，每图 ≤10 个，客户端大地图画的绿点就是它）；
 *       · **怪物刷新点** = `gamedb.mapspawnpoint`（**本类画的红点**，每图 1~149 个，4059 行）。
 *       两者是不同的数据、不同的用途，颜色也各挑各的（不是因为概念相同才用绿/红）。
 *
 *     ⚠ **朝向线的角度单位是 0..4095（`ANGLE_CIRCLE = 4096`），不是 65536** ——
 *       依据是服务端自己的换算 `NpcSpawnService`：`angleDx = angle / ANGLE_CIRCLE * 2π`，
 *       再按原版客户端渲染做 yaw 镜像 `angleGl = π - angleDx`。这里照抄同一条式子，
 *       画布上再按客户端**地图标记**的记法 `rotate(-angle)`（它的 `drawMonsterMark`）落线。
 *       （曾用 65536 画三角 ⇒ 转出来的朝向小了 16 倍，几乎都指北 —— 已修。）
 *     ⚠ 原版客户端的**地图**其实只画一个点、不画朝向（`DrawMapNPC` + `MatNpcPos`）；朝向只体现在 3D 模型上。
 *       编辑器里加上朝向线是**我们有意多给的**（摆放 NPC 时"朝哪边"是要紧的信息），不是复现。
 *
 *     ⚠ **基准方向取客户端标记的几何，不是"想当然的北"**（用户 2026-09-22 实测："线方向反了"）：
 *       客户端 `drawMonsterMark` 的三角尖在 `(0, +5.5)` —— 画布 y 正方向 = **+z = 南**，
 *       即它们的基准朝向是**南**；我一开始把线画向 `-y`（北）⇒ 整条线差 **180°**。
 *       现改为 `(0, +len)`：基准南 + `rotate(-angleGl)` ⇒ 与客户端标记的朝向**逐个吻合**。
 *   · 图没就绪/缺图时**涂底 + 明说**（客户端也是这么做的：不静默留白）
 *
 * 用法：`PTMapCanvas.create(hostEl, {mapId, onPick, onHover, onMarkerMove})` → handle
 * （`setMarkers` / `setMode` / `setSelected` / `fit` / `redraw` / `worldToScreen` / `screenToWorld` / `destroy`）。
 */
(function () {
  var ZOOM_MIN = 8;
  var ZOOM_MAX = 128;
  var PAD = 0.06;
  /** 命中标记的半径（屏幕像素）。 */
  var HIT_R = 11;
  /** 判定"是拖拽而不是点击"的位移阈值（屏幕像素）。 */
  var CLICK_SLOP = 4;

  // index.json 全页只取一次（63 张图的 AABB）
  var indexPromise = null;

  function loadIndex() {
    if (!indexPromise) {
      // ⚠ 两点：
      //   ① 静态资产不走 `PT.request`（那个包装认的是 `Result` 信封 `{code,msg,data}`），直接 fetch；
      //   ② **必须用 `PT.apiUrl()` 补 context-path** —— 应用挂在 `/pt` 下，写根绝对的 `/res/...`
      //      会落到 `http://host/res/...`（=404）。`apiUrl` 把它变成 `./res/...`，再由页面里注入的
      //      `<base href="/pt/">` 解析成 `/pt/res/...`（这正是 api.js 那套约定存在的理由）。
      indexPromise = fetch(window.PT.apiUrl('/res/image/planemap/index.json'), { cache: 'no-cache' })
        .then(function (r) {
          if (!r.ok) {
            throw new Error('HTTP ' + r.status);
          }
          return r.json();
        })
        .catch(function (e) {
          indexPromise = null;   // 失败不缓存，下次重试
          throw e;
        });
    }
    return indexPromise;
  }

  function clampUnit(u) {
    return Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, u));
  }

  function create(host, opts) {
    opts = opts || {};
    var canvas = document.createElement('canvas');
    canvas.className = 'map-canvas';
    host.appendChild(canvas);
    var ctx = canvas.getContext('2d');

    var meta = null;         // index.json 里这张图的条目（含 aabb / w / h / format）
    var format = 'webp';
    var imgState = 'loading'; // loading | ok | failed
    var img = new Image();
    var view = { cx: 0, cz: 0, unit: 64 };
    var markers = [];
    var mode = 'select';      // select | place-npc | place-point | delete
    var selectedId = null;
    var hover = null;         // 指针下的世界坐标（页面显示读数用）
    var drag = null;          // {kind:'pan'|'marker', id, sx, sy, cx, cz, moved}

    function cssW() { return Math.max(1, Math.round(host.clientWidth)); }
    function cssH() { return Math.max(1, Math.round(host.clientHeight)); }

    /** 画布按设备像素比放大（不这么做在高分屏上字与线都是糊的）。 */
    function resize() {
      var dpr = window.devicePixelRatio || 1;
      canvas.width = Math.round(cssW() * dpr);
      canvas.height = Math.round(cssH() * dpr);
      canvas.style.width = '100%';
      canvas.style.height = '100%';
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);   // 之后一律用 CSS 像素做数学
      draw();
    }

    // ── 坐标变换（与客户端 WorldMap 同法）─────────────────────────────
    function toScreen(x, z) {
      return [cssW() / 2 + (x - view.cx) / view.unit, cssH() / 2 + (z - view.cz) / view.unit];
    }
    function toWorld(sx, sy) {
      return [view.cx + (sx - cssW() / 2) * view.unit, view.cz + (sy - cssH() / 2) * view.unit];
    }
    function boxOnScreen(b) {
      var p0 = toScreen(b.minX, b.minZ);
      var p1 = toScreen(b.maxX, b.maxZ);
      return [p0[0], p0[1], p1[0] - p0[0], p1[1] - p0[1]];
    }

    function fit() {
      if (!meta || !meta.aabb) {
        return;
      }
      var b = meta.aabb;
      var w = Math.max(b.maxX - b.minX, 1);
      var h = Math.max(b.maxZ - b.minZ, 1);
      var s = Math.min(cssW(), cssH()) * (1 - PAD * 2);
      view = {
        cx: (b.minX + b.maxX) / 2,
        cz: (b.minZ + b.maxZ) / 2,
        unit: clampUnit(Math.max(w, h) / s)
      };
      draw();
    }

    /** 滚轮缩放：以指针为锚（先记世界点、缩放后平移回来）。 */
    function zoomAt(factor, sx, sy) {
      var before = toWorld(sx, sy);
      view.unit = clampUnit(view.unit * factor);
      var after = toWorld(sx, sy);
      view.cx += before[0] - after[0];
      view.cz += before[1] - after[1];
      draw();
    }

    // ── 绘制 ──────────────────────────────────────────────────────────
    function draw() {
      var w = cssW();
      var h = cssH();
      ctx.clearRect(0, 0, w, h);
      ctx.fillStyle = '#0f0d17';
      ctx.fillRect(0, 0, w, h);
      if (!meta) {
        placeholder('…');
        return;
      }
      var rect = boxOnScreen(meta.aabb);
      if (rect[2] < 1 || rect[3] < 1) {
        return;
      }
      if (imgState === 'ok') {
        ctx.imageSmoothingQuality = 'high';
        ctx.drawImage(img, rect[0], rect[1], rect[2], rect[3]);
      } else {
        // 图未就绪/缺失：涂底并说明（不静默留白 —— 与客户端 drawMap 同一条规矩）
        ctx.fillStyle = imgState === 'failed' ? '#5a2b2b' : '#2c3742';
        ctx.fillRect(rect[0], rect[1], rect[2], rect[3]);
        ctx.fillStyle = '#e8dcc8';
        ctx.font = '600 13px "Microsoft YaHei", system-ui, sans-serif';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(imgState === 'failed' ? '平面图缺失（/res/image/planemap/…）' : '平面图加载中…',
          rect[0] + rect[2] / 2, rect[1] + rect[3] / 2);
      }
      // AABB 边框（编辑时要看得见图的边界；客户端的**世界图**不画框是因为那里框太多）
      ctx.strokeStyle = 'rgba(255,212,121,.35)';
      ctx.lineWidth = 1;
      ctx.strokeRect(rect[0] + 0.5, rect[1] + 0.5, rect[2] - 1, rect[3] - 1);
      markers.forEach(drawMarker);
    }

    function placeholder(text) {
      ctx.fillStyle = '#e8dcc8';
      ctx.font = '600 13px "Microsoft YaHei", system-ui, sans-serif';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText(text, cssW() / 2, cssH() / 2);
    }

    function drawMarker(m) {
      var p = toScreen(m.x, m.z);
      var x = p[0];
      var y = p[1];
      if (x < -20 || y < -20 || x > cssW() + 20 || y > cssH() + 20) {
        return;   // 画布外不画（客户端同法）
      }
      var hot = selectedId === m.id || (hover && hover.markerId === m.id);
      ctx.save();
      ctx.translate(x, y);
      if (m.kind === 'npc') {
        // NPC = **绿色圆点 + 朝向线**（用户 2026-09-22 定）
        var green = m.color || '#8bf08b';
        if (typeof m.angle === 'number') {
          // DB 的 DX 角（0..4095）→ GL 弧度（与 NpcSpawnService 同一条式子）
          var angleDx = (m.angle / 4096) * Math.PI * 2;
          var angleGl = Math.PI - angleDx;
          ctx.save();
          ctx.rotate(-angleGl);          // 客户端地图标记的记法（WorldMap.drawMonsterMark）
          // 基准朝向 = **+y（画布向下 = +z = 南）**，与客户端标记的三角尖同向；
          // `rotate(-angleGl)` 之后就是该 NPC 的实际朝向。
          var len = hot ? 15 : 13;
          // 先画粗深色底线再压亮色，浅色地形上也看得见（客户端给标记描边是同一个理由）
          ctx.beginPath();
          ctx.moveTo(0, 0);
          ctx.lineTo(0, len);
          ctx.strokeStyle = 'rgba(0,0,0,.5)';
          ctx.lineWidth = 4;
          ctx.stroke();
          ctx.beginPath();
          ctx.moveTo(0, 0);
          ctx.lineTo(0, len);
          ctx.strokeStyle = green;
          ctx.lineWidth = 2;
          ctx.stroke();
          ctx.restore();
        }
        ctx.beginPath();
        ctx.arc(0, 0, hot ? 6.5 : 5, 0, Math.PI * 2);
        ctx.fillStyle = green;
        ctx.fill();
        ctx.strokeStyle = 'rgba(0,0,0,.65)';
        ctx.lineWidth = 1.2;
        ctx.stroke();
      } else {
        ctx.beginPath();
        ctx.arc(0, 0, hot ? 6.5 : 5, 0, Math.PI * 2);
        ctx.fillStyle = m.color || '#ff5252';   // 怪物刷新点 = 红点（与 NPC 的绿点区分）
        ctx.fill();
        ctx.strokeStyle = 'rgba(0,0,0,.65)';
        ctx.lineWidth = 1.2;
        ctx.stroke();
      }
      if (selectedId === m.id) {
        ctx.beginPath();
        ctx.arc(0, 0, 13, 0, Math.PI * 2);
        ctx.strokeStyle = '#ffd479';
        ctx.lineWidth = 2;
        ctx.stroke();
      }
      ctx.restore();
    }

    // ── 命中与交互 ────────────────────────────────────────────────────
    function localXY(e) {
      var rect = canvas.getBoundingClientRect();
      return [e.clientX - rect.left, e.clientY - rect.top];
    }

    function markerAt(sx, sy) {
      var best = null;
      var bestD = HIT_R * HIT_R;
      markers.forEach(function (m) {
        var p = toScreen(m.x, m.z);
        var dx = p[0] - sx;
        var dy = p[1] - sy;
        var d = dx * dx + dy * dy;
        if (d <= bestD) {
          bestD = d;
          best = m;
        }
      });
      return best;
    }

    function emitHover(sx, sy) {
      var p = toWorld(sx, sy);
      var m = markerAt(sx, sy);
      hover = { x: Math.round(p[0]), z: Math.round(p[1]), markerId: m ? m.id : null };
      if (opts.onHover) {
        opts.onHover(hover, m || null);
      }
    }

    canvas.addEventListener('wheel', function (e) {
      e.preventDefault();
      var p = localXY(e);
      zoomAt(Math.exp(e.deltaY * 0.0012), p[0], p[1]);   // 与客户端同一个系数
      emitHover(p[0], p[1]);
    }, { passive: false });

    canvas.addEventListener('pointerdown', function (e) {
      if (e.button !== 0) {
        return;
      }
      var p = localXY(e);
      var hit = markerAt(p[0], p[1]);
      if (hit && (mode === 'select' || mode === 'delete')) {
        // 按在标记上：拖 = 移动它；松手没位移 = 选中它
        selectedId = hit.id;
        drag = { kind: 'marker', id: hit.id, sx: p[0], sy: p[1], moved: false };
        if (opts.onSelect) {
          opts.onSelect(hit);
        }
        draw();
      } else {
        // 按在空处：拖 = 平移画布；松手没位移 = 交给页面（放置 / 取消选中）
        drag = { kind: 'pan', sx: p[0], sy: p[1], cx: view.cx, cz: view.cz, moved: false };
      }
      canvas.setPointerCapture(e.pointerId);
    });

    canvas.addEventListener('pointermove', function (e) {
      var p = localXY(e);
      if (drag) {
        var dx = p[0] - drag.sx;
        var dy = p[1] - drag.sy;
        if (!drag.moved && (Math.abs(dx) > CLICK_SLOP || Math.abs(dy) > CLICK_SLOP)) {
          drag.moved = true;
        }
        if (drag.moved && drag.kind === 'pan') {
          view.cx = drag.cx - dx * view.unit;
          view.cz = drag.cz - dy * view.unit;
          draw();
        } else if (drag.moved && drag.kind === 'marker') {
          var m = markerById(drag.id);
          if (m) {
            var world = toWorld(p[0], p[1]);
            m.x = Math.round(world[0]);
            m.z = Math.round(world[1]);
            if (opts.onMarkerMove) {
              opts.onMarkerMove(m);
            }
            draw();
          }
        }
        emitHover(p[0], p[1]);
        return;
      }
      emitHover(p[0], p[1]);
    });

    canvas.addEventListener('pointerup', function (e) {
      if (!drag) {
        return;
      }
      var wasDrag = drag.moved;
      var kind = drag.kind;
      var dragId = drag.id;
      drag = null;
      var p = localXY(e);
      if (!wasDrag) {
        // 没位移 = 点击：交给页面决定（放置 / 选中 / 取消选中）
        var world = toWorld(p[0], p[1]);
        var hit = markerAt(p[0], p[1]);
        if (opts.onPick) {
          opts.onPick({
            x: Math.round(world[0]),
            z: Math.round(world[1]),
            marker: hit || null,
            mode: mode
          });
        }
      } else if (kind === 'marker' && opts.onMarkerMoved) {
        // 拖完了：把最终位置报给页面（页面负责标脏/更新面板；要不要写库由页面决定）
        opts.onMarkerMoved(markerById(dragId));
      }
    });

    function markerById(id) {
      for (var i = 0; i < markers.length; i++) {
        if (markers[i].id === id) {
          return markers[i];
        }
      }
      return null;
    }

    // ── 装载这张图 ────────────────────────────────────────────────────
    loadIndex().then(function (index) {
      format = index.format || 'webp';
      meta = (index.maps || []).filter(function (m) { return m.id === opts.mapId; })[0] || null;
      if (!meta) {
        imgState = 'failed';
        draw();
        return;
      }
      img.onload = function () { imgState = 'ok'; fit(); };
      img.onerror = function () { imgState = 'failed'; fit(); };
      img.src = window.PT.apiUrl('/res/image/planemap/' + opts.mapId + '.' + format);
      fit();
    }).catch(function () {
      imgState = 'failed';
      draw();
      if (opts.onError) {
        opts.onError('index.json');
      }
    });

    var ro = null;
    if (window.ResizeObserver) {
      ro = new ResizeObserver(function () { resize(); });
      ro.observe(host);
    } else {
      window.addEventListener('resize', resize);
    }
    resize();

    return {
      setMarkers: function (list) { markers = list || []; draw(); },
      setMode: function (m) { mode = m; },
      setSelected: function (id) { selectedId = id; draw(); },
      getSelected: function () { return selectedId; },
      fit: fit,
      redraw: draw,
      worldToScreen: toScreen,
      screenToWorld: toWorld,
      mapMeta: function () { return meta; },
      destroy: function () {
        if (ro) { ro.disconnect(); }
        canvas.remove();
      }
    };
  }

  window.PTMapCanvas = { create: create };
})();
