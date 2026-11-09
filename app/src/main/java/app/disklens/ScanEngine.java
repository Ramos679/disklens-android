package app.disklens;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

public final class ScanEngine {
  public interface Progress {
    void update(String message);
  }

  public static final class Result {
    public StorageNode root;
    public final List<StorageNode> files = new ArrayList<>();
    public int skipped, unknown;
    public boolean cancelled, limited;
    public long finishedAt;
    public String source;
    public boolean document;

    public void sort() {
      files.sort((a, b) -> Long.compare(b.size, a.size));
    }
  }

  public static final class DuplicateResult {
    public final List<List<StorageNode>> groups = new ArrayList<>();
    public int unreadable;
    public boolean cancelled;
  }

  private final Context context;
  private final Progress progress;
  public volatile boolean cancelled;
  private long lastUpdate;
  private int entries;
  private static final int MAX_ENTRIES = 250000;
  private static final int MAX_DEPTH = 128;

  public ScanEngine(Context context, Progress progress) {
    this.context = context.getApplicationContext();
    this.progress = progress;
  }

  private void update(String message) {
    long now = SystemClock.elapsedRealtime();
    if (now - lastUpdate > 160) {
      lastUpdate = now;
      progress.update(message);
    }
  }

  public Result scan(String source, boolean document) throws IOException {
    Result r = new Result();
    r.source = source;
    r.document = document;
    if (document) {
      Uri tree = Uri.parse(source);
      Uri root =
          DocumentsContract.buildDocumentUriUsingTree(
              tree, DocumentsContract.getTreeDocumentId(tree));
      String name = "Selected folder";
      try (Cursor c =
          context
              .getContentResolver()
              .query(
                  root,
                  new String[] {DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                  null,
                  null,
                  null)) {
        if (c != null && c.moveToFirst()) name = c.getString(0);
      }
      r.root = new StorageNode(name, root.toString(), true, true);
      scanDocuments(tree, r.root, r, 0);
    } else {
      File dir = new File(source);
      if (!dir.exists() || !dir.isDirectory())
        throw new IOException("This storage location is unavailable.");
      r.root =
          new StorageNode(
              dir.getName().equals("0") ? "Internal storage" : dir.getName(),
              dir.getAbsolutePath(),
              true,
              false);
      scanFiles(dir, r.root, r, 0);
    }
    r.cancelled = cancelled;
    r.finishedAt = System.currentTimeMillis();
    r.sort();
    return r;
  }

  private boolean stop(Result r, int depth) {
    if (cancelled) return true;
    if (entries >= MAX_ENTRIES || depth > MAX_DEPTH) {
      r.limited = true;
      return true;
    }
    return false;
  }

  private void scanFiles(File dir, StorageNode node, Result r, int depth) {
    if (stop(r, depth)) return;
    update("Scanning " + node.name + " · " + r.files.size() + " files");
    File[] files = dir.listFiles();
    if (files == null) {
      r.skipped++;
      return;
    }
    for (File file : files) {
      if (stop(r, depth + 1)) break;
      entries++;
      try {
        if (Files.isSymbolicLink(file.toPath())) {
          r.skipped++;
          continue;
        }
        boolean folder = file.isDirectory();
        if (!folder && !file.isFile()) {
          r.skipped++;
          continue;
        }
        StorageNode child = new StorageNode(file.getName(), file.getAbsolutePath(), folder, false);
        child.modified = file.lastModified();
        node.add(child);
        if (folder) scanFiles(file, child, r, depth + 1);
        else {
          child.size = file.length();
          child.fileCount = 1;
          child.deletable = file.canWrite();
          r.files.add(child);
        }
      } catch (SecurityException ex) {
        r.skipped++;
      }
    }
    node.total();
  }

  private void scanDocuments(Uri tree, StorageNode node, Result r, int depth) {
    if (stop(r, depth)) return;
    update("Scanning " + node.name + " · " + r.files.size() + " files");
    String id = DocumentsContract.getDocumentId(Uri.parse(node.location));
    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
    String[] columns = {
      DocumentsContract.Document.COLUMN_DOCUMENT_ID,
      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_MIME_TYPE,
      DocumentsContract.Document.COLUMN_SIZE,
      DocumentsContract.Document.COLUMN_LAST_MODIFIED,
      DocumentsContract.Document.COLUMN_FLAGS
    };
    // Close each provider cursor before descending, so deep trees do not exhaust handles.
    try (Cursor cursor = context.getContentResolver().query(children, columns, null, null, null)) {
      if (cursor == null) {
        r.skipped++;
        return;
      }
      while (cursor.moveToNext()) {
        if (stop(r, depth + 1)) break;
        entries++;
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
        boolean folder = DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2));
        StorageNode child = new StorageNode(cursor.getString(1), uri.toString(), folder, true);
        child.modified = cursor.isNull(4) ? 0 : cursor.getLong(4);
        child.deletable = (cursor.getInt(5) & DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0;
        if (!folder) {
          child.unknownSize = cursor.isNull(3) || cursor.getLong(3) < 0;
          child.size = child.unknownSize ? 0 : cursor.getLong(3);
          child.fileCount = 1;
          if (child.unknownSize) r.unknown++;
          r.files.add(child);
        }
        node.add(child);
      }
    } catch (Exception ex) {
      r.skipped++;
    }
    for (StorageNode child : node.children)
      if (child.directory && !cancelled) scanDocuments(tree, child, r, depth + 1);
    node.total();
  }

  public InputStream open(StorageNode node) throws IOException {
    InputStream in =
        node.document
            ? context.getContentResolver().openInputStream(Uri.parse(node.location))
            : new FileInputStream(node.location);
    if (in == null) throw new IOException("The file provider could not open this file.");
    return in;
  }

  public DuplicateResult duplicates(List<StorageNode> files) {
    DuplicateResult result = new DuplicateResult();
    Map<Long, List<StorageNode>> sizes = new TreeMap<>(Collections.reverseOrder());
    for (StorageNode n : files)
      if (n.size > 0 && !n.unknownSize)
        sizes.computeIfAbsent(n.size, k -> new ArrayList<>()).add(n);
    int processed = 0;
    byte[] buffer = new byte[128 * 1024];
    for (List<StorageNode> sameSize : sizes.values()) {
      if (sameSize.size() < 2) continue;
      Map<String, List<StorageNode>> hashes = new HashMap<>();
      for (StorageNode n : sameSize) {
        if (cancelled) break;
        try (InputStream in = open(n)) {
          MessageDigest digest = MessageDigest.getInstance("SHA-256");
          int read;
          long bytes = 0;
          while (!cancelled && (read = in.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
            bytes += read;
            update("Checking " + n.name + " · " + StorageNode.bytes(bytes));
          }
          if (cancelled) break;
          if (bytes != n.size
              || (!n.document
                  && (new File(n.location).lastModified() != n.modified
                      || new File(n.location).length() != n.size))) {
            result.unreadable++;
            continue;
          }
          String hash = Base64.getEncoder().encodeToString(digest.digest());
          hashes.computeIfAbsent(hash, k -> new ArrayList<>()).add(n);
          update("Compared " + (++processed) + " files");
        } catch (Exception ex) {
          result.unreadable++;
        }
      }
      if (cancelled) break;
      for (List<StorageNode> group : hashes.values())
        if (group.size() > 1) result.groups.add(group);
    }
    result.cancelled = cancelled;
    result.groups.sort(
        (a, b) -> Long.compare(b.get(0).size * (b.size() - 1), a.get(0).size * (a.size() - 1)));
    return result;
  }

  public static String csvCell(String s) {
    // Neutralize spreadsheet formulas in untrusted filenames.
    if (!s.isEmpty() && "=+-@\t\r\n".indexOf(s.charAt(0)) >= 0) s = "'" + s;
    return "\"" + s.replace("\"", "\"\"") + "\"";
  }

  public static void export(Result result, OutputStream stream) throws IOException {
    try (Writer writer =
        new BufferedWriter(
            new OutputStreamWriter(stream, java.nio.charset.StandardCharsets.UTF_8))) {
      writer.write("Path,Bytes,Category,ModifiedUnixMillis,SizeKnown\r\n");
      for (StorageNode n : result.files)
        writer.write(
            csvCell(n.displayPath())
                + ","
                + n.size
                + ","
                + csvCell(n.category().label)
                + ","
                + n.modified
                + ","
                + (!n.unknownSize)
                + "\r\n");
    }
  }

}
