package com.mobilegh.nav;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Objects;

/** Real content URIs whose metadata and byte streams are controlled by each test. */
public final class ShareImportProvider extends ContentProvider {
    private static final class Entry {
        final String mime;
        final String name;
        final long bytes;
        final Long reportedSize;
        final Long failAfter;

        Entry(Uri uri) {
            mime = Objects.requireNonNull(uri.getQueryParameter("mime"));
            name = Objects.requireNonNull(uri.getQueryParameter("name"));
            bytes = Long.parseLong(Objects.requireNonNull(uri.getQueryParameter("bytes")));
            String reported = uri.getQueryParameter("reported");
            String failure = uri.getQueryParameter("failAfter");
            reportedSize = reported == null ? null : Long.parseLong(reported);
            failAfter = failure == null ? null : Long.parseLong(failure);
        }
    }

    @Override public boolean onCreate() {
        return true;
    }

    @Override public String getType(Uri uri) {
        return new Entry(uri).mime;
    }

    @Override public Cursor query(
        Uri uri,
        String[] projection,
        String selection,
        String[] selectionArgs,
        String sortOrder
    ) {
        Entry item = new Entry(uri);
        String[] columns = projection == null
            ? new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE }
            : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[index])) row[index] = item.name;
            if (OpenableColumns.SIZE.equals(columns[index])) row[index] = item.reportedSize;
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new IllegalArgumentException("read-only test provider");
        Entry item = new Entry(uri);
        ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createReliablePipe();
        } catch (IOException exception) {
            throw new FileNotFoundException("failure making reliable test pipe");
        }
        Thread writer = new Thread(() -> {
            ParcelFileDescriptor.AutoCloseOutputStream output =
                new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
            try {
                long writtenBytes = Math.min(item.bytes, item.failAfter == null ? item.bytes : item.failAfter);
                long offset = 0;
                while (offset < writtenBytes) {
                    int count = (int) Math.min(8192, writtenBytes - offset);
                    output.write(bytesAt(offset, count));
                    offset += count;
                }
                output.flush();
                if (item.failAfter != null) throw new IOException("requested test stream failure");
                output.close();
            } catch (IOException exception) {
                try {
                    pipe[1].closeWithError(exception.getMessage());
                } catch (IOException ignored) {
                    // The reader has already disconnected.
                }
            }
        }, "share-import-test-writer");
        writer.start();
        return pipe[0];
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only test provider");
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only test provider");
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only test provider");
    }

    private static byte[] bytesAt(long offset, int count) {
        byte[] bytes = new byte[count];
        for (int index = 0; index < count; index++) {
            bytes[index] = (byte) ((offset + index) * 31 % 251);
        }
        return bytes;
    }
}
