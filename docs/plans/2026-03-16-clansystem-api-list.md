# ClanSystem 接口清单（原版 ASP 对照）

**日期**: 2026-03-16  
**来源**: PristonTale-EU-main/ClanSystem/Clan/*.asp  
**用途**: 确认 pt-clan-server（或合并后的统一 Web 服务）需要提供的 ClanSystem 接口，便于与游戏客户端/服务端联调。

**后端路由约定**：本仓库所有后端接口均以 **`/api/`** 为前缀，例如 `/api/user/register`、`/api/clan/member`。Clan 的 Web 端路径为 `/api/clan/xxx`；客户端兼容路径仍为 `/Clan/xxx.asp`（见 〇.1）。

原版所有接口均通过 **GET 或 POST** 传参（`Request("key")`），响应为**文本**：多行用 `Chr(0x0D)`（`\r`）分隔，键值对形式 `Key=Value`；SodScore.asp 部分接口额外使用 `Chr(0x7C)`（`|`）分隔。**Code=100** 表示参数缺失/错误；**Code=0** 一般表示业务失败；**Code=1** 或 **Code=2** 等表示成功或不同状态。

---

## 〇、接口路由与路径对照（合一）

客户端使用 **`/Clan/xxx.asp`**，Web 端使用 **`/api/clan/xxx`**（符合项目约定 `/api/` 前缀）。在 Java Controller 中**同时定义**两列路径，同一业务逻辑复用；.asp 返回原版文本格式，/api/clan 可返回 JSON 或同结构。原版 ASP 未区分 GET/POST，建议两套路径**同时支持 GET 与 POST**。

| 客户端路径（.asp） | Web 端路径（/api/clan） | 请求方式 | 功能描述 |
|--------------------|-------------------------|----------|----------|
| `/Clan/InviteClan.asp` | `/api/clan/invite` | POST | 族长/副族长邀请玩家入会（参数：userid, gserver, chname, clName, clwon, clwonUserid, lv, chtype, chlv, chipflag） |
| `/Clan/NewClan.asp` | `/api/clan/new` | POST | 创建公会（参数：userid, gserver, chname, clName, chtype, lv） |
| `/Clan/DeleteClan.asp` | `/api/clan/delete` | POST | 解散公会，仅族长且 ClanList.AccountName=userid 时可操作（参数：userid, gserver, chname, clName） |
| `/Clan/LeavePlayer.asp` | `/api/clan/leave-player` | POST | 族长/副族长踢出成员（参数：userid, gserver, chname, clName, clwon1, ticket） |
| `/Clan/LeavePlayerSelf.asp` | `/api/clan/leave-self` | POST | 自己退会（参数：userid, gserver, chname, clName） |
| `/Clan/ClanMember.asp` | `/api/clan/member` | GET / POST | 查询当前角色公会详情（族长/副族长/成员返回不同 Code；参数：userid, gserver, chname） |
| `/Clan/ChangeLeader.asp` | `/api/clan/change-leader` | POST | 转让族长给指定角色（参数：chname, gserver, clName） |
| `/Clan/SubLeaderRelease.asp` | `/api/clan/sub-leader-release` | POST | 取消指定角色的副族长身份（参数：chname, gserver） |
| `/Clan/SubLeaderUpdate.asp` | `/api/clan/sub-leader-update` | POST | 将指定角色设为副族长（同公会其他副族长先被取消）（参数：chname, gserver） |
| `/Clan/GetClanMembers.asp` | `/api/clan/members` | GET / POST | 获取本公会成员列表（参数：userid, gserver, chname） |
| `/Clan/CheckClanLeader.asp` | `/api/clan/check-leader` | GET / POST | 查询当前账号是否为某公会族长（参数：userid, gserver） |
| `/Clan/CheckDate.asp` | `/api/clan/check-date` | GET / POST | 查询角色是否在公会（参数：chname） |
| `/Clan/CheckUnknown.asp` | `/api/clan/check-unknown` | GET / POST | 查询角色是否在公会（参数：chname） |
| `/Clan/CheckClanPlayer.asp` | `/api/clan/check-player` | GET / POST | 占位接口，直接返回 Code=1（参数：clwon, gserver） |
| `/Clan/CheckClanID.asp` | `/api/clan/check-id` | GET / POST | 按公会图标编号查公会名与公告（参数：num, gserver） |
| `/Clan/CheckClanName.asp` | `/api/clan/check-name` | GET / POST | 查询公会名是否已存在（参数：ClName, gserver） |
| `/Clan/SodScore.asp` | `/api/clan/sod-score` | GET / POST | SOD/贝尔特拉：index=1 主页面信息，index=3 高分公会列表（参数：userid, gserver, chname, index） |

---

## 〇.1 双路径约定（已采纳）

在 Java Controller 中**同时定义两类路径**，同一业务逻辑复用，仅路径与响应形式区分：

| 路径风格 | 用途 | 响应格式 |
|----------|------|----------|
| **`/Clan/xxx.asp`** | 原版 C++ 客户端兼容 | 与原版一致：文本、`\r` 分隔、`Key=Value`、Code=0/1/2/100 等 |
| **`/api/clan/xxx`** | 后续开发的 Web 端（管理/查询等） | 可由 Web 端约定（如 JSON）；与 .asp 同逻辑时可先复用文本格式，再按需增加 JSON |

**实现要点**：每个接口在 Controller 中同时声明 `.asp` 与 `/api/clan/xxx` 两套路径（见上表），业务放在同一 Service；.asp 的 Handler 转成原版文本响应，/api/clan 的 Handler 可返回 JSON。参数从 Query 或 Form 取，参数名与原 ASP 一致。

---

## 一、核心公会操作（游戏内调用）

| # | ASP 文件 | 逻辑名（注释） | 参数 | 响应要点 |
|---|----------|-----------------|------|----------|
| 1 | **InviteClan.asp** | clanInsertClanWon | userid, gserver, chname, clName, clwon, clwonUserid, lv, chtype, chlv, chipflag | Code=1 成功；Code=0 无公会/无权限/已在公会；Code=2 成员超 100；Code=4 成员>20 |
| 2 | **NewClan.asp** | — | userid, gserver, chname, clName, chtype, lv | Code=1 成功；Code=100 缺参；Code=2 已在公会；Code=3 公会名已存在 |
| 3 | **DeleteClan.asp** | clanRemove | userid, gserver, chname, clName | Code=1 成功；Code=0 失败/非族长；仅当 ClanList.AccountName=userid 且 chname=ClanLeader 时执行删除 |
| 4 | **LeavePlayer.asp** | clanWonRelease | userid, gserver, chname, clName, clwon1, ticket | 族长/副族长踢人 clwon1；Code=1 成功；Code=0/4（族长不能踢自己） |
| 5 | **LeavePlayerSelf.asp** | clanWonSelfLeave | userid, gserver, chname, clName | 自己退会；Code=1 成功；Code=0/4（族长不能自己退会） |
| 6 | **ClanMember.asp** | isClanMember | userid, gserver, chname | 查询当前角色公会信息；Code=0 无公会；Code=1 普通成员；Code=2 族长；Code=5 副族长；返回 CName, CNote, CZang, CSubChip, CMCnt, CIMG, CRegiD, CLimitD, CPFlag, CKFlag, CMoney, CNFlag 等 |
| 7 | **ChangeLeader.asp** | clanChipChange | chname, gserver, clName | 转让族长给 chname；Code=1 成功，Code=0 失败 |
| 8 | **SubLeaderRelease.asp** | clanSubChipRelease | chname, gserver | 取消 chname 的副族长（Permi=0） |
| 9 | **SubLeaderUpdate.asp** | clanSubChipUpdate | chname, gserver | 先清空同公会其他副族长再设 chname 为副族长（Permi=2） |
| 10 | **GetClanMembers.asp** | GetAllMyClanMember | userid, gserver, chname | Code=1 成功时返回 CClanName, CClanZang, 以及多行 CMem=角色名 |

---

## 二、校验/查询（游戏内调用）

| # | ASP 文件 | 逻辑名（注释） | 参数 | 响应要点 |
|---|----------|-----------------|------|----------|
| 11 | **CheckClanLeader.asp** | isCheckClanJang | userid, gserver | 当前账号是否为某公会族长；Code=1 是，Code=0 否 |
| 12 | **CheckDate.asp** | isPFlag | chname（注：注释写 userid,gserver,chname,clName,PFlag,Gubun，实现仅用 chname） | 角色是否在公会；Code=1 在，Code=0 不在 |
| 13 | **CheckUnknown.asp** | isKflag | chname | 同上，角色是否在公会；Code=1 在，Code=0 不在 |
| 14 | **CheckClanPlayer.asp** | isCheckClanwon | clwon, gserver | 未查库，直接返回 Code=1（占位） |
| 15 | **CheckClanID.asp** | isCheckClanNum | num, gserver | 按公会图标编号 num（CL.MIconCnt）查公会；Code=1 返回 CName, CNote；Code=0 不存在 |
| 16 | **CheckClanName.asp** | isCheckClanName | ClName, gserver | 公会名是否存在；Code=1 存在，Code=0 不存在 |

---

## 三、SOD/贝尔特拉相关（SodScore.asp）

| # | ASP 文件 | 逻辑名（注释） | 参数 | 响应要点 |
|---|----------|-----------------|------|----------|
| 17 | **SodScore.asp** | sod2info | userid, gserver, chname, index | **index=1**：主页面（Karina），按身份返回不同 Code(1–6) 及 CClanMoney, CName, CNote, CZang, CIMG, TotalEMoney 等（分隔符 `\|`）；**index=3**：高分公会列表，Code=1 + 多行 CIMG, CName, CPoint, CRegistDay；Code=100/104 参数错误 |

注：SodScore.asp 使用 `SODsettings.asp`（可能多库配置），响应除 `strSplit` 外还有 `strSplit2 = Chr(0x7C)`。

---

## 四、Ticket 校验（settings.asp）

- **CheckTicket(useridcheck, ticketcheck)**：从 CT 表查 `UserID` + `ServerName`，校验 Ticket（SNo）；失败时统一输出 `Code=100` 并 `Response.End`。  
- 部分 ASP（如 LeavePlayer.asp）会传 `ticket` 参数，原版是否在每页都调 CheckTicket 需看各 ASP include 与调用顺序；实现时建议对需要认证的接口统一做 Ticket 或登录态校验。

---

## 五、不需要实现的文件

- **default.asp**：重定向到外部站，与游戏无关。
- **ErrorPage.asp**：错误页，可合并到统一错误响应。
- **settings.asp**：公共配置与 CheckTicket、SQL 注入检查，逻辑应合并到 pt-clan-server 的配置与拦截器中，不单独暴露接口。

---

## 六、汇总：需提供的接口列表（建议路径与方法）

合并到统一 Web 服务时，建议在 **`/clan/`** 下按原 ASP 文件名或逻辑名提供等价 HTTP 接口，保持参数与响应格式与客户端/服务端预期一致，便于联调：

| 原 ASP | 建议路径 | 说明 |
|--------|----------|------|
| InviteClan.asp | POST /clan/invite | 邀请入会 |
| NewClan.asp | POST /clan/new | 创建公会 |
| DeleteClan.asp | POST /clan/delete | 解散公会（仅族长且 AccountName 匹配） |
| LeavePlayer.asp | POST /clan/leave-player | 踢人（族长/副族长） |
| LeavePlayerSelf.asp | POST /clan/leave-self | 自己退会 |
| ClanMember.asp | GET/POST /clan/member | 查询当前角色公会详情 |
| ChangeLeader.asp | POST /clan/change-leader | 转让族长 |
| SubLeaderRelease.asp | POST /clan/sub-leader-release | 取消副族长 |
| SubLeaderUpdate.asp | POST /clan/sub-leader-update | 设为副族长 |
| GetClanMembers.asp | GET/POST /clan/members | 获取本公会成员列表 |
| CheckClanLeader.asp | GET/POST /clan/check-leader | 是否族长（按 userid） |
| CheckDate.asp | GET/POST /clan/check-date | 角色是否在公会 |
| CheckUnknown.asp | GET/POST /clan/check-unknown | 同上 |
| CheckClanPlayer.asp | GET/POST /clan/check-player | 占位，固定 Code=1 |
| CheckClanID.asp | GET/POST /clan/check-id | 按图标编号查公会名/公告 |
| CheckClanName.asp | GET/POST /clan/check-name | 公会名是否存在 |
| SodScore.asp | GET/POST /clan/sod-score | index=1 主页面信息，index=3 高分公会列表 |

**响应格式**：与原版一致，文本、`\r` 分隔行、`Key=Value`；Code=100 参数错误，Code=0 业务失败，Code=1/2/… 成功或状态码。SodScore 部分用 `|` 分隔。

---

## 七、C++ 代码中何处使用这些接口

### 7.1 结论概览

- **调用方**：**游戏客户端（Game）**，不是服务端（Server）。客户端通过 **WinInet** 打开 ClanSystem 的 ASP URL（GET 或 POST 由原版 exe 决定），请求由浏览器或内嵌 HTTP 逻辑发出。
- **服务端**：**不直接请求 ClanSystem 的 HTTP 接口**。服务端只读写数据库（如 ClanDB 等价表、UserDB.CharacterInfo 的 ClanId 等），或通过包与客户端同步公会/贝尔特拉等数据；ClanSystem 的 ASP 由客户端连到同一套 Web 服务完成“创建/解散/邀请/退会/查询”等操作。

### 7.2 客户端（Game）中的位置

| 位置 | 说明 |
|------|------|
| **game/game/Game.h** | 声明 `InternetOpenUrlClan`：用于替换原版 exe 中通过 WinInet 打开 URL 的函数指针。 |
| **game/game/Game.cpp** | 实现 `InternetOpenUrlClan`：内部调用 `InternetOpenUrlA( hInternet, lpszUrl, ... )`，即实际发起对 ClanSystem URL 的请求。**URL 的构建（哪个 .asp、哪些查询参数）不在本仓库源码中**，由原版 game.exe 内逻辑或未反编译部分决定。 |
| **game/game/DLL.cpp** | 约第 3855 行：`(*(DWORD*)0x04AF8124) = (DWORD)&Game::InternetOpenUrlClan`；将原版 exe 中地址 `0x04AF8124` 处的函数指针替换为 `Game::InternetOpenUrlClan`。因此原版所有“打开 Clan 相关 URL”的调用都会走该实现。 |
| **game/game/ManageWindow.cpp** | 公会成员列表 UI（`RenderClanMemberStatus`、`pszaClanMembers` 等），显示从服务端/Clan 接口取得的成员与在线状态；不直接包含 ASP 名称。 |
| **game/game/CCharacterScreen.cpp** | 调用 `Game::DeleteClanTag( serverName )`，仅删除本地 `save\clanDATA\<server>\*.bmp` 与 `*.rep` 文件，与 HTTP 接口无关。 |
| **Files/Game/image/clanImage/Help/ClanText.msg** | 文案中引用 `*szisCheckClanJangErrorMsg`（对应 CheckClanLeader 错误提示），说明 **CheckClanLeader.asp**（isCheckClanJang）在客户端流程中被使用。 |

由以上可知：**具体在游戏流程的哪一步调用哪一个 .asp**（如 InviteClan、NewClan、ClanMember 等），在现有 C++ 源码中**没有显式列出**，只能确定“所有 Clan 相关 HTTP 请求都经由 `InternetOpenUrlClan` 发出的 URL”。实现 pt-clan-server 时需按原版 ASP 列表**全部提供**上述 17 个接口，以保证客户端任意界面或操作触发到某 ASP 时都能正确响应。

### 7.3 服务端（Server）中的说明

| 位置 | 说明 |
|------|------|
| **Server/server/bellatraserver.cpp** | 约第 115 行注释：`//from ClanMember.asp`，含义为“玩家从 ClanMember.asp 获取 crown 索引等信息”，即**客户端**从 ClanMember.asp 取数，不是服务端去请求该 ASP。同文件中的 `RecordPoint( ..., SodScore, ... )`、`AddTaxGoldToTopThreeClans` 等为服务端**内部**对贝尔特拉/公会积分与税收的逻辑，写数据库或发包，**不调用 ClanSystem HTTP**。 |
| **Server/server/netserver.cpp** | `SendClan( pcUserData )`、公会在线广播等，为服务端与客户端之间的**游戏协议包**，与 ClanSystem 的 ASP 无关。 |
| **Server/server/characterserver.cpp** | 公会相关发包、升级广播等，同上，均为游戏协议，不请求 ASP。 |
| **Server/server/blesscastleserver.cpp** | 祝福城堡与公会 ID、TopClans 同步等，读写 DB 或发包，不请求 ClanSystem。 |

因此：**服务端没有任何一处发起对 ClanSystem（InviteClan.asp、NewClan.asp、ClanMember.asp 等）的 HTTP 请求**。服务端只依赖数据库中的公会与角色数据；客户端通过 HTTP 调用 ClanSystem 完成公会操作后，再通过游戏协议与登录/角色流程与服务端同步（如 ClanId、公会名等）。

---

## 八、用 Java 实现 Clan 后如何让客户端生效

用 Java 实现完 ClanSystem 的 17 个接口后，要让**原版 C++ 客户端**连到你的 Java 服务，需要满足两点：**客户端能拿到 Clan 的地址**，以及**请求路径与响应格式与客户端预期一致**。

### 8.1 客户端如何拿到 Clan 服务器地址

原版 **Login Server** 在发送**服务器列表包**（PacketServerList）时，会带上一个 **“clan” 服务器条目**，客户端用这个条目的 **IP + 端口** 作为 ClanSystem 的 HTTP 基地址。

- **C++ 位置**：`Server/server/server.cpp` 的 `PHServerList()`（约 1192–1236 行）。
- **逻辑**：在游戏服列表之后追加一条 `servers[j]`，`szName = "clan"`，`szaIP[0/1/2]` 取的是**第一个游戏服的 IP**（`saServerInfo[0].szIP`），**iaPort[0] = 80**（HTTP），`iClanServerIndex = j`。
- **客户端行为**：用该条目的 IP + **port[0]**（80）拼出 Clan 的基地址，再按原版逻辑拼路径（见下），例如 `http://<该IP>:80/Clan/InviteClan.asp?...`。

因此要让客户端连到你的 Java 服务，有两种做法：

**做法 A（推荐）：在 pt-login-server 里正确下发 Clan 条目**

- 在 **pt-login-server** 的 `sendServerList()` 中，除游戏服外再填一条 **Clan 服务器**：
  - `servers[1].name = "clan"`（或与 C++ 一致）；
  - `servers[1].ip[0]`（及 ip[1]、ip[2] 若客户端会用）= **运行 Java Clan 服务的那台机子的 IP 或域名**（客户端要能访问）；
  - `servers[1].port[0] = 80`（若 Java 用 80）或 **8080**（若 Java 用 8080，则填 8080）；
  - `header.setGameServers(1)`，**header.setClanServerIndex(1)**，表示 `servers[0]` 是游戏服，`servers[1]` 是 Clan 服。
- 这样客户端登录后拿到的列表里就包含你的 Java Clan 地址，无需改客户端 exe。

**做法 B：不改 Login Server，改客户端或网络**

- 若暂时不改 pt-login-server，则客户端当前拿到的 Clan 地址仍是 C++ 里配置的（例如第一个游戏服 IP + 80 端口）。可：
  - 在**运行 Java Clan 的那台机子上**用 **80 端口**（或与 C++ 配置一致）提供 Clan 接口，并把该机 IP 配成“第一个游戏服”的 IP（或 hosts/DNS 把该域名指到这台机子）；或
  - 用 **反向代理**（如 Nginx）在该 IP:80 上把 `/Clan/*.asp` 转到 Java 的对应路径（见 8.2）。

### 8.2 路径与响应格式要让客户端“无感”

客户端会按**原版 ASP 的路径**发请求，例如：

- `http://<clan_host>:<port>/Clan/InviteClan.asp?userid=...&gserver=...&...`
- `http://<clan_host>:<port>/Clan/ClanMember.asp?...`

**已采纳做法**（与 **〇.1 双路径约定** 一致）：在 Java Controller 中**直接定义** `/Clan/xxx.asp` 路径，同时定义 `/api/clan/xxx` 供 Web 端使用。.asp 路径返回原版文本格式，客户端无需任何修改，只要 8.1 的地址指到你的 Java 服务即可；无需再用反向代理做路径重写。

### 8.3 小结：最小改动让客户端生效

1. **pt-login-server**：在 `sendServerList()` 里增加/修正 Clan 条目，把 **Clan 的 IP 和端口**（你 Java Clan 服务或前面反向代理的地址）发给客户端；并设置 `header.setClanServerIndex(1)`（或与当前 servers 数组下标一致）。
2. **Java Clan 服务**：在 Controller 中同时提供 **/Clan/xxx.asp**（客户端兼容）与 **/api/clan/xxx**（Web 端），见 〇.1。
3. **响应格式**：所有接口返回**文本**、`\r` 分隔、`Code=N` 及原有 `Key=Value`，与文档一～三节及原版 ASP 一致。

完成后，客户端在游戏内进行“创建公会、邀请、退会、查询”等操作时，会向你在服务器列表中下发的地址发 HTTP 请求，即可命中你的 Java 实现。

**Nginx 兼容**：本仓库开发环境（`server/docker-compose.yml`）中的 Nginx 已将 **`/Clan/`** 反向代理到宿主机 8080，与 `/api/` 一致。若客户端拿到的 Clan 地址为 Nginx 所在主机:80（例如与 Web 站同机），则对 `http://<host>:80/Clan/xxx.asp` 的请求会由 Nginx 转发到后端，无需额外配置。

---

## 九、与现有 pt-dao 的对应

SQL 与 Mapper 对应已见 **docs/plans/2026-03-15-clansystem-asp-sql-migration.md**；本清单仅列出“需要对外提供的接口”及参数/响应，实现时在 pt-clan-server（或合并后的 Web 模块）中调用 pt-dao 的 ClMapper、UlMapper、ClanListMapper、CtMapper、LiMapper 及 userdb.CharacterInfoMapper 完成上述接口逻辑。
