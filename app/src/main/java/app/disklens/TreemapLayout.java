package app.disklens;

import java.util.*;

/** Balanced area partition. Each tile's area is proportional to its byte count. */
public final class TreemapLayout {
  public static final class Tile {
    public final StorageNode node;
    public final float x, y, w, h;

    Tile(StorageNode node, float x, float y, float w, float h) {
      this.node = node;
      this.x = x;
      this.y = y;
      this.w = w;
      this.h = h;
    }
  }

  public static List<Tile> layout(List<StorageNode> nodes, float width, float height) {
    List<StorageNode> positive = new ArrayList<>();
    for (StorageNode n : nodes) if (n.size > 0) positive.add(n);
    positive.sort((a, b) -> Long.compare(b.size, a.size));
    List<Tile> out = new ArrayList<>();
    split(positive, 0, positive.size(), 0, 0, width, height, out);
    return out;
  }

  private static void split(
      List<StorageNode> n, int start, int end, float x, float y, float w, float h, List<Tile> out) {
    if (start == end || w <= 0 || h <= 0) return;
    if (end - start == 1) {
      out.add(new Tile(n.get(start), x, y, w, h));
      return;
    }
    double total = 0;
    for (int i = start; i < end; i++) total += n.get(i).size;
    double part = n.get(start).size;
    int mid = start + 1;
    while (mid < end - 1
        && Math.abs(total / 2 - (part + n.get(mid).size)) < Math.abs(total / 2 - part))
      part += n.get(mid++).size;
    float fraction = (float) (part / total);
    if (w >= h) {
      float a = w * fraction;
      split(n, start, mid, x, y, a, h, out);
      split(n, mid, end, x + a, y, w - a, h, out);
    } else {
      float a = h * fraction;
      split(n, start, mid, x, y, w, a, out);
      split(n, mid, end, x, y + a, w, h - a, out);
    }
  }
}
