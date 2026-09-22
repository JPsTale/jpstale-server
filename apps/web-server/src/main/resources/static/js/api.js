/*
 * 静态页的共用请求层（页面已随 2026-09-21 迁移搬进 web-server 的 jar，不再是 www/ + nginx）。
 *
 * 服务端 /api/** 统一返回 Result：{ code, msg, data }，其中 msg 是一个 translate key
 * （如 "error.web.loginFailed"）—— 游戏客户端会用 i18n 翻译它，而这些静态页没有 i18n，
 * 所以在这里按 code 查一张中文小表。
 *
 * 表里没有的 code 不静默：回退到调用方给的兜底文案，并 console.warn 出 code 与 key，
 * 免得"服务端加了新错误码"在页面上表现为一句看不懂的原文。
 *
 * ---------------------------------------------------------------------------
 * 路径约定（重要）
 * ---------------------------------------------------------------------------
 * 页面与 /api 现在同属一个 context-path（Ubuntu 部署是 /pt，docker 那套是空）。
 * 调用方一律按 **根绝对形式** 写路径（'/api/user/login'），由本文件的 apiUrl() 按
 * window.PT_API_BASE 归一化成相对 URL —— 于是同一份代码在两种 context-path 下都通。
 *
 *   window.PT_API_BASE 默认 './'：
 *     · 根层页面（/pt/login.html、/pt/）用默认值即可，'./api/...' 会解析成 '/pt/api/...'；
 *     · 页面若放在**子目录**（如 /pt/maps/index.html），必须在加载本文件**之前**
 *       声明 window.PT_API_BASE = '../'，否则 './api/...' 会被解析成 '/pt/maps/api/...'。
 *
 * ⚠ 为什么不做"自动推算"：从 location.pathname 反推 context 根只在"页面与 /api 同层"时成立，
 *   页面一嵌深就错，而且错得静默（症状是 404，看起来像"服务端没这个接口"）。
 *   显式一行，读代码就能看出该页假设自己在哪一层。
 */
(function () {
  var MESSAGES = {
    10101: '账号或密码错误',
    10102: '账号已被封禁或停用',
    10105: '当前密码不正确',
    10106: '新密码不能与当前密码相同',
    10202: '未登录或登录已失效',
    10203: '没有权限执行该操作',
    10300: '请求参数无效',
    10404: '用户不存在',
    10405: '地图不存在',
    10406: '物品不存在',
    10407: '怪物不存在',
    10408: 'NPC 不存在',
    10409: '账号已存在',
    10410: '该邮箱已被注册',
    10501: '角色不在公会中',
    10502: '公会名已存在'
  };

  var warned = {};

  /** 相对基数。根层页面用默认 './'；子目录页面在加载本文件前覆盖 window.PT_API_BASE。 */
  var API_BASE = (typeof window !== 'undefined' && window.PT_API_BASE) || './';

  /**
   * 把 '/api/...' 这类以根开始的路径按 API_BASE 变成相对 URL。
   * 已是绝对 URL（http://、https://、//host）的原样返回。
   *
   * 注意不能直接拼：'./' + '/api' 会得到 './/api'，而 '//api' 是**协议相对 URL**
   * （主机名会被当成 api），所以这里先剥掉开头的斜杠。
   */
  function apiUrl(url) {
    var s = String(url);
    if (/^[a-z][a-z0-9+.-]*:\/\//i.test(s) || s.indexOf('//') === 0) return s;
    return API_BASE + s.replace(/^\/+/, '');
  }

  function msgOf(code, fallback) {
    if (MESSAGES[code]) return MESSAGES[code];
    if (code !== undefined && !warned[code]) {
      warned[code] = true;
      console.warn('[api] 未收录的错误码 code=' + code + '，已回退到兜底文案');
    }
    return fallback || '请求失败，请稍后重试';
  }

  /**
   * 统一请求。返回 { ok, status, code, data, msg }：
   *   ok     = HTTP 成功且业务 code === 200（调用方只需判这一个）
   *   status = HTTP 状态码（401/403 这类要单独处理时用）
   *   data   = 业务数据（登录 token、列表等）
   *   msg    = 服务端返回的 translate key（给 msgOf 当兜底用）
   *
   * url 按根绝对形式传（'/api/...'）；也接受 apiUrl() 已处理过的相对 URL。
   */
  function request(url, opts) {
    return fetch(apiUrl(url), opts).then(function (res) {
      return res.json().catch(function () { return null; }).then(function (body) {
        return {
          ok: res.ok && !!body && body.code === 200,
          status: res.status,
          code: body ? body.code : undefined,
          data: body ? body.data : null,
          msg: body ? body.msg : ''
        };
      });
    });
  }

  window.PT = { msgOf: msgOf, request: request, apiUrl: apiUrl, apiBase: API_BASE };
})();
