# 协议综合文档

**文档版本**：1.0
**创建日期**：2026-03-17
**目标**：完整记录客户端-服务端通信协议规范

## 1. 协议基础

### 1.1 包格式
```
[8字节包头] + [包体数据]

包头结构：
0-1   WORD   iLength        // 包总长度（包含8字节包头）
2     BYTE   iEncKeyIndex  // 加密密钥索引
3     BYTE   iEncrypted    // 是否已加密
4-7   DWORD  iHeader       // 包ID（见第2章定义）
```

### 1.2 编码规范
- **字节序**：小端序（Little Endian）
- **字符串**：C风格，定长数组，NULL结尾，UTF-8编码
- **布尔值**：BOOL = int（0=假，非0=真）

### 1.3 加密规则
- 登录包（PKTHDR_LoginUser）等关键包需要加密
- 加密使用iEncKeyIndex指定的密钥
- 客户端密码已在客户端计算SHA256哈希

## 2. 包ID定义（按功能分类）

### 2.1 认证相关（0x4848xxxx）
| 包名 | ID | 方向 | 描述 |
|------|-----|------|------|
| PKTHDR_LoginUser | 0x48480001 | C→S | 登录请求 |
| PKTHDR_AccountLoginCode | 0x48470023 | S→C | 登录响应 |
| PKTHDR_UserInfo | 0x48470086 | S→C | 用户信息（角色列表） |

### 2.2 世界认证（Login↔Game通信）
| 包名 | ID | 方向 | 描述 |
|------|-----|------|------|
| PKTHDR_WorldLoginToken | 0x48470084 | S→C | World登录令牌 |
| PKTHDR_WorldLoginAuth | 0x48470022 | C→S | World登录认证 |
| PKTHDR_SendToLoginServer | 0x6A6A0003 | S→S | 服务器间通信 |

### 2.3 通信相关（0x435Axxxx）
| 包名 | ID | 方向 | 描述 |
|------|-----|------|------|
| PKTHDR_Ping | 0x435A0007 | 双向 | 心跳包 |
| PKTHDR_PingLoginServer | 0x435A0008 | 双向 | 登录服务器心跳 |
| PKTHDR_Connected | 0x48470080 | S→C | 连接确认 |

### 2.4 服务器间通信（0x7Fxxxxxx）
| 包名 | ID | 方向 | 描述 |
|------|-----|------|------|
| PKTHDR_NetIdentifier | 0x7F000001 | S↔S | 服务器标识 |
| PKTHDR_NetPingPong | 0x7F00000C | S↔S | 服务器心跳 |
| PKTHDR_NetCharacterSync | 0x7F00000A | S↔S | 角色同步 |
| PKTHDR_NetPlayerWorldToken | 0x7F000040 | S↔S | 玩家World令牌 |

### 2.5 游戏相关（0x4847xxxx）
| 包名 | ID | 方向 | 描述 |
|------|-----|------|------|
| PKTHDR_Save | 0x484700E8 | C→S | 保存数据 |
| PKTHDR_SaveData | 0x48470020 | C→S | 大数据保存 |
| PKTHDR_Disconnect | 0x484700E6 | 双向 | 断开连接 |
| PKTHDR_Reconnect | 0x48478010 | 双向 | 重连请求 |
| PKTHDR_ReconnectLogin | 0x48478011 | 双向 | 重连登录 |

### 2.6 物品相关（0x4847xxxx）
| 包名 | ID | 方向 | 描述 |
|------|-----|------|------|
| PKTHDR_ThrowItem | 0x48470053 | C→S | 丢弃物品 |
| PKTHDR_SendWarehouse | 0x48470047 | S→C | 仓库数据 |
| PKTHDR_RecvWarehouse | 0x48470048 | C→S | 仓库请求 |
| PKTHDR_TradeData | 0x48470041 | 双向 | 交易数据 |

### 2.7 地图和角色（0x4847xxxx/0x4355xxxx）
| 包名 | ID | 方向 | 描述 |
|------|-----|------|------|
| PKTHDR_PlayDataChar | 0x48470013 | S→C | 其他玩家数据 |
| PKTHDR_UnitStatusContainer | 0x48470014 | S→C | 玩家状态 |
| PKTHDR_GameStatus | 0x48470018 | S→C | 游戏状态 |
| PKTHDR_SetExp | 0x43550002 | S→C | 设置经验 |

## 3. 关键包详解

### 3.1 登录流程相关

#### 3.1.1 PKTHDR_LoginUser（0x48480001）
**结构**：PacketLoginUser
```cpp
struct PacketLoginUser : Packet {
    DWORD      dwUnk[3];          // 未知字段
    char       szUserID[32];      // 用户名
    char       szPassword[65];    // SHA256哈希密码
    char       szMacAddr[20];     // MAC地址
    char       szPCName[32];      // PC名称
    DWORD      dwSerialHD;        // 硬盘序列号
    char       szVideoName[256];  // 显卡名称
    char       szHardwareID[40];  // 硬件ID
    UINT       uWidthScreen;      // 屏幕宽度
    UINT       uHeightScreen;     // 屏幕高度
    int        iSystemOS;         // 系统类型
    int        iVersion;          // 客户端版本
};
```
**大小**：477字节（不含8字节包头）

#### 3.1.2 PKTHDR_AccountLoginCode（0x48470023）
**结构**：PacketAccountLoginCode
```cpp
struct PacketAccountLoginCode : Packet {
    DWORD       dwReserved;         // 保留字段（通常为0）
    EAccountLogin iCode;            // 登录结果码
    int         iFailCode;          // 失败码（通常为1）
    char        szMessage[256];     // 错误消息
};
```
**错误码定义**：
```cpp
enum EAccountLogin {
    ACCOUNTLOGIN_Success = 1,
    ACCOUNTLOGIN_LoginPending = 0,
    ACCOUNTLOGIN_IncorrectName = -1,
    ACCOUNTLOGIN_IncorrectPassword = -2,
    ACCOUNTLOGIN_Banned = -3,
    ACCOUNTLOGIN_LoggedIn = -4,
    ACCOUNTLOGIN_Maintenance = -8,
    ACCOUNTLOGIN_AccountNotActive = -16,
    ACCOUNTLOGIN_WrongVersion = -17,
    ACCOUNTLOGIN_TempBanned = -18,
    // ... 其他
};
```

#### 3.1.3 PKTHDR_UserInfo（0x48470086）
**结构**：PacketUserInfo
```cpp
struct PacketUserInfo : Packet {
    char                szUserID[32];          // 用户名
    int                 CharCount;             // 角色数量
    _TRANS_CHAR_INFO    sCharacterData[6];    // 角色列表（最多6个）
};
```
**大小**：1476字节（不含8字节包头）

#### 3.1.4 _TRANS_CHAR_INFO结构
```cpp
struct _TRANS_CHAR_INFO {
    char    szName[32];      // 角色名
    char    szModelName[64]; // 身体模型
    char    szModelName2[64];// 头部模型
    DWORD   JobCode;         // 职业代码
    int     iLevel;          // 等级
    DWORD   Brood;           // 种族
    DWORD   dwArmorCode;     // 装备代码
    int     StartField;      // 起始地图
    int     PosX, PosZ;      // 坐标
    DWORD   dwTemp[13];      // 保留字段
};
```

### 3.2 World认证流程

#### 3.2.1 流程概述
1. 客户端登录Login Server成功，获得Ticket
2. Login Server通过内网告知Game Server该Ticket
3. 客户端使用Ticket连接Game Server
4. Game Server验证Ticket，允许进入游戏

#### 3.2.2 PKTHDR_WorldLoginToken（0x48470084）
```cpp
struct PacketWorldLoginToken : Packet {
    char TokenPass[65];     // 令牌密码
};
```

#### 3.2.3 PKTHDR_WorldLoginAuth（0x48470022）
```cpp
struct PacketWorldLoginAuth : Packet {
    char Token[65];         // 令牌
    char TokenPass[65];     // 令牌密码
};
```

### 3.3 心跳机制

#### 3.3.1 PKTHDR_Ping（0x435A0007）
**结构**：PacketPing（通常无包体）
**用途**：维持连接，防止超时
**频率**：约每30秒一次

### 3.4 服务器间通信

#### 3.4.1 PKTHDR_NetIdentifier（0x7F000001）
**用途**：服务器启动时相互标识
**包体**：服务器类型和ID信息

#### 3.4.2 PKTHDR_NetPlayerWorldToken（0x7F000040）
**用途**：Login Server通知Game Server玩家的Ticket
**包体**：账号名、Ticket、时间戳等

## 4. 登录完整时序

```
客户端                    Login Server               Game Server
  |                           |                         |
  |-- PKTHDR_LoginUser ------>|                         |
  |                           |---(查询数据库)---------->|
  |                           |<--(返回用户信息)---------|
  |<-- PKTHDR_AccountLoginCode -|                         |
  |<-- PKTHDR_UserInfo -------|                         |
  |                           |---(发送Ticket)--------->|
  |                           |                         |
  |                                                      |
  |================断开登录连接，连接游戏服务器====================|
  |                                                      |
  |-- PKTHDR_WorldLoginAuth -->|                         |
  |                           |                         |
  |<-- PKTHDR_Connected -------|                         |
```

## 5. Java实现要点

### 5.1 包定义原则
```java
// 所有包继承Packet基类
public abstract class Packet {
    protected short length;
    protected byte encKeyIndex;
    protected byte encrypted;
    protected PacketHeader pktHeader;

    // 子类实现
    protected abstract void readBody(ByteBuffer in);
    protected abstract void writeBody(ByteBuffer out);
}
```

### 5.2 枚举定义原则
```java
// 所有枚举需要fromValue方法
public enum EAccountLogin {
    SUCCESS(1),
    FAILURE(-1);

    public static EAccountLogin fromValue(int value) {
        for (EAccountLogin e : values()) {
            if (e.value == value) return e;
        }
        return DEFAULT;
    }
}
```

### 5.3 字符串处理
```java
// 定长C字符串处理
public static String readCString(ByteBuffer in, int maxLen) {
    byte[] buf = new byte[maxLen];
    in.get(buf);
    int end = 0;
    while (end < maxLen && buf[end] != 0) end++;
    return new String(buf, 0, end, StandardCharsets.UTF_8);
}
```

## 6. 安全考虑

### 6.1 加密包处理
- 敏感包必须加密传输
- 加密使用流密码或块密码
- 密钥定期更换

### 6.2 防作弊措施
- 客户端版本验证
- 硬件指纹记录
- IP和MAC地址封禁
- 异常行为检测

### 6.3 频率限制
- 登录请求限制
- 包发送频率限制
- 防止DDoS攻击

## 7. 待实现包列表

### 7.1 优先级1（基础功能）
- [ ] PKTHDR_CreateCharacter（创建角色）
- [ ] PKTHDR_DeleteCharacter（删除角色）
- [ ] PKTHDR_SelectCharacter（选择角色）
- [ ] PKTHDR_PlayData（进入游戏）

### 7.2 优先级2（核心玩法）
- [ ] PKTHDR_MoveRequest（移动请求）
- [ ] PKTHDR_SkillRequest（技能请求）
- [ ] PKTHDR_AttackRequest（攻击请求）
- [ ] PKTHDR_PickupItem（拾取物品）

### 7.3 优先级3（高级功能）
- [ ] PKTHDR_ClanCreate（创建公会）
- [ ] PKTHDR_ClanJoin（加入公会）
- [ ] PKTHDR_QuestAccept（接受任务）
- [ ] PKTHDR_TradeRequest（交易请求）

## 8. 调试建议

### 8.1 日志记录
- 记录所有收发包（开发环境）
- 包ID、大小、来源IP、处理时间
- 异常包的详细信息

### 8.2 测试工具
- 独立的包构造器
- 模拟客户端测试工具
- 压力测试工具

### 8.3 兼容性验证
- 与原版服务端对比包内容
- 确保字节序一致
- 验证加密解密正确性