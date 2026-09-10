-- 背包装备系统 location 十进制分段迁移（design-背包装备系统 v0.2）
-- 旧语义：0=背包  1=仓库  2=装备栏  6=备用武器槽
-- 新语义：0=装备栏  1=副装备栏  10=背包页1  30=仓库页1
-- 3/4/5 旧预留（邮件/拍卖/交易锁定）原未使用，无迁移；若存量请先盘点。

-- 旧背包 → 背包页1
UPDATE userdb.item SET location = 10 WHERE location = 0 AND delete_time IS NULL;
-- 旧仓库 → 仓库页1
UPDATE userdb.item SET location = 30 WHERE location = 1 AND delete_time IS NULL;
-- 旧装备栏 → 新装备栏
UPDATE userdb.item SET location = 0  WHERE location = 2 AND delete_time IS NULL;
-- 旧备用武器槽 → 副装备栏
UPDATE userdb.item SET location = 1  WHERE location = 6 AND delete_time IS NULL;

-- 软删行（delete_time 非空）不迁移：保留原 location 以可回溯审计。