package app.disklens;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Retained across Activity recreation; only application context is held by background tasks. */
final class ScanSession {
  interface Listener {
    void changed(boolean progressOnly);
  }

  final ExecutorService executor = Executors.newSingleThreadExecutor();
  final Handler main = new Handler(Looper.getMainLooper());
  Listener listener;
  ScanEngine.Result result;
  ScanEngine.DuplicateResult duplicates;
  ScanEngine engine;
  boolean busy;
  String progress = "", error = "", operation = "";

  void notifyChange(boolean progressOnly) {
    if (listener != null) listener.changed(progressOnly);
  }

  private ScanEngine create(Context context) {
    return new ScanEngine(
        context,
        message ->
            main.post(
                () -> {
                  progress = message;
                  notifyChange(true);
                }));
  }

  void scan(Context context, String source, boolean document) {
    if (busy) return;
    engine = create(context);
    busy = true;
    error = "";
    operation = "scan";
    progress = "Reading folders…";
    duplicates = null;
    notifyChange(false);
    executor.execute(
        () -> {
          try {
            ScanEngine.Result next = engine.scan(source, document);
            main.post(
                () -> {
                  result = next;
                  busy = false;
                  notifyChange(false);
                });
          } catch (Exception ex) {
            main.post(
                () -> {
                  error = "Could not scan this location. Check access and try again.";
                  busy = false;
                  notifyChange(false);
                });
          }
        });
  }

  void findDuplicates(Context context) {
    if (busy || result == null) return;
    engine = create(context);
    busy = true;
    error = "";
    operation = "duplicates";
    progress = "Finding files with matching sizes…";
    notifyChange(false);
    executor.execute(
        () -> {
          ScanEngine.DuplicateResult found = engine.duplicates(result.files);
          main.post(
              () -> {
                duplicates = found;
                busy = false;
                notifyChange(false);
              });
        });
  }

  void cancel() {
    if (engine != null) engine.cancelled = true;
    progress = "Stopping…";
    notifyChange(true);
  }

  void close() {
    listener = null;
    cancel();
    executor.shutdown();
  }
}
