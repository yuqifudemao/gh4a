package com.gh4a.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import com.gh4a.BaseActivity;
import com.gh4a.R;
import com.gh4a.utils.DiagnosticLogger;
import java.io.File;
import java.io.IOException;

public class DiagnosticsActivity extends BaseActivity {
    private TextView mLogView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.diagnostics_activity);
        mLogView = findViewById(R.id.diagnostics_log);
        findViewById(R.id.diagnostics_share).setOnClickListener(v -> shareLog());
        findViewById(R.id.diagnostics_clear).setOnClickListener(v -> {
            DiagnosticLogger.clear();
            refreshLog();
            Toast.makeText(this, R.string.diagnostics_cleared, Toast.LENGTH_SHORT).show();
        });
        refreshLog();
    }

    @Nullable
    @Override
    protected String getActionBarTitle() {
        return getString(R.string.diagnostics);
    }

    private void refreshLog() {
        String log = DiagnosticLogger.read();
        mLogView.setText(log.isEmpty() ? getString(R.string.diagnostics_empty) : log);
    }

    private void shareLog() {
        try {
            File file = DiagnosticLogger.createShareFile(this);
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".diagnostics", file);
            Intent intent = new Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diagnostics))
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.diagnostics_share)));
        } catch (IOException e) {
            DiagnosticLogger.logThrowable("SHARE", e);
            Toast.makeText(this, R.string.diagnostics_share_failed, Toast.LENGTH_LONG).show();
        }
    }
}
