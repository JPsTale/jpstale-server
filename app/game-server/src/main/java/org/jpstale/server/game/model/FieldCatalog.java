package org.jpstale.server.game.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 地图目录：读取 classpath 上的 fields/fields.json（纯数组，EU MapGame.cpp 生成）。
 * 替代旧硬编码 FieldMap 枚举。
 */
public class FieldCatalog {

    private final List<FieldInfo> fields;
    private final Map<Integer, FieldInfo> byId;

    private FieldCatalog(List<FieldInfo> fields) {
        this.fields = List.copyOf(fields);
        this.byId = new HashMap<>();
        for (FieldInfo f : this.fields) {
            byId.put(f.getId(), f);
        }
    }

    public List<FieldInfo> list() {
        return fields;
    }

    public FieldInfo get(int id) {
        return byId.get(id);
    }

    public boolean has(int id) {
        return byId.containsKey(id);
    }

    /** 覆盖的最大 field id（越界检查用），无数据返回 -1 */
    public int maxId() {
        int max = -1;
        for (FieldInfo f : fields) {
            max = Math.max(max, f.getId());
        }
        return max;
    }

    public int size() {
        return fields.size();
    }

    public static FieldCatalog loadFromClasspath() {
        try (InputStream in = FieldCatalog.class.getResourceAsStream("/fields/fields.json")) {
            if (in == null) {
                throw new IllegalStateException("classpath resource /fields/fields.json not found");
            }
            ObjectMapper om = new ObjectMapper();
            om.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            List<FieldInfo> list = om.readValue(in, new TypeReference<List<FieldInfo>>() {
            });
            return new FieldCatalog(list);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load fields/fields.json", e);
        }
    }

    private static volatile FieldCatalog instance;

    /** 进程级单例（懒加载） */
    public static FieldCatalog get() {
        FieldCatalog c = instance;
        if (c == null) {
            synchronized (FieldCatalog.class) {
                c = instance;
                if (c == null) {
                    c = loadFromClasspath();
                    instance = c;
                }
            }
        }
        return c;
    }
}
