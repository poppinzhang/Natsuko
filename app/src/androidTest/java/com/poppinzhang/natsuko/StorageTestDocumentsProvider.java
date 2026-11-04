package com.poppinzhang.natsuko;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

/** Real SAF fixture with write failures and a throttled stream for cancellation tests. */
public final class StorageTestDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "com.poppinzhang.natsuko.storage.tests";

    private File root() {
        File root = new File(getContext().getFilesDir(), "storage-test-provider");
        root.mkdirs();
        return root;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection == null ? new String[] {
                Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS
        } : projection);
        cursor.newRow().add(Root.COLUMN_ROOT_ID, "root").add(Root.COLUMN_DOCUMENT_ID, "root")
                .add(Root.COLUMN_TITLE, "Storage tests").add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE);
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = documentCursor(projection);
        addFile(cursor, documentId, documentFile(documentId));
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        MatrixCursor cursor = documentCursor(projection);
        File[] files = documentFile(parentDocumentId).listFiles();
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (File file : files) addFile(cursor, documentId(file), file);
        }
        return cursor;
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return documentId.startsWith(parentDocumentId + "/");
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        File directory = documentFile(parentDocumentId);
        if (!displayName.equals(new File(displayName).getName())) throw new FileNotFoundException(displayName);
        File target = new File(directory, displayName);
        int suffix = 1;
        while (target.exists()) target = new File(directory, displayName + " (" + suffix++ + ")");
        try {
            boolean created = Document.MIME_TYPE_DIR.equals(mimeType) ? target.mkdir() : target.createNewFile();
            if (!created) throw new FileNotFoundException(target.toString());
            return documentId(target);
        } catch (IOException error) {
            throw new FileNotFoundException(error.getMessage());
        }
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        if ("root".equals(documentId) || !deleteRecursively(documentFile(documentId))) {
            throw new FileNotFoundException(documentId);
        }
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        File file = documentFile(documentId);
        if (mode.contains("w") && file.getName().startsWith("fail-")) {
            throw new FileNotFoundException("Fixture refuses writes");
        }
        if (mode.contains("w") && file.getName().startsWith("slow-")) {
            try {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
                Thread writer = new Thread(() -> {
                    try (FileInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0]);
                         FileOutputStream output = new FileOutputStream(file)) {
                        byte[] buffer = new byte[4096];
                        int count;
                        while ((count = input.read(buffer)) >= 0) {
                            output.write(buffer, 0, count);
                            output.flush();
                            Thread.sleep(5);
                        }
                    } catch (IOException | InterruptedException ignored) {
                        // Closing a cancelled writer is expected in this fixture.
                    }
                }, "storage-test-slow-writer");
                writer.setDaemon(true);
                writer.start();
                return pipe[1];
            } catch (IOException error) {
                throw new FileNotFoundException(error.getMessage());
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    private File documentFile(String documentId) throws FileNotFoundException {
        if ("root".equals(documentId)) return root();
        if (!documentId.startsWith("root/")) throw new FileNotFoundException(documentId);
        try {
            File file = new File(root(), documentId.substring("root/".length())).getCanonicalFile();
            if (!file.getPath().startsWith(root().getCanonicalPath() + File.separator) || !file.exists()) {
                throw new FileNotFoundException(documentId);
            }
            return file;
        } catch (IOException error) {
            throw new FileNotFoundException(error.getMessage());
        }
    }

    private String documentId(File file) throws FileNotFoundException {
        try {
            String prefix = root().getCanonicalPath() + File.separator;
            String path = file.getCanonicalPath();
            if (!path.startsWith(prefix)) throw new FileNotFoundException(path);
            return "root/" + path.substring(prefix.length());
        } catch (IOException error) {
            throw new FileNotFoundException(error.getMessage());
        }
    }

    private boolean deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) if (!deleteRecursively(child)) return false;
        return file.delete();
    }

    private MatrixCursor documentCursor(String[] projection) {
        return new MatrixCursor(projection == null ? new String[] {
                Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
                Document.COLUMN_FLAGS, Document.COLUMN_SIZE
        } : projection);
    }

    private void addFile(MatrixCursor cursor, String documentId, File file) {
        Object[] values = new Object[cursor.getColumnCount()];
        for (int index = 0; index < values.length; index++) {
            switch (cursor.getColumnNames()[index]) {
                case Document.COLUMN_DOCUMENT_ID:
                    values[index] = documentId;
                    break;
                case Document.COLUMN_DISPLAY_NAME:
                    values[index] = "root".equals(documentId) ? "Storage tests" : file.getName();
                    break;
                case Document.COLUMN_MIME_TYPE:
                    values[index] = file.isDirectory() ? Document.MIME_TYPE_DIR : "application/octet-stream";
                    break;
                case Document.COLUMN_FLAGS:
                    values[index] = file.isDirectory() ? Document.FLAG_DIR_SUPPORTS_CREATE
                            : Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE;
                    break;
                case Document.COLUMN_SIZE:
                    values[index] = file.length();
                    break;
                default:
                    values[index] = null;
            }
        }
        cursor.addRow(values);
    }
}
