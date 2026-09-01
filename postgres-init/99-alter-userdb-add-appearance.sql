SET search_path TO userdb, public;

-- CharacterAppearance: 结构化外貌字段（head=头型/头模型编号 0-2，rank=转职阶级 0=普通）
ALTER TABLE userdb.characterinfo ADD COLUMN IF NOT EXISTS head integer NOT NULL DEFAULT 0;
ALTER TABLE userdb.characterinfo ADD COLUMN IF NOT EXISTS rank integer NOT NULL DEFAULT 0;
