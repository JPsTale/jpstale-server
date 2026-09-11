-- 装备信息框单值属性持久化：必杀 / 射程 / 攻速
-- 这三项此前只存在 ItemInstance 内存/模板，未随实例持久化 → 读档后归零（信息框不显示）。
-- 现补列并随实例存取；射程列名与 spec_shooting_range 对齐用 shooting_range。

ALTER TABLE userdb.item ADD COLUMN IF NOT EXISTS critical integer;
ALTER TABLE userdb.item ADD COLUMN IF NOT EXISTS attack_speed integer;

-- 兼容历史列名 attack_range → shooting_range
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'userdb' AND table_name = 'item' AND column_name = 'attack_range')
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'userdb' AND table_name = 'item' AND column_name = 'shooting_range') THEN
        ALTER TABLE userdb.item RENAME COLUMN attack_range TO shooting_range;
    END IF;
END $$;

ALTER TABLE userdb.item ADD COLUMN IF NOT EXISTS shooting_range integer;

-- 从模板回填存量行（item_code = idcode）
UPDATE userdb.item i
SET critical       = COALESCE(l.critical, 0),
    shooting_range = COALESCE(l.range, 0),
    attack_speed   = COALESCE(l.atkspeed, 0)
FROM gamedb.itemlist l
WHERE l.idcode = i.item_code;
