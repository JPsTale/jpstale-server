package org.jpstale.server.game.item;

/**
 * 画布位图：固定 W×H 网格，slot = y*W + x。
 * <p>
 * 支持物品按 w×h 占多格：只记录左上角锚点，占位由锚点+尺寸推导。
 * 服务端用它做寻位/碰撞/空位校验（客户端另有同规则位图做拖拽手感）。
 */
public final class CanvasGrid {

    private final int w;
    private final int h;
    /** 每格是否被占用（含作为他人物品"身体"的格） */
    private final boolean[] occupied;

    public CanvasGrid(int w, int h) {
        this.w = w;
        this.h = h;
        this.occupied = new boolean[w * h];
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
                if (occupied[yy * w + xx]) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 放置：标记占用。调用方需先 canPlace。 */
    public void place(int x, int y, int wg, int hg) {
        for (int yy = y; yy < y + hg; yy++) {
            for (int xx = x; xx < x + wg; xx++) {
                occupied[yy * w + xx] = true;
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
                if (occupied[yy * w + xx]) {
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
                    occupied[yy * w + xx] = false;
                }
            }
        }
    }

    /** 全清（重建位图前用）。 */
    public void clear() {
        java.util.Arrays.fill(occupied, false);
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
