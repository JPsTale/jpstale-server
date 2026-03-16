# ClanSystem ASP 源码实现细节摘录

**日期**: 2026-03-16  
**来源**: 查阅 `PristonTale-EU-main/ClanSystem/Clan/*.asp` 所得，用于 Java 实现时与原版完全兼容。

---

## 一、公共约定

- **参数**: 全部通过 `Request("key")` 读取，不区分 GET/POST；多数页面用 `Trim(Request("..."))`。
- **响应分隔符**: `strSplit = Chr(&H0D)` 即 `\r`；SodScore.asp 另用 `strSplit2 = Chr(&H7C)` 即 `|`。
- **缺参/错误**: 输出 `Code=100` + strSplit 后 `Response.End`（部分页面如 SodScore 缺 index 时 `Code=104`）。
- **settings.asp**: 每个 ASP 通过 `<!-- #include file ="settings.asp" -->` 引入；内含 SQL 注入检查（BlackList = "--", ";"），发现则 Redirect ErrorPage；`CheckTicket(useridcheck, ticketcheck)` 用 CT 表校验 SNo，失败写 `Code=100` 并 End。**LeavePlayer.asp 未调用 CheckTicket**，仅接收 ticket 参数。

---

## 二、各 ASP 逻辑摘要

### 1. InviteClan.asp（clanInsertClanWon）

- **必参**: userid, gserver, chname, clName, clwon, clwonUserid, lv, chtype, chlv（chipflag 未参与校验）。
- **步骤**:
  1. 缺参 → Code=100。
  2. `SELECT IDX,ClanZang,MemCnt FROM CL WHERE ClanName=clName`；无记录 → Code=0。
  3. 若 `(MemCnt+1) > 100` → Code=2（注：原 ASP 写的是 MemCnt，实际应取上步的 MemCnt，即 ClanMembers）。
  4. `SELECT ChName FROM UL WHERE Permi=2 AND ClanName=clName` 得副族长 ClanSubChief；若 `ClanLeader <> chname And ClanSubChief <> chname` → Code=0（无权限）。
  5. `SELECT ClanName FROM UL WHERE ChName=clwon`；若已有公会（UclName 非空）→ Code=0；若原记录存在但 ClanName 空则先 `DELETE FROM UL WHERE ChName=clwon`。
  6. `ClanMembers = ClanMembers + 1`；若 `ClanMembers > 20` → Code=4。
  7. `UPDATE CL SET MemCnt=ClanMembers WHERE ClanName=clName`；`INSERT INTO UL (IDX,userid,ChName,ClanName,ChType,ChLv,Permi,...) values(IDX, clwonUserid, clwon, clName, chtype, chlv, '0', ...)`。
- **成功**: Code=1。

### 2. NewClan.asp

- **必参**: userid, gserver, chname, clName, chtype, lv。
- **步骤**:
  1. 缺参 → Code=100。
  2. `SELECT ClanName FROM UL WHERE ChName=chname`；若存在且非空 → Code=2 & CMoney=0；若存在但为空则 `DELETE FROM UL WHERE ChName=chname`。
  3. `SELECT ClanZang FROM CL WHERE ClanName=clName`；若有记录 → Code=3 & CMoney=0。
  4. LI 表：`SELECT IMG FROM LI WHERE ID=1`；无则 INSERT LI 再取；`iIMG = iIMG + 1`，`UPDATE LI SET IMG=iIMG WHERE ID=1`。
  5. `INSERT INTO CL (ClanName, UserID, ClanZang, MemCnt, Note, MIconCnt, ...)`，Note 固定 `"RenaissancePT"`，MemCnt=1，MIconCnt=iIMG。
  6. `SELECT IDX FROM CL WHERE ClanName=clName` 得 IDX；`INSERT INTO UL (IDX, userid, ChName, ClanName, ChType, ChLv, Permi, ..., MIconCnt)`，Permi='0'，MIconCnt=iIMG（族长）。
  7. `Insert Into ClanList (ClanName, ClanLeader, Note, AccountName, MembersCount, IconID, RegisDate, LimitDate, ...)`，AccountName=userid，ClanLeader=chname，IconID=0，RegisDate=1，LimitDate=10 等。
  8. `SELECT ID FROM CLANLIST WHERE ClanName=clName` 得 IDXx；`update UserDb..CharacterInfo set ClanId=IDXx where Name=chname`。
- **成功**: Code=1 & CMoney=0。

### 3. DeleteClan.asp（clanRemove）

- **必参**: userid, gserver, chname, clName。
- **步骤**:
  1. 缺参 → Code=100。
  2. `SELECT * FROM ClanList WHERE AccountName=userid`；若记录数 ≠ 1 → 不输出 Code（原版输出葡萄牙语提示）。
  3. `SELECT ClanLeader FROM ClanList WHERE ClanName=clName`；无记录或 `chname <> ClanLeader` → Code=0。
  4. 顺序：`DELETE FROM ClanList WHERE ClanName=clName`；`DELETE FROM CL WHERE ClanName=clName`；`DELETE FROM UL WHERE ClanName=clName`；`update UserDb..CharacterInfo set ClanId=0 where ClanName=chname`（原 ASP 写的是 ClanName=chname，即只更新该角色名所在行）。
- **成功**: Code=1。

### 4. LeavePlayer.asp（clanWonRelease）

- **必参**: userid, gserver, chname, clName, clwon1, ticket（ticket 未在 ASP 中参与校验）。
- **步骤**:
  1. 缺参 → Code=100。
  2. `SELECT ClanName FROM UL WHERE ChName=clwon1`；无记录或 ClanName 空（并删 UL）或 clName2<>clName → Code=0。
  3. `SELECT ClanZang,MemCnt FROM CL WHERE ClanName=clName`；若 `clwon1 = ClanLeader` → Code=4（不能踢族长）；否则 MemCnt-1，`UPDATE CL SET MemCnt`，`DELETE FROM UL WHERE ChName=clwon1`。
- **成功**: Code=1。

### 5. LeavePlayerSelf.asp（clanWonSelfLeave）

- **必参**: userid, gserver, chname, clName。
- **步骤**: 同 LeavePlayer，但操作对象为 chname；若 `chname = ClanLeader` → Code=4。
- **成功**: Code=1。

### 6. ClanMember.asp（isClanMember）

- **必参**: userid, gserver, chname。
- **步骤**:
  1. 缺参 → Code=100。
  2. `SELECT ClanName FROM UL WHERE ChName=chname`；无记录或 clan 名为空（并删 UL）→ Code=0 & CMoney=500000 & CNFlag=0。
  3. `SELECT ClanZang,MemCnt,Note,MIconCnt,RegiDate,LimitDate,PFlag,KFlag,ClanMoney FROM CL WHERE ClanName=clname`；无则删 UL 并 Code=0 & CMoney=500000 & CNFlag=0。
  4. 计算 CNFlag：`SELECT ClanName,Cpoint FROM CL ORDER BY Cpoint DESC`，找到当前 clname 的排名，1→CNFlag=1，2→2，3→3，否则 0（Cpoint 需>0）。
  5. CreateDate/EndDate 格式化为“日期部分 + 空格 + 时间部分”（原版用 VBScript 逐字符拼，中间有特殊字符）。
  6. `SELECT ChName FROM UL WHERE ClanName=clname AND Permi=2` 得 ClanSubChief。
  7. 按身份输出：
     - 族长(ClanLeader=chname)：Code=2，带 CSubChip（若有副族长）；CStats=1, CMCnt, CIMG, CSec=60, CRegiD, CLimitD, CDelActive=0, CPFlag, CKFlag, CMoney, CNFlag。
     - 普通成员：Code=1，CPFlag=0,CKFlag=0。
     - 副族长(ClanSubChief=chname 且非族长)：Code=5。
  8. 最后 `UPDATE UL SET MIconCnt=iIMG WHERE ChName=chname`（与公会 MIconCnt 同步）。
- **响应键**: CName, CNote, CZang, CSubChip, CStats, CMCnt, CIMG, CSec, CRegiD, CLimitD, CDelActive, CPFlag, CKFlag, CMoney, CNFlag。

### 7. ChangeLeader.asp（clanChipChange）

- **必参**: chname, gserver, clName。
- **步骤**: `SELECT userid FROM UL WHERE ChName=chname AND ClanName=clName`；有则 `UPDATE CL SET ClanZang=chname, UserID=UserID WHERE ClanName=clName`；否则 Code=0。
- **成功**: Code=1。

### 8. SubLeaderRelease.asp（clanSubChipRelease）

- **必参**: chname, gserver。
- **步骤**: `UPDATE UL SET Permi=0 WHERE ChName=chname`；根据 Err 返回 Code=0 或 Code=1。
- **成功**: Code=1。

### 9. SubLeaderUpdate.asp（clanSubChipUpdate）

- **必参**: chname, gserver。
- **步骤**: 先 `UPDATE UL SET Permi=0 WHERE ClanName IN (SELECT ClanName FROM UL WHERE ChName=chname)`，再 `UPDATE UL SET Permi='2' WHERE ChName=chname`。
- **成功**: Code=1。

### 10. GetClanMembers.asp（GetAllMyClanMember）

- **必参**: userid, gserver, chname。
- **步骤**: 查 UL 得 clname；无 → Code=0。查 CL 得 ClanZang(ClanLeader)。输出 `Code=1`、`CClanName=clname`、`CClanZang=ClanLeader`，再逐行 `CMem=ChName`（`SELECT ChName FROM UL WHERE ClanName=clname`）。
- **成功**: Code=1 + CClanName + CClanZang + 多行 CMem。

### 11. CheckClanLeader.asp（isCheckClanJang）

- **必参**: userid, gserver。
- **步骤**: `SELECT ClanName FROM CL WHERE UserID=userid`（CL 表存族长账号 UserID）；有则 Code=1，无则 Code=0。
- **说明**: pt-dao 的 ClMapper 需提供按 user_id 查 ClanName 的方法（若尚无则新增）。

### 12. CheckDate.asp（isPFlag）/ 13. CheckUnknown.asp（isKflag）

- **参数**: 仅用 chname（gserver 等未参与查询）。
- **步骤**: `SELECT ClanName FROM UL WHERE ChName=chname`；RecordCount=0 → Code=0，否则 Code=1。

### 14. CheckClanPlayer.asp（isCheckClanwon）

- **必参**: clwon, gserver。
- **步骤**: 不查库，直接 `Code=1`。

### 15. CheckClanID.asp（isCheckClanNum）

- **必参**: num；gserver 未参与。
- **步骤**: `SELECT ClanName,Note FROM CL WHERE MIconCnt=num`；无 → Code=0；有 → Code=1 & CName & CNote。

### 16. CheckClanName.asp（isCheckClanName）

- **必参**: ClName（注意大小写）, gserver。
- **步骤**: `SELECT ClanZang FROM CL WHERE ClanName=clname`；无 → Code=0，有 → Code=1。

### 17. SodScore.asp（sod2info）

- **include**: SODsettings.asp（定义 dbname1=ClanDB, dbname2=SoD2DB 等）。
- **必参**: userid, gserver, chname；index 缺则 Code=104。
- **index=1（主页面）**:
  - 分隔符用 `|`（strSplit2）。
  - `SELECT * FROM CL ORDER BY Cpoint DESC` 取**第一条**（排名第一的公会）的 ClanName, Note, MIconCnt, ClanZang, ClanMoney 等。
  - `SELECT * FROM UL WHERE chname=chname`；若无 → Code=0 & CClanMoney=0 & CTax=0 & CName,CNote,CZang,CIMG（无 TotalEMoney）。
  - 若在 UL 中：比较 ClanSub 与当前第一公会 ClanName；同公会时：族长→Code=1，副族长→Code=2，否则→Code=3；不同公会时：该公会族长=chname→Code=4，副族长→Code=5，否则→Code=6。Code=1 带 TotalEMoney/TotalMoney。
- **index=3（高分列表）**:
  - 分隔符用 `\r`。`SELECT * FROM CL ORDER BY Cpoint DESC`，最多 9 条，仅 Cpoint>0；每行输出 CIMG, CName, CPoint, CRegistDay（RegiDate 的日期部分，原版用字符串截取到第一个空格前）。
- **其他 index**: Code=104。

---

## 三、与 pt-dao 的对应与缺口

- **CheckClanLeader**: ASP 用 `SELECT ClanName FROM CL WHERE UserID=?`。**已补充**：ClMapper 已增加 `selectClanNameByUserId(userId)`（XML + 接口），见 pt-dao 的 ClMapper.xml / ClMapper.java。
- **DeleteClan 的 CharacterInfo**: 原版为 `where ClanName=chname`（仅更新该角色行）；若需“按公会清空所有成员 ClanId”，需在 CharacterInfoMapper 增加按 ClanName 批量更新（与当前 ASP 行为不完全一致，可选）。
- **ClanMember 日期格式**: 原版 CRegiD/CLimitD 为“日期+空格+时间”的字符串；Java 可用同一格式或与客户端联调确认。
- **SodScore index=1**: 取的是“Cpoint 排名第一”的公会信息，再根据 chname 是否在该公会及身份返回不同 Code；index=3 仅输出 Cpoint>0 的前若干条，CRegistDay 为日期部分。

---

## 四、参考路径

- 源码目录: `PristonTale-EU-main/ClanSystem/Clan/`
- 接口清单与路由: [2026-03-16-clansystem-api-list.md](2026-03-16-clansystem-api-list.md)
- SQL 与 Mapper: [2026-03-15-clansystem-asp-sql-migration.md](2026-03-15-clansystem-asp-sql-migration.md)
