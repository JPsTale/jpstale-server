# 登录流程分析文档

**分析日期**：2026-03-17
**分析目标**：深入理解C++服务端的登录处理逻辑

## 1. 登录流程概览

### 1.1 整体流程
```
客户端 → LoginServer(10009) → 数据库验证 → 返回用户信息和服务器列表
```

### 1.2 关键组件
- **PacketServer** - 包分发器（packetserver.cpp）
- **AccountServer** - 登录处理器（accountserver.cpp）
- **数据库** - PostgreSQL/SQL Server（UserInfo表）

## 2. 详细处理流程

### 2.1 包接收与分发（packetserver.cpp）

```cpp
switch (psPacket->iHeader) {
    case PKTHDR_LoginUser:
        // 1. 验证版本号
        if (p->iLength != sizeof(PacketLoginUser) || p->iVersion != GAME_VERSION) {
            // 返回版本错误
            PacketAccountLoginCode s;
            s.iCode = ACCOUNTLOGIN_WrongVersion;
            SENDPACKET(pcUser, &s, TRUE);
        } else {
            // 2. 调用AccountServer处理
            ACCOUNTSERVER->PHAccountLogin(pcUser, p);
        }
        break;
}
```

### 2.2 登录队列处理（PHAccountLogin）

```cpp
void AccountServer::PHAccountLogin(User *pcUser, PacketLoginUser *psPacket) {
    // 1. 构造AccountLogin结构
    AccountLogin al;
    STRINGCOPY(al.szAccountName, psPacket->szUserID);
    STRINGCOPY(al.szPassword, psPacket->szPassword);
    // ... 复制其他字段

    // 2. 加入登录队列（异步处理）
    if (!AddAccountLogin(pcUser, al)) {
        iCode = ACCOUNTLOGIN_LoginPending;
        // 返回队列已满错误
    }
}
```

### 2.3 登录处理核心逻辑（ProcessAccountLogin）

#### 2.3.1 基础验证
```cpp
// 1. 账号密码不能为空
if (pszAccountName[0] == 0) {
    iCode = ACCOUNTLOGIN_IncorrectName;
} else if (pszPassword[0] == 0) {
    iCode = ACCOUNTLOGIN_IncorrectPassword;
}

// 2. 检查是否已登录
if (USERDATABYACCOUNTNAME(pszAccountName) != NULL) {
    iCode = ACCOUNTLOGIN_LoggedIn;
}
```

#### 2.3.2 数据库查询
```cpp
SQLUser *psSqlUser = new SQLUser();
if (ACCOUNTSERVER->GetSQLUserInfoData(pszAccountName, psSqlUser)) {
    // 验证账号状态
    int iAccountFlag = psSqlUser->iFlag;

    // 检查账号状态标志
    if (iAccountFlag == ACCOUNTFLAG_NotExist) {
        iCode = ACCOUNTLOGIN_IncorrectName;
    } else if (!(ACCOUNTFLAG_Activated & iAccountFlag)) {
        iCode = ACCOUNTLOGIN_AccountNotActive;
    }
    // ... 其他状态检查
}
```

#### 2.3.3 账号状态标志
```
Bit Position | Flag                    | Meaning
------------|------------------------|-------------------------
0           | ACCOUNTFLAG_Mule        | 多开账号
1           | ACCOUNTFLAG_Activated   | 已激活（必须为1）
2           | ACCOUNTFLAG_EventGM     | 活动GM
3           | ACCOUNTFLAG_Supporter    | 支持者
4           | ACCOUNTFLAG_GameMaster  | GM标识
5           | ACCOUNTFLAG_AcceptedLatestTOA | 接受最新协议
6           | ACCOUNTFLAG_Approved    | 已批准
7           | ACCOUNTFLAG_MustConfirm | 需要确认
8           | ACCOUNTFLAG_GMIPRequired | 需要GM IP
```

正常的Flag值应该是 **114** (0b01110010)

#### 2.3.4 封禁检查
```cpp
// 永久封禁
if (psSqlUser->iBanStatus == EBanStatus::BANSTATUS_Banned) {
    iCode = ACCOUNTLOGIN_Banned;
}
// 临时封禁
else if (psSqlUser->iBanStatus == EBanStatus::BANSTATUS_TempBanned) {
    // 检查是否过期
    if (dwSystemTime >= dwUnBanExpiryTime) {
        // 自动解封
        iCode = ACCOUNTLOGIN_LoginPending;
    } else {
        iCode = ACCOUNTLOGIN_TempBanned;
    }
}
```

#### 2.3.5 密码验证
```cpp
// 直接字符串比较（密码已经是SHA256哈希）
if (STRINGCOMPARE(pszPassword, psSqlUser->szPassword)) {
    iCode = ACCOUNTLOGIN_Success;
} else {
    iCode = ACCOUNTLOGIN_IncorrectPassword;
}
```

**注意**：客户端发送的密码已经是SHA256哈希值，格式为：
```
SHA256(UPPERCASE(账号) + ":" + 明文密码)
```

### 2.4 登录成功处理（OnLoginSuccess）

```cpp
BOOL AccountServer::OnLoginSuccess(UserData *pcUserData) {
    // 1. 构造用户信息包
    PacketUserInfo sPacketUserInfoLogin;
    sPacketUserInfoLogin.iLength = sizeof(PacketUserInfo);
    sPacketUserInfoLogin.iHeader = PKTHDR_UserInfo;
    STRINGCOPY(sPacketUserInfoLogin.szUserID, pcUserData->szAccountName);

    // 2. 查询角色列表（最多6个）
    String query = "SELECT TOP 6 * FROM CharacterInfo WHERE AccountName=? ORDER BY Experience DESC";

    // 3. 从.chr文件读取角色数据
    for (int i = 0; i < 6; i++) {
        // 读取 Data/Character/{角色名}.chr 文件
        FILE *pFile = fopen(szPathBuffer, "rb");
        if (pFile) {
            fread(&sPacketCharacterDataLogin, sizeof(PacketCharacterRecordData), 1, pFile);
            // 转换数据格式
            // ...
        }
    }

    // 4. 发送用户信息包
    SENDPACKET(USERDATATOUSER(pcUserData), &sPacketUserInfoLogin);
}
```

## 3. Ticket机制与服务器列表

### 3.1 Ticket生成
```cpp
// 登录成功后生成随机Ticket
pcUserData->iTicket = Dice::RandomI(1, 1000);
```

### 3.2 服务器列表发送
```cpp
// PHServerList函数负责发送服务器列表和Ticket
Server::GetInstance()->PHServerList(pcSocketData, pcUserData->iTicket);
```

## 4. 错误码定义

| 错误码 | 含义 | 说明 |
|--------|------|------|
| ACCOUNTLOGIN_Success | 0 | 登录成功 |
| ACCOUNTLOGIN_IncorrectName | 1 | 账号不存在 |
| ACCOUNTLOGIN_IncorrectPassword | 2 | 密码错误 |
| ACCOUNTLOGIN_LoggedIn | 3 | 账号已登录 |
| ACCOUNTLOGIN_WrongVersion | 4 | 版本不匹配 |
| ACCOUNTLOGIN_AccountNotActive | 5 | 账号未激活 |
| ACCOUNTLOGIN_Banned | 6 | 永久封禁 |
| ACCOUNTLOGIN_TempBanned | 7 | 临时封禁 |
| ACCOUNTLOGIN_LoginPending | 8 | 队列处理中 |
| ACCOUNTLOGIN_Maintenance | 9 | 服务器维护中 |

## 5. Java端实现要点

### 5.1 必须实现的关键函数
1. **接收LoginUser包**
   - 验证包长度和版本号
   - 异步处理登录请求

2. **ProcessAccountLogin等效逻辑**
   - 账号状态验证
   - 密码验证（直接字符串比较）
   - 封禁状态检查

3. **OnLoginSuccess等效逻辑**
   - 查询角色列表
   - 发送PacketUserInfo
   - 生成Ticket

### 5.2 数据库表结构
```sql
UserInfo表：
- AccountName (账号名)
- Password (SHA256哈希)
- Flag (账号状态标志)
- BanStatus (封禁状态)
- GameMasterType (GM标识)
- GameMasterLevel (GM等级)
- IsMuted (是否禁言)
- UnbanDate (解封日期)
- UnmuteDate (解禁日期)
```

### 5.3 Java实现建议
```java
// 异步处理登录请求
@Async
public CompletableFuture<EAccountLogin> processLogin(PacketLoginUser loginPacket,
                                                  UserConnection connection) {
    // 1. 基础验证
    // 2. 数据库查询
    // 3. 状态检查
    // 4. 密码验证
    // 5. 返回结果
}

// 登录成功处理
private void onLoginSuccess(UserConnection connection, SQLUser userInfo) {
    // 1. 查询角色列表
    // 2. 构造UserInfo包
    // 3. 生成随机Ticket
    // 4. 发送响应
}
```

## 6. 性能考虑

1. **登录队列**：防止大量并发登录请求
2. **异步处理**：避免阻塞网络线程
3. **缓存机制**：缓存账号基本信息
4. **防刷机制**：限制同一IP的登录频率

## 7. 安全建议

1. **登录失败日志**：记录所有登录尝试
2. **暴力破解防护**：限制失败次数
3. **IP封禁**：支持IP和硬件ID封禁
4. **密码强度**：虽然使用SHA256，但仍建议使用更安全的算法