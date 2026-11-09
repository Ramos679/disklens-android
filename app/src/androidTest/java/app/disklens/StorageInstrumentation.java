package app.disklens;

import android.app.Instrumentation;
import android.os.Bundle;
import java.io.*;
import java.util.*;

/** Device tests with real files and real Android APIs, without third-party test libraries. */
public final class StorageInstrumentation extends Instrumentation {
  int checks;

  void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }

  private void write(File f, byte[] data) throws Exception {
    try (FileOutputStream out = new FileOutputStream(f)) {
      out.write(data);
    }
  }

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    File fixture =
        new File(
            getTargetContext().getCacheDir(),
            "instrumentation-fixture-" + System.currentTimeMillis());
    try {
      check(fixture.mkdir(), "create fixture");
      File nested = new File(fixture, "Pictures");
      check(nested.mkdir(), "create nested folder");
      byte[] same = new byte[8192];
      new Random(42).nextBytes(same);
      write(new File(fixture, "first.mp4"), same);
      write(new File(nested, "copy.mp4"), same);
      byte[] other = same.clone();
      other[0]++;
      write(new File(fixture, "different.mp4"), other);
      write(new File(nested, "photo.JPG"), new byte[1024]);
      write(new File(fixture, "empty.txt"), new byte[0]);
      ScanEngine engine = new ScanEngine(getTargetContext(), s -> {});
      ScanEngine.Result scan = engine.scan(fixture.getAbsolutePath(), false);
      check(scan.files.size() == 5, "all nested files scanned");
      check(scan.root.size == 25600, "byte aggregation");
      check(scan.root.fileCount == 5, "file count aggregation");
      check(scan.files.get(0).size == 8192, "largest first");
      check(scan.skipped == 0, "no skipped paths");
      ScanEngine.DuplicateResult duplicates = engine.duplicates(scan.files);
      check(duplicates.groups.size() == 1, "exactly one duplicate group");
      check(duplicates.groups.get(0).size() == 2, "same size but different contents excluded");
      check(
          StorageNode.Category.of("PHOTO.JPG") == StorageNode.Category.IMAGE,
          "case insensitive extension");
      check(StorageNode.Category.of("file") == StorageNode.Category.OTHER, "extensionless files");
      check(StorageNode.bytes(1024).equals("1.00 KB"), "binary size units");
      android.net.Uri fileUri = ReadOnlyFileProvider.uri(new File(fixture, "first.mp4"));
      try (InputStream opened = getTargetContext().getContentResolver().openInputStream(fileUri)) {
        check(opened != null && opened.read() == (same[0] & 255), "read-only provider opens file");
      }
      check(
          "video/mp4".equals(getTargetContext().getContentResolver().getType(fileUri)),
          "provider MIME type");
      boolean rejectedWrite = false;
      try (android.os.ParcelFileDescriptor ignored =
          getTargetContext().getContentResolver().openFileDescriptor(fileUri, "w")) {
      } catch (SecurityException expected) {
        rejectedWrite = true;
      }
      check(rejectedWrite, "provider rejects write access");
      ByteArrayOutputStream csv = new ByteArrayOutputStream();
      ScanEngine.export(scan, csv);
      String text = csv.toString("UTF-8");
      check(text.contains("photo.JPG"), "CSV contains nested files");
      check(ScanEngine.csvCell("=SUM(A1)").equals("\"'=SUM(A1)\""), "CSV formula escaping");
      check(ScanEngine.csvCell("a\"b").equals("\"a\"\"b\""), "CSV quote escaping");
      java.util.List<TreemapLayout.Tile> tiles = TreemapLayout.layout(scan.root.children, 400, 280);
      double area = 0;
      for (TreemapLayout.Tile t : tiles) {
        area += t.w * t.h;
        check(t.x >= 0 && t.y >= 0 && t.x + t.w <= 400.01 && t.y + t.h <= 280.01, "tile bounds");
        check(
            Math.abs((double) t.w * t.h / (400 * 280) - (double) t.node.size / scan.root.size)
                < .0001,
            "area represents bytes");
      }
      check(Math.abs(area - 400 * 280) < 1, "tiles fill viewport");
      for (int a = 0; a < tiles.size(); a++)
        for (int b = a + 1; b < tiles.size(); b++) {
          TreemapLayout.Tile x = tiles.get(a), y = tiles.get(b);
          check(
              Math.min(x.x + x.w, y.x + y.w) - Math.max(x.x, y.x) < .01
                  || Math.min(x.y + x.h, y.y + y.h) - Math.max(x.y, y.y) < .01,
              "tiles do not overlap");
        }
      ScanEngine stopped = new ScanEngine(getTargetContext(), s -> {});
      stopped.cancelled = true;
      check(stopped.scan(fixture.getAbsolutePath(), false).cancelled, "cancellation reported");
      result.putString(
          "stream",
          "PASS: "
              + checks
              + " checks; real recursive scan, sorting, exact duplicates, CSV, treemap geometry,"
              + " cancellation.\n");
      finish(-1, result);
    } catch (Throwable failure) {
      StringWriter trace = new StringWriter();
      failure.printStackTrace(new PrintWriter(trace));
      result.putString("stream", "FAIL after " + checks + " checks\n" + trace);
      finish(1, result);
    } finally {
      removeFixture(fixture);
    }
  }

  private void removeFixture(File file) {
    File[] children = file.listFiles();
    if (children != null) for (File child : children) removeFixture(child);
    file.delete();
  }
}
