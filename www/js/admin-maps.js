(function () {
  var tbody = document.getElementById('mapsTableBody');
  var searchInput = document.getElementById('searchInput');
  var refreshBtn = document.getElementById('refreshBtn');
  var errorBox = document.getElementById('errorBox');
  var userLabel = document.getElementById('adminUserLabel');
  var logoutBtn = document.getElementById('logoutBtn');

  var allMaps = [];

  function showError(text) {
    if (!errorBox) return;
    errorBox.textContent = text;
    errorBox.classList.remove('hidden');
  }

  function hideError() {
    if (!errorBox) return;
    errorBox.classList.add('hidden');
  }

  function renderTable(list) {
    if (!tbody) return;
    tbody.innerHTML = '';
    if (!list || list.length === 0) {
      var tr = document.createElement('tr');
      var td = document.createElement('td');
      td.colSpan = 5;
      td.textContent = '暂无数据';
      td.style.textAlign = 'center';
      tr.appendChild(td);
      tbody.appendChild(tr);
      return;
    }

    list.forEach(function (m) {
      var tr = document.createElement('tr');

      var tdId = document.createElement('td');
      tdId.textContent = m.id;
      tr.appendChild(tdId);

      var tdName = document.createElement('td');
      tdName.textContent = m.name || '';
      tr.appendChild(tdName);

      var tdShort = document.createElement('td');
      tdShort.textContent = m.shortName || '';
      tr.appendChild(tdShort);

      var tdType = document.createElement('td');
      tdType.textContent = m.typeMap || '';
      tr.appendChild(tdType);

      var tdLevel = document.createElement('td');
      tdLevel.textContent = m.levelReq != null ? m.levelReq : '';
      tr.appendChild(tdLevel);

      tbody.appendChild(tr);
    });
  }

  function applyFilter() {
    var keyword = (searchInput && searchInput.value || '').trim().toLowerCase();
    if (!keyword) {
      renderTable(allMaps);
      return;
    }
    var filtered = allMaps.filter(function (m) {
      var name = (m.name || '').toLowerCase();
      var shortName = (m.shortName || '').toLowerCase();
      return name.indexOf(keyword) !== -1 || shortName.indexOf(keyword) !== -1;
    });
    renderTable(filtered);
  }

  function redirectToLogin() {
    window.location.href = '/login.html';
  }

  function loadMaps() {
    hideError();
    fetch('/api/admin/maps', {
      method: 'GET',
      credentials: 'include'
    })
      .then(function (res) {
        if (res.status === 401) {
          redirectToLogin();
          return null;
        }
        if (res.status === 403) {
          showError('当前账号无管理员权限，无法访问地图管理。');
          return null;
        }
        if (!res.ok) {
          showError('加载失败：' + res.status);
          return null;
        }
        return res.json();
      })
      .then(function (data) {
        if (!data) {
          renderTable([]);
          return;
        }
        if (!Array.isArray(data)) {
          // 兼容包装结构 { code, data }
          if (data && Array.isArray(data.data)) {
            allMaps = data.data;
          } else {
            allMaps = [];
          }
        } else {
          allMaps = data;
        }
        applyFilter();
      })
      .catch(function () {
        showError('加载失败，请稍后重试');
        renderTable([]);
      });
  }

  function loadCurrentUser() {
    if (!userLabel) {
      loadMaps();
      return;
    }
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
        if (data && data.accountName) {
          userLabel.textContent = data.accountName + (data.webAdmin ? '（管理员）' : '');
        }
      })
      .finally(function () {
        loadMaps();
      });
  }

  if (searchInput) {
    searchInput.addEventListener('input', function () {
      applyFilter();
    });
  }

  if (refreshBtn) {
    refreshBtn.addEventListener('click', function () {
      loadMaps();
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

  loadCurrentUser();
})();