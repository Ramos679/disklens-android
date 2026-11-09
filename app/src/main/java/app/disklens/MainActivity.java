package app.disklens;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.os.storage.*;
import android.provider.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.text.DateFormat;
import java.util.*;

public final class MainActivity extends Activity {
  private static final int BG = 0xFF0B1415,
      CARD = 0xFF142123,
      LINE = 0xFF293A3B,
      TEXT = 0xFFF2F5EE,
      MUTED = 0xFF9CAFAA,
      ACCENT = 0xFFB2F279;
  private static final int PICK = 20, ACCESS = 21, EXPORT = 22;
  private ScanSession session;
  private StorageNode folder;
  private ScanEngine.Result displayedResult;
  private LinearLayout root, body, rows;
  private TextView progressLabel;
  private int tab = 0, visibleLimit = 100;
  private boolean largest = true, pendingAccess;
  private String search = "", pendingStorage;
  private ScanEngine.Result exportResult;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    session = (ScanSession) getLastNonConfigurationInstance();
    if (session == null) session = new ScanSession();
    if (state != null) {
      tab = state.getInt("tab");
      largest = state.getBoolean("largest", true);
      pendingAccess = state.getBoolean("pendingAccess");
      pendingStorage = state.getString("pendingStorage");
    }
    session.listener =
        onlyProgress -> {
          if (onlyProgress && progressLabel != null) progressLabel.setText(session.progress);
          else render();
        };
    getWindow().setStatusBarColor(BG);
    getWindow().setNavigationBarColor(BG);
    if (android.os.Build.VERSION.SDK_INT >= 33)
      getOnBackInvokedDispatcher()
          .registerOnBackInvokedCallback(
              android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
              () -> {
                if (!navigateBack()) finish();
              });
    render();
  }

  @Override
  public Object onRetainNonConfigurationInstance() {
    return session;
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    out.putInt("tab", tab);
    out.putBoolean("largest", largest);
    out.putBoolean("pendingAccess", pendingAccess);
    out.putString("pendingStorage", pendingStorage);
  }

  @Override
  protected void onDestroy() {
    session.listener = null;
    if (!isChangingConfigurations()) session.close();
    super.onDestroy();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (pendingAccess
        && android.os.Build.VERSION.SDK_INT >= 30
        && Environment.isExternalStorageManager()) {
      pendingAccess = false;
      scan(
          pendingStorage == null
              ? Environment.getExternalStorageDirectory().getAbsolutePath()
              : pendingStorage,
          false);
    }
  }

  private int dp(float n) {
    return (int) (getResources().getDisplayMetrics().density * n + 0.5f);
  }

  private LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  private GradientDrawable rounded(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  private TextView text(String value, int size, int color, boolean bold) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setFontFeatureSettings("tnum");
    t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
    t.setIncludeFontPadding(false);
    t.setLineSpacing(dp(3), 1f);
    return t;
  }

  private TextView button(String label, boolean primary, Runnable action) {
    TextView t = text(label, 14, primary ? BG : TEXT, true);
    t.setGravity(Gravity.CENTER);
    t.setPadding(dp(16), dp(12), dp(16), dp(12));
    t.setMinHeight(dp(48));
    t.setBackground(rounded(primary ? ACCENT : LINE, 14));
    t.setOnClickListener(v -> action.run());
    t.setFocusable(true);
    t.setAccessibilityDelegate(
        new View.AccessibilityDelegate() {
          @Override
          public void onInitializeAccessibilityNodeInfo(
              View host, android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setClassName(Button.class.getName());
          }
        });
    return t;
  }

  private void space(LinearLayout l, int height) {
    View v = new View(this);
    l.addView(v, new LinearLayout.LayoutParams(1, dp(height)));
  }

  private void add(LinearLayout l, View v) {
    l.addView(v, new LinearLayout.LayoutParams(-1, -2));
  }

  private LinearLayout card(LinearLayout parent) {
    LinearLayout c = column();
    c.setPadding(dp(18), dp(18), dp(18), dp(18));
    c.setBackground(rounded(CARD, 20));
    add(parent, c);
    return c;
  }

  private void subtitle(LinearLayout parent, String label) {
    TextView t = text(label, 11, MUTED, true);
    t.setLetterSpacing(.13f);
    add(parent, t);
  }

  private void section(LinearLayout parent, String title, String right, Runnable action) {
    LinearLayout r = row();
    TextView t = text(title, 19, TEXT, true);
    r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
    if (right != null) {
      TextView b = text(right, 12, ACCENT, true);
      b.setGravity(Gravity.CENTER);
      b.setMinHeight(dp(48));
      b.setPadding(dp(10), 0, 0, 0);
      b.setOnClickListener(v -> action.run());
      r.addView(b);
    } else r.setMinimumHeight(dp(48));
    add(parent, r);
  }

  private void render() {
    if (isFinishing()) return;
    if (displayedResult != session.result) {
      displayedResult = session.result;
      folder = session.result == null ? null : session.result.root;
      visibleLimit = 100;
      search = "";
    }
    root = column();
    root.setBackgroundColor(BG);
    setContentView(root);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          root.setPadding(
              insets.getSystemWindowInsetLeft(),
              insets.getSystemWindowInsetTop(),
              insets.getSystemWindowInsetRight(),
              insets.getSystemWindowInsetBottom());
          return insets;
        });
    root.requestApplyInsets();
    if (session.busy) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    LinearLayout header = row();
    header.setPadding(dp(22), dp(10), dp(18), dp(10));
    LinearLayout brand = column();
    TextView name = text("DiskLens", 25, TEXT, true);
    add(brand, name);
    TextView tagline = text("STORAGE, MADE VISIBLE", 9, MUTED, true);
    tagline.setLetterSpacing(.19f);
    space(brand, 4);
    add(brand, tagline);
    header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
    TextView menu = button("•••", false, this::showMenu);
    menu.setContentDescription("More options");
    header.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
    add(root, header);
    if (session.busy) {
      LinearLayout busy = column();
      busy.setPadding(dp(22), dp(10), dp(22), dp(12));
      LinearLayout line = row();
      progressLabel = text(session.progress, 12, ACCENT, false);
      progressLabel.setMaxLines(2);
      line.addView(progressLabel, new LinearLayout.LayoutParams(0, -2, 1));
      if ("scan".equals(session.operation) || "duplicates".equals(session.operation)) {
        TextView cancel = button("Stop", false, () -> session.cancel());
        line.addView(cancel);
      }
      add(busy, line);
      space(busy, 6);
      ProgressBar p = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
      p.setIndeterminate(true);
      add(busy, p);
      add(root, busy);
    } else progressLabel = null;
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    body = column();
    body.setPadding(dp(22), dp(14), dp(22), dp(24));
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    if (!session.error.isEmpty()) {
      TextView error = text(session.error, 13, 0xFFF1BC72, false);
      add(body, error);
      space(body, 16);
    }
    if (session.result == null) welcome();
    else {
      if (tab == 0) overview();
      else if (tab == 1) files();
      else if (tab == 2) types();
      else duplicates();
    }
    if (session.result != null) navigation();
  }

  private void welcome() {
    add(body, text("Storage analysis", 26, TEXT, true));
    space(body, 12);
    add(body, text("Select device storage or a folder to scan. The results show file sizes, folders, and a storage map.", 14, MUTED, false));
    space(body, 24);
    add(body, button("Scan device storage", true, this::chooseStorage));
    space(body, 10);
    add(body, button("Choose a folder", false, this::pickFolder));
    space(body, 20);
    add(body, text("Android restricts access to system files and other apps’ private data.", 12, MUTED, false));
  }

  private void navigation() {
    LinearLayout nav = row();
    nav.setPadding(dp(10), dp(6), dp(10), dp(6));
    nav.setBackgroundColor(CARD);
    String[] labels = {"Overview", "Files", "Types", "Duplicates"};
    String[] symbols = {"◫", "≡", "◉", "▣"};
    for (int i = 0; i < labels.length; i++) {
      final int index = i;
      LinearLayout cell = column();
      cell.setGravity(Gravity.CENTER);
      cell.setPadding(0, dp(8), 0, dp(8));
      TextView icon = text(symbols[i], 21, tab == i ? ACCENT : MUTED, true);
      icon.setGravity(Gravity.CENTER);
      add(cell, icon);
      space(cell, 5);
      TextView label = text(labels[i], 10, tab == i ? ACCENT : MUTED, tab == i);
      label.setGravity(Gravity.CENTER);
      add(cell, label);
      cell.setContentDescription(labels[i]);
      cell.setFocusable(true);
      if (tab == i) cell.setBackground(rounded(LINE, 14));
      cell.setOnClickListener(
          v -> {
            tab = index;
            search = "";
            visibleLimit = 100;
            render();
          });
      nav.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
    }
    add(root, nav);
  }

  private void overview() {
    ScanEngine.Result result = session.result;
    LinearLayout top = row();
    TextView title = text("Your storage, decoded.", 24, TEXT, true);
    top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    add(body, top);
    space(body, 16);
    LinearLayout hero = card(body);
    subtitle(hero, "SCANNED FILES");
    space(hero, 12);
    TextView size = text(StorageNode.bytes(result.root.size), 44, ACCENT, true);
    add(hero, size);
    space(hero, 6);
    add(
        hero,
        text(
            String.format(Locale.US, "%,d files  ·  %s", result.files.size(), result.root.name),
            13,
            MUTED,
            false));
    space(hero, 20);
    LinearLayout stripe = row();
    stripe.setBackground(rounded(LINE, 4));
    EnumMap<StorageNode.Category, Long> totals = categoryTotals();
    for (StorageNode.Category c : StorageNode.Category.values())
      if (totals.get(c) > 0) {
        View segment = new View(this);
        segment.setBackgroundColor(c.color);
        stripe.addView(
            segment,
            new LinearLayout.LayoutParams(
                0, dp(7), (float) ((double) totals.get(c) / Math.max(1, result.root.size))));
      }
    add(hero, stripe);
    space(hero, 14);
    if (!result.document) {
      try {
        StatFs stats = new StatFs(result.source);
        add(
            hero,
            text(
                StorageNode.bytes(stats.getAvailableBytes())
                    + " free of "
                    + StorageNode.bytes(stats.getTotalBytes())
                    + " on this volume",
                12,
                MUTED,
                false));
      } catch (Exception ex) {
        add(hero, text("Shared storage files only", 12, MUTED, false));
      }
    } else
      add(
          hero,
          text(
              "Selected folder · logical file sizes",
              12,
              MUTED,
              false));
    space(body, 12);
    String coverage = "";
    if (result.cancelled) coverage += "Scan stopped. Showing files found so far. ";
    if (result.limited) coverage += "Scan limit reached (250,000 entries / 128 levels). ";
    if (result.skipped > 0) coverage += result.skipped + " locations were unreadable or skipped. ";
    if (result.unknown > 0) coverage += result.unknown + " files have unknown sizes. ";
    if (!coverage.isEmpty()) {
      add(body, text(coverage, 12, 0xFFF1BC72, false));
      space(body, 10);
    }
    {
      add(
          body,
          text(
              "App-private and system data are outside this scan. Totals are file sizes, not"
                  + " allocated disk space.",
              11,
              MUTED,
              false));
      space(body, 8);
    }
    section(body, "Storage map", "New scan", this::chooseStorage);
    breadcrumb(body);
    space(body, 8);
    TreemapView map = new TreemapView(this, folder.children, this::selectNode);
    body.addView(map, new LinearLayout.LayoutParams(-1, dp(275)));
    space(body, 9);
    add(body, text("Size = space used. Tap a folder to look inside.", 11, MUTED, false));
    space(body, 12);
    LinearLayout legend = row();
    for (StorageNode.Category c :
        new StorageNode.Category[] {
          StorageNode.Category.VIDEO, StorageNode.Category.IMAGE, StorageNode.Category.ARCHIVE
        }) {
      TextView item = text("●  " + c.label, 11, c.color, false);
      legend.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
    }
    add(body, legend);
    space(body, 16);
    section(
        body,
        folder == result.root ? "At a glance" : folder.name,
        "View all",
        () -> {
          tab = 1;
          largest = false;
          search = "";
          render();
        });
    int count = 0;
    for (StorageNode n : folder.children) {
      if (count++ == 6) break;
      nodeRow(body, n, folder.size, false);
    }
    if (folder.children.isEmpty())
      add(body, text("This folder has no accessible files.", 14, MUTED, false));
    space(body, 16);
    add(
        body,
        text(
            "Updated "
                + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(new Date(result.finishedAt)),
            10,
            MUTED,
            false));
  }

  private void breadcrumb(LinearLayout parent) {
    LinearLayout r = row();
    if (folder.parent != null) {
      TextView up =
          button(
              "↑",
              false,
              () -> {
                folder = folder.parent;
                visibleLimit = 100;
                render();
              });
      up.setContentDescription("Up one folder");
      r.addView(up, new LinearLayout.LayoutParams(dp(48), dp(48)));
    }
    TextView path =
        text(folder == session.result.root ? folder.name : relative(folder), 12, MUTED, false);
    path.setMaxLines(2);
    path.setEllipsize(TextUtils.TruncateAt.START);
    path.setPadding(dp(10), dp(8), 0, dp(8));
    r.addView(path, new LinearLayout.LayoutParams(0, -2, 1));
    add(parent, r);
  }

  private String relative(StorageNode n) {
    String rootPath = session.result.root.displayPath(), path = n.displayPath();
    return path.startsWith(rootPath + "/") ? path.substring(rootPath.length() + 1) : path;
  }

  private void selectNode(StorageNode n) {
    if (n.directory && n.location.isEmpty()) {
      folder = n.parent;
      tab = 1;
      largest = false;
      visibleLimit = 100;
      search = "";
      render();
      return;
    }
    if (n.directory) {
      folder = n;
      visibleLimit = 100;
      search = "";
      if (tab == 1) largest = false;
      render();
    } else details(n);
  }

  private void nodeRow(LinearLayout parent, StorageNode n, long total, boolean path) {
    LinearLayout outer = column();
    outer.setPadding(0, dp(12), 0, dp(12));
    LinearLayout r = row();
    TextView icon = text(n.directory ? "▰" : "▪", 21, TreemapView.color(n), true);
    icon.setGravity(Gravity.CENTER);
    icon.setBackground(rounded(LINE, 12));
    r.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
    LinearLayout info = column();
    info.setPadding(dp(12), 0, dp(8), 0);
    TextView name = text(n.name, 14, TEXT, true);
    name.setSingleLine(true);
    name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
    add(info, name);
    space(info, 5);
    String sub = path ? relative(n) : (n.directory ? n.fileCount + " files" : n.extension());
    TextView desc = text(sub, 11, MUTED, false);
    desc.setSingleLine(true);
    desc.setEllipsize(TextUtils.TruncateAt.START);
    add(info, desc);
    r.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
    LinearLayout numbers = column();
    TextView bytes = text(n.unknownSize ? "Unknown" : StorageNode.bytes(n.size), 13, TEXT, true);
    bytes.setGravity(Gravity.END);
    add(numbers, bytes);
    space(numbers, 5);
    TextView percent =
        text(
            n.directory
                ? String.format(Locale.US, "%.1f%%  ›", 100.0 * n.size / Math.max(1, total))
                : String.format(Locale.US, "%.1f%%", 100.0 * n.size / Math.max(1, total)),
            11,
            MUTED,
            false);
    percent.setGravity(Gravity.END);
    add(numbers, percent);
    r.addView(
        numbers,
        new LinearLayout.LayoutParams(dp(88 * getResources().getConfiguration().fontScale), -2));
    add(outer, r);
    outer.setOnClickListener(v -> selectNode(n));
    outer.setFocusable(true);
    outer.setContentDescription(
        n.name + ", " + StorageNode.bytes(n.size) + (n.directory ? ", folder" : ""));
    add(parent, outer);
    View divider = new View(this);
    divider.setBackgroundColor(LINE);
    parent.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
  }

  private void files() {
    add(body, text("Find the big things.", 26, TEXT, true));
    space(body, 8);
    add(body, text("Explore folders or sort every scanned file by size.", 13, MUTED, false));
    space(body, 18);
    LinearLayout switcher = row();
    TextView big =
        button(
            "Largest files",
            largest,
            () -> {
              largest = true;
              visibleLimit = 100;
              search = "";
              render();
            });
    TextView browse =
        button(
            "Browse folders",
            !largest,
            () -> {
              largest = false;
              visibleLimit = 100;
              search = "";
              render();
            });
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
    p.setMargins(0, 0, dp(8), 0);
    switcher.addView(big, p);
    switcher.addView(browse, new LinearLayout.LayoutParams(0, -2, 1));
    add(body, switcher);
    space(body, 12);
    if (!largest) breadcrumb(body);
    EditText filter = new EditText(this);
    filter.setTextColor(TEXT);
    filter.setHintTextColor(MUTED);
    filter.setTextSize(14);
    filter.setSingleLine(true);
    filter.setHint(largest ? "Search all files…" : "Search this folder…");
    filter.setPadding(dp(16), dp(12), dp(16), dp(12));
    filter.setBackground(rounded(CARD, 14));
    filter.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
    filter.setText(search);
    add(body, filter);
    space(body, 12);
    rows = column();
    add(body, rows);
    fillFiles();
    filter.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int st, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            search = s.toString();
            visibleLimit = 100;
            fillFiles();
          }

          public void afterTextChanged(Editable e) {}
        });
  }

  private void fillFiles() {
    rows.removeAllViews();
    java.util.List<StorageNode> source = largest ? session.result.files : folder.children;
    java.util.List<StorageNode> filtered = new ArrayList<>();
    String query = search.toLowerCase(Locale.ROOT);
    for (StorageNode n : source)
      if (n.name.toLowerCase(Locale.ROOT).contains(query)) filtered.add(n);
    add(rows, text(filtered.size() + " items · largest first", 11, MUTED, false));
    space(rows, 6);
    int count = 0;
    for (StorageNode n : filtered) {
      if (count++ >= visibleLimit) break;
      nodeRow(rows, n, largest ? session.result.root.size : folder.size, largest);
    }
    if (filtered.isEmpty()) {
      space(rows, 28);
      add(rows, text("No matching files", 18, TEXT, true));
      space(rows, 8);
      add(rows, text("Try another name or choose a different folder.", 13, MUTED, false));
    }
    if (filtered.size() > visibleLimit) {
      space(rows, 16);
      add(
          rows,
          button(
              "Show next 100",
              false,
              () -> {
                visibleLimit += 100;
                fillFiles();
              }));
    }
  }

  private EnumMap<StorageNode.Category, Long> categoryTotals() {
    EnumMap<StorageNode.Category, Long> totals = new EnumMap<>(StorageNode.Category.class);
    for (StorageNode.Category c : StorageNode.Category.values()) totals.put(c, 0L);
    for (StorageNode n : session.result.files)
      totals.put(n.category(), totals.get(n.category()) + n.size);
    return totals;
  }

  private void types() {
    add(body, text("Every byte has a type.", 26, TEXT, true));
    space(body, 8);
    add(body, text("A breakdown of all scanned files.", 13, MUTED, false));
    space(body, 22);
    EnumMap<StorageNode.Category, Long> totals = categoryTotals();
    for (StorageNode.Category c : StorageNode.Category.values()) {
      int count = 0;
      for (StorageNode n : session.result.files) if (n.category() == c) count++;
      if (count == 0) continue;
      LinearLayout card = card(body);
      LinearLayout r = row();
      TextView name = text("●  " + c.label, 15, c.color, true);
      r.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
      r.addView(text(StorageNode.bytes(totals.get(c)), 16, TEXT, true));
      add(card, r);
      space(card, 8);
      add(
          card,
          text(
              count
                  + " files · "
                  + String.format(
                      Locale.US,
                      "%.1f%% of scanned size",
                      100.0 * totals.get(c) / Math.max(1, session.result.root.size)),
              12,
              MUTED,
              false));
      space(card, 12);
      LinearLayout bar = row();
      bar.setBackground(rounded(LINE, 4));
      float weight = (float) ((double) totals.get(c) / Math.max(1, session.result.root.size));
      View fill = new View(this);
      fill.setBackground(rounded(c.color, 4));
      bar.addView(fill, new LinearLayout.LayoutParams(0, dp(5), Math.max(.001f, weight)));
      View rest = new View(this);
      bar.addView(rest, new LinearLayout.LayoutParams(0, dp(5), Math.max(.001f, 1 - weight)));
      add(card, bar);
      space(body, 10);
    }
    section(body, "By extension", null, null);
    Map<String, Long> extensions = new HashMap<>();
    for (StorageNode n : session.result.files)
      extensions.put(n.extension(), extensions.getOrDefault(n.extension(), 0L) + n.size);
    ArrayList<Map.Entry<String, Long>> sorted = new ArrayList<>(extensions.entrySet());
    sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
    int i = 0;
    for (Map.Entry<String, Long> entry : sorted) {
      if (i++ >= 100) break;
      LinearLayout r = row();
      r.setPadding(0, dp(12), 0, dp(12));
      TextView name = text(entry.getKey(), 14, TEXT, false);
      r.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
      r.addView(text(StorageNode.bytes(entry.getValue()), 14, MUTED, true));
      add(body, r);
    }
    if (sorted.size() > 100)
      add(body, text("Showing the 100 largest extensions.", 11, MUTED, false));
  }

  private void duplicates() {
    add(body, text("Same file. Extra space.", 26, TEXT, true));
    space(body, 8);
    add(body, text("Compare file contents to find identical copies.", 13, MUTED, false));
    space(body, 22);
    if (session.duplicates == null) {
      LinearLayout c = card(body);
      add(c, text("A careful second look", 20, TEXT, true));
      space(c, 10);
      add(
          c,
          text(
              "DiskLens groups files by size, then compares their complete SHA-256 hashes. This"
                  + " reads matching-size files and can take a while.",
              14,
              MUTED,
              false));
      space(c, 16);
      add(c, text("Empty files are excluded. Nothing is deleted automatically.", 12, MUTED, false));
      space(c, 20);
      add(
            c,
            button(
                session.busy ? "Working…" : "Find duplicate files",
                true,
                () -> {
                  if (!session.busy) session.findDuplicates(this);
                }));
      return;
    }
    ScanEngine.DuplicateResult d = session.duplicates;
    long extra = 0;
    for (java.util.List<StorageNode> group : d.groups)
      extra += group.get(0).size * (group.size() - 1);
    LinearLayout c = card(body);
    subtitle(c, "SPACE IN EXTRA COPIES");
    space(c, 10);
    add(c, text(StorageNode.bytes(extra), 38, ACCENT, true));
    space(c, 8);
    add(c, text(d.groups.size() + " groups of matching files", 13, MUTED, false));
    if (d.cancelled || d.unreadable > 0) {
      space(c, 8);
      add(
          c,
          text(
              (d.cancelled ? "Check stopped. Results are partial. " : "")
                  + d.unreadable
                  + " files unreadable or changed.",
              12,
              0xFFF1BC72,
              false));
    }
    space(body, 16);
    add(
        body,
        text(
            "Review each copy before deleting. Keep at least one. Files may change after this"
                + " comparison.",
            12,
            MUTED,
            false));
    space(body, 14);
    if (d.groups.isEmpty())
      add(
          body,
          text(
              d.cancelled
                  ? "No matches found before stopping."
                  : "No identical files found among files checked.",
              15,
              TEXT,
              true));
    int groupIndex = 0;
    for (java.util.List<StorageNode> group : d.groups) {
      if (groupIndex++ >= 50) break;
      section(body, group.size() + " copies · " + StorageNode.bytes(group.get(0).size), null, null);
      for (StorageNode n : group) nodeRow(body, n, session.result.root.size, true);
    }
    if (d.groups.size() > 50)
      add(body, text("Showing the 50 largest duplicate groups.", 12, MUTED, false));
    space(body, 18);
    add(
        body,
        button(
            "Check again",
            false,
            () -> {
              if (!session.busy) session.findDuplicates(this);
            }));
  }

  private void details(StorageNode n) {
    String message =
        StorageNode.bytes(n.size) + " · " + n.category().label + "\n\n" + n.displayPath();
    if (n.modified > 0)
      message += "\n\nModified " + DateFormat.getDateTimeInstance().format(new Date(n.modified));
    new AlertDialog.Builder(this)
        .setTitle(n.name)
        .setMessage(message)
        .setPositiveButton("Open", (d, w) -> openFile(n))
        .setNeutralButton("Close", null)
        .setNegativeButton("Delete…", (d, w) -> confirmDelete(n))
        .show();
  }

  private void openFile(StorageNode n) {
    try {
      Uri uri = n.document ? Uri.parse(n.location) : ReadOnlyFileProvider.uri(new File(n.location));
      String mime = getContentResolver().getType(uri);
      Intent intent =
          new Intent(Intent.ACTION_VIEW)
              .setDataAndType(uri, mime == null ? "*/*" : mime)
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      intent.setClipData(ClipData.newRawUri(n.name, uri));
      startActivity(Intent.createChooser(intent, "Open with"));
    } catch (Exception ex) {
      alert(
          "Could not open file",
          "The file may have moved, or no installed app can open this type.");
    }
  }

  private void confirmDelete(StorageNode n) {
    if (session.busy) {
      toast("Wait for the current operation to finish.");
      return;
    }
    if (!n.deletable) {
      alert(
          "Read-only file",
          "Android or this file provider does not allow DiskLens to delete this file.");
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("Permanently delete this file?")
        .setMessage(
            n.name
                + "\n"
                + StorageNode.bytes(n.size)
                + "\n\n"
                + n.displayPath()
                + "\n\nThis cannot be undone. There is no recycle bin.")
        .setNegativeButton("Keep file", null)
        .setPositiveButton("Delete file", (dialog, which) -> deleteFile(n))
        .show();
  }

  private void deleteFile(StorageNode n) {
    if (session.busy) return;
    session.busy = true;
    session.operation = "delete";
    session.progress = "Deleting " + n.name;
    render();
    session.executor.execute(
        () -> {
          boolean success = false;
          try {
            if (n.directory) throw new IOException("Folder deletion is not supported.");
            if (n.document)
              success =
                  DocumentsContract.deleteDocument(getContentResolver(), Uri.parse(n.location));
            else {
              File f = new File(n.location);
              if (f.isFile()
                  && !java.nio.file.Files.isSymbolicLink(f.toPath())
                  && f.length() == n.size
                  && f.lastModified() == n.modified) success = f.delete();
            }
          } catch (Exception ignored) {
          }
          final boolean deleted = success;
          session.main.post(
              () -> {
                session.busy = false;
                if (deleted) {
                  session.result.files.remove(n);
                  if (n.unknownSize) session.result.unknown--;
                  StorageNode parent = n.parent;
                  if (parent != null) parent.children.remove(n);
                  while (parent != null) {
                    parent.total();
                    parent = parent.parent;
                  }
                  session.duplicates = null;
                  session.result.finishedAt = System.currentTimeMillis();
                  session.error = "";
                } else
                  session.error =
                      "Could not delete the file. It may be read-only, moved, or changed since"
                          + " scanning. Rescan and try again.";
                session.notifyChange(false);
              });
        });
  }

  private void showMenu() {
    String[] items = {
      "Scan device storage",
      "Choose a folder",
      "Rescan current location",
      "Export file list (CSV)",
      "About & privacy"
    };
    new AlertDialog.Builder(this)
        .setTitle("DiskLens")
        .setItems(
            items,
            (d, w) -> {
              if (w == 0) chooseStorage();
              else if (w == 1) pickFolder();
              else if (w == 2) rescan();
              else if (w == 3) export();
              else about();
            })
        .show();
  }

  private void chooseStorage() {
    if (session.busy) {
      toast("Stop or finish the current operation first.");
      return;
    }
    java.util.List<String> names = new ArrayList<>(), paths = new ArrayList<>();
    names.add("Internal shared storage");
    paths.add(Environment.getExternalStorageDirectory().getAbsolutePath());
    if (android.os.Build.VERSION.SDK_INT >= 30) {
      StorageManager manager = (StorageManager) getSystemService(STORAGE_SERVICE);
      for (StorageVolume volume : manager.getStorageVolumes())
        if (!volume.isPrimary() && volume.getDirectory() != null) {
          names.add(volume.getDescription(this));
          paths.add(volume.getDirectory().getAbsolutePath());
        }
    }
    names.add("Choose a folder instead…");
    new AlertDialog.Builder(this)
        .setTitle("Choose storage")
        .setItems(
            names.toArray(new String[0]),
            (d, w) -> {
              if (w == paths.size()) pickFolder();
              else {
                pendingStorage = paths.get(w);
                requestStorage();
              }
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void requestStorage() {
    if (android.os.Build.VERSION.SDK_INT >= 30) {
      if (Environment.isExternalStorageManager()) {
        scan(pendingStorage, false);
        return;
      }
      new AlertDialog.Builder(this)
          .setTitle("Allow storage analysis")
          .setMessage(
              "To map shared storage, enable ‘Allow access to manage all files’ for DiskLens on the"
                  + " next screen.\n\n"
                  + "Private app data stays restricted. You can also choose a single folder without"
                  + " this permission.")
          .setPositiveButton(
              "Open settings",
              (d, w) -> {
                pendingAccess = true;
                try {
                  startActivity(
                      new Intent(
                          Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                          Uri.parse("package:" + getPackageName())));
                } catch (ActivityNotFoundException ex) {
                  try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                  } catch (Exception e) {
                    pendingAccess = false;
                    alert(
                        "Settings unavailable", "Choose a folder with the system picker instead.");
                  }
                }
              })
          .setNeutralButton("Choose folder", (d, w) -> pickFolder())
          .setNegativeButton("Cancel", null)
          .show();
    } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED)
      requestPermissions(
          new String[] {
            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE
          },
          ACCESS);
    else scan(pendingStorage, false);
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(request, permissions, grants);
    if (request == ACCESS) {
      if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED)
        scan(pendingStorage, false);
      else
        alert(
            "Storage access not granted",
            "You can still use ‘Choose a folder’ to analyze a location you select.");
    }
  }

  private void pickFolder() {
    if (session.busy) {
      toast("Stop or finish the current operation first.");
      return;
    }
    pendingAccess = false;
    try {
      startActivityForResult(
          new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
              .addFlags(
                  Intent.FLAG_GRANT_READ_URI_PERMISSION
                      | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                      | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                      | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION),
          PICK);
    } catch (ActivityNotFoundException ex) {
      alert(
          "File picker unavailable",
          "This device does not provide a folder picker. Try scanning device storage.");
    }
  }

  private void scan(String source, boolean document) {
    tab = 0;
    search = "";
    session.scan(this, source, document);
  }

  private void rescan() {
    if (session.result == null) chooseStorage();
    else scan(session.result.source, session.result.document);
  }

  private void export() {
    if (session.result == null) {
      toast("Scan storage first.");
      return;
    }
    if (session.busy) {
      toast("Wait for the current operation to finish.");
      return;
    }
    exportResult = session.result;
    Intent intent =
        new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/csv")
            .putExtra(
                Intent.EXTRA_TITLE,
                "DiskLens-"
                    + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date())
                    + ".csv");
    try {
      startActivityForResult(intent, EXPORT);
    } catch (ActivityNotFoundException ex) {
      alert("Export unavailable", "This device does not provide a file-saving picker.");
    }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null) return;
    Uri uri = data.getData();
    if (request == PICK) {
      try {
        getContentResolver()
            .takePersistableUriPermission(
                uri,
                (data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
                    ? Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    : Intent.FLAG_GRANT_READ_URI_PERMISSION);
      } catch (SecurityException ignored) {
      }
      scan(uri.toString(), true);
    } else if (request == EXPORT) {
      final ScanEngine.Result snapshot = exportResult != null ? exportResult : session.result;
      if (snapshot == null) return;
      session.busy = true;
      session.operation = "export";
      session.progress = "Saving CSV…";
      render();
      session.executor.execute(
          () -> {
            String error = "";
            try {
              OutputStream out = getContentResolver().openOutputStream(uri);
              if (out == null) throw new IOException();
              ScanEngine.export(snapshot, out);
            } catch (Exception ex) {
              error = "Could not save the CSV. Check access and available space.";
            }
            final String message = error;
            session.main.post(
                () -> {
                  session.busy = false;
                  session.error = message;
                  session.notifyChange(false);
                  if (message.isEmpty() && session.listener != null) toast("File list exported");
                });
          });
    }
  }

  private void about() {
    alert(
        "DiskLens 1.0",
        "An independent Android storage analyzer inspired by WinDirStat. Not affiliated with the"
            + " WinDirStat team.\n\n"
            + "• Interactive storage map and folder browsing\n"
            + "• Largest files and file-type statistics\n"
            + "• Full SHA-256 duplicate comparison\n"
            + "• File opening, confirmed deletion, and CSV export\n\n"
            + "Privacy: no internet permission, analytics, ads, accounts, or uploads. Scans stay in"
            + " memory and are cleared when the app process exits. CSV exports stay where you save"
            + " them.\n\n"
            + "Scope: Android 8.0+. Shared storage or folders you grant access to. System files and"
            + " other apps’ private data are excluded. KB / MB / GB use powers of 1024.\n\n"
            + "Scans run while the app process is alive; keep the app open for long scans. Results"
            + " reflect the last scan.\n\n"
            + "Original source code is included with this build under the MIT license.");
  }

  private void alert(String title, String message) {
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton("OK", null)
        .show();
  }

  private void toast(String message) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
  }

  // Legacy devices only: API 33+ uses the native OnBackInvokedDispatcher registered in onCreate.
  @android.annotation.SuppressLint("GestureBackNavigation")
  @Override
  public void onBackPressed() {
    if (!navigateBack()) super.onBackPressed();
  }

  private boolean navigateBack() {
    if (folder != null && folder.parent != null && (tab == 0 || (tab == 1 && !largest))) {
      folder = folder.parent;
      render();
      return true;
    }
    if (tab != 0) {
      tab = 0;
      render();
      return true;
    }
    return false;
  }
}
