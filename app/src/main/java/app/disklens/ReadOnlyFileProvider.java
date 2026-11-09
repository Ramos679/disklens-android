package app.disklens;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.MimeTypeMap;
import java.io.*;
import java.util.Locale;

/** Only explicitly granted URIs are accessible externally; write access is never provided. */
public final class ReadOnlyFileProvider extends ContentProvider {
  public static Uri uri(File file) {
    return new Uri.Builder()
        .scheme("content")
        .authority("app.disklens.files")
        .appendPath(
            Base64.encodeToString(
                file.getAbsolutePath().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP))
        .build();
  }

  private File file(Uri uri) {
    if (!"app.disklens.files".equals(uri.getAuthority()) || uri.getPathSegments().size() != 1)
      throw new SecurityException("Invalid URI");
    String path =
        new String(
            Base64.decode(uri.getLastPathSegment(), Base64.URL_SAFE | Base64.NO_WRAP),
            java.nio.charset.StandardCharsets.UTF_8);
    return new File(path);
  }

  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public String getType(Uri uri) {
    String name = file(uri).getName();
    int dot = name.lastIndexOf('.');
    String type =
        dot < 0
            ? null
            : MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    return type == null ? "application/octet-stream" : type;
  }

  @Override
  public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
    File file = file(uri);
    String[] columns =
        projection == null
            ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
            : projection;
    MatrixCursor cursor = new MatrixCursor(columns);
    Object[] row = new Object[columns.length];
    for (int i = 0; i < columns.length; i++) {
      if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = file.getName();
      if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
    }
    cursor.addRow(row);
    return cursor;
  }

  @Override
  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (!"r".equals(mode)) throw new SecurityException("Read-only access");
    return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
  }

  @Override
  public Uri insert(Uri uri, ContentValues v) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int update(Uri uri, ContentValues v, String s, String[] a) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int delete(Uri uri, String s, String[] a) {
    throw new UnsupportedOperationException();
  }
}
