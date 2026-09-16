package org.jpstale.server.game.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 怪物**动画条目表**：读 classpath 上的 `monster-anim.json`
 * （由客户端仓库 `npm run monster-anim` 统计 `.inx` 生成，见该脚本头部）。
 *
 * <p>服务端在这里扮演的角色，与 `docs/chars/语义化动画系统.md` / `docs/chars/动画同步.md`
 * 给玩家定的完全一致：**服务端持有动画数据 → 自己选变体 → 把"变体 ID（`.inx` 条目索引）"
 * 同步给所有客户端**，客户端只负责"拿到 ID 后用本地 `.smb` 播出来"。
 *
 * <p>玩家那条链已经是这么走的（`anim_index`，谁播的谁上报）；怪物**没有上报者**，此前只能用
 * `deriveAnimSeed(monsterId, state)` 各端确定性派生 —— 那是 `动画同步.md` §3 明写的**过渡方案**，
 * 本类就是它的终局：服务端选、服务端下发。
 *
 * <p>顺带解决"怪在攻击状态要站多久"：服务端**知道选中条目的帧区间**
 * ⇒ 时长 = `(end - start) × 160 ÷ 播放步进 ÷ 60` 秒（精确），不必对多条取 max 猜上界。
 *
 * <p>键与 `MonsterSpawnService.normalizeModelPath` 的产物同形（小写、正斜杠、`.inx` 结尾）；
 * 模型/状态查不到返回**空列表**（调用方自行决定，不在这里猜）。
 */
@Slf4j
public class MonsterAnimData {

    /** 一条动画条目（`.inx` 里的一个槽位）。 */
    public record Entry(int index, int start, int end, int repeat) {
        /** 帧数（播完要走多少动画帧） */
        public int frames() {
            return Math.max(0, end - start);
        }

        /** 是否循环（循环的不需要"等它播完"） */
        public boolean looping() {
            return repeat != 0;
        }
    }

    private final Map<String, Map<String, List<Entry>>> models;

    private MonsterAnimData(Map<String, Map<String, List<Entry>>> models) {
        this.models = Map.copyOf(models);
    }

    /**
     * 该模型某状态的全部条目（可能多条 = 变体）；模型或状态不存在 → 空列表。
     * @param state 状态名（`attack` / `skill` / `stand` / `walk` / `run` / `dead` …），
     *              与客户端 `char-format.ts` 的 `CHRMOTION_STATE` 对应
     */
    public List<Entry> entriesOf(String modelFile, String state) {
        if (modelFile == null || state == null) {
            return List.of();
        }
        Map<String, List<Entry>> byState = models.get(modelFile);
        if (byState == null) {
            return List.of();
        }
        List<Entry> list = byState.get(state);
        return list == null ? List.of() : list;
    }

    /**
     * **选一条变体**（服务端权威 —— 所有客户端必须看到同一条）。
     * @return 选中的条目；该模型没有这个状态 → null（调用方自行决定，不在这里猜）
     */
    public Entry pick(String modelFile, String state) {
        List<Entry> list = entriesOf(modelFile, state);
        if (list.isEmpty()) {
            return null;
        }
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    /** 该模型某状态**最长**的一条帧数（没有 → 0）。仅在"还没选定具体条目"时才该用它。 */
    public int maxFramesOf(String modelFile, String state) {
        int max = 0;
        for (Entry e : entriesOf(modelFile, state)) {
            max = Math.max(max, e.frames());
        }
        return max;
    }

    /** 该模型是否在表里（用于区分"表没这个模型"与"模型没这个状态"） */
    public boolean hasModel(String modelFile) {
        return modelFile != null && models.containsKey(modelFile);
    }

    public int size() {
        return models.size();
    }

    private static volatile MonsterAnimData instance;

    /** 进程级单例（懒加载） */
    public static MonsterAnimData get() {
        MonsterAnimData c = instance;
        if (c == null) {
            synchronized (MonsterAnimData.class) {
                c = instance;
                if (c == null) {
                    c = loadFromClasspath();
                    instance = c;
                }
            }
        }
        return c;
    }

    @SuppressWarnings("unchecked")
    static MonsterAnimData loadFromClasspath() {
        try (InputStream in = MonsterAnimData.class.getResourceAsStream("/monster-anim.json")) {
            if (in == null) {
                // 资源缺失是**部署问题**，要说清楚而不是让所有怪都"不锁动画"（那会退回打断动画的行为）
                throw new IllegalStateException(
                        "classpath resource /monster-anim.json not found"
                                + "（客户端仓库跑 npm run monster-anim 生成）");
            }
            ObjectMapper om = new ObjectMapper();
            om.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            Map<String, Object> root = om.readValue(in, new TypeReference<Map<String, Object>>() {
            });
            Object raw = root.get("models");
            Map<String, Map<String, List<Entry>>> map = raw == null
                    ? Collections.emptyMap()
                    : om.convertValue(raw, new TypeReference<Map<String, Map<String, List<Entry>>>>() {
            });
            log.info("[MonsterAnimData] loaded {} 个模型的动画条目", map.size());
            return new MonsterAnimData(map);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load monster-anim.json", e);
        }
    }
}
