package org.jpstale.server.game.model;

import lombok.Getter;
import lombok.Setter;
import org.jpstale.common.service.model.MonsterStats;
import org.jpstale.server.game.entity.BaseEntity;
import org.jpstale.server.game.entity.EntityIdSource;

/**
 * 怪物实体
 *
 * 由 BaseEntity 继承运行时 id/mapId/x/y/z/angle;本类保留怪模板与运行时游戏数据。
 */
@Getter
@Setter
public class Monster extends BaseEntity {

    /**
     * 掉落表键 = `monsterlist.monsterid`（业务 id，**不是**主键 id；见
     * {@code MonsterSpawnService.createMonster}）。同时作为 `appear.templateId` 下发（客户端忽略）。
     */
    private int templateId;
    private String name;
    /** 怪物显示名的 i18n 键名段（= 该怪 .inf 文件名词干，小写无扩展名；空 = 没对上 inf）。 */
    private String nameKey;
    private int level;
    private int hp;
    private int maxHp;
    private int mp;
    private int maxMp;
    private int atkMin;
    private int atkMax;
    private int defense;
    /** 命中值（模板 attackrating）：怪打玩家的命中判定用（原版 sinGetMonsterAccuracy） */
    private int attackRating;
    private float speed;
    private float attackRange;
    /**
     * `monsterlist.attackspeed` —— **原版语义是"攻击动画的播放速度档"**，不是"间隔毫秒"。
     *
     * 依据（EU `shared/unit.h:697` 的字段注释）：`iMotionLoopSpeed // Frame rate for repeating motion`，
     * 而它由 `GetAttackFrameSpeed(iAttackSpeed)` 算出（`80 + 10*clamp(speed-6,0,6)`，
     * ex-machina `playsub.cpp:5374`）。⇒ **数值越高，动画播得越快、出刀越快**。
     * 实测 DB 取值 1/4/5/6/7/8/9（多数 7~9）⇒ 播放步进 80~110。
     */
    private float attackSpeed;
    /**
     * 本刀**选中的攻击动画条目索引**（`.inx` 序号）—— 出刀时由 `MonsterAnimData.pick` 选定，
     * 随 `S2C_MonsterMove.anim_index` 下发给客户端（它们直接 `playMotion(这一条)`，不再各自随机）。
     * `-1` = 还没选 / 该模型没有攻击动画。
     */
    private int attackAnimIndex = -1;
    /**
     * 本刀选中条目的**帧数**（`MonsterAnimData.Entry.frames()`）；0 = 该模型没有攻击动画。
     *
     * 为什么要它：原版服务端跑同一份 `smCHAR::Main()`，怪进入 ATTACK 后要等动画播完才切状态；
     * 我们服务端不持有动画数据，只能靠"帧数 ÷ 播放步进"算出这段时长（见 `getAttackIntervalMs`）。
     * 因为服务端**自己选的条目**，这里的帧数是**精确值**（不是对多条取的上界）。
     */
    private int attackAnimFrames;

    /** 出刀时选定本刀要播的攻击动画条目（服务端权威 —— 所有客户端必须看到同一条）。 */
    public void setAttackAnim(MonsterAnimData.Entry entry) {
        this.attackAnimIndex = entry == null ? -1 : entry.index();
        this.attackAnimFrames = entry == null ? 0 : entry.frames();
    }
    private MonsterState state;
    private Long targetPlayerId; // 当前仇恨目标（玩家）
    /**
     * 当前仇恨目标（**怪物**）—— 只在"召唤物 ↔ 怪"这条链上用。
     *
     * <p>
     * 为什么是两个字段而不是一个泛型目标：目标的两条链在**结算方式**上完全不同
     * （对玩家走 `calculateMonsterToPlayer` + 格挡/受击硬直，对怪走
     * `calculateMonsterToMonster` + 百分比吸收），把它们塞进一个类型只会让
     * {@code tryAttack} 里出现一层没有语义的强制转换。位移侧已经解耦
     * （`MovementService` 读 `AiContext.targetX/targetZ`），所以这里多一个字段不会扩散。
     */
    private Long targetMonsterId;
    private long lastMoveTime;
    private long lastAttackTime;
    private long deathTime;

    // ======== 召唤物归属（怪物水晶） ========
    //
    // 对应原版的三件套：`lpMasterPlayInfo`（主人指针）+ `smCharInfo.Next_Exp`（**借字段**存主人
    // serial）+ `smCharInfo.szModelName2+1`（**借字段**存主人名字）—— 见
    // `docs/召唤物系统-源码分析.md` §3.5。原版是字段复用，我们**全部显式化**（该文 §10.1 #2）：
    // 复用字段的代价是任何读那个字段的代码都得知道这个约定，而且会与字段本意打架。

    /** 主人角色 id（`PlayerCharacter.id`）；0 = 不是召唤物。判定"主人还在不在"用它。 */
    private long ownerCharId;
    /** 主人**运行时实体 id**（`PlayerEntity.getId()`）。下发给客户端，供它认"这是我自己的召唤物"。 */
    private long ownerEntityId;
    /** 主人角色名 —— 客户端在名牌第二行画 `(名字)`（原版 `Winmain.cpp:4010-4019`）。 */
    private String ownerName;
    /** 到期时刻（ms）；0 = 不超时。原版 = `dwUpdateCharInfoTime`（4 分钟 + 主人等级×2 秒）。 */
    private long summonExpireMs;

    /**
     * 召唤物的**总寿命**（毫秒，= `4 分钟 + 主人等级×2 秒`）。
     *
     * <p>
     * 为什么要额外存一份"总量"：客户端要在头顶画一条**按比例**收缩的倒计时条
     * （用户 2026-09-23 要求），那就需要"当前剩余 / 一开始有多少"。只发剩余量的话，
     * 客户端要么把寿命公式抄一份（= 服务端规则出现第二份实现），要么在"走出视野再走回来"
     * 收到新的 Appear 时把进度条重置成满格（错）。两个数都由服务端给，客户端只做除法。
     */
    private long summonLifeTotalMs;

    /** 是不是玩家的召唤物（有主人）。原版判据是 `smCharInfo.Brood == smCHAR_MONSTER_USER`。 */
    public boolean isSummon() {
        return ownerCharId > 0;
    }
    /**
     * 死亡后的刷新冷却（毫秒）——由**出生点**消费（`SpawnPoint.onMonsterDeath`），
     * 表达"这个刷新位被击杀后要等多久才补下一只"。
     *
     * ⚠ 它**不是**尸体停留时长（那是 `decayTime`）。过去两者是同一个时钟：尸体在
     * `respawnTime` 之后才被移除，而出生点名额是在"移除"那一刻才释放 —— 于是"尸体躺多久"
     * 顺带决定了"刷多快"。现在拆开：尸体停留 = `decayTime`（表现），刷新节奏 = 本字段（玩法）。
     */
    private int respawnTime; // 刷新冷却（毫秒）

    public static final int DEFAULT_DECAY_MS = 2000;
    private int decayTime = DEFAULT_DECAY_MS;
    private int absorption;     // 吸收率 (%)
    private int exp;            // 击杀经验
    private int gold;           // 掉落金币
    private float viewsight;    // 视野/仇恨范围
    private int intelligence;   // AI 类型（0=被动，>0=主动攻击）
    private int nature;         // 本性：1=Evil主动攻击, 0=Neutral被动(受击反击), 2=Good中立

    /**
     * 目标信息窗相机补正 = monsterlist.cameray / cameraz（客户端 smCHAR_INFO.ArrowPosi[2]，
     * 怪物 .inf 的 `*화면보정`"画面补正"行）。随 S2C_MonsterAppear 下发，**只对怪物生效**：
     * [y]=目标窗相机在锚点上再抬高、[z]=再拉远（语义见 docs/目标信息窗-源码分析.md §3）。
     */
    private int cameraY;
    private int cameraZ;

    /**
     * 种族（原版 `smCharInfo.Brood`，来自 monsterlist.**propertymon** 列 —— 用户 2026-09-24 指认；
     * 原版同源：怪物脚本 `*몬스터종족` 字段 ⇒ `fileread.cpp:4130-4175` 的 Brood 赋值）。
     * 消费点：Jumping Crash 对 DEMON +30%（`Svr_Damge.cpp:2834`）等"对某族加成"系。
     */
    public enum Brood { NORMAL, UNDEAD, MUTANT, DEMON, MECHANIC }

    private Brood brood = Brood.NORMAL;

    public Brood getBrood() {
        return brood;
    }

    public void setBrood(Brood brood) {
        this.brood = brood == null ? Brood.NORMAL : brood;
    }
    private float moveRange;    // 活动/归位范围（出生点为中心）

    /** 能否跑步(IQ≥6,原版以此决定 run 动画与跑速档);出生时由模板 intelligence 决定 */
    private boolean canRun;

    // 出生点信息（归位用）
    private int spawnPointIndex = -1;  // 所属出生点索引
    private double spawnX;              // 出生点X坐标
    private double spawnZ;              // 出生点Z坐标
    private long lastTransTime;        // 最后与玩家交互时间

    // 客户端渲染：资产相对路径（如 char/monster/monimp/monimp-a.inx）
    private String modelFile;

    /** 怪物音效/特效 ID（C++ dwCharSoundCode / EMonsterEffectID），从 INI 的 ȿ 字段读出 */
    private int monsterEffectId;

    // 掉落（对齐 monsterlist.dropquantity / dropispublic）
    private int dropQuantity = 1;      // 掉落掷点次数
    private boolean dropIsPublic;      // true=公共可见，false=仅击杀者可见

    // 广播节流（AOI 写入）：动画 token / 位置 / 朝向只在变化时下发给观察者
    private int lastBroadcastAnim = -1;
    private double lastBroadcastX = Double.NaN;
    private double lastBroadcastZ = Double.NaN;
    private double lastBroadcastAngle = Double.NaN;

    /** D10 邻近回收：最近一次"有玩家临近(DISCONNECT 内)"的时间(ms) */
    private long lastNearPlayerMs = System.currentTimeMillis();

    /**
     * 死亡事件负载（击杀者 + 经验/金币），在结算那一刻写入 `MonsterAOI.onMonsterDeath` 读取后组包。
     *
     * **为什么记在怪物身上**：结算（`CombatService.handleMonsterDeath`）与 AOI 广播之间夹着
     * 掉落地生成、战斗日志、**落库**等耗时步骤，而广播按**观察者**逐条组包 —— 经验/金币
     * 只发给击杀者，其余观察者只收"死亡"这个事件。负载先落在怪物上，广播时按 pid 决定带不带 exp/gold。
     *
     * 另一条不变式：`state == DEAD` ⇒ `deathInfo != null`（唯一的致死入口就是
     * `CombatService.handleMonsterDeath` → `onDeath()`，`takeDamage` 这条旁路已删除）。
     * 若哪天出现"没有死亡负载的尸体"，那说明有新的致死路径绕过了结算 —— 客户端会表现为
     * "怪站着不动然后凭空消失"，所以那条路必须补上负载，不能只改状态。
     */
    private DeathInfo deathInfo;

    /**
     * 「DEAD 但没有死亡负载」这条不变式只报一次（`MonsterAOI.reconcile` 每 tick 逐怪自检，
     * 不设这个标记会 20 次/秒刷屏）。仅诊断用，不参与任何玩法判定。
     */
    private boolean missingDeathPayloadLogged;

    /**
     * 死亡事件负载（不可变）。字段类型对齐 CombatService.onMonsterDeath 的入参（killerId/exp 为 long）。
     * {@code expShares} = 组队分摊表（成员 → 各自份额，**含击杀者**）；单人击杀时是单条表。
     * {@code exp} = 击杀者本人的份额（兼容旧读法）。
     */
    public record DeathInfo(long killerId, long exp, int gold, java.util.Map<Long, Long> expShares) {}

    public DeathInfo getDeathInfo() {
        return deathInfo;
    }

    public void setDeathInfo(DeathInfo deathInfo) {
        this.deathInfo = deathInfo;
    }

    public Monster() {
        super(EntityIdSource.nextId());
        initDefaults();
    }

    public Monster(long id) {
        super(id);
        initDefaults();
    }

    private void initDefaults() {
        this.state = MonsterState.IDLE;
        this.attackRange = 2.0f;
        this.attackSpeed = 1000.0f; // 1秒
        this.respawnTime = 30000; // 30秒
    }

    /** 致死入口（唯一）：`state/hp/deathTime` 只在这里改。hp 必须一并置 0 —— `isAlive()` 读的是 hp。 */
    public void onDeath() {
        state = MonsterState.DEAD;
        hp = 0;
        deathTime = System.currentTimeMillis();
        targetPlayerId = null;
        targetMonsterId = null;
    }

    public boolean isAlive() {
        return hp > 0;
    }

    /**
     * 该模型**没有 ATTACK 动画条目**时用来推间隔的帧数。
     *
     * 实测 456 条 `monsterlist` 里有 **20 个**模型没有 ATTACK 条目（`Naz`/`Mystic`/`Ice Bomb`/
     * `Shurikar`/`Seal Crasher`/`Soldier 1,2`/`Minigue`/城门/部分 NPC…），帧数统计的中位是 **40**。
     * 这类怪**照样要出刀**（原版 `SetMotionFromCode(ATTACK)` 找不到条目时只是状态不变，
     * 伤害照结算）—— 取中位是为了让它们的节奏与普通怪同量级，不是为了"假装有动画"。
     */
    private static final int NO_ANIM_ATTACK_FRAMES = 40;

    private static final int RENDER_FPS = 60;

    /** 客户端动画基准（`char/animation.ts` 的 `ANIM_UNITS_PER_SEC`）—— 换算播放速率用 */
    private static final int CLIENT_ANIM_UNITS_PER_SEC = 4800;

    /** 攻击动画的播放步进（每渲染帧前进的动画单位；1 动画帧 = 160 单位，60 渲染帧/秒） */
    public int attackAnimStep() {
        int clamped = Math.clamp((int) attackSpeed - 6, 0, 6);
        return 80 + 10 * clamped;   // 原版 GetAttackFrameSpeed（ex-machina playsub.cpp:5374）
    }

    public long getAttackIntervalMs() {
        int frames = attackAnimFrames > 0 ? attackAnimFrames : NO_ANIM_ATTACK_FRAMES;
        return Math.round(frames * 160.0 * 1000.0 / (attackAnimStep() * RENDER_FPS));
    }

    public float getAnimRate() {
        return attackAnimStep() * (float) RENDER_FPS / 4800f;
    }

    /** 死亡时刻 + `decayTime` 已到（只用于"何时移除"，不是"是否可见"）。 */
    public boolean isDecayed(long nowMs) {
        return !isAlive() && nowMs - deathTime >= decayTime;
    }

    public void heal(int amount) {
        hp = Math.min(maxHp, hp + amount);
    }

    public double distanceTo(double targetX, double targetY, double targetZ) {
        double dx = x - targetX;
        double dy = y - targetY;
        double dz = z - targetZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public void moveTo(double targetX, double targetY, double targetZ, double maxDistance) {
        double dx = targetX - x;
        double dy = targetY - y;
        double dz = targetZ - z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (distance <= maxDistance) {
            x = targetX;
            y = targetY;
            z = targetZ;
        } else {
            double ratio = maxDistance / distance;
            x += dx * ratio;
            y += dy * ratio;
            z += dz * ratio;
        }
    }

    /**
     * 战斗计算所需的属性快照（共享层 `DamageCalculator` 只认 {@link MonsterStats}，不认本实体）。
     * 多一项入参就得改这里 + 所有调用点 —— 这是有意的摩擦，防止共享层悄悄依赖实体。
     */
    public MonsterStats combatStats() {
        return new MonsterStats(level, defense, absorption, maxHp, attackRating, atkMin, atkMax);
    }
}
