package com.poppinzhang.natsuko;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

/** Grants access from the provider-owning APK, like a system directory picker. */
public final class StorageTestGrantActivity extends Activity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String targetPackage = getIntent().getStringExtra("target_package");
        if (targetPackage == null) throw new IllegalArgumentException("Missing target package");
        Uri uri = DocumentsContract.buildTreeDocumentUri(StorageTestDocumentsProvider.AUTHORITY, "root");
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        if (getIntent().getBooleanExtra("revoke", false)) {
            revokeUriPermission(targetPackage, uri, flags);
        } else {
            grantUriPermission(targetPackage, uri, flags | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        }
        finish();
    }
}
