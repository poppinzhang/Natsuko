package com.poppinzhang.natsuko;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Test-APK-only share source. Its descriptors are pipes, with no seekable filesystem path. */
public final class VideoTestProvider extends ContentProvider {
    public static final String AUTHORITY = "com.poppinzhang.natsuko.video.tests";

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name != null && name.endsWith(".mkv")) return "video/x-matroska";
        if (name != null && name.endsWith(".mov")) return "video/quicktime";
        return "video/mp4";
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        String[] columns = projection == null
                ? new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE } : projection;
        MatrixCursor result = new MatrixCursor(columns);
        Object[] values = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[index])) values[index] = uri.getLastPathSegment();
            // Unknown size is intentional: a cloud/streaming provider need not advertise it.
        }
        result.addRow(values);
        return result;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Fixture is read only");
        String name = uri.getLastPathSegment();
        if ("denied.mp4".equals(name)) throw new SecurityException("Fixture read permission revoked");
        if (name == null || !name.matches("[a-z0-9]+\\.(mp4|mkv|mov)")) {
            throw new FileNotFoundException("Unknown video fixture");
        }
        final InputStream input;
        try {
            input = "corrupt.mp4".equals(name)
                    ? new ByteArrayInputStream("not a video".getBytes(StandardCharsets.UTF_8))
                    : getContext().getAssets().open("video/" + name);
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
            Thread writer = new Thread(() -> {
                try (InputStream source = input;
                     OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = source.read(buffer)) >= 0) output.write(buffer, 0, count);
                } catch (IOException ignored) {
                    // The reader closes the pipe when import is cancelled.
                }
            }, "video-test-source");
            writer.setDaemon(true);
            writer.start();
            return pipe[0];
        } catch (IOException error) {
            throw new FileNotFoundException(error.getMessage());
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
