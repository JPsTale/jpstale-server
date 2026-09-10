package org.jpstale.server.game.item;

/**
 * 画布位图：固定 W×H 网格，slot = y*W + x。
 * <p>
 * 支持物品按 w×h 占多格：只记录左上角锚点，占位由锚点+尺寸推导。
 * 服务端用它做寻位/碰撞/空位校验（客户端另有同规则位图做拖拽手感）。
 * <p>
 * 位图仅用于占用判定，不存储任何业务/数据库语义字段；调试快照用的物主标记
 * 是内部递增序号，纯展示用途，与物品 id 无关。
 */
public final class CanvasGrid {

    private final int w;
    private final int h;
    /**
     * 每格占用标记（0=空）。占多格的同一物品，其所有格子共享同一个序号：
     * 由一次 place() 对整块区域赋值同一 seq 形成。序号仅用于调试快照的可读性。
     */
    private final int[] owner;
    private int seq;

    private static final String SYMBOLS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    public CanvasGrid(int w, int h) {
        this.w = w;
        this.h = h;
        this.owner = new int[w * h];
    }

    public int width() {
        return w;
    }

    public int height() {
        return h;
    }

    public int slots() {
        return w * h;
    }

    /** (x,y) → slot。越界返回 -1。 */
    public int slotAt(int x, int y) {
        if (x < 0 || y < 0 || x >= w || y >= h) {
            return -1;
        }
        return y * w + x;
    }

    /** slot → x。 */
    public int xOf(int slot) {
        return slot % w;
    }

    /** slot → y。 */
    public int yOf(int slot) {
        return slot / w;
    }

    /** 物品 w_g×h_g 放到左上角 (x,y) 是否合法（在界内 + 无重叠）。 */
    public boolean canPlace(int x, int y, int wg, int hg) {
        if (x < 0 || y < 0 || x + wg > w || y + hg > h) {
            return false;
        }
        for (int yy = y; yy < y + hg; yy++) {
            for (int xx = x; xx < x + wg; xx++) {
                if (owner[yy * w + xx] != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 放置：标记占用。调用方需先 canPlace。同一物品的各格共享同一序号。 */
    public void place(int x, int y, int wg, int hg) {
        int id = ++seq;
        for (int yy = y; yy < y + hg; yy++) {
            for (int xx = x; xx < x + wg; xx++) {
                owner[yy * w + xx] = id;
            }
        }
    }

    /** 去掉一矩形(ex,ey,ew,eh)后检查 (x,y,wg,hg) 是否为空（原版：拿起后源格视为空，
     *  拖动上移/换位时与"自己当前足迹"重叠不判占用）。ex/ey 为排除矩形左上角格。 */
    public boolean canPlaceExcept(int x, int y, int wg, int hg, int ex, int ey, int ew, int eh) {
        if (x < 0 || y < 0 || x + wg > w || y + hg > h) {
            return false;
        }
        for (int yy = y; yy < y + hg; yy++) {
            for (int xx = x; xx < x + wg; xx++) {
                if (owner[yy * w + xx] != 0) {
                    boolean insideEx = xx >= ex && xx < ex + ew && yy >= ey && yy < ey + eh;
                    if (!insideEx) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** 移除：清除占用。 */
    public void remove(int x, int y, int wg, int hg) {
        for (int yy = y; yy < y + hg; yy++) {
            for (int xx = x; xx < x + wg; xx++) {
                if (xx >= 0 && yy >= 0 && xx < w && yy < h) {
                    owner[yy * w + xx] = 0;
                }
            }
        }
    }

    /** 全清（重建位图前用）。 */
    public void clear() {
        java.util.Arrays.fill(owner, 0);
    }

    /**
     * 排障：打印整张位图快照。同一物品的所有格子用同一符号；符号按物主序号
     * 映射到 0-9A-Za-z（顺序分配），物品种类超过符号表上限时统一用 '#'。
     * 行首为 y 坐标 + '|'，行尾 '|'。
     */
    public String dumpOccupied() {
        java.util.Map<Integer, Character> symbolByOwner = new java.util.HashMap<>();
        StringBuilder sb = new StringBuilder();
        for (int yy = 0; yy < h; yy++) {
            sb.append(String.format("%2d|", yy));
            for (int xx = 0; xx < w; xx++) {
                int o = owner[yy * w + xx];
                if (o == 0) {
                    sb.append('.');
                    continue;
                }
                Character c = symbolByOwner.get(o);
                if (c == null) {
                    c = symbolByOwner.size() < SYMBOLS.length()
                        ? SYMBOLS.charAt(symbolByOwner.size())
                        : '#';
                    symbolByOwner.put(o, c);
                }
                sb.append(c);
            }
            sb.append('|');
        }
        return sb.toString();
    }

    /**
     * 为 w_g×h_g 的物品找一个空位（从左上逐格扫描，返回 slot；无则 -1）。
     * 自动摆放（拾取/发放）用。
     */
    public int findFreeSlot(int wg, int hg) {
        if (wg > w || hg > h) {
            return -1;
        }
        for (int y = 0; y + hg <= h; y++) {
            for (int x = 0; x + wg <= w; x++) {
                if (canPlace(x, y, wg, hg)) {
                    return slotAt(x, y);
                }
            }
        }
        return -1;
    }
}