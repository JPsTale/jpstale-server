# 数据结构分析文档

**分析日期**：2026-03-17
**分析目标**：深入理解C++核心数据结构与Java实现的对应关系

## 1. SQLUser（UserInfo）结构

### 1.1 C++ SQLUser结构（accountserver.h）
```cpp
struct SQLUser {
    int                 iID;                    // db id
    char                szAccountName[32];      // 账号名
    char                szPassword[65];         // SHA256哈希密码
    int                 iFlag;                  // 账号状态标志（如114）
    int                 iActive;                // 激活状态
    int                 iCoins;                 // 金币
    int                 iGold;                  // 游戏金币
    BOOL                bGameMasterType;        // 是否GM
    EGameLevel          iGameMasterLevel;       // GM等级
    char                szGameMasterMacAddress[20]; // GM的MAC地址
    EBanStatus          iBanStatus;            // 封禁状态
    SYSTEMTIME          sUnbanDate;            // 解封日期
    BOOL                bIsMuted;              // 是否禁言
    int                 iMuteCount;            // 禁言次数
    SYSTEMTIME          sUnmuteDate;           // 解禁日期
};
```

### 1.2 Java UserInfo实体（ORM映射）
```java
@Data
@TableName(schema = "userdb", value = "user_info")
public class UserInfo {
    private Integer id;                  // 对应iID
    private String accountName;          // 对应szAccountName
    private String password;             // 对应szPassword
    private Integer flag;                // 对应iFlag
    private Integer active;              // 对应iActive
    private Integer coins;               // 对应iCoins
    private String email;                // 新增字段
    private Integer gameMasterType;      // 对应bGameMasterType（BOOL转Integer）
    private Integer gameMasterLevel;     // 对应iGameMasterLevel
    private String gameMasterMacAddress; // 对应szGameMasterMacAddress
    private Integer banStatus;           // 对应iBanStatus
    private LocalDateTime unbanDate;     // 对应sUnbanDate
    private Integer isMuted;             // 对应bIsMuted
    private Integer muteCount;           // 对应iMuteCount
    private LocalDateTime unmuteDate;    // 对应sUnmuteDate
    private Boolean webAdmin;            // 新增字段（Web管理权限）
}
```

**差异说明**：
- C++的BOOL类型在Java中使用Integer表示（0/1）
- SYSTEMTIME转换为LocalDateTime
- 增加了email和webAdmin等Web系统所需字段

## 2. CharacterData结构

### 2.1 C++ CharacterData结构（character.h）
```cpp
struct CharacterData {
    // 基本信息
    char                szName[32];              // 角色名
    union {
        struct { char szBodyModel[64]; char szHeadModel[64]; } Player;
        struct { char szBodyModel[64]; char szHeadModel[60]; int iMonsterID; } Monster;
        struct { char szBodyModel[64]; char szHeadModel[60]; int iNPCID; } NPC;
        struct { char szBodyModel[65]; char szOwnerName[63]; } Pet;
    };

    // 核心属性
    unsigned int        iID;                    // 对象序列号
    unsigned int        iClanID;                // 公会ID
    ECharacterType      iType;                  // 角色类型（玩家/NPC/怪物）
    int                 iShadowSize;            // 影子大小
    ECharacterClass     iClass;                 // 职业代码
    int                 iLevel;                 // 等级

    // 六大基础属性
    int                 iStrength;              // 力量
    int                 iSpirit;                // 精神
    int                 iTalent;                // 天赋
    int                 iAgility;               // 敏捷
    int                 iHealth;                // 体质
    int                 iAccuracy;              // 准确

    // 战斗属性
    int                 iAttackRating;          // 攻击力
    int                 iMinDamage;             // 最小伤害
    int                 iMaxDamage;             // 最大伤害
    int                 iAttackSpeed;           // 攻击速度
    int                 iAttackRange;           // 攻击范围
    int                 iCritical;              // 暴击
    int                 iDefenseRating;         // 防御力
    int                 iBlockRating;           // 格挡
    int                 iAbsorbRating;          // 吸收

    // 状态值
    short               sElementalDef[8];       // 元素防御
    short               sElementalAtk[8];       // 元素攻击
    CurMax              sHP;                    // 生命值
    CurMax              sMP;                    // 法力值
    CurMax              sSP;                    // 体力值
    float               fHPRegen;               // 生命恢复
    float               fMPRegen;               // 法力恢复
    float               fSPRegen;               // 体力恢复

    // 经验和位置
    unsigned int        iCurrentExpLow;         // 经验值（低32位）
    unsigned int        iCurrentExpHigh;        // 经验值（高32位）
    int                 iGold;                  // 金币

    // 其他属性
    EMonsterType        iMonsterType;           // 怪物类型
    int                 iStatPoints;            // 属性点（玩家）或UniqueMonsterID（怪物）
    short               sMapID;                 // 地图ID
    ECharacterRank      iRank;                  // 角色阶级
    EClassFlag          iFlag;                  // 职业标志
    // ... 其他字段
};
```

**结构大小**：约464字节（0x1D0）

### 2.2 CharacterSave结构（角色保存数据）
```cpp
struct CharacterSave {
    DWORD               dwHeader;              // 头部标识
    EMapID              iMapID;                // 当前地图ID
    int                 iCameraMode;           // 摄像机模式
    int                 iCameraPositionX;      // X坐标
    int                 iCameraPositionZ;      // Z坐标
    int                 iLastGold;             // 上次金币
    RecordSkill         sSkillInfo;            // 技能信息
    QuestCharacterSave  sQuestInfo;            // 任务信息
    char                szAccountName[32];     // 所属账号
    // ... 其他保存相关数据
};
```

### 2.3 PacketCharacterRecordData（角色数据包）
```cpp
struct PacketCharacterRecordData : Packet {
    char                szHeader[8];              // 文件头
    CharacterData       sCharacterData;           // 角色数据
    CharacterSave       sCharacterSaveData;       // 保存数据
    DropItemData        sDropItemData[64];        // 地上物品
    int                 iDropItemCount;           // 地上物品数量
    int                 iItemCount;               // 背包物品数量
    BYTE                baData[...];              // 背包物品详情
};
```

## 3. 数据包结构对比

### 3.1 PacketLoginUser（登录请求包）
| C++字段 | Java字段 | 大小 | 说明 |
|---------|----------|------|------|
| DWORD dwUnk[3] | int[] unk | 12 | 未知字段 |
| char szUserID[32] | String userId | 32 | 用户名 |
| char szPassword[65] | String password | 65 | 密码哈希 |
| char szMacAddr[20] | String macAddr | 20 | MAC地址 |
| char szPCName[32] | String pcname | 32 | PC名称 |
| DWORD dwSerialHD | int serialHd | 4 | 硬盘序列号 |
| char szVideoName[256] | String videoName | 256 | 显卡名称 |
| char szHardwareID[40] | String hardwareId | 40 | 硬件ID |
| UINT uWidthScreen | int widthScreen | 4 | 屏幕宽度 |
| UINT uHeightScreen | int heightScreen | 4 | 屏幕高度 |
| int iSystemOS | int systemOs | 4 | 系统类型 |
| int iVersion | int version | 4 | 客户端版本 |

**总计**：477字节（不含8字节包头）

### 3.2 PacketUserInfo（用户信息响应包）
| C++字段 | Java字段 | 大小 | 说明 |
|---------|----------|------|------|
| char szUserID[32] | String userId | 32 | 用户名 |
| int CharCount | int charCount | 4 | 角色数量 |
| _TRANS_CHAR_INFO sCharacterData[6] | TransCharInfo[] characterData | 1440 | 角色列表 |

**总计**：1476字节（不含8字节包头）

### 3.3 _TRANS_CHAR_INFO（角色简报）
```cpp
struct _TRANS_CHAR_INFO {
    char    szName[32];      // 角色名称
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

**大小**：240字节

## 4. 数据持久化策略

### 4.1 数据库存储
- **UserInfo表**：存储账号基本信息
- **CharacterInfo表**：存储角色基本列表（从数据库读取.chr文件）
- **.chr文件**：存储完整的角色数据（二进制格式）

### 4.2 文件结构
```
/Character/
  ├──角色名.chr（完整角色数据，包含CharacterData + CharacterSave + 物品等）
  └── ...
```

### 4.3 Java端建议实现
1. **保留.chr文件格式**：确保与C++版本完全兼容
2. **数据库备份**：在数据库中存储关键数据用于备份和查询
3. **增强搜索功能**：可考虑将部分常用数据同步到数据库

## 5. 关键实现要点

### 5.1 字节序问题
- 所有多字节字段使用小端序（Little Endian）
- Java序列化时需明确指定ByteOrder.LITTLE_ENDIAN

### 5.2 字符串处理
- C风格字符串（NULL结尾）
- UTF-8编码处理中文字符
- 定长数组，不足部分补0

### 5.3 联合体（Union）处理
```java
// C++中的union需要特殊处理
public class ModelData {
    private String bodyModel;  // szBodyModel
    private String headModel;  // szHeadModel
    private Integer monsterId; // iMonsterID（仅怪物使用）
    // 根据角色类型决定使用哪个字段
}
```

### 5.4 位标志处理
```cpp
// C++中的位操作
if (iAccountFlag & ACCOUNTFLAG_Activated)
```

```java
// Java中对应实现
if ((accountFlag & ACCOUNTFLAG_Activated.getValue()) != 0)
```

## 6. 性能优化建议

1. **缓存机制**：缓存频繁访问的角色数据
2. **延迟加载**：仅加载必要的角色字段
3. **批量查询**：一次查询角色列表而非逐个查询
4. **内存池**：复用CharacterData对象减少GC压力
5. **异步I/O**：异步读写.chr文件