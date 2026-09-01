SET search_path TO userdb, public;

-- CharacterAppearance: 结构化外貌字段（head=头型/头模型编号 0-2，rank=转职阶级 0=普通）
ALTER TABLE userdb.characterinfo ADD COLUMN IF NOT EXISTS head integer NOT NULL DEFAULT 0;
ALTER TABLE userdb.characterinfo ADD COLUMN IF NOT EXISTS rank integer NOT NULL DEFAULT 0;

-- 物品实例关联唯一定义（gamedb.itemlist.id），消除 idcode 非唯一歧义
ALTER TABLE userdb.item ADD COLUMN IF NOT EXISTS itemlist_id integer NULL;
