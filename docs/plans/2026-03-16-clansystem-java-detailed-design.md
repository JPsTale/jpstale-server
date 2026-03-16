# ClanSystem Java 实现详细设计（第三项任务）

**日期**: 2026-03-16  
**目标**: 在合并后的统一 Web 服务中，参考原版 ASP 实现与现有文档，完成 17 个 Clan 接口的 Java 实现，使客户端与 Web 端均可使用且与原版兼容。

**依赖文档**:
- [2026-03-16-clansystem-api-list.md](2026-03-16-clansystem-api-list.md) — 接口路由、参数、响应约定
- [2026-03-16-clansystem-asp-implementation-notes.md](2026-03-16-clansystem-asp-implementation-notes.md) — ASP 源码逻辑摘录
- [2026-03-15-clansystem-asp-sql-migration.md](2026-03-15-clansystem-asp-sql-migration.md) — SQL 与 Mapper 对应

---

## 一、总体架构

### 1.1 模块与包

- **位置**: 合并后置于 **pt-web-server**（或统一 Web 模块）内，包名建议 `org.jpstale.server.web.clan`（或与现有 controller 风格一致）。
- **分层**:
  - **Controller 层**: 双路径（`/Clan/xxx.asp` + `/api/clan/xxx`），统一从 Query/Form 取参，调用 Service；.asp 路径将 Service 返回的结构转为原版文本（`\r` 分隔、Key=Value），/api/clan 可返回 JSON 或同结构。
  - **Service 层**: 17 个业务方法，按 ASP 逻辑顺序编写，调用 pt-dao（ClMapper、UlMapper、ClanListMapper、CtMapper、LiMapper）及 userdb.CharacterInfoMapper。
  - **响应 DTO/文本**: 定义内部结果对象（如 Code、以及各接口的 Key-Value 集合），由 Controller 格式化为 ASP 文本或 JSON。

### 1.2 参数与安全

- **入参**: 所有参数从 `Request` 等价获取（Spring 中 `@RequestParam` 或 Map），参数名与 ASP 一致（如 ClName、userid、gserver、chname、clName、clwon、index 等）；同时支持 GET 与 POST。
- **SQL 注入**: 禁止拼接 SQL；全部使用 Mapper 参数绑定。对入参做黑名单检查（如包含 `--`、`;`）可返回 Code=100 或重定向错误页（与 settings.asp 一致）。
- **Ticket（可选）**: LeavePlayer 原版未校验 ticket；若需校验，可调用 CtMapper.selectSNoByUserIdAndServerName，不一致则 Code=100。

### 1.3 响应格式（.asp 兼容）

- **行分隔**: `\r`（0x0D）。
- **行内容**: `Key=Value`，无引号；若 Value 含特殊字符，与原版保持一致（通常为数字、单行文本）。
- **Code**: 每响应均含一行 `Code=N`；100=参数/校验错误，0=业务失败，1/2/4/5 等=成功或状态（见接口清单）。

---

## 二、pt-dao clandb Mapper 复用说明

### 2.1 结论

pt-dao 模块下 **clandb 的 Mapper XML**（ClMapper、UlMapper、ClanListMapper、CtMapper、LiMapper）中，已按原版 ClanSystem 写好的 SQL **均可复用**；对应方法已全部在各自 Mapper 接口类中**暴露**，ClanSystem Java 实现直接注入上述 Mapper 调用即可。**唯一补充**：为支持 CheckClanLeader（按族长账号 userid 查公会名），在 ClMapper 中新增了 `selectClanNameByUserId`（XML + 接口已加）。

### 2.2 ClanSystem 所用 Mapper 方法一览（均已暴露）

| Mapper | 方法名 | ClanSystem 接口中的用途 |
|--------|--------|--------------------------|
| **ClMapper** | selectIdClanZangMemCntByClanName | InviteClan, LeavePlayer, LeavePlayerSelf |
| | selectClanZangByClanName | NewClan(存在检查), GetClanMembers, CheckClanName |
| | selectIdByClanName | NewClan(取 id 写 UL), InviteClan(取 id 写 UL) |
| | **selectClanNameByUserId** | **CheckClanLeader**（本次新增） |
| | selectClanNameNoteByMIconCnt | CheckClanID |
| | selectByClanNameOrderByCpointDesc | ClanMember(CNFlag 排名), SodScore(index=1/3) |
| | selectClanZangMemCntNoteMIconCntRegiDateLimitDatePFlagKFlagClanMoneyByClanName | ClanMember |
| | insertCl | NewClan |
| | updateMemCntByClanName | InviteClan, LeavePlayer, LeavePlayerSelf |
| | updateClanZangAndUserIdByClanName | ChangeLeader |
| | deleteByClanName | DeleteClan |
| **UlMapper** | selectClanNameByChName | 多数接口：查角色是否在公会、公会名 |
| | selectClanNameAndPermiByChName | LeavePlayerSelf |
| | selectChNameByPermi2AndClanName | InviteClan(权限), ClanMember(副族长名) |
| | selectChNameListByClanName | GetClanMembers |
| | selectUserIdByChNameAndClanName | ChangeLeader |
| | selectByChName | SodScore(index=1) |
| | insertUl | InviteClan, NewClan |
| | updatePermi0ByChName | SubLeaderRelease, SubLeaderUpdate |
| | updatePermi2ByChName | SubLeaderUpdate |
| | updatePermi0ByClanNameInChName | SubLeaderUpdate |
| | updateMIconCntByChName | ClanMember |
| | deleteByChName | InviteClan(清理), LeavePlayer, LeavePlayerSelf, DeleteClan |
| | deleteByClanName | DeleteClan |
| **ClanListMapper** | selectByAccountName | DeleteClan(校验是否唯一公会) |
| | selectClanLeaderByClanName | DeleteClan |
| | selectIdByClanName | NewClan(写 CharacterInfo.ClanId) |
| | insertClanList | NewClan |
| | deleteByClanName | DeleteClan |
| **CtMapper** | selectSNoByUserIdAndServerName | CheckTicket（可选，如 LeavePlayer） |
| **LiMapper** | selectImgById | NewClan(取/生成 MIconCnt) |
| | insertLi | NewClan(LI 无记录时) |
| | updateImgById | NewClan(自增后更新) |

说明：ClanListMapper、CtMapper 的 XML 中另有供 C++（characterserver、bellatraserver）使用的方法，ClanSystem 仅用上表所列；上述方法均在对应 Java Mapper 接口中声明，可直接调用。

### 2.3 其他补充（可选）

| 用途 | Mapper | 说明 |
|------|--------|------|
| DeleteClan 清空角色公会 | CharacterInfoMapper | 原 ASP 仅 `where ClanName=chname` 更新一行；若产品需“解散后全员 ClanId=0”，可在 CharacterInfoMapper 增加按 ClanName 批量 update。 |

---

## 三、各接口实现要点

以下按**接口编号**给出：参数校验、主要步骤、返回 Code 与 Key、使用的 Mapper。详细分支与顺序以 [2026-03-16-clansystem-asp-implementation-notes.md](2026-03-16-clansystem-asp-implementation-notes.md) 为准。

### 3.1 InviteClan（邀请入会）

- **参数**: userid, gserver, chname, clName, clwon, clwonUserid, lv, chtype, chlv（必填；chipflag 可选）。
- **校验**: 任一必参为空 → Code=100。
- **逻辑**: ClMapper.selectIdClanZangMemCntByClanName → 无公会 Code=0；(memCnt+1)>100 → Code=2；UlMapper.selectChNameByPermi2AndClanName 得副族长；若当前 chname 既非族长(ClanZang)也非副族长 → Code=0；UlMapper.selectClanNameByChName(clwon) 若已在公会 → Code=0，若原记录空则 UlMapper.deleteByChName(clwon)；memCnt+1>20 → Code=4；ClMapper.updateMemCntByClanName；UlMapper.insertUl（Permi='0'，IDX 取自 CL.id）。
- **成功**: Code=1。

### 3.2 NewClan（创建公会）

- **参数**: userid, gserver, chname, clName, chtype, lv。
- **校验**: 缺参 → Code=100。
- **逻辑**: UlMapper.selectClanNameByChName(chname) 已有非空 → Code=2 & CMoney=0；ClMapper.selectClanZangByClanName(clName) 存在 → Code=3 & CMoney=0；LiMapper 查/插/更新 ID=1 的 IMG，得到新 iIMG；ClMapper.insertCl；ClMapper.selectIdByClanName 得 id；UlMapper.insertUl（族长 Permi='0', MIconCnt=iIMG）；ClanListMapper.insertClanList（AccountName=userid, ClanLeader=chname, IconID=0, RegisDate=1, LimitDate=10 等）；ClanListMapper.selectIdByClanName；CharacterInfoMapper 更新该角色 ClanId。
- **成功**: Code=1 & CMoney=0。

### 3.3 DeleteClan（解散公会）

- **参数**: userid, gserver, chname, clName。
- **校验**: 缺参 → Code=100。
- **逻辑**: ClanListMapper.selectByAccountName(userid) 数量≠1 时可视为非法（与 ASP 一致）；ClanListMapper.selectClanLeaderByClanName(clName)；若非 chname=ClanLeader → Code=0；顺序删除 ClanListMapper.deleteByClanName、ClMapper.deleteByClanName、UlMapper.deleteByClanName；CharacterInfoMapper 更新 ClanId=0（原版 where 条件为 chname，仅更新该角色）。
- **成功**: Code=1。

### 3.4 LeavePlayer（踢人）

- **参数**: userid, gserver, chname, clName, clwon1, ticket。
- **校验**: 缺参 → Code=100；可选校验 ticket（CtMapper.selectSNoByUserIdAndServerName）。
- **逻辑**: UlMapper.selectClanNameByChName(clwon1) 无或非本公会 → Code=0；ClMapper.selectIdClanZangMemCntByClanName 若 clwon1=ClanZang → Code=4；否则 memCnt-1，ClMapper.updateMemCntByClanName，UlMapper.deleteByChName(clwon1)。
- **成功**: Code=1。

### 3.5 LeavePlayerSelf（自己退会）

- **参数**: userid, gserver, chname, clName。
- **逻辑**: 同 LeavePlayer，操作对象为 chname；若 chname=族长 → Code=4。
- **成功**: Code=1。

### 3.6 ClanMember（当前角色公会详情）

- **参数**: userid, gserver, chname。
- **逻辑**: UlMapper.selectClanNameByChName(chname)；无或空 → Code=0 & CMoney=500000 & CNFlag=0；ClMapper.selectClanZangMemCntNoteMIconCntRegiDateLimitDatePFlagKFlagClanMoneyByClanName；计算 CNFlag（ClMapper.selectByClanNameOrderByCpointDesc，按 c_point 排名 1/2/3）；UlMapper.selectChNameByPermi2AndClanName 得副族长；按族长/副族长/成员输出 Code=2/5/1 及 CName, CNote, CZang, CSubChip, CMCnt, CIMG, CRegiD, CLimitD, CPFlag, CKFlag, CMoney, CNFlag 等；最后 UlMapper.updateMIconCntByChName(chname, clan的MIconCnt)。
- **日期格式**: CRegiD/CLimitD 建议“日期 时间”字符串（与原版一致），便于客户端解析。

### 3.7 ChangeLeader（转让族长）

- **参数**: chname, gserver, clName。
- **逻辑**: UlMapper.selectUserIdByChNameAndClanName(chname, clName)；无 → Code=0；ClMapper.updateClanZangAndUserIdByClanName(chname, userId, clName)。
- **成功**: Code=1。

### 3.8 SubLeaderRelease（取消副族长）

- **参数**: chname, gserver。
- **逻辑**: UlMapper.updatePermi0ByChName(chname)；成功即 Code=1，异常 Code=0。
- **成功**: Code=1。

### 3.9 SubLeaderUpdate（设为副族长）

- **参数**: chname, gserver。
- **逻辑**: UlMapper.updatePermi0ByClanNameInChName(chname)；UlMapper.updatePermi2ByChName(chname)。
- **成功**: Code=1。

### 3.10 GetClanMembers（成员列表）

- **参数**: userid, gserver, chname。
- **逻辑**: UlMapper.selectClanNameByChName(chname) 无 → Code=0；ClMapper.selectClanZangByClanName 得族长；UlMapper.selectChNameListByClanName 逐行输出 CMem=chName。
- **成功**: Code=1 & CClanName & CClanZang & 多行 CMem。

### 3.11 CheckClanLeader（是否族长）

- **参数**: userid, gserver。
- **逻辑**: ClMapper.selectClanNameByUserId(userid)；有 → Code=1，无 → Code=0。

### 3.12 CheckDate / 3.13 CheckUnknown（角色是否在公会）

- **参数**: chname（gserver 等可选）。
- **逻辑**: UlMapper.selectClanNameByChName(chname)；无 → Code=0，有 → Code=1。

### 3.14 CheckClanPlayer（占位）

- **参数**: clwon, gserver。
- **逻辑**: 不查库，直接返回 Code=1。

### 3.15 CheckClanID（按图标编号查公会）

- **参数**: num；gserver 可选。
- **逻辑**: ClMapper.selectClanNameNoteByMIconCnt(num)；无 → Code=0；有 → Code=1 & CName & CNote。

### 3.16 CheckClanName（公会名是否存在）

- **参数**: ClName, gserver。
- **逻辑**: ClMapper.selectClanZangByClanName(ClName)；无 → Code=0，有 → Code=1。

### 3.17 SodScore（贝尔特拉）

- **参数**: userid, gserver, chname, index。
- **校验**: 缺 userid/gserver/chname → Code=100；缺 index → Code=104。
- **index=1**: 分隔符 `|`。ClMapper.selectByClanNameOrderByCpointDesc 取第一条（排名第一公会）；UL 查 chname；按是否在同公会、族长/副族长/成员 返回 Code=0/1/2/3/4/5/6，带 CClanMoney, CName, CNote, CZang, CIMG, TotalEMoney/TotalMoney 等（见 ASP 笔记）。
- **index=3**: 分隔符 `\r`。同 ORDER BY Cpoint DESC，取 Cpoint>0 最多 9 条；每行 CIMG, CName, CPoint, CRegistDay（RegiDate 日期部分）。
- **其他 index**: Code=104。

---

## 四、实施顺序建议

1. **pt-dao 补充**: ClMapper 增加按 user_id 查 ClanName（CheckClanLeader）；确认 CharacterInfo 更新 ClanId/ClanName 的接口存在。
2. **公共层**: 参数解析（Query+Form）、黑名单校验、统一响应构建（拼接 Key=Value、\r 分隔）。
3. **简单接口先行**: CheckClanPlayer、CheckDate、CheckUnknown、CheckClanName、CheckClanID、CheckClanLeader → 再实现 NewClan、DeleteClan、LeavePlayerSelf、LeavePlayer、InviteClan、ChangeLeader、SubLeaderRelease、SubLeaderUpdate、GetClanMembers、ClanMember、SodScore。
4. **双路径与联调**: Controller 同时挂载 `/Clan/xxx.asp` 与 `/api/clan/xxx`；用原版客户端或脚本请求 .asp 路径，对照响应格式与 Code。
5. **Nginx 与 login**: 确认 Nginx 仅代理 `/Clan/*.asp`；pt-login-server 下发 Clan 地址与端口（见 api-list 文档 §8）。

---

## 五、测试与兼容性

- **单元测试**: 每 Service 方法可对“参数缺失→Code=100”“无权限→Code=0”“成功→Code=1”等分支写单测，Mock Mapper。
- **集成测试**: 使用真实 clandb/userdb（或 Testcontainers），调用 Controller 的 .asp 路径，断言响应文本含 `Code=1\r` 或 `Code=0\r` 及关键 Key。
- **客户端联调**: 用游戏客户端连接配置好的 Clan 地址，执行创建公会、邀请、退会、查询成员等，确认无报错且界面显示正确。

---

## 六、文档与引用

- 接口路由与路径对照: [2026-03-16-clansystem-api-list.md](2026-03-16-clansystem-api-list.md) 〇 节
- ASP 逐步逻辑: [2026-03-16-clansystem-asp-implementation-notes.md](2026-03-16-clansystem-asp-implementation-notes.md)
- Mapper 与 SQL: [2026-03-15-clansystem-asp-sql-migration.md](2026-03-15-clansystem-asp-sql-migration.md)
- 合并与路由约定: [2026-03-16-merge-web-and-role-design.md](2026-03-16-merge-web-and-role-design.md)
