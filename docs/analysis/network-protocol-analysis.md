# 网络协议分析文档

**分析日期**：2026-03-17
**分析目标**：理解C++服务端的网络通信机制

## 1. 网络架构概述

### 1.1 SocketServer（网络层基础）
- **文件位置**：`PristonTale-EU-main/Server/server/socketserver.h/cpp`
- **功能**：管理所有网络连接，处理数据收发
- **关键特性**：
  - Windows Sockets API（WSA）
  - 多线程处理（Listener、Sender、Receiver线程）
  - IP连接限制（同一IP最多15个连接）
  - 包流控制（MAX_PACKETFLOW = 400，TIM_PACKETFLOW = 2000ms）

### 1.2 连接管理
```cpp
// 关键常量
#define MAX_SOCKET_ON_SAME_IP 15          // 同IP最大连接数
#define MAX_DISCONNECT_TIME (10*1000)     // 断开连接超时10秒
#define MAX_PACKETFLOW 400                // 包流量限制
```

## 2. 包格式定义

### 2.1 包基本结构（8字节头部）
```cpp
struct Packet {
    PKTLEN   iLength;        // 包总长度（WORD）
    PKTENC   iEncKeyIndex;   // 加密密钥索引（BYTE）
    PKTENC   iEncrypted;     // 是否加密（BYTE）
    PKTHDR   iHeader;        // 包ID（DWORD）
};
```

### 2.2 包大小限制
- **最大包大小**：MAX_PKTSIZ = 0x2000 = 8192字节
- **包体最大大小**：8192 - 8 = 8184字节

## 3. 关键数据包分析

### 3.1 登录包（PacketLoginUser）
```
包ID：0x48480001
结构：
- dwUnk[3] - 未知字段（12字节）
- szUserID[32] - 用户名
- szPassword[65] - 密码哈希（64位十六进制+1个空字符）
- szMacAddr[20] - MAC地址
- szPCName[32] - PC名称
- dwSerialHD - 硬盘序列号
- szVideoName[256] - 显卡名称
- szHardwareID[40] - 硬件ID
- uWidthScreen/uHeightScreen - 屏幕分辨率
- iSystemOS/iVersion - 系统和版本信息
```

### 3.2 用户信息响应包（PacketUserInfo）
```
包ID：0x48470086
结构：
- szUserID[32] - 用户名
- CharCount - 角色数量
- sCharacterData[6] - 最多6个角色信息
```

### 3.3 角色信息结构（_TRANS_CHAR_INFO）
```
- szName[32] - 角色名
- szModelName[64] - 模型名称
- szModelName2[64] - 模型名称2
- JobCode - 职业代码
- iLevel - 等级
- Brood - 种族
- dwArmorCode - 护甲代码
- StartField - 起始地图
- PosX/PosZ - 登出坐标
- dwTemp[13] - 预留空间
```

## 4. 包处理流程

### 4.1 包接收流程
1. **SocketServer::SocketPacket()** - 接收原始数据
2. **PacketReceiving** - 组装完整包
3. **PacketServer::AnalyzePacket()** - 解析包内容
4. **路由到具体处理函数** - 根据包ID分发

### 4.2 包发送流程
1. **构造包结构** - 填充数据
2. **加密处理**（如需要）
3. **SENDPACKET宏** - 调用PacketServer::Send()
4. **Socket层发送** - 通过SocketData发送

## 5. 加密机制

### 5.1 密码哈希
- **算法**：SHA256
- **输入**：UPPERCASE(账号) + ":" + 明文密码
- **输出**：64位十六进制大写字符串

### 5.2 包加密
- **iEncKeyIndex**：加密密钥索引
- **iEncrypted**：是否已加密标志
- **使用场景**：敏感数据传输

## 6. Java端实现要点

### 6.1 必须匹配的关键点
1. **包格式完全一致**：8字节头部结构
2. **包ID保持不变**：所有0x48xxxxx和0x43xxxxx等
3. **字节序**：确保网络字节序（大端）
4. **字符串编码**：ASCII/GBK（根据中文字符）
5. **加密算法**：与C++版本完全一致

### 6.2 Java实现建议
```java
// 包基类
public class Packet {
    private short length;      // iLength - PKTLEN
    private byte encKeyIndex;  // iEncKeyIndex - PKTENC
    private byte encrypted;    // iEncrypted - PKTENC
    private int header;        // iHeader - PKTHDR

    // 序列化方法
    public byte[] toByteArray() {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        buffer.order(ByteOrder.LITTLE_ENDIAN); // 注意字节序
        buffer.putShort(length);
        buffer.put(encKeyIndex);
        buffer.put(encrypted);
        buffer.putInt(header);
        // 写入包体...
        return buffer.array();
    }
}
```

## 7. 下一步分析任务

1. **accountserver.cpp** - 深入分析登录处理逻辑
2. **PacketServer::AnalyzePacket()** - 理解包分发机制
3. **World Auth协议** - Login Server与Game Server通信
4. **数据库交互** - SQL查询和事务处理
5. **错误处理** - 各种错误码和响应包

## 8. 已知问题/风险

1. **字节序问题**：需要确认C++端使用的是大端还是小端
2. **字符串编码**：中文字符的编码处理
3. **加密细节**：包加密的具体算法
4. **线程安全**：多线程包处理的同步机制
5. **内存对齐**：C++结构体的内存布局可能不同于Java