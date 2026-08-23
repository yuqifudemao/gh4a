/*
 * Copyright 2011 Azwan Adli Abdullah
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gh4a.activities;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.gh4a.utils.ActivityResultHelpers;
import com.google.android.material.appbar.AppBarLayout;
import android.util.Pair;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;

import com.gh4a.BaseActivity;
import com.gh4a.BuildConfig;
import com.gh4a.Gh4Application;
import com.gh4a.R;
import com.gh4a.ServiceFactory;
import com.gh4a.fragment.LoginModeChooserFragment;
import com.gh4a.utils.ApiHelpers;
import com.gh4a.utils.IntentUtils;
import com.gh4a.utils.RxUtils;
import com.meisolsson.githubsdk.model.User;
import com.meisolsson.githubsdk.service.users.UserService;

import org.json.JSONObject;

import java.io.IOException;

import io.reactivex.Single;
import io.reactivex.disposables.Disposable;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * The Github4Android activity.
 */
public class Github4AndroidActivity extends BaseActivity implements
        View.OnClickListener, LoginModeChooserFragment.ParentCallback {
    private static final String DEVICE_CODE_URL = "https://github.com/login/device/code";
    private static final String ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final Uri DEVICE_LOGIN_URI = Uri.parse("https://github.com/login/device");
    private static final String DEVICE_GRANT_TYPE =
            "urn:ietf:params:oauth:grant-type:device_code";

    private final OkHttpClient mOauthClient = new OkHttpClient();
    private Disposable mDeviceLoginDisposable;
    private AlertDialog mDeviceLoginDialog;

    private View mContent;
    private View mProgress;

    private final ActivityResultLauncher<Void> mSettingsLauncher = registerForActivityResult(
            new ActivityResultHelpers.StartSettingsContract(),
            themeChange -> {
                if (themeChange) {
                    Intent intent = new Intent(getIntent());
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(intent);
                    finish();
                }
            });

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Gh4Application app = Gh4Application.get();
        if (app.isAuthorized()) {
            if (!handleIntent(getIntent())) {
                goToToplevelActivity();
            }
            finish();
        } else {
            setContentView(R.layout.main);

            AppBarLayout abl = findViewById(R.id.header);
            abl.setEnabled(false);

            FrameLayout contentContainer = (FrameLayout) findViewById(R.id.content).getParent();
            contentContainer.setForeground(null);

            findViewById(R.id.login_button).setOnClickListener(this);
            mContent = findViewById(R.id.welcome_container);
            mProgress = findViewById(R.id.login_progress_container);

            handleIntent(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if (!handleIntent(intent)) {
            super.onNewIntent(intent);
        }
    }

    private boolean handleIntent(Intent intent) {
        return false;
    }

    @Override
    protected int getLeftNavigationDrawerMenuResource() {
        return R.menu.home_nav_drawer;
    }

    @IdRes
    protected int getInitialLeftDrawerSelection(Menu menu) {
        menu.setGroupCheckable(R.id.navigation, false, false);
        menu.setGroupCheckable(R.id.explore, false, false);
        menu.setGroupVisible(R.id.my_items, false);
        return super.getInitialLeftDrawerSelection(menu);
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        super.onNavigationItemSelected(item);
        switch (item.getItemId()) {
            case R.id.settings:
                mSettingsLauncher.launch(null);
                return true;
            case R.id.search:
                startActivity(SearchActivity.makeIntent(this));
                return true;
            case R.id.bookmarks:
                startActivity(new Intent(this, BookmarkListActivity.class));
                return true;
            case R.id.pub_timeline:
                startActivity(new Intent(this, TimelineActivity.class));
                return true;
            case R.id.blog:
                startActivity(new Intent(this, BlogListActivity.class));
                return true;
            case R.id.trend:
                startActivity(new Intent(this, TrendingActivity.class));
                return true;
        }
        return false;
    }

    @Override
    protected boolean canSwipeToRefresh() {
        return false;
    }

    @Override
    public void onClick(View view) {
        if (view.getId() == R.id.login_button) {
            LoginModeChooserFragment.newInstance().show(getSupportFragmentManager(), "login");
            setProgressShown(true);
        }
    }

    @Override
    public void onBackPressed() {
        if (mProgress.getVisibility() == View.VISIBLE) {
            setProgressShown(false);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onLoginStartOauth() {
        requestDeviceCode()
                .compose(RxUtils::doInBackground)
                .subscribe(this::showDeviceLogin, this::onDeviceLoginFailure);
    }

    @Override
    public void onLoginFinished(String token, User user) {
        Gh4Application.get().addAccount(user, token);
        goToToplevelActivity();
        finish();
    }

    @Override
    public void onLoginFailed(Throwable error) {
        handleLoadFailure(error);
        setProgressShown(false);
    }

    @Override
    public void onLoginCanceled() {
        if (mDeviceLoginDisposable != null) {
            mDeviceLoginDisposable.dispose();
            mDeviceLoginDisposable = null;
        }
        setProgressShown(false);
    }

    private void setProgressShown(boolean show) {
        mContent.setVisibility(show ? View.GONE : View.VISIBLE);
        mProgress.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private Single<DeviceCode> requestDeviceCode() {
        return Single.fromCallable(() -> {
            FormBody body = new FormBody.Builder()
                    .add("client_id", BuildConfig.CLIENT_ID)
                    .add("scope", LoginModeChooserFragment.SCOPES)
                    .build();
            Request request = new Request.Builder()
                    .url(DEVICE_CODE_URL)
                    .header("Accept", "application/json")
                    .post(body)
                    .build();
            try (Response response = mOauthClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IOException("HTTP " + response.code());
                }
                JSONObject json = new JSONObject(response.body().string());
                if (json.has("error")) {
                    throw new IOException(json.optString("error_description",
                            json.optString("error")));
                }
                return new DeviceCode(
                        json.getString("device_code"),
                        json.getString("user_code"),
                        Math.max(5, json.optInt("interval", 5)),
                        json.optInt("expires_in", 900));
            }
        });
    }

    private void showDeviceLogin(DeviceCode deviceCode) {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("GitHub device code",
                deviceCode.userCode));
        Toast.makeText(this, R.string.device_login_copied, Toast.LENGTH_SHORT).show();

        mDeviceLoginDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.device_login_title)
                .setMessage(getString(R.string.device_login_message, deviceCode.userCode))
                .setPositiveButton(R.string.device_login_open,
                        (dialog, which) -> IntentUtils.openInCustomTabOrBrowser(
                                this, DEVICE_LOGIN_URI))
                .setNegativeButton(R.string.device_login_cancel,
                        (dialog, which) -> onLoginCanceled())
                .setOnCancelListener(dialog -> onLoginCanceled())
                .show();

        mDeviceLoginDisposable = pollForAccessToken(deviceCode)
                .flatMap(token -> {
                    UserService userService = ServiceFactory.get(UserService.class, true,
                            null, token, null);
                    return userService.getUser()
                            .map(ApiHelpers::throwOnFailure)
                            .map(user -> Pair.create(token, user));
                })
                .compose(RxUtils::doInBackground)
                .subscribe(pair -> {
                    if (mDeviceLoginDialog != null) {
                        mDeviceLoginDialog.dismiss();
                    }
                    onLoginFinished(pair.first, pair.second);
                }, this::onDeviceLoginFailure);
    }

    private Single<String> pollForAccessToken(DeviceCode deviceCode) {
        return Single.create(emitter -> {
            int intervalSeconds = deviceCode.intervalSeconds;
            long expiresAt = System.currentTimeMillis() + deviceCode.expiresInSeconds * 1000L;
            while (!emitter.isDisposed() && System.currentTimeMillis() < expiresAt) {
                Thread.sleep(intervalSeconds * 1000L);

                FormBody body = new FormBody.Builder()
                        .add("client_id", BuildConfig.CLIENT_ID)
                        .add("device_code", deviceCode.deviceCode)
                        .add("grant_type", DEVICE_GRANT_TYPE)
                        .build();
                Request request = new Request.Builder()
                        .url(ACCESS_TOKEN_URL)
                        .header("Accept", "application/json")
                        .post(body)
                        .build();
                try (Response response = mOauthClient.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new IOException("HTTP " + response.code());
                    }
                    JSONObject json = new JSONObject(response.body().string());
                    String token = json.optString("access_token", null);
                    if (token != null) {
                        emitter.onSuccess(token);
                        return;
                    }

                    String error = json.optString("error");
                    if ("authorization_pending".equals(error)) {
                        continue;
                    }
                    if ("slow_down".equals(error)) {
                        intervalSeconds += 5;
                        continue;
                    }
                    throw new IOException(json.optString("error_description", error));
                }
            }
            if (!emitter.isDisposed()) {
                emitter.onError(new IOException("Authorization code expired"));
            }
        });
    }

    private void onDeviceLoginFailure(Throwable error) {
        if (mDeviceLoginDialog != null) {
            mDeviceLoginDialog.dismiss();
            mDeviceLoginDialog = null;
        }
        Toast.makeText(this,
                getString(R.string.device_login_failed, error.getMessage()),
                Toast.LENGTH_LONG).show();
        handleLoadFailure(error);
        setProgressShown(false);
    }

    @Override
    protected void onDestroy() {
        if (mDeviceLoginDisposable != null) {
            mDeviceLoginDisposable.dispose();
        }
        super.onDestroy();
    }

    private static class DeviceCode {
        final String deviceCode;
        final String userCode;
        final int intervalSeconds;
        final int expiresInSeconds;

        DeviceCode(String deviceCode, String userCode, int intervalSeconds,
                int expiresInSeconds) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.intervalSeconds = intervalSeconds;
            this.expiresInSeconds = expiresInSeconds;
        }
    }
}
