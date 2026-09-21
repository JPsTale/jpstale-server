(function () {
  var userLabel = document.getElementById('meUserLabel');
  var accountLine = document.getElementById('meAccountLine');
  var logoutBtn = document.getElementById('meLogoutBtn');

  var changePwdForm = document.getElementById('changePasswordForm');
  var changePwdMsg = document.getElementById('meChangePwdMsg');
  var changePwdSubmitBtn = document.getElementById('changePasswordSubmitBtn');

  /**
   * 当前账号名，**来自 /api/user/me 的数据字段**。
   * 改密码时要拿它算哈希 —— 不能像以前那样从 `userLabel.textContent` 反推：
   * 那是"用界面文案当数据"，文案一改（或还没加载出来时显示"未登录"）就会静默算错哈希。
   */
  var accountName = '';

  function redirectToLogin() {
    window.location.href = './login.html';
  }

  function showChangePwdMsg(text, isError) {
    if (!changePwdMsg) return;
    changePwdMsg.textContent = text;
    changePwdMsg.className = 'msg ' + (isError ? 'error' : 'success');
    changePwdMsg.classList.remove('hidden');
  }

  function hideChangePwdMsg() {
    if (!changePwdMsg) return;
    changePwdMsg.classList.add('hidden');
  }

  function sha256Hex(str) {
    return crypto.subtle.digest('SHA-256', new TextEncoder().encode(str))
      .then(function (buf) {
        var arr = new Uint8Array(buf);
        var hex = '';
        for (var i = 0; i < arr.length; i++) {
          hex += ('0' + arr[i].toString(16)).slice(-2).toUpperCase();
        }
        return hex;
      });
  }

  function loadMe() {
    // 复用 nav.js 记忆化的 /api/user/me（同一页不重复请求；菜单也用同一份结果）
    PTNav.me()
      .then(function (r) {
        if (r.status === 401) {
          redirectToLogin();
          return;
        }
        if (!r.ok) {
          if (accountLine) {
            accountLine.textContent = '加载失败，请稍后重试。';
          }
          return;
        }
        var data = r.data || {};
        accountName = data.accountName || '';
        var name = accountName;
        if (userLabel) {
          userLabel.textContent = name + (data.webAdmin ? '（管理员）' : '');
        }
        if (accountLine) {
          accountLine.textContent = '当前登录账号：' + (name || '（未知）') +
            (data.webAdmin ? '（管理员账号）' : '');
        }
      });
  }

  if (logoutBtn) {
    logoutBtn.addEventListener('click', function () {
      fetch(PT.apiUrl('/api/user/logout'), {
        method: 'POST',
        credentials: 'include'
      }).finally(function () {
        redirectToLogin();
      });
    });
  }

  if (changePwdForm) {
    changePwdForm.addEventListener('submit', function (e) {
      e.preventDefault();
      hideChangePwdMsg();

      var oldPwd = document.getElementById('oldPassword').value;
      var newPwd = document.getElementById('newPassword').value;
      var newPwd2 = document.getElementById('newPasswordConfirm').value;

      if (!oldPwd) {
        showChangePwdMsg('请输入当前密码', true);
        return;
      }
      if (!newPwd) {
        showChangePwdMsg('请输入新密码', true);
        return;
      }
      if (newPwd !== newPwd2) {
        showChangePwdMsg('两次输入的新密码不一致', true);
        return;
      }

      if (!accountName) {
        showChangePwdMsg('账号信息尚未加载，请刷新页面后重试', true);
        return;
      }

      changePwdSubmitBtn.disabled = true;

      // 按登录/注册的约定，用当前账号名算哈希。accountName 来自 /me 的数据字段（见文件头的说明）
      var upperAccount = accountName.toUpperCase();

      Promise.all([
        sha256Hex(upperAccount + ':' + oldPwd),
        sha256Hex(upperAccount + ':' + newPwd)
      ])
        .then(function (pair) {
          var oldHash = pair[0];
          var newHash = pair[1];
          return PT.request('/api/user/change-password', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'include',
            body: JSON.stringify({ oldPassword: oldHash, newPassword: newHash })
          });
        })
        .then(function (r) {
          if (!r.ok) {
            showChangePwdMsg(PT.msgOf(r.code, '修改密码失败'), true);
            return;
          }
          showChangePwdMsg('密码修改成功', false);
          changePwdForm.reset();
        })
        .catch(function () {
          showChangePwdMsg('网络错误，请稍后重试', true);
        })
        .finally(function () {
          changePwdSubmitBtn.disabled = false;
        });
    });
  }

  loadMe();
})();

