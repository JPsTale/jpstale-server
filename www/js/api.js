/*
 * www 静态页的共用请求层。
 *
 * 服务端 /api/** 统一返回 Result：{ code, msg, data }，其中 msg 是一个 translate key
 * （如 "error.web.loginFailed"）—— 游戏客户端会用 i18n 翻译它，而这些静态页没有 i18n，
 * 所以在这里按 code 查一张中文小表。
 *
 * 表里没有的 code 不静默：回退到调用方给的兜底文案，并 console.warn 出 code 与 key，
 * 免得"服务端加了新错误码"在页面上表现为一句看不懂的原文。
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
    10409: '账号已存在',
    10410: '该邮箱已被注册',
    10501: '角色不在公会中',
    10502: '公会名已存在'
  };

  var warned = {};

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
   */
  function request(url, opts) {
    return fetch(url, opts).then(function (res) {
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

  window.PT = { msgOf: msgOf, request: request };
})();
