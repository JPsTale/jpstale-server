(function () {
  var userLabel = document.getElementById('meUserLabel');
  var accountLine = document.getElementById('meAccountLine');
  var logoutBtn = document.getElementById('meLogoutBtn');
  var adminNavMaps = document.getElementById('adminNavMaps');

  var changePwdForm = document.getElementById('changePasswordForm');
  var changePwdMsg = document.getElementById('meChangePwdMsg');
  var changePwdSubmitBtn = document.getElementById('changePasswordSubmitBtn');

  function redirectToLogin() {
    window.location.href = '/login.html';
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
    fetch('/api/user/me', {
      method: 'GET',
      credentials: 'include'
    })
      .then(function (res) {
        if (res.status === 401) {
          redirectToLogin();
          return null;
        }
        if (!res.ok) {
          return null;
        }
        return res.json();
      })
      .then(function (data) {
        if (!data) {
          if (accountLine) {
            accountLine.textContent = '加载失败，请稍后重试。';
          }
          return;
        }
        var name = data.accountName || '';
        if (userLabel) {
          userLabel.textContent = name + (data.webAdmin ? '（管理员）' : '');
        }
        if (accountLine) {
          accountLine.textContent = '当前登录账号：' + (name || '（未知）') +
            (data.webAdmin ? '（管理员账号）' : '');
        }
        if (adminNavMaps && data.webAdmin) {
          adminNavMaps.style.display = 'block';
        }
      });
  }

  if (logoutBtn) {
    logoutBtn.addEventListener('click', function () {
      fetch('/api/user/logout', {
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

      changePwdSubmitBtn.disabled = true;

      // 修改密码时仍按登录/注册的约定，使用当前账号名计算哈希。
      var accountName = (userLabel && userLabel.textContent) ? userLabel.textContent.split('（')[0] : '';
      var upperAccount = (accountName || '').toUpperCase();

      Promise.all([
        sha256Hex(upperAccount + ':' + oldPwd),
        sha256Hex(upperAccount + ':' + newPwd)
      ])
        .then(function (pair) {
          var oldHash = pair[0];
          var newHash = pair[1];
          return fetch('/api/user/change-password', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            credentials: 'include',
            body: JSON.stringify({ oldPassword: oldHash, newPassword: newHash })
          });
        })
        .then(function (res) { return res.json(); })
        .then(function (data) {
          if (!data || data.success !== true) {
            showChangePwdMsg((data && data.message) || '修改密码失败', true);
            return;
          }
          showChangePwdMsg(data.message || '密码修改成功', false);
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

