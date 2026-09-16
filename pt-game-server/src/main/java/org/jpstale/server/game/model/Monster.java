package org.jpstale.server.game.model;

import lombok.Getter;
import lombok.Setter;
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

    private int templateId;
    private String name;
    private int level;
    private int hp;
    private int maxHp;
    private int mp;
    private int maxMp;
    private int attack;
    private int defense;
    /** 命中值（模板 attackrating）：怪打玩家的命中判定用（原版 sinGetMonsterAccuracy） */
    private int attackRating;
    private float speed;
    private float attackRange;
    private float attackSpeed; // 攻击间隔（毫秒）
    private MonsterState state;
    private Long targetPlayerId; // 当前仇恨目标
    private long lastMoveTime;
    private long lastAttackTime;
    private long deathTime;
    /**
     * 死亡后的刷新冷却（毫秒）——由**出生点**消费（`SpawnPoint.onMonsterDeath`），
     * 表达"这个刷新位被击杀后要等多久才补下一只"。
     *
     * ⚠ 它**不是**尸体停留时长（那是 `decayTime`）。过去两者是同一个时钟：尸体在
     * `respawnTime` 之后才被移除，而出生点名额是在"移除"那一刻才释放 —— 于是"尸体躺多久"
     * 顺带决定了"刷多快"。现在拆开：尸体停留 = `decayTime`（表现），刷新节奏 = 本字段（玩法）。
     */
    private int respawnTime; // 刷新冷却（毫秒）

    /**
     * 尸体停留时长（毫秒）——从死亡时刻起算，到点后服务端发 `S2C_MonsterDisappear` 并移除实体。
     *
     * 依据（两个私服来源一致）：服务端 `OnSever.cpp` 的 `FrameCounter > 400` 才 `Close()` +
     * `DeleteMonTable`；客户端 `character.cpp` 的 `Draw` 里也是 `FrameCounter > 400` 让尸体全黑。
     * 两侧的 `FrameCounter` 都等价于"70fps 下的帧数" —— 服务端每次 `smCHAR::Main()` 在动作分支里
     * +3、函数尾部再无条件 +1（`gameserver/Legacy/Game/Character/character.cpp:3530`），而驱动被
     * `(srAutoPlayCount & 3) == 0` 抽成 1/4，净效果恰好 70/秒 = fps（`OnSever.cpp:9093` 的 `fps = 70`）。
     * ⇒ 400 帧 ≈ 5.71 秒，取整 **6000ms**。
     *
     * 这是**我们定的数**，出处就是上面这条推导 —— 原版没有 `m_dwDieTime` 这类常量，只有裸字面量
     * 400（另：`AUTO_DEAD_COUNT` 在两棵树里都定义了但从未被引用）。要改尸体躺多久，只改这里。
     */
    public static final int DEFAULT_DECAY_MS = 6000;
    private int decayTime = DEFAULT_DECAY_MS;
    private int absorption;     // 吸收率 (%)
    private int exp;            // 击杀经验
    private int gold;           // 掉落金币
    private float viewsight;    // 视野/仇恨范围
    private int intelligence;   // AI 类型（0=被动，>0=主动攻击）
    private int nature;         // 本性：1=Evil主动攻击, 0=Neutral被动(受击反击), 2=Good中立
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

    /** 死亡事件负载（不可变）。字段类型对齐 CombatService.onMonsterDeath 的入参（killerId/exp 为 long） */
    public record DeathInfo(long killerId, long exp, int gold) {}

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

    /**
     * 致死入口（**唯一**）。`state = DEAD` / `hp = 0` / `deathTime` 只在这里设置 ——
     * 调用方 `CombatService.handleMonsterDeath` 紧接着会 `setDeathInfo(...)`，
     * 于是"死"与"死亡负载"总是同时成立（见 `deathInfo` 的不变式）。
     *
     * `hp = 0` 是**必须**的，不是冗余：`isAlive()` 读的是 hp，而"尸体"的判据（`Appear.dead`、
     * AOI 的自检、客户端的红名/血条）全都走 `isAlive()`。若这里不动 hp，就会出现
     * `state == DEAD` 但 `isAlive() == true` 的怪物 —— 表现为"尸体被当成活怪下发"。
     * （生产路径上 hp 本来就是 0 才触发致死，这一步是把两个判据**钉成同一个**。）
     */
    public void onDeath() {
        state = MonsterState.DEAD;
        hp = 0;
        deathTime = System.currentTimeMillis();
        targetPlayerId = null;
    }

    public boolean isAlive() {
        return hp > 0;
    }

    /**
     * 尸体是否已到消失时刻（死亡时刻 + `decayTime`）。
     *
     * 尸体在这之前**是正常可见实体**（留在 AOI 可见集里、照样发 Appear），
     * 所以这个判据的用途只有"何时移除" —— 不要拿它当"是否对玩家可见"用。
     */
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
}
