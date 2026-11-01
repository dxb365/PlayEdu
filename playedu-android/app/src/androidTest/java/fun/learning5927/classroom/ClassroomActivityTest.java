package fun.learning5927.classroom;

import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ClassroomActivityTest {
    private static final String FIXTURE = "<html><meta name='viewport' content='width=device-width,initial-scale=1'><body><h1>Classroom test</h1><video id='video'></video><script>window.checkpoints=0;window.addEventListener('playedu:app-background',function(){window.checkpoints++;localStorage.setItem('checkpoint','saved');});window.ready=true;</script></body></html>";

    @Test public void backgroundsCheckpointAndKeepsSession() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<WebView> browser = new AtomicReference<>();
            scenario.onActivity(activity -> {
                WebView view = activity.findViewById(R.id.classroom_webview);
                browser.set(view);
                view.stopLoading();
                view.setWebViewClient(new WebViewClient() {
                    @Override public WebResourceResponse shouldInterceptRequest(WebView webView, WebResourceRequest request) {
                        return new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream(FIXTURE.getBytes(StandardCharsets.UTF_8)));
                    }
                });
                view.loadUrl(BuildConfig.DEFAULT_SERVER + "fixture");
            });
            awaitJavascript(browser.get(), "window.ready === true", "true");
            scenario.moveToState(Lifecycle.State.CREATED);
            Thread.sleep(1000);
            scenario.moveToState(Lifecycle.State.RESUMED);
            awaitJavascript(browser.get(), "window.checkpoints > 0", "true");
            assertEquals("\"saved\"", javascript(browser.get(), "localStorage.getItem('checkpoint')"));
        }
    }

    @Test public void fullscreenBackRestoresClassroom() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                WebView view = activity.findViewById(R.id.classroom_webview);
                final int[] callbacks = {0};
                view.getWebChromeClient().onShowCustomView(new FrameLayout(activity), () -> callbacks[0]++);
                assertEquals(View.VISIBLE, activity.findViewById(R.id.fullscreen_container).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.normal_content).getVisibility());
                activity.onBackPressed();
                assertEquals(View.GONE, activity.findViewById(R.id.fullscreen_container).getVisibility());
                assertEquals(View.VISIBLE, activity.findViewById(R.id.normal_content).getVisibility());
                assertEquals(1, callbacks[0]);
            });
        }
    }

    private static String javascript(WebView view, String expression) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> view.evaluateJavascript(expression, value -> {
            result.set(value); latch.countDown();
        }));
        assertTrue("WebView JavaScript timed out", latch.await(5, TimeUnit.SECONDS));
        return result.get();
    }

    private static void awaitJavascript(WebView view, String expression, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 15000;
        String actual;
        do {
            actual = javascript(view, expression);
            if (expected.equals(actual)) return;
            Thread.sleep(200);
        } while (System.currentTimeMillis() < deadline);
        assertEquals(expected, actual);
    }
}
