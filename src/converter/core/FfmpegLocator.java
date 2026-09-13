package converter.core;

import java.io.File;
import java.util.concurrent.TimeUnit;

/** 定位 ffmpeg.exe：配置文件 → 系统 PATH → 常见安装位置。 */
public final class FfmpegLocator {

    public record Result(String path, String how) { }

    private FfmpegLocator() { }

    public static Result find() {
        String saved = Config.get("ffmpeg.path", "").trim();
        if (!saved.isEmpty() && works(saved)) return new Result(saved, "配置");
        if (works("ffmpeg")) return new Result("ffmpeg", "系统 PATH");
        String localAppData = System.getenv("LOCALAPPDATA");
        String[] dirs = {
                "C:/ffmpeg/bin",
                "C:/Program Files/ffmpeg/bin",
                "C:/Program Files (x86)/ffmpeg/bin",
                localAppData == null ? "" : localAppData.replace('\\', '/') + "/ffmpeg/bin"
        };
        for (String dir : dirs) {
            if (dir.isBlank()) continue;
            File f = new File(dir, "ffmpeg.exe");
            if (f.isFile() && works(f.getAbsolutePath())) return new Result(f.getAbsolutePath(), "默认目录");
        }
        return null;
    }

    public static boolean works(String exe) {
        try {
            Process p = new ProcessBuilder(exe, "-version").redirectErrorStream(true).start();
            if (!p.waitFor(4, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            byte[] out;
            try (var in = p.getInputStream()) {
                out = in.readAllBytes();
            }
            return p.exitValue() == 0 && new String(out).contains("ffmpeg");
        } catch (Exception e) {
            return false;
        }
    }
}
