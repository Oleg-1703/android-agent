import android.app.UiAutomation;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.lang.reflect.Method;

/**
 * "uiautomator dump" without the waitForIdle(1000) step, which never succeeds on screens that
 * update once a second (timers, progress, uptime rows). Same XML writer, taken from uiautomator.jar.
 */
public final class UiDump {
    public static void main(String[] args) throws Exception {
        Class<?> wrapperClass = Class.forName("com.android.uiautomator.core.UiAutomationShellWrapper");
        Object wrapper = wrapperClass.getConstructor().newInstance();
        wrapperClass.getMethod("connect").invoke(wrapper);
        int status = 1;
        try {
            UiAutomation automation = (UiAutomation) wrapperClass.getMethod("getUiAutomation").invoke(wrapper);
            wrapperClass.getMethod("setCompressedLayoutHierarchy", boolean.class).invoke(wrapper, false);
            AccessibilityNodeInfo root = null;
            for (int attempt = 0; root == null && attempt < 20; attempt++) {
                root = automation.getRootInActiveWindow();
                if (root == null) Thread.sleep(100);
            }
            if (root != null) {
                Method writer = Class.forName("com.android.uiautomator.core.AccessibilityNodeInfoDumper")
                        .getMethod("dumpWindowToFile", AccessibilityNodeInfo.class, File.class,
                                int.class, int.class, int.class);
                // Width and height only clip node bounds; the harness clips to the real screen size itself.
                writer.invoke(null, root, new File(args[0]), 0, 100000, 100000);
                status = 0;
            }
        } finally {
            wrapperClass.getMethod("disconnect").invoke(wrapper);
        }
        System.exit(status);
    }
}
