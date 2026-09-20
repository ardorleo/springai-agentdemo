package io.github.javaside.springai.codetui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** 独立 JVM 验证真实启动入口，不污染其他测试的全局 JUL handlers。 */
class CodeTuiApplicationLoggingTest {
    @Test
    void startupRoutesJulToFileWithoutConsoleLeaksOrDuplicates(@TempDir Path dir) throws Exception {
        Path stdout = dir.resolve("stdout.txt");
        Path stderr = dir.resolve("stderr.txt");
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dcodetui.log.dir=" + dir,
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Probe.class.getName());
        // 无 key 启动会正常返回；不请求网络、不启动 TUI，也不把宿主凭据传给探针。
        builder.environment().keySet().removeIf(key -> key.endsWith("API_KEY")
                || key.equals("JAVA_TOOL_OPTIONS") || key.equals("JDK_JAVA_OPTIONS")
                || key.equals("_JAVA_OPTIONS"));
        Process child = builder.redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "日志探针应有界退出");
            assertEquals(0, child.exitValue(), Files.readString(stderr));
            assertEquals("", Files.readString(stderr), "JUL 告警不能直写终端");
            assertFalse(Files.readString(stdout).contains("jul-routing-probe"));
            String log = Files.readString(dir.resolve("springai-code-tui.log"));
            assertEquals(1, log.lines().filter(line -> line.contains("jul-routing-probe")).count(),
                    "重复启动初始化不能重复记录，也不能吞掉告警");
            assertTrue(log.contains("IllegalStateException: jul-probe-cause"), "必须保留异常信息");
            assertTrue(log.contains("CodeTuiApplicationLoggingTest$Probe.main"), "必须保留异常栈");
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            CodeTuiApplication.main(new String[0]);
            CodeTuiApplication.main(new String[0]);
            Logger.getLogger("okhttp3.OkHttpClient").log(Level.WARNING,
                    "jul-routing-probe", new IllegalStateException("jul-probe-cause"));
        }
    }
}
