package org.jpstale.common.service.item;

import org.jpstale.dao.gamedb.entity.AgeList;
import org.jpstale.dao.gamedb.mapper.AgeListMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 锻造曲线（`gamedb.agelist`，20 行 = +1..+20 每级一行）的加载与查询。
 *
 * <p>
 * **行与等级的对应**：EU 读 `SELECT * FROM AgeList WHERE AgeNumber = sAgeLevel + 1`
 * （`itemserver.cpp:2541-2590`）⇒ 等级 0（还没锻造）用 `agenumber = 1` 那行。
 * 本类用 {@link #rowForLevel(int)} 封装这条偏移，**越界返回 null**（不兜底、不夹到最大值 ——
 * 调用方必须按"超出上限"处理，见纠错 #12）。
 *
 * <p>
 * 曲线列的真义（EU 按**列序号**读，与我们导出的列序一致，见分析文档 §3.13.1）：
 * `failchance`=普通失败率、`plus2chance`=成功时跳级率、`minus2chance`/`minus1chance`=失败降档率、
 * `brokenchance`=破坏率、**`agestone`=用锻造石时的失败率**（我们库里 20 行全 0 ⇒ 用石必成功）。
 */
@Service
public class AgeListService {

    private static final Logger log = LoggerFactory.getLogger(AgeListService.class);

    private final AgeListMapper mapper;
    /** `agenumber` → 行（1..N）。 */
    private volatile Map<Integer, AgeList> byNumber = Map.of();

    /**
     * ⚠ **必须标 `@Autowired`**（2026-09-22 血的教训）：本类有两个构造函数
     * （这个收 mapper 的 + 下面给 `forRows` 用的私有 no-arg）。
     * Spring 在"多个构造函数且都没标 `@Autowired`"时会**回退到无参构造**（**私有也能反射调用**），
     * 于是 mapper 恒为 null、`reload()` 从没执行 ⇒ **这张%s表恒为空**：
     * 合成属性全不生效（`byUniqueId` 永远 null）、战斗养进度永远不涨（`rowForLevel` 永远 null 且静默 return）。
     * 症状是"代码/库/单测全对，跑起来就是不动"，日志里连一条载入日志都没有 —— 极难猜，故留此注释。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public AgeListService(AgeListMapper mapper) {
        this.mapper = mapper;
        reload();
    }

    /** **离线构造**（单测/管理端预览用）：直接给曲线行，不接 DB。 */
    public static AgeListService forRows(List<AgeList> rows) {
        AgeListService svc = new AgeListService();
        svc.index(rows);
        return svc;
    }

    private AgeListService() {
        this.mapper = null;
    }

    public synchronized int reload() {
        return index(mapper.selectList(null));
    }

    private synchronized int index(List<AgeList> rows) {
        Map<Integer, AgeList> map = new LinkedHashMap<>();
        int dup = 0;
        for (AgeList r : rows == null ? List.<AgeList>of() : rows) {
            if (r.getAgeNumber() == null) {
                log.warn("[Age] 曲线行 id={} 没有 agenumber，跳过", r.getId());
                continue;
            }
            if (map.putIfAbsent(r.getAgeNumber(), r) != null) {
                dup++;
                log.warn("[Age] agenumber={} 重复 —— 只会用先出现的那行", r.getAgeNumber());
            }
        }
        byNumber = Map.copyOf(map);
        if (byNumber.isEmpty()) {
            // 空曲线 = 战斗养永远不涨（`rowForLevel` 恒 null）+ 投石判不出结果。必须 ERROR，别让它躺在 INFO 里。
            log.error("[Age] 锻造曲线是**空的** —— 战斗养进度永远不会涨、投石也判不出结果！"
                    + "（多半是 AgeListService 的构造函数没标 @Autowired，Spring 回退到了无参构造）");
        }
        log.info("[Age] 锻造曲线载入 {} 级（agenumber {}..{}，重复 {}）",
                byNumber.size(), maxAgeNumber() == 0 ? "-" : "1", maxAgeNumber(), dup);
        return byNumber.size();
    }

    /** 锻造**等级** → 曲线行（EU 的 `AgeNumber = 等级 + 1` 偏移）；越界/缺行返回 null。 */
    public AgeList rowForLevel(int level) {
        return byNumber.get(level + 1);
    }

    /**
     * **可锻造到的最高等级** = 最大 `agenumber` − 1（同样因为 `agenumber = 等级 + 1`）。
     * ⚠ 曾经这里直接用 `maxAgeNumber()` 当上限 ⇒ 判定会拿"等级 5 对上限 6"放行，
     * 于是 5 级还能再投石（单测抓到）。上限要和"行是否存在"同一口径。
     */
    public int maxLevel() {
        return Math.max(0, maxAgeNumber() - 1);
    }

    /** 曲线覆盖到的最大 `agenumber`。 */
    public int maxAgeNumber() {
        int max = 0;
        for (int n : byNumber.keySet()) {
            max = Math.max(max, n);
        }
        return max;
    }
}
