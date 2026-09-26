package org.jpstale.server.game.skill;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 职业效果类的**唯一登记处**：job → 实现（Spring 收集所有 {@link JobSkills} Bean）。
 *
 * <p>两个查询，替代旧实现里散在 4 处的 {@code if (skillId == …)} 链：
 * <ul>
 *   <li>{@link #knows(int)} —— 起手门：该技能有没有效果实现（没有 ⇒ {@code NOT_MIGRATED}，走旧路）；</li>
 *   <li>{@link #of(int)} —— 事件帧结算：拿该职业的实现类去 {@code settle}。</li>
 * </ul>
 * 新迁一个技能**不改这里**：实现类自己往注册表里加，这里自动看见。
 */
@Service
public class JobSkillsCatalog {

    private final Map<Integer, JobSkills> byJob;

    public JobSkillsCatalog(List<JobSkills> all) {
        byJob = all.stream()
                .collect(Collectors.toUnmodifiableMap(JobSkills::job, Function.identity()));
    }

    /** 该职业的效果实现；`null` = 该职业还没有技能类（= 全部技能未迁入，走旧路）。 */
    public JobSkills of(int jobId) {
        return byJob.get(jobId);
    }

    /** 该技能是否已迁入（skillId 高 16 位 = 职业号）。 */
    public boolean knows(int skillId) {
        JobSkills h = of(skillId >> 16);
        return h != null && h.handles(skillId);
    }
}
