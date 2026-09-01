-- ============================================================
-- userdb.item — 角色物品实例表（全字段水平展开）
-- 依据 exm CreateDefItem 掷点字段 + 状态字段设计
-- 基础字段（ItemName/Weight/Sight/Attack_Speed/Critical_Hit/
--   Shooting_Range/fMagic_Mastery/Potion_Space 等）来自定义模板
--   sDEF_ITEMINFO.Item（memcpy 复制），不存实例表，从 gamedb.itemlist 取。
-- 参考文档：/data/PristonTale/plans/item-instance-storage-reference.md
-- ============================================================

CREATE SCHEMA IF NOT EXISTS userdb;

SET search_path TO userdb, public;

CREATE TABLE IF NOT EXISTS userdb.item (
    -- ---- 4.1 实例定位 ----
    id            bigint GENERATED ALWAYS AS IDENTITY NOT NULL PRIMARY KEY,
    character_id  integer NOT NULL,                -- 归属角色 (FK userdb.character_info.id)
    location      smallint NOT NULL,               -- 0=背包 1=装备槽 2=仓库
    slot          smallint NOT NULL,               -- 槽位 (背包0-99 / 装备INVENTORY_POS_* / 仓库0-99)
    item_code     integer NOT NULL,                -- 物品定义 (FK gamedb.itemlist.idcode)
    itemlist_id   integer NULL,                    -- 物品定义唯一主键 (FK gamedb.itemlist.id)，消除 idcode 非唯一歧义
    count         integer NOT NULL DEFAULT 1,      -- 堆叠数量 (PotionCount)

    -- ---- 4.2 头部/校验 (ItemHeader, 网络层防复制) ----
    head          integer NOT NULL DEFAULT 0,      -- ItemHeader.Head
    dw_version    integer NOT NULL DEFAULT 0,      -- ItemHeader.dwVersion
    dw_time       integer NOT NULL DEFAULT 0,      -- ItemHeader.dwTime
    chksum        integer NOT NULL DEFAULT 0,      -- ItemHeader.dwChkSum (ReformStateCode)

    -- ---- 4.3 掷点结果字段 (CreateDefItem 覆盖模板的字段) ----
    -- 耐久 (生成时定格，之后战斗中变化；Durability[0]=当前 [1]=最大)
    durability     smallint NOT NULL DEFAULT 0,
    durability_max smallint NOT NULL DEFAULT 0,

    -- 8 项抗性 (Resistance[8]，sinItem.h:243-251 实锤)
    -- 0=BIONIC生化 1=EARTH大地 2=FIRE火 3=ICE冰 4=LIGHTING闪电 5=POISON毒 6=WATER水 7=WIND风
    res_bionic   smallint NOT NULL DEFAULT 0,
    res_earth    smallint NOT NULL DEFAULT 0,
    res_fire     smallint NOT NULL DEFAULT 0,
    res_ice      smallint NOT NULL DEFAULT 0,
    res_lighting smallint NOT NULL DEFAULT 0,
    res_poison   smallint NOT NULL DEFAULT 0,
    res_water    smallint NOT NULL DEFAULT 0,
    res_wind     smallint NOT NULL DEFAULT 0,

    -- 攻防
    damage_min    smallint NOT NULL DEFAULT 0,     -- Damage[0]
    damage_max    smallint NOT NULL DEFAULT 0,     -- Damage[1]
    attack_rating integer NOT NULL DEFAULT 0,
    absorb        real    NOT NULL DEFAULT 0,      -- fAbsorb
    defence       integer NOT NULL DEFAULT 0,
    block_rating  real    NOT NULL DEFAULT 0,      -- fBlock_Rating
    speed         real    NOT NULL DEFAULT 0,      -- fSpeed

    -- 回复/上限
    mana_regen    real NOT NULL DEFAULT 0,         -- fMana_Regen
    life_regen    real NOT NULL DEFAULT 0,         -- fLife_Regen
    stamina_regen real NOT NULL DEFAULT 0,         -- fStamina_Regen
    increase_life   real NOT NULL DEFAULT 0,       -- fIncrease_Life
    increase_mana   real NOT NULL DEFAULT 0,       -- fIncrease_Mana
    increase_stamina real NOT NULL DEFAULT 0,      -- fIncrease_Stamina

    -- 需求属性 (掷点/职业修正后的具体值；Level/Strength/Spirit/Talent/Dexterity/Health)
    req_level    integer NOT NULL DEFAULT 0,
    req_strength integer NOT NULL DEFAULT 0,
    req_spirit   integer NOT NULL DEFAULT 0,
    req_talent   integer NOT NULL DEFAULT 0,
    req_agility  integer NOT NULL DEFAULT 0,       -- Dexterity
    req_health   integer NOT NULL DEFAULT 0,

    -- 价格 (命中职业特效时 +20%)
    price        integer NOT NULL DEFAULT 0,

    -- 职业特效 (JobCodeMask：0=无职业限定；命中则 JobItem 生效，单一职业 bit)
    job_code_mask integer NOT NULL DEFAULT 0,

    -- ---- 4.4 职业特效增益 (JobItem / sITEM_SPECIAL，17 个实际使用字段) ----
    -- 客户端 sinInvenTory.cpp:4718 `if (JobBitMask & JobCodeMask)` 命中才生效
    spec_absorb          real NOT NULL DEFAULT 0,  -- Add_fAbsorb
    spec_defence         integer NOT NULL DEFAULT 0, -- Add_Defence
    spec_speed           real NOT NULL DEFAULT 0,  -- Add_fSpeed
    spec_block_rating    real NOT NULL DEFAULT 0,  -- Add_fBlock_Rating
    spec_attack_speed    integer NOT NULL DEFAULT 0, -- Add_Attack_Speed
    spec_critical        integer NOT NULL DEFAULT 0, -- Add_Critical_Hit
    spec_shooting_range  integer NOT NULL DEFAULT 0, -- Add_Shooting_Range
    spec_magic_mastery   real NOT NULL DEFAULT 0,  -- Add_fMagic_Mastery
    spec_res_bionic      smallint NOT NULL DEFAULT 0, -- Add_Resistance[0]
    spec_res_earth       smallint NOT NULL DEFAULT 0, -- Add_Resistance[1]
    spec_res_fire        smallint NOT NULL DEFAULT 0, -- Add_Resistance[2]
    spec_res_ice         smallint NOT NULL DEFAULT 0, -- Add_Resistance[3]
    spec_res_lighting    smallint NOT NULL DEFAULT 0, -- Add_Resistance[4]
    spec_res_poison      smallint NOT NULL DEFAULT 0, -- Add_Resistance[5]
    spec_res_water       smallint NOT NULL DEFAULT 0, -- Add_Resistance[6]
    spec_res_wind        smallint NOT NULL DEFAULT 0, -- Add_Resistance[7]
    spec_lev_mana        integer NOT NULL DEFAULT 0, -- Lev_Mana (角色Level/该值)
    spec_lev_life        integer NOT NULL DEFAULT 0, -- Lev_Life
    spec_lev_attack_rating integer NOT NULL DEFAULT 0, -- Lev_Attack_Rating
    spec_lev_damage_max  integer NOT NULL DEFAULT 0, -- Lev_Damage[1] (客户端只用到[1])
    spec_lev_res_bionic  smallint NOT NULL DEFAULT 0, -- Lev_Attack_Resistance[0]
    spec_lev_res_earth   smallint NOT NULL DEFAULT 0, -- Lev_Attack_Resistance[1]
    spec_lev_res_fire    smallint NOT NULL DEFAULT 0, -- Lev_Attack_Resistance[2]
    spec_lev_res_ice     smallint NOT NULL DEFAULT 0, -- Lev_Attack_Resistance[3]
    spec_lev_res_lighting smallint NOT NULL DEFAULT 0, -- Lev_Attack_Resistance[4]
    spec_lev_res_poison  smallint NOT NULL DEFAULT 0, -- Lev_Attack_Resistance[5]
    spec_lev_res_water   smallint NOT NULL DEFAULT 0, -- Lev_Attack_Resistance[6]
    spec_lev_res_wind    smallint NOT NULL DEFAULT 0, -- Lev_Attack_Resistance[7]
    spec_per_mana_regen  real NOT NULL DEFAULT 0,  -- Per_Mana_Regen (值/2)
    spec_per_life_regen  real NOT NULL DEFAULT 0,  -- Per_Life_Regen
    spec_per_stamina_regen real NOT NULL DEFAULT 0, -- Per_Stamina_Regen

    -- ---- 4.5 锻造/合成字段 (ItemAging 系列 + ItemKindMask/ItemKindCode) ----
    -- 锻造(aging)：宝石+金钱→等级+1/失败；合成(craft)：配方加固定属性不失败
    aging_num      smallint NOT NULL DEFAULT 0,    -- ItemAgingNum[0] 锻造/合成等级 0~19
    aging_num2     smallint NOT NULL DEFAULT 0,    -- ItemAgingNum[1] 锻造材料标记(=1)/任务武器等级
    aging_exp      integer NOT NULL DEFAULT 0,     -- ItemAgingCount[0] 锻造经验当前值
    aging_exp_max  integer NOT NULL DEFAULT 0,     -- ItemAgingCount[1] 锻造经验上限
    aging_protect  integer NOT NULL DEFAULT 0,     -- ItemAgingProtect[0] GetMixItemForm 反篡改快照
    craft_mask     integer NOT NULL DEFAULT 0,     -- ItemKindMask SIN_ADD_* 位(合成属性类型)
    kind_code      smallint NOT NULL DEFAULT 0,    -- ItemKindCode (ITEM_KIND_CRAFT=合成过 / ITEM_KIND_AGING=可作材料)

    -- ---- 其他状态 ----
    special_flag   smallint NOT NULL DEFAULT 0,    -- SpecialItemFlag[0]
    create_time    timestamp without time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delete_time    timestamp without time zone NULL
);

-- ---- 4.6 索引 ----
CREATE INDEX IF NOT EXISTS idx_item_character ON userdb.item (character_id, location, slot);
CREATE INDEX IF NOT EXISTS idx_item_code ON userdb.item (item_code);