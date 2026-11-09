package app.disklens;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.*;

final class TreemapView extends View {
  interface Selection {
    void selected(StorageNode node);
  }

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF rect = new RectF();
  private final Map<TreemapLayout.Tile, java.util.List<TreemapLayout.Tile>> nestedTiles =
      new IdentityHashMap<>();
  private final java.util.List<StorageNode> nodes;
  private java.util.List<TreemapLayout.Tile> tiles = new ArrayList<>();
  private final Selection selection;
  private float downX, downY;

  public TreemapView(Context context) {
    this(context, Collections.emptyList(), node -> {});
  }

  TreemapView(Context context, java.util.List<StorageNode> nodes, Selection selection) {
    super(context);
    this.nodes = compact(nodes);
    this.selection = selection;
    setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    setContentDescription(
        "Storage map. Larger rectangles use more space. Tap a folder to explore. The same items are"
            + " listed below.");
    setFocusable(true);
  }

  private static java.util.List<StorageNode> compact(java.util.List<StorageNode> source) {
    if (source.size() <= 180) return source;
    java.util.List<StorageNode> sorted = new ArrayList<>(source);
    sorted.sort((a, b) -> Long.compare(b.size, a.size));
    java.util.List<StorageNode> visible = new ArrayList<>(sorted.subList(0, 179));
    StorageNode remainder =
        new StorageNode("More items (" + (source.size() - 179) + ")", "", true, false);
    remainder.parent = source.get(0).parent;
    // References are grouped for display only; preserve the real file hierarchy.
    remainder.children.addAll(sorted.subList(179, sorted.size()));
    remainder.total();
    visible.add(remainder);
    return visible;
  }

  private float dp(float n) {
    return n * getResources().getDisplayMetrics().density;
  }

  static int color(StorageNode n) {
    StorageNode leaf = n;
    while (leaf.directory && !leaf.children.isEmpty()) leaf = leaf.children.get(0);
    return leaf.category().color;
  }

  @Override
  protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
    super.onSizeChanged(width, height, oldWidth, oldHeight);
    tiles = TreemapLayout.layout(nodes, width, height);
    nestedTiles.clear();
    for (TreemapLayout.Tile tile : tiles) {
      float w = tile.w - dp(4), h = tile.h - dp(4);
      if (tile.node.directory && h > dp(90) && w > dp(65))
        nestedTiles.put(
            tile, TreemapLayout.layout(compact(tile.node.children), w - dp(20), h - dp(63)));
    }
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (tiles.isEmpty()) {
      paint.setColor(0xFF9BAEAB);
      paint.setTextSize(dp(14));
      paint.setTypeface(Typeface.DEFAULT);
      canvas.drawText("No measurable files in this folder", dp(14), getHeight() / 2f, paint);
      return;
    }
    for (TreemapLayout.Tile tile : tiles) {
      float gap = dp(2),
          x = tile.x + gap,
          y = tile.y + gap,
          w = tile.w - 2 * gap,
          h = tile.h - 2 * gap;
      if (w < 1 || h < 1) continue;
      rect.set(x, y, x + w, y + h);
      int c = color(tile.node);
      paint.setColor(c);
      canvas.drawRoundRect(rect, dp(7), dp(7), paint);
      canvas.save();
      canvas.clipRect(x + dp(8), y + dp(5), x + w - dp(8), y + h - dp(5));
      if (w > dp(48) && h > dp(32)) {
        paint.setColor(0xFF142222);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextSize(dp(13));
        canvas.drawText(ellipsize(tile.node.name, w - dp(20)), x + dp(10), y + dp(23), paint);
        if (h > dp(52)) {
          paint.setTypeface(Typeface.DEFAULT);
          paint.setTextSize(dp(11));
          canvas.drawText(StorageNode.bytes(tile.node.size), x + dp(10), y + dp(41), paint);
        }
        if (tile.node.directory && h > dp(90) && w > dp(65)) {
          java.util.List<TreemapLayout.Tile> nested = nestedTiles.get(tile);
          for (TreemapLayout.Tile t : nested) {
            paint.setColor(0x26101E1C);
            rect.set(
                x + dp(10) + t.x + 1,
                y + dp(53) + t.y + 1,
                x + dp(10) + t.x + t.w - 1,
                y + dp(53) + t.y + t.h - 1);
            canvas.drawRoundRect(rect, dp(3), dp(3), paint);
          }
        }
      }
      canvas.restore();
    }
  }

  private String ellipsize(String text, float width) {
    if (paint.measureText(text) <= width) return text;
    int n = paint.breakText(text, true, Math.max(0, width - paint.measureText("…")), null);
    return text.substring(0, n) + "…";
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (event.getAction() == MotionEvent.ACTION_DOWN) {
      downX = event.getX();
      downY = event.getY();
      return true;
    }
    if (event.getAction() == MotionEvent.ACTION_UP) {
      if (Math.abs(event.getX() - downX) + Math.abs(event.getY() - downY) < dp(14)) {
        performClick();
        for (TreemapLayout.Tile t : tiles)
          if (event.getX() >= t.x
              && event.getX() < t.x + t.w
              && event.getY() >= t.y
              && event.getY() < t.y + t.h) {
            selection.selected(t.node);
            break;
          }
      }
      return true;
    }
    return true;
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }
}
