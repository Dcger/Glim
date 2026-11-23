package dev.discovery.wallpaper;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

public final class RestoredPdfProvider extends ContentProvider {
    private static final String SUFFIX = ".restored-pdfs";

    static Uri forReader(Context context, Uri uri) {
        if (!"file".equals(uri.getScheme())) return uri;
        String path = uri.getPath();
        if (path == null) throw new IllegalArgumentException("Missing PDF path");
        File file = new File(path);
        File folder = file.getParentFile();
        if (folder == null || !context.getFilesDir().equals(folder.getParentFile())
                || !valid(folder.getName(), file.getName())) {
            throw new IllegalArgumentException("Invalid restored PDF");
        }
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + SUFFIX)
                .appendPath(folder.getName()).appendPath(file.getName()).build();
    }

    private static boolean valid(String folder, String name) {
        return folder.matches("card-media-[a-f0-9-]{36}") && name.matches("asset-pdf-[0-9]+\\.pdf");
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        List<String> parts = uri.getPathSegments();
        if (parts.size() != 2 || !valid(parts.get(0), parts.get(1))) throw new FileNotFoundException();
        File file = new File(new File(getContext().getFilesDir(), parts.get(0)), parts.get(1));
        if (!file.isFile()) throw new FileNotFoundException();
        return file;
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return AgentShare.PDF; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String[] columns = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor result = new MatrixCursor(columns);
        try {
            File file = resolve(uri);
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = file.getName();
                else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = file.length();
            }
            result.addRow(values);
        } catch (FileNotFoundException ignored) {
            // The attachment may have been removed since the URI was granted.
        }
        return result;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
