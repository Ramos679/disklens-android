package app.disklens;

import java.util.*;

/** Run with javac/java; validates proportional layouts over varied data distributions. */
public final class CoreProperties {
  static int checks;

  static void check(boolean condition, String message) {
    checks++;
    if (!condition) throw new AssertionError(message);
  }

  public static void main(String[] args) {
    Random random = new Random(8713);
    for (int run = 0; run < 150; run++) {
      StorageNode root = new StorageNode("root", "root", true, false);
      int count = 1 + random.nextInt(250);
      for (int i = 0; i < count; i++) {
        StorageNode n = new StorageNode("f" + i, "f" + i, false, false);
        n.size = random.nextInt(8) == 0 ? 0 : 1 + Math.abs(random.nextLong() % 100000000000L);
        n.fileCount = 1;
        root.add(n);
      }
      root.total();
      float width = 20 + random.nextInt(1400), height = 20 + random.nextInt(1200);
      List<TreemapLayout.Tile> tiles = TreemapLayout.layout(root.children, width, height);
      double area = 0;
      for (TreemapLayout.Tile tile : tiles) {
        area += tile.w * tile.h;
        check(tile.w >= 0 && tile.h >= 0, "non-negative size");
        check(
            tile.x >= -.001
                && tile.y >= -.001
                && tile.x + tile.w <= width + .01
                && tile.y + tile.h <= height + .01,
            "inside viewport");
        check(
            Math.abs(
                    (double) tile.w * tile.h / (width * height)
                        - (double) tile.node.size / root.size)
                < .00001,
            "proportional area");
      }
      if (root.size > 0)
        check(Math.abs(area - width * height) / (width * height) < .00001, "full area covered");
      for (int a = 0; a < tiles.size(); a++)
        for (int b = a + 1; b < tiles.size(); b++) {
          TreemapLayout.Tile x = tiles.get(a), y = tiles.get(b);
          check(
              Math.min(x.x + x.w, y.x + y.w) - Math.max(x.x, y.x) < .01
                  || Math.min(x.y + x.h, y.y + y.h) - Math.max(x.y, y.y) < .01,
              "non-overlap");
        }
    }
    check(TreemapLayout.layout(Collections.emptyList(), 200, 200).isEmpty(), "empty layout");
    check(StorageNode.bytes(0).equals("0 B"), "zero bytes");
    check(StorageNode.bytes(1024 * 1024).equals("1.00 MB"), "binary megabyte");
    check(StorageNode.Category.of("Vacation.MP4") == StorageNode.Category.VIDEO, "mixed case");
    check(StorageNode.Category.of("README") == StorageNode.Category.OTHER, "no extension");
    System.out.println("PASS: " + checks + " property checks across 150 randomized treemaps.");
  }
}
