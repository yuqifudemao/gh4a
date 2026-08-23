package com.gh4a.utils;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.TextView;
import android.text.SpannableStringBuilder;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Translates English text visible on the current screen with Google online translation. */
public final class PageTranslator {
    private static final String TRANSLATE_ENDPOINT =
            "https://translate.googleapis.com/translate_a/single";
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
    private final OkHttpClient mClient = new OkHttpClient();
    private final WeakHashMap<TextView, CharSequence> mOriginalText = new WeakHashMap<>();
    private final List<WebView> mTranslatedWebViews = new ArrayList<>();
    private boolean mTranslated;

    public PageTranslator(@NonNull Activity activity) {
        mActivity = activity;
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
        AtomicInteger pending = new AtomicInteger(segments.size());
        Runnable segmentFinished = () -> {
            if (pending.decrementAndGet() != 0) return;
            SpannableStringBuilder translated = new SpannableStringBuilder(original);
            boolean changed = false;
            for (int i = segments.size() - 1; i >= 0; i--) {
                TextSegment segment = segments.get(i);
                if (segment.translation != null) {
                    translated.replace(segment.start, segment.end, segment.translation);
                    changed = true;
                }
            }
            if (changed) {
                successes.incrementAndGet();
                if (!mActivity.isFinishing()) view.setText(translated);
            }
            finished.run();
        };
        for (TextSegment segment : segments) {
            translateText(segment.source, text -> {
                segment.translation = text;
                segmentFinished.run();
            }, segmentFinished);
        }
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
                AtomicInteger pending = new AtomicInteger(nodes.length());
                for (int i = 0; i < nodes.length(); i++) {
                    final int index = i;
                    Runnable nodeFinished = () -> {
                        if (pending.decrementAndGet() == 0) finished.run();
                    };
                    translateText(nodes.getString(i), text -> {
                        successes.incrementAndGet();
                        webView.evaluateJavascript("window.__octoTranslationNodes[" + index +
                                "].nodeValue=" + JSONObject.quote(text), null);
                        nodeFinished.run();
                    }, nodeFinished);
                }
            } catch (Exception ignored) {
                finished.run();
            }
        });
    }

    private void translateText(String text, Consumer<String> success, Runnable failure) {
        HttpUrl url = HttpUrl.get(TRANSLATE_ENDPOINT).newBuilder()
                .addQueryParameter("client", "gtx")
                .addQueryParameter("sl", "auto")
                .addQueryParameter("tl", "zh-CN")
                .addQueryParameter("dt", "t")
                .addQueryParameter("q", text)
                .build();
        mClient.newCall(new Request.Builder().url(url).get().build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                mActivity.runOnUiThread(failure);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        mActivity.runOnUiThread(failure);
                        return;
                    }
                    JSONArray chunks = new JSONArray(response.body().string()).getJSONArray(0);
                    StringBuilder translated = new StringBuilder();
                    for (int i = 0; i < chunks.length(); i++) {
                        JSONArray chunk = chunks.optJSONArray(i);
                        if (chunk != null) translated.append(chunk.optString(0));
                    }
                    mActivity.runOnUiThread(() -> success.accept(translated.toString()));
                } catch (Exception e) {
                    mActivity.runOnUiThread(failure);
                }
            }
        });
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
        String translation;

        TextSegment(int start, int end, String source) {
            this.start = start;
            this.end = end;
            this.source = source;
        }
    }
}
