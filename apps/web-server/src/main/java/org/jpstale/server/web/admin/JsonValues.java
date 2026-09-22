package org.jpstale.server.web.admin;

/**
 * JSON 值 → 基本类型的转换（**唯一实现**）。
 *
 * <p>
 * 谁需要它：实体列的部分更新（{@link AdminEntityService} 的 coerce）与跨表保存
 * （例如怪物掉落表的整表保存）都要把请求体里的值转成 Integer/Double/Boolean。
 * 这类"转换不过就拒绝、绝不猜"的判定抄第二份就会漂（AGENTS #15）。
 *
 * <p>
 * 口径：**null 一律返回 null**，由调用方决定 null 的含义（列更新里 = "不改"，掉落行里 = "没给"）；
 * 转换不了抛 {@link IllegalArgumentException}，消息里带 `what`（列名/行号），便于定位。
 */
public final class JsonValues {

    private JsonValues() {
    }

    public static Integer toInt(Object value, String what) {
        if (value == null) {
            return null;
        }
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Long l) {
            if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(what + " 超出整数范围：" + l);
            }
            return l.intValue();
        }
        if (value instanceof Number n) {
            // JSON 里的大整数在 Jackson 里可能是 BigInteger；这里按 double 判断是否有小数部分
            double d = n.doubleValue();
            if (d != Math.rint(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(what + " 不是整数：" + value);
            }
            return (int) d;
        }
        if (value instanceof String s) {
            try {
                return Integer.valueOf(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(what + " 不是整数：" + s);
            }
        }
        throw new IllegalArgumentException(what + " 需要整数，收到 " + value.getClass().getSimpleName());
    }

    public static Double toDouble(Object value, String what) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.valueOf(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(what + " 不是小数：" + s);
            }
        }
        throw new IllegalArgumentException(what + " 需要小数，收到 " + value.getClass().getSimpleName());
    }

    /**
     * 取字符串值：**只有 JSON 字符串才算**（其它类型返回 null，由调用方按"没给"处理）。
     * 不在这里做 `String.valueOf` —— 那会把数字 101 悄悄变成 "101"，而调用方以为收到的是码。
     */
    public static String toStr(Object value) {
        return value instanceof String s ? s : null;
    }

    public static Boolean toBool(Object value, String what) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        if (value instanceof String s) {
            String v = s.trim().toLowerCase();
            if (v.equals("true") || v.equals("1") || v.equals("t")) {
                return Boolean.TRUE;
            }
            if (v.equals("false") || v.equals("0") || v.equals("f")) {
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException(what + " 不是布尔值：" + s);
        }
        throw new IllegalArgumentException(what + " 需要布尔值，收到 " + value.getClass().getSimpleName());
    }
}
