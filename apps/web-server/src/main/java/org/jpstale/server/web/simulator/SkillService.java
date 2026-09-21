package org.jpstale.server.web.simulator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能数据服务。
 * <p>
 * 数据源：classpath:data/skills.json（由 extract-skills.py 从 EU ESkillID + exm 表提取）。
 * 每项技能含：name(英文名)、skillCode(EU码)、active(主动/被动)、iconFile(图标文件名)、
 * weaponRestrict(武器限制)。
 */
@Service
public class SkillService {

    /** jobId(1-10) → JSON 中的 key（职业目录名） */
    private static final Map<Integer, String> JOB_KEY = new LinkedHashMap<>();
    static {
        JOB_KEY.put(1, "fighter");
        JOB_KEY.put(2, "mecha");
        JOB_KEY.put(3, "archer");
        JOB_KEY.put(4, "pikeman");
        JOB_KEY.put(5, "atalanta");
        JOB_KEY.put(6, "knight");
        JOB_KEY.put(7, "magician");
        JOB_KEY.put(8, "priestess");
        JOB_KEY.put(9, "assassin");
        JOB_KEY.put(10, "shaman");
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile Map<String, List<Map<String, Object>>> skillData;

    public SkillService() {
    }

    private Map<String, List<Map<String, Object>>> data() {
        Map<String, List<Map<String, Object>>> d = skillData;
        if (d == null) {
            synchronized (this) {
                d = skillData;
                if (d == null) {
                    d = load();
                    skillData = d;
                }
            }
        }
        return d;
    }

    private Map<String, List<Map<String, Object>>> load() {
        try (InputStream in = new ClassPathResource("data/skills.json").getInputStream()) {
            return objectMapper.readValue(in, new TypeReference<Map<String, List<Map<String, Object>>>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("技能数据加载失败: data/skills.json", e);
        }
    }

    /**
     * 获取某职业技能列表。
     *
     * @param jobId 职业ID（1-10）
     * @return 技能列表；未知职业返回空列表
     */
    public List<Map<String, Object>> skills(int jobId) {
        String key = JOB_KEY.get(jobId);
        if (key == null) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> list = data().get(key);
        return list != null ? list : new ArrayList<>();
    }
}
