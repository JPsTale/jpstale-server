# 服务器间通信机制分析

**文档版本**：1.0
**创建日期**：2026-03-17
**分析目标**：理解Login Server与Game Server之间的内部通信机制

## 1. 通信架构概述

### 1.1 通信流向
```
Login Server (10009) ←→ Game Server (10007)
     ↓                      ↓
     └───── 内网通信 ───────┘
```

### 1.2 通信基础
- **协议**：TCP，使用相同的包格式（8字节包头+包体）
- **包ID范围**：0x7F000000 - 0x7FFFFFF（Net前缀的包）
- **认证机制**：InterServerAuth标志

## 2. 服务器连接建立

### 2.1 启动流程
1. **Login Server启动**
   - 监听10009端口（客户端连接）
   - 准备接受Game Server连接

2. **Game Server启动**
   - 监听10007端口（客户端连接）
   - 主动连接Login Server内网端口
   - 发送认证包建立连接

### 2.2 NetConnection机制
```cpp
class NetConnection {
    SocketData* pcSocketData;    // Socket连接
    bool bConnected;             // 连接状态
    bool bAuthed;                // 是否已认证
};
```

### 2.3 ConnectAll函数流程
```cpp
void NetServer::ConnectAll() {
    // 1. 遍历所有配置的服务器连接
    for (each NetConnection) {
        // 2. 尝试连接
        nc->Connect();
    }

    // 3. 等待连接建立
    Sleep(100);

    // 4. 发送标识包
    for (each connected NetConnection) {
        SENDPACKET(nc, &identifierPacket);
    }
}
```

## 3. 关键通信包

### 3.1 PKTHDR_NetIdentifier（0x7F000001）
**用途**：服务器间相互标识
**方向**：双向
**包体**：服务器信息（名称、类型、ID等）

### 3.2 PKTHDR_NetPingPong（0x7F00000C）
**用途**：保持服务器间连接活跃
**频率**：约每30秒
**包体**：时间戳

### 3.3 PKTHDR_NetPlayerWorldToken（0x7F000040）
**用途**：Login Server通知Game Server玩家的登录凭证
**结构**：
```cpp
struct PacketNetPlayerWorldToken : Packet {
    char Token[65];      // 客户端令牌
    char TokenPass[65];  // 令牌密码
};
```

### 3.4 PKTHDR_NetCharacterSync（0x7F00000A）
**用途**：同步角色基本信息
**包体**：角色名、等级、位置等

### 3.5 PKTHDR_NetUsersOnline（0x7F000015）
**用途**：定期同步在线用户数
**频率**：每分钟一次

## 4. 用户登录场景：从Login到Game

### 4.1 完整流程图
```
客户端          Login Server           Game Server
  |                   |                   |
  |-- 1. LoginUser -->|                   |
  |                   |-- 2. 查询数据库    |
  |                   |-- 3. 生成Ticket    |
  |<-- 4. UserInfo ---|                   |
  |<-- 5. ServerList -|                   |
  |                   |-- 6. NetPlayerWorldToken -->|
  |                   |                   |-- 7. 缓存Token
  |                   |                   |
  |============断开连接，连接Game Server============|
  |                                      |
  |-- 8. WorldLoginAuth ----------------->|
  |                   |                   |
  |                   |                   |-- 9. 验证Token
  |<-- 10. Connected --------------------|
  |                   |                   |
  |<-- 11. PlayData --------------------|
```

### 4.2 详细步骤说明

#### 步骤1-5：客户端登录Login Server
1. 客户端发送PKTHDR_LoginUser
2. Login Server验证账号密码
3. 验证成功后生成随机Ticket（1-1000）
4. 发送用户信息和角色列表
5. 发送服务器列表（包含Ticket）

#### 步骤6：Login Server通知Game Server
```cpp
// Login Server处理登录成功后
if (iCode == ACCOUNTLOGIN_Success) {
    // 生成Ticket
    pcUserData->iTicket = Dice::RandomI(1, 1000);

    // 生成Token和TokenPass
    std::string token = GenerateToken(pcUserData->szAccountName, pcUserData->iTicket);
    std::string tokenPass = GenerateTokenPass(token);

    // 发送给所有Game Server
    SendPlayerWorldLoginToken(token, tokenPass);

    // 发送服务器列表给客户端
    Server::GetInstance()->PHServerList(pcSocketData, pcUserData->iTicket);
}
```

#### 步骤8-11：客户端连接Game Server
1. 客户端断开Login Server
2. 连接选中的Game Server
3. 发送PKTHDR_WorldLoginAuth（包含Token）
4. Game Server验证Token
5. 验证成功，发送游戏数据

## 5. Token机制详解

### 5.1 Token生成
```cpp
// 伪代码
std::string GenerateToken(const char* accountName, int ticket) {
    // 使用账号名和Ticket生成
    SHA256(accountName + std::to_string(ticket));
}

std::string GenerateTokenPass(const std::string& token) {
    // 使用Token生成Password
    SHA256(token + "secret_salt");
}
```

### 5.2 Token存储和验证
```cpp
// Game Server端
std::map<std::string, std::string> m_PlayerWorldLoginToken;

// 存储Token
void StoreToken(const std::string& token, const std::string& tokenPass) {
    m_PlayerWorldLoginToken[token] = tokenPass;
}

// 验证Token
bool UseToken(const std::string& token, const std::string& tokenPass) {
    auto it = m_PlayerWorldLoginToken.find(token);
    if (it != m_PlayerWorldLoginToken.end() && it->second == tokenPass) {
        m_PlayerWorldLoginToken.erase(it);  // 使用后删除
        return true;
    }
    return false;
}
```

### 5.3 PKTHDR_WorldLoginAuth处理
```cpp
// packetserver.cpp
case PKTHDR_WorldLoginAuth:
{
    PacketWorldLoginAuth* p = (PacketWorldLoginAuth*)psPacket;

    // 验证Token
    if (NETSERVER->UsePlayerWorldLoginToken(p->Token, p->TokenPass)) {
        // 验证成功，标记已认证
        pcUserData->bWorldAuthed = TRUE;

        // 发送连接确认
        SENDPACKETBLANK(pcUser, PKTHDR_Connected);
    } else {
        // 验证失败
        NETSERVER->DisconnectUser(pcUserData);
    }
    break;
}
```

## 6. 服务器列表包（PKTHDR_ServerList）

### 6.1 包结构
```cpp
struct PacketServerList : Packet {
    struct Header {
        char    szServerName[16];      // Login Server名称
        DWORD   dwTime;               // 当前时间戳
        int     iTicket;              // Ticket
        DWORD   dwUnknown;            // 未知
        int     iClanServerIndex;     // 公会服务器索引
        int     iGameServers;         // 游戏服务器数量
    } sHeader;

    struct Server {
        char    szName[32];           // 服务器名称
        char    szaIP[3][20];         // IP地址（3个备选）
        int     iaPort[4];            // 端口（通常相同）
    } sServers[4];
};
```

### 6.2 服务器列表示例
```
Header:
  ServerName: "LoginServer"
  Time: 1234567890
  Ticket: 123
  GameServers: 2

Servers[0]:
  Name: "GameServer-1"
  IP[0]: "192.168.1.100"
  IP[1]: "192.168.1.101"
  IP[2]: "192.168.1.102"
  Port[0-3]: 10007

Servers[1]:
  Name: "GameServer-2"
  IP[0]: "192.168.1.200"
  Port[0-3]: 10007

Servers[2]: (Clan Server)
  Name: "clan"
  IP[0]: "192.168.1.100"
  Port[0]: 10007
  Port[1]: 80 (Web)
```

## 7. Java实现建议

### 7.1 NetConnection类
```java
public class NetConnection {
    private SocketChannel channel;
    private boolean connected = false;
    private boolean authed = false;
    private String serverType;
    private int serverId;

    public void connect(String host, int port) {
        // 建立TCP连接
    }

    public void sendPacket(Packet packet) {
        // 发送数据包
    }

    public void onReceive(Packet packet) {
        // 处理接收的包
    }
}
```

### 7.2 NetServer管理类
```java
@Component
public class NetServer {
    private List<NetConnection> connections = new ArrayList<>();
    private Map<String, String> playerWorldTokens = new ConcurrentHashMap<>();

    public void init() {
        // 启动时连接所有配置的服务器
    }

    public void sendToAllGameServers(Packet packet) {
        // 向所有Game Server发送包
    }

    public void onPlayerLogin(String accountName, int ticket) {
        // 生成Token并发送给Game Server
        String token = generateToken(accountName, ticket);
        String tokenPass = generateTokenPass(token);

        PacketNetPlayerWorldToken packet = new PacketNetPlayerWorldToken();
        packet.setToken(token);
        packet.setTokenPass(tokenPass);

        sendToAllGameServers(packet);

        // 缓存Token用于验证
        playerWorldTokens.put(token, tokenPass);
    }

    public boolean verifyWorldToken(String token, String tokenPass) {
        String storedPass = playerWorldTokens.get(token);
        if (storedPass != null && storedPass.equals(tokenPass)) {
            playerWorldTokens.remove(token);  // 一次性使用
            return true;
        }
        return false;
    }
}
```

### 7.3 GameServer处理流程
```java
@Component
public class GameServer {
    @Autowired
    private NetServer netServer;

    public void handleWorldLoginAuth(ChannelHandlerContext ctx, PacketWorldLoginAuth packet) {
        if (netServer.verifyWorldToken(packet.getToken(), packet.getTokenPass())) {
            // 验证成功
            UserConnection user = createUserConnection(ctx);
            user.setWorldAuthed(true);

            // 发送确认
            Packet blank = new Packet(PacketHeader.PKTHDR_Connected);
            ctx.writeAndFlush(blank.toWireBytes());

            // 发送游戏数据
            sendGameStatus(user);
        } else {
            // 验证失败，断开连接
            ctx.close();
        }
    }
}
```

## 8. 注意事项

1. **Ticket的唯一性**：同时只能有一个连接使用相同的Ticket
2. **Token时效性**：Token生成后有有效期（通常几分钟）
3. **内网通信**：服务器间通信应在内网中进行，不暴露给外网
4. **重连机制**：连接断开应有自动重连机制
5. **负载均衡**：可根据负载选择Game Server

## 9. 安全考虑

1. **Token加密**：Token和TokenPass都经过加密
2. **IP白名单**：只允许配置的服务器IP连接
3. **心跳检测**：定期检查服务器间连接状态
4. **防重放攻击**：Token使用后立即删除防止重用