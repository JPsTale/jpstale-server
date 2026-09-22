(function () {
  var form = document.getElementById('loginForm');
  var msgEl = document.getElementById('loginMsg');
  var submitBtn = document.getElementById('loginSubmitBtn');

  function showMsg(text, isError) {
    msgEl.textContent = text;
    msgEl.className = 'msg ' + (isError ? 'error' : 'success');
    msgEl.classList.remove('hidden');
  }

  function hideMsg() {
    msgEl.classList.add('hidden');
  }

  // 与注册/登录约定一致：SHA256(UPPERCASE(account)+":"+明文密码) 十六进制大写
  // 用 js-sha256（纯 JS）而非 crypto.subtle —— 后者只在安全上下文（https/localhost）可用
  function sha256Hex(str) {
    return Promise.resolve(sha256(str).toUpperCase());
  }

  if (!form) {
    return;
  }

  form.addEventListener('submit', function (e) {
    e.preventDefault();
    hideMsg();

    var account = (document.getElementById('loginAccount').value || '').trim();
    var password = document.getElementById('loginPassword').value;

    if (!account) {
      showMsg('请输入账号', true);
      return;
    }
    if (!password) {
      showMsg('请输入密码', true);
      return;
    }

    submitBtn.disabled = true;

    var input = account.toUpperCase() + ':' + password;
    sha256Hex(input)
      .then(function (passwordHash) {
        return PT.request('/api/user/login', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          credentials: 'include',
          body: JSON.stringify({ account: account, password: passwordHash })
        });
      })
      .then(function (r) {
        if (!r.ok) {
          showMsg(PT.msgOf(r.code, '登录失败，请检查账号或密码'), true);
          return;
        }
        showMsg('登录成功，即将进入用户中心。', false);
        setTimeout(function () {
          window.location.href = './me.html';
        }, 600);
      })
      .catch(function () {
        showMsg('网络错误，请稍后重试', true);
      })
      .finally(function () {
        submitBtn.disabled = false;
      });
  });
})();

