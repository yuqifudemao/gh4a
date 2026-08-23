package com.gh4a.utils;

import android.app.Activity;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.text.SpannableStringBuilder;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Translates English text visible on the current screen with Google online translation. */
public final class PageTranslator {
    private static final String TRANSLATE_ENDPOINT =
            "https://translate.google.com/translate_a/single";
    private static final String MYMEMORY_ENDPOINT =
            "https://api.mymemory.translated.net/get";
    private static final String BATCH_SEPARATOR = "[[[9876543210123456789]]]";
    private static final String COLLECT_TEXT_NODES =
            "(function(){window.__octoTranslationNodes=[];var w=document.createTreeWalker(" +
            "document.body,NodeFilter.SHOW_TEXT);var n,a=[];while(n=w.nextNode()){var p=n.parentNode;" +
            "if(!p||/^(SCRIPT|STYLE|CODE|PRE|TEXTAREA|INPUT)$/i.test(p.nodeName))continue;" +
            "var t=n.nodeValue.trim();if(/[A-Za-z]{2}/.test(t)){window.__octoTranslationNodes.push(n);" +
            "a.push(n.nodeValue);}}window.__octoTranslationOriginal=a.slice();return JSON.stringify(a);})()";
    private static final String RESTORE_WEB_TEXT =
            "(function(){if(!window.__octoTranslationNodes)return;window.__octoTranslationNodes." +
            "forEach(function(n,i){n.nodeValue=window.__octoTranslationOriginal[i];});})()";

    private final Activity mActivity;
    private final OkHttpClient mClient = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
    private final ExecutorService mTranslationQueue = Executors.newSingleThreadExecutor();
    private final SharedPreferences mCache;
    private final WeakHashMap<TextView, CharSequence> mOriginalText = new WeakHashMap<>();
    private final List<WebView> mTranslatedWebViews = new ArrayList<>();
    private boolean mTranslated;
    private volatile boolean mRateLimited;
    private volatile boolean mRateLimitNoticeShown;

    public PageTranslator(@NonNull Activity activity) {
        mActivity = activity;
        mCache = activity.getSharedPreferences("online_translation_cache", Activity.MODE_PRIVATE);
    }

    public boolean isTranslated() {
        return mTranslated;
    }

    public void translate(@NonNull View root, @NonNull Runnable onComplete,
            @NonNull Consumer<Exception> onFailure) {
        translateViews(root, onComplete, onFailure);
    }

    public void restore(@NonNull View root) {
        for (TextView view : new ArrayList<>(mOriginalText.keySet())) {
            CharSequence original = mOriginalText.get(view);
            if (view != null && original != null) view.setText(original);
        }
        for (WebView webView : mTranslatedWebViews) {
            if (webView != null) webView.evaluateJavascript(RESTORE_WEB_TEXT, null);
        }
        mOriginalText.clear();
        mTranslatedWebViews.clear();
        mTranslated = false;
    }

    private void translateViews(View root, Runnable onComplete, Consumer<Exception> onFailure) {
        List<TextView> textViews = new ArrayList<>();
        List<WebView> webViews = new ArrayList<>();
        collectViews(root, textViews, webViews);
        int characterCount = 0;
        for (TextView view : textViews) characterCount += view.getText().length();
        DiagnosticLogger.log("TRANSLATE", "page=" + mActivity.getClass().getSimpleName()
                + ", characters=" + characterCount + ", textViews=" + textViews.size()
                + ", webViews=" + webViews.size() + ", provider=Google");
        AtomicInteger pending = new AtomicInteger(textViews.size() + webViews.size());
        AtomicInteger successes = new AtomicInteger();
        if (pending.get() == 0) {
            mTranslated = true;
            onComplete.run();
            return;
        }
        Runnable finishedOne = () -> {
            if (pending.decrementAndGet() == 0) {
                mTranslated = successes.get() > 0;
                if (mTranslated) onComplete.run();
                else if (mRateLimited) onFailure.accept(new RateLimitException());
                else onFailure.accept(new IOException("No text could be translated"));
            }
        };
        for (TextView view : textViews) {
            translateTextView(view, successes, finishedOne);
        }
        for (WebView webView : webViews) translateWebView(webView, successes, finishedOne);
    }

    private void translateTextView(TextView view, AtomicInteger successes, Runnable finished) {
        CharSequence original = view.getText();
        mOriginalText.put(view, original);
        String source = original.toString();
        List<TextSegment> segments = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= source.length(); i++) {
            if (i == source.length() || source.charAt(i) == '\n') {
                String line = source.substring(start, i);
                if (shouldTranslate(line) && line.indexOf('\ufffc') < 0) {
                    segments.add(new TextSegment(start, i, line));
                }
                start = i + 1;
            }
        }
        if (segments.isEmpty()) {
            finished.run();
            return;
        }
        List<String> parts = new ArrayList<>();
        for (TextSegment segment : segments) parts.add(segment.source);
        translateParts(parts, translations -> {
            SpannableStringBuilder translated = new SpannableStringBuilder(original);
            for (int i = segments.size() - 1; i >= 0; i--) {
                TextSegment segment = segments.get(i);
                translated.replace(segment.start, segment.end, translations.get(i));
            }
            successes.incrementAndGet();
            if (!mActivity.isFinishing()) view.setText(translated);
            finished.run();
        }, finished);
    }

    private void translateWebView(WebView webView, AtomicInteger successes, Runnable finished) {
        webView.evaluateJavascript(COLLECT_TEXT_NODES, value -> {
            try {
                String decoded = value == null ? "[]" : new JSONArray("[" + value + "]").getString(0);
                JSONArray nodes = new JSONArray(decoded);
                if (nodes.length() == 0) {
                    finished.run();
                    return;
                }
                mTranslatedWebViews.add(webView);
                List<String> parts = new ArrayList<>();
                for (int i = 0; i < nodes.length(); i++) {
                    parts.add(nodes.getString(i));
                }
                translateParts(parts, translations -> {
                    successes.incrementAndGet();
                    for (int i = 0; i < translations.size(); i++) {
                        webView.evaluateJavascript("window.__octoTranslationNodes[" + i +
                                "].nodeValue=" + JSONObject.quote(translations.get(i)), null);
                    }
                    finished.run();
                }, finished);
            } catch (Exception ignored) {
                finished.run();
            }
        });
    }

    private void translateParts(List<String> parts, Consumer<List<String>> success,
            Runnable failure) {
        List<BatchGroup> groups = new ArrayList<>();
        for (int start = 0; start < parts.size();) {
            int end = start;
            int length = 0;
            StringBuilder batch = new StringBuilder();
            while (end < parts.size()) {
                String part = parts.get(end);
                int added = part.length() + (end > start ? BATCH_SEPARATOR.length() : 0);
                if (end > start && length + added > 2500) break;
                if (end > start) batch.append(BATCH_SEPARATOR);
                batch.append(part);
                length += added;
                end++;
            }
            groups.add(new BatchGroup(start, end, batch.toString()));
            start = end;
        }
        String[] result = new String[parts.size()];
        AtomicInteger pending = new AtomicInteger(groups.size());
        AtomicInteger failures = new AtomicInteger();
        Runnable groupFinished = () -> {
            if (pending.decrementAndGet() != 0) return;
            if (failures.get() > 0) failure.run();
            else success.accept(java.util.Arrays.asList(result));
        };
        for (BatchGroup group : groups) {
            translateText(group.source, translated -> {
                String[] split = translated.split(Pattern.quote(BATCH_SEPARATOR), -1);
                if (split.length != group.end - group.start) {
                    failures.incrementAndGet();
                } else {
                    System.arraycopy(split, 0, result, group.start, split.length);
                }
                groupFinished.run();
            }, () -> {
                failures.incrementAndGet();
                groupFinished.run();
            });
        }
    }

    private void translateText(String text, Consumer<String> success, Runnable failure) {
        String cacheKey = cacheKey(text);
        String cached = mCache.getString(cacheKey, null);
        if (cached != null) {
            DiagnosticLogger.log("TRANSLATE", "cache hit, characters=" + text.length());
            mActivity.runOnUiThread(() -> success.accept(cached));
            return;
        }
        HttpUrl url = HttpUrl.get(TRANSLATE_ENDPOINT).newBuilder()
                .addQueryParameter("client", "gtx")
                .addQueryParameter("sl", "auto")
                .addQueryParameter("tl", "zh-CN")
                .addQueryParameter("dt", "t")
                .build();
        FormBody body = new FormBody.Builder().add("q", text).build();
        Request request = new Request.Builder().url(url).post(body).build();
        mTranslationQueue.execute(() -> {
            Exception lastError = null;
            for (int attempt = 0; attempt < 5; attempt++) {
                try (Response response = mClient.newCall(request).execute()) {
                    DiagnosticLogger.log("TRANSLATE", "response=" + response.code()
                            + ", characters=" + text.length() + ", attempt=" + (attempt + 1));
                    if (response.code() == 429) {
                        mRateLimited = true;
                        if (!mRateLimitNoticeShown) {
                            mRateLimitNoticeShown = true;
                            mActivity.runOnUiThread(() -> Toast.makeText(mActivity,
                                    com.gh4a.R.string.translation_rate_limited_retrying,
                                    Toast.LENGTH_LONG).show());
                        }
                        if (attempt < 4) {
                            long delay = 1000L << attempt;
                            DiagnosticLogger.log("TRANSLATE", "rate limited; retry in "
                                    + delay + " ms");
                            Thread.sleep(delay);
                            continue;
                        }
                    }
                    if (!response.isSuccessful() || response.body() == null) {
                        lastError = new IOException("HTTP " + response.code());
                        break;
                    }
                    JSONArray chunks = new JSONArray(response.body().string()).getJSONArray(0);
                    StringBuilder translated = new StringBuilder();
                    for (int i = 0; i < chunks.length(); i++) {
                        JSONArray chunk = chunks.optJSONArray(i);
                        if (chunk != null) translated.append(chunk.optString(0));
                    }
                    String result = translated.toString();
                    mCache.edit().putString(cacheKey, result).apply();
                    mActivity.runOnUiThread(() -> success.accept(result));
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    lastError = e;
                    break;
                } catch (Exception e) {
                    lastError = e;
                    break;
                }
            }
            DiagnosticLogger.log("TRANSLATE", "request failed, characters=" + text.length()
                    + ", reason=" + (lastError != null
                    ? lastError.getClass().getSimpleName() : "unknown"));
            mActivity.runOnUiThread(() -> Toast.makeText(mActivity,
                    com.gh4a.R.string.translation_using_fallback,
                    Toast.LENGTH_SHORT).show());
            try {
                String result = translateWithMyMemory(text);
                mCache.edit().putString(cacheKey, result).apply();
                mActivity.runOnUiThread(() -> success.accept(result));
            } catch (Exception fallbackError) {
                DiagnosticLogger.log("TRANSLATE", "fallback failed, characters="
                        + text.length() + ", reason="
                        + fallbackError.getClass().getSimpleName());
                mActivity.runOnUiThread(failure);
            }
        });
    }

    private String translateWithMyMemory(String text) throws Exception {
        StringBuilder output = new StringBuilder();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + 450, text.length());
            if (end < text.length()) {
                int separator = text.lastIndexOf(BATCH_SEPARATOR, end);
                int whitespace = Math.max(text.lastIndexOf('\n', end), text.lastIndexOf(' ', end));
                int boundary = separator > start ? separator : whitespace;
                if (boundary > start + 100) end = boundary;
            }
            String chunk = text.substring(start, end);
            HttpUrl url = HttpUrl.get(MYMEMORY_ENDPOINT).newBuilder()
                    .addQueryParameter("q", chunk)
                    .addQueryParameter("langpair", "en|zh-CN")
                    .build();
            try (Response response = mClient.newCall(new Request.Builder().url(url).get().build())
                    .execute()) {
                DiagnosticLogger.log("TRANSLATE", "provider=MyMemory, response="
                        + response.code() + ", characters=" + chunk.length());
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IOException("MyMemory HTTP " + response.code());
                }
                JSONObject json = new JSONObject(response.body().string());
                String translated = json.getJSONObject("responseData")
                        .optString("translatedText", "");
                if (translated.isEmpty() || translated.startsWith("MYMEMORY WARNING")) {
                    throw new IOException("MyMemory returned no translation");
                }
                output.append(translated);
            }
            start = end;
        }
        return output.toString();
    }

    public void close() {
        mTranslationQueue.shutdownNow();
    }

    private static String cacheKey(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder("v1_");
            for (byte b : digest) key.append(String.format("%02x", b));
            return key.toString();
        } catch (Exception ignored) {
            return "v1_" + text.hashCode();
        }
    }

    public static final class RateLimitException extends IOException {
        RateLimitException() {
            super("Google translation rate limit exceeded");
        }
    }

    private static void collectViews(View view, List<TextView> textViews,
            List<WebView> webViews) {
        if (view instanceof WebView) {
            webViews.add((WebView) view);
            return;
        }
        if (view instanceof TextView && !(view instanceof EditText)) {
            String text = ((TextView) view).getText().toString().trim();
            if (shouldTranslate(text)) textViews.add((TextView) view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                collectViews(group.getChildAt(i), textViews, webViews);
        }
    }

    private static boolean shouldTranslate(String text) {
        if (text.length() < 2 || !text.matches("(?s).*[A-Za-z]{2}.*")) return false;
        if (text.matches("(?i)^(https?://|git@|[a-f0-9]{7,40}$).*")) return false;
        return !text.matches("^[\\w./@:#%+~=\\-]+$");
    }

    private static final class TextSegment {
        final int start;
        final int end;
        final String source;
        TextSegment(int start, int end, String source) {
            this.start = start;
            this.end = end;
            this.source = source;
        }
    }

    private static final class BatchGroup {
        final int start;
        final int end;
        final String source;

        BatchGroup(int start, int end, String source) {
            this.start = start;
            this.end = end;
            this.source = source;
        }
    }
}
