/*
 * 侧栏菜单：**唯一的菜单定义** + 按当前账号动态渲染。
 *
 * 为什么要有这个文件：菜单原先在每页 HTML 里**各抄一份**（me.html 抄 3 项、admin-maps.html 抄 2 项），
 * 加一个页面就得记得改所有页，漏一个的症状是"某页菜单里没有它"——而且是静默的。
 * 现在只有下面 MENU 一处；新页面在那里加一行即可。
 *
 * 权限：显示哪些项由**服务端**的 `/api/user/me` → `webAdmin` 决定（后端来源见 GameMasterRule），
 * 前端不自己判角色，只把服务端说的角色翻译成"显示哪些项"。
 *
 * ⚠ **菜单不是权限门**：真正的门在各接口的注解与显式校验上（`/api/admin/**` 会 401/403）。
 *   这里藏项只是不给无效入口；非管理员手敲 `./admin-maps.html` 仍能打开页面骨架，
 *   页面的接口调用会拿到 403 并显示提示（admin-maps.js 已有该分支）。
 *   刻意不在前端再判一次角色 —— 判角色只能有一处（服务端），否则两边会漂。
 *
 * 用法：页面里放一个容器 `<nav class="admin-nav" id="ptNav"></nav>`，
 * 并在 api.js 之后、页面自己的脚本之前引本文件。页面自己的脚本要用当前账号信息时，
 * 调 `PTNav.me()`（全页只发一次请求），不要各自再打 `/api/user/me`。
 */
(function () {
  /**
   * 菜单定义 —— 加页面只改这里。
   *
   * 两种条目：
   *   { label, href }                     顶层单项
   *   { group, adminOnly?, items: [...] } 一组（渲染组标题 + 分隔线）
   * `adminOnly` 加在组上则整组（含组标题）只对管理员显示。
   * href 用相对当前页的路径；所有页面都在 static 根层，故一律写 './x.html'。
   */
  var MENU = [
    { label: '首页', href: './index.html' },
    {
      group: '账号',
      items: [
        { label: '用户中心', href: './me.html' }
      ]
    },
    {
      group: '管理功能',
      adminOnly: true,
      items: [
        { label: '地图管理', href: './admin-maps.html' },
        { label: '物品管理', href: './admin-items.html' }
        // 预留：NPC / 怪物模板等，加在这里
      ]
    }
  ];

  var meReq = null;

  /**
   * 当前登录账号信息 —— **全页只请求一次**（记忆化）。
   * 返回 PT.request 的原始结果 { ok, status, code, data, msg }，调用方按 status/ok 自行分支。
   * 不把它做成 reject：网络异常也resolve成一个失败结果，免得调用方要各自 catch。
   */
  function me() {
    if (!meReq) {
      meReq = PT.request('/api/user/me', { method: 'GET', credentials: 'include' })
        .catch(function () {
          return { ok: false, status: 0, code: undefined, data: null, msg: '' };
        });
    }
    return meReq;
  }

  function basename(path) {
    var s = String(path).split('?')[0].split('#')[0];
    var tail = s.substring(s.lastIndexOf('/') + 1);
    return tail || 'index.html';
  }

  function esc(s) {
    return String(s).replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }

  function renderItem(it, here) {
    var active = basename(it.href) === here;
    return '<a href="' + it.href + '" class="admin-nav-item'
      + (active ? ' admin-nav-item-active' : '') + '">' + esc(it.label) + '</a>';
  }

  function render(navEl, account) {
    var isAdmin = !!(account && account.webAdmin);
    var here = basename(window.location.pathname);
    var html = '';
    MENU.forEach(function (entry) {
      if (entry.adminOnly && !isAdmin) {
        return;
      }
      if (entry.href) {
        html += renderItem(entry, here);
        return;
      }
      html += '<span class="admin-nav-separator"></span>';
      html += '<span class="admin-nav-label">' + esc(entry.group) + '</span>';
      entry.items.forEach(function (it) {
        if (it.adminOnly && !isAdmin) {
          return;
        }
        html += renderItem(it, here);
      });
    });
    navEl.innerHTML = html;
  }

  function init() {
    var navEl = document.getElementById('ptNav');
    if (!navEl) {
      return;
    }
    // 两段渲染：先按"非管理员"出一份（不依赖请求，菜单不会因 /me 慢或失败而空白），
    // 拿到身份后按角色补上管理员项。失败方向是"少显示"而不是"多显示"。
    render(navEl, null);
    me().then(function (r) {
      render(navEl, r && r.ok ? r.data : null);
    });
  }

  window.PTNav = { me: me, MENU: MENU };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
