# Game Server 连接 Login Server 的架构设计

## 问题回答：Game Server是**已经计划并实现**的！

### 当前实现状态

#### ✅ 1. 实现位置
- **代码位置**：`InterServerChannelManager.java` (pt-server-core/src/main/java/...)
- **配置文件**：`pt-game-server/src/main/resources/application.yml`

#### ✅ 2. 连接流程
```
Game Server 启动
     ↓
读取配置：pt.inter-server.login.address=127.0.0.1:10009
     ↓
主动连接 Login Server
     ↓
发送 PKTHDR_NetIdentifier 包（内网标识）
     ↓
保持连接池管理
```

#### ✅ 3. 关键代码逻辑

**Game Server端（initAsGame方法）**：
```java
private void initAsGame() {
    log.info("InterServerChannelManager starting as GAME, loginAddress={}", loginAddress);
    String[] parts = loginAddress.trim().split(":");
    String host = parts[0];
    int port = Integer.parseInt(parts[1]);
    connectOne(host, port); // 主动连接Login Server
}
```

**Login Server端（initAsLogin方法）**：
```java
private void initAsLogin() {
    // 读取所有Game Server地址（多个）
    for (String addr : gameAddresses.split(",")) {
        String host = ...;
        int port = ...;
        connectOne(host, port); // 主动连接每个Game Server
    }
}
```

### 4. 完整架构图

```
┌───────────────┐          ┌──────────────┐
│  Login Server │          │ Game Server 1 │
│  (10009)      │          │  (10007)      │
└──────┬──────┘          └──────┬─────┘
       │                          │
       │ 1. 启动时主动连接    │
       │    （配置文件地址）    │
       │                          │
       │ ◄━━━━━━━━━━━━━━━━◄ │
       │ InterServerChannel   │
       │ Manager             │
       │                      │
       │ 2. 发送NetIdentifier  │
       │    （服务器标识）       │
       │                      │
       │ ◄━━━━━━━━━━━━━━◄ │
       │ 3. 接收PlayerWorldToken│
       │    （登录凭证传递）     │
       │                      │
       │ 4. 处理中转请求       │
│      ↳┐                  ↳└┐ │
│  其他功能  ├──────────────►┼─┐│◄┌┐◄┌┐
│  - 玩家同步 │                │ │ ││
│  - 数据同步 │                │ ││
│  - 负包处理 │                │ ││
└──────────┴────────────────┴┴─┴┴─┴
```

### 5. 配置详解

#### Game Server配置：
```yaml
pt:
  game:
    port: 10007
  inter-server:
    login:
      address: 127.0.0.1:10009  # Login Server地址
```

#### Login Server配置：
```yaml
pt:
  login:
    port: 10009
  inter-server:
    game:
      addresses: 127.0.0.1:10017,127.0.0.1:10018  # 多个Game Server
```

### 6. 连接时机

#### 启动顺序：
1. **Login Server先启动**（10009端口）
2. **各Game Server依次启动**：
   - 读取配置
   - 连接Login Server
   - 发送NetIdentifier
3. **建立稳定的内网通道**

### 7. Token传递流程（已实现）

```
用户登录Login Server
     ↓
1. 生成Token
     ↓
2. 通过InterServerChannelManager广播给所有Game Server
     ↓
3. Game Server缓存Token
     ↓
4. 用户用Token连接Game Server
     ↓
5. 验证成功，进入游戏
```

### 8. 实施计划中的位置

在实施计划文档中，这个功能属于：
- **阶段一：基础设施完善**（已完成）
- **任务1.2：实现NetServer通信**（已实现）

### 9. 后续工作

虽然基础连接已实现，但仍需完善：
- ✅ 连接管理（重连、心跳）
- ✅ 包分发机制（broadcastNetPacket）
- 🔲 连接状态监控
- 🔲 负载均衡和故障转移
- 🔲 动态服务器发现

### 10. 优势

1. **主动性**：Game Server不依赖Login Server通知，自主管理连接
2. **可靠性**：连接断开会重试
3. **扩展性**：可轻松添加更多Game Server
4. **解耦**：通过配置灵活调整服务器分布

## 总结

Game Server主动连接Login Server的机制**不仅是计划中的，而且已经**完整实现**！这是架构设计的重要部分，确保了服务器间通信的稳定性和可靠性。