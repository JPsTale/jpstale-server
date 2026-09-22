/*
 * 静态页的 i18n：**服务端只发 translate key，文案在这里翻译**。
 *
 * 为什么要这个文件（用户 2026-09-21）：我把属性名（"火抗性"等）硬编码进了服务端 Java，
 * 用户指出"我的 web 页面后面怎么搞 i18n"。服务端现在只发 key（与 `Result.msg` 的既有约定一致），
 * 文案集中在 `static/i18n/{zh,en}.json`。
 *
 * 约定（刻意与游戏客户端 `jpstale-client/src/i18n/index.ts` 保持同一套，将来可合表）：
 *   · 语言选择：`localStorage['locale']`，否则按 `navigator.language` 前缀
 *   · `t(key, params)` 走点分路径查表，支持 `{name}` 占位替换
 *   · **查不到不静默**：先回退到 zh，再回退为 key 本身并 `console.warn`（看得见，不是假装有文案）
 *   · HTML 里的静态文案用 `data-i18n` / `data-i18n-placeholder` / `data-i18n-title` 标注
 *   · ⚠ 表里的属性名 key 与客户端同名同义（`itemtip.*`），改名时两边一起改
 *
 * 用法：页面在启动链里 `PTi18n.load()`（失败也不阻塞：key 会原样显示，故障可见）。
 */
(function () {
  var LOCALES = ['zh', 'en'];
  var FALLBACK = 'zh';
  var tables = {};
  var warned = {};

  function pickLocale() {
    var saved = null;
    try {
      saved = localStorage.getItem('locale');
    } catch (e) {
      // 隐私模式等取不到 localStorage —— 按浏览器语言走，不算错
    }
    if (saved && LOCALES.indexOf(saved) !== -1) {
      return saved;
    }
    return String(navigator.language || FALLBACK).toLowerCase().indexOf('zh') === 0 ? 'zh' : 'en';
  }

  var locale = pickLocale();

  function walk(table, key) {
    var val = table;
    var parts = String(key).split('.');
    for (var i = 0; i < parts.length; i++) {
      if (val && typeof val === 'object') {
        val = val[parts[i]];
      } else {
        return null;
      }
    }
    return typeof val === 'string' ? val : null;
  }

  function lookup(key) {
    var msg = walk(tables[locale], key);
    if (msg === null && locale !== FALLBACK) {
      msg = walk(tables[FALLBACK], key);
    }
    return msg;
  }

  /** 查文案；缺 key 时回退为 key 本身并警告一次（不静默）。 */
  function t(key, params) {
    var msg = lookup(key);
    if (msg === null) {
      if (!warned[key]) {
        warned[key] = true;
        console.warn('[i18n] 缺文案 key=' + key + '（已原样显示 key）');
      }
      msg = key;
    }
    if (params) {
      Object.keys(params).forEach(function (k) {
        msg = msg.split('{' + k + '}').join(String(params[k]));
      });
    }
    return msg;
  }

  /** 把 DOM 里标注过的静态文案填上（页面已渲染的子树也可传入）。 */
  function apply(root) {
    var scope = root || document;
    scope.querySelectorAll('[data-i18n]').forEach(function (el) {
      el.textContent = t(el.getAttribute('data-i18n'));
    });
    scope.querySelectorAll('[data-i18n-placeholder]').forEach(function (el) {
      el.setAttribute('placeholder', t(el.getAttribute('data-i18n-placeholder')));
    });
    scope.querySelectorAll('[data-i18n-title]').forEach(function (el) {
      el.setAttribute('title', t(el.getAttribute('data-i18n-title')));
    });
  }

  /** 载入语言表（全部 locale 都载，便于运行时切换后无需再发请求）。 */
  function load() {
    return Promise.all(LOCALES.map(function (loc) {
      return fetch('./i18n/' + loc + '.json', { cache: 'no-cache' })
        .then(function (r) {
          if (!r.ok) {
            throw new Error('HTTP ' + r.status);
          }
          return r.json();
        })
        .then(function (json) {
          tables[loc] = json;
        })
        .catch(function (e) {
          // 载不到就留空表 —— t() 会回退成 key，故障可见（不假装有文案）
          console.warn('[i18n] 语言表加载失败 ' + loc + '：' + e.message);
          tables[loc] = {};
        });
    })).then(function () {
      apply(document);
      return locale;
    });
  }

  /**
   * 取某个命名空间下的所有 key（当前语言）。
   * 用途：把"本地化名"反查回"内名称"这类场景（NPC 名就是以**内名称为键**的 `npcName.*`）。
   */
  function keys(namespace) {
    var node = tables[locale];
    if (!node) {
      return [];
    }
    var parts = String(namespace).split('.');
    for (var i = 0; i < parts.length && node; i++) {
      node = node[parts[i]];
    }
    return (node && typeof node === 'object') ? Object.keys(node) : [];
  }

  /** 该 key 在当前语言下有没有文案（没有 = t() 会原样回退成 key）。 */
  function has(key) {
    return lookup(key) !== null;
  }

  window.PTi18n = {
    t: t, apply: apply, load: load, keys: keys, has: has,
    getLocale: function () { return locale; }
  };
})();
