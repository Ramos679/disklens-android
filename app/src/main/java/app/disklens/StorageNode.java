package app.disklens;

import java.util.*;

public final class StorageNode {
  public final String name;
  public final String location;
  public final boolean directory;
  public final boolean document;
  public StorageNode parent;
  public long size, modified;
  public int fileCount;
  public boolean deletable, unknownSize;
  public final List<StorageNode> children = new ArrayList<>();

  public StorageNode(String name, String location, boolean directory, boolean document) {
    this.name = name == null ? "Unnamed" : name;
    this.location = location;
    this.directory = directory;
    this.document = document;
  }

  public void add(StorageNode child) {
    child.parent = this;
    children.add(child);
  }

  public void total() {
    if (!directory) {
      fileCount = 1;
      return;
    }
    size = 0;
    fileCount = 0;
    for (StorageNode n : children) {
      size += n.size;
      fileCount += n.fileCount;
    }
    children.sort((a, b) -> Long.compare(b.size, a.size));
  }

  public String displayPath() {
    if (!document) return location;
    return parent == null ? name : parent.displayPath() + "/" + name;
  }

  public String extension() {
    int dot = name.lastIndexOf('.');
    return dot < 1 || dot == name.length() - 1
        ? "No extension"
        : name.substring(dot).toLowerCase(Locale.ROOT);
  }

  public Category category() {
    return Category.of(name);
  }

  public enum Category {
    VIDEO("Videos", 0xFFA89DE6, "mp4 mkv mov avi webm m4v 3gp mts"),
    IMAGE("Images", 0xFF6CCDC5, "jpg jpeg png gif webp heic heif raw dng svg avif bmp"),
    AUDIO("Audio", 0xFFF1BC72, "mp3 wav ogg flac aac m4a opus wma"),
    DOCUMENT(
        "Documents", 0xFF86ADE9, "pdf doc docx txt md csv xls xlsx ppt pptx epub json html xml"),
    ARCHIVE("Archives", 0xFFEF9EAD, "zip rar 7z tar gz bz2 xz iso"),
    APP("App packages", 0xFFB2F279, "apk apks xapk aab dex"),
    OTHER("Other", 0xFF899E9C, "");
    public final String label, extensions;
    public final int color;

    Category(String label, int color, String extensions) {
      this.label = label;
      this.color = color;
      this.extensions = extensions;
    }

    public static Category of(String name) {
      int dot = name.lastIndexOf('.');
      String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
      if (!ext.isEmpty())
        for (Category c : values())
          if ((" " + c.extensions + " ").contains(" " + ext + " ")) return c;
      return OTHER;
    }
  }

  public static String bytes(long n) {
    if (n < 1024) return n + " B";
    double value = n;
    String[] units = {"B", "KB", "MB", "GB", "TB", "PB"};
    int u = 0;
    while (value >= 1024 && u < units.length - 1) {
      value /= 1024;
      u++;
    }
    return String.format(
        Locale.US, value >= 100 ? "%.0f %s" : value >= 10 ? "%.1f %s" : "%.2f %s", value, units[u]);
  }
}
