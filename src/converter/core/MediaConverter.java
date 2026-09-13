package converter.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 通过外部 ffmpeg 进程完成音视频互转、m3u8 合并、图片格式转换。 */
public class MediaConverter {
    private static final Pattern DUR = Pattern.compile("Duration:\\s*(\\d+):(\\d+):(\\d+)\\.(\\d+)");
    private static final Pattern TIME = Pattern.compile("time=(\\d+):(\\d+):(\\d+)\\.(\\d+)");

    private final String ffmpeg;
    private volatile Process running;

    public MediaConverter(String ffmpegPath) {
        this.ffmpeg = ffmpegPath;
    }

    public String path() {
        return ffmpeg;
    }

    /** 通用转换：按输出扩展名自动选择编码参数。 */
    public void convert(String input, File out, Options opt, Log log) throws Exception {
        Files.deleteIfExists(out.toPath());
        run(buildArgs(input, out, opt), opt, log);
        requireOutput(out);
    }

    /** 静默执行一条 ffmpeg 命令（供 PDF 服务把特殊图片先转成 PNG）。 */
    public void convertQuietly(String input, File out) throws IOException {
        try {
            run(new ArrayList<>(List.of(ffmpeg, "-y", "-hide_banner", "-i", input, "-frames:v", "1", out.getAbsolutePath())),
                    Options.none(), Log.QUIET);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
        if (!valid(out)) throw new IOException("ffmpeg 转换图片失败: " + input);
    }

    /** m3u8 → mp4：先尝试无损拼接（-c copy），失败自动改用重新编码。 */
    public void convertM3u8ToMp4(String input, File out, Options opt, Log log) throws Exception {
        Files.deleteIfExists(out.toPath());
        List<String> copy = new ArrayList<>(List.of(ffmpeg, "-y", "-hide_banner", "-i", input,
                "-c", "copy", "-movflags", "+faststart", out.getAbsolutePath()));
        log.info("先尝试无损模式（-c copy）…");
        int code = run(copy, opt, log);
        if (code != 0 || !valid(out)) {
            log.info("无损模式不可用，改用重新编码…");
            Files.deleteIfExists(out.toPath());
            run(buildArgs(input, out, opt), opt, log);
        }
        requireOutput(out);
    }

    // ------------------------------------------------------------------

    private List<String> buildArgs(String in, File out, Options opt) {
        String t = ext(out.getName());
        List<String> a = new ArrayList<>(List.of(ffmpeg, "-y", "-hide_banner", "-i", in));
        switch (t) {
            case "mp4", "mkv", "mov", "ts", "m4v", "flv" -> {
                a.addAll(List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "23"));
                a.addAll(List.of("-c:a", "aac", "-b:a", opt.audioBitrate()));
                if (t.equals("mp4")) a.addAll(List.of("-movflags", "+faststart"));
            }
            case "avi" -> a.addAll(List.of("-c:v", "mpeg4", "-q:v", "4", "-c:a", "libmp3lame", "-b:a", opt.audioBitrate()));
            case "webm" -> a.addAll(List.of("-c:v", "libvpx", "-crf", "10", "-b:v", "2M", "-c:a", "libvorbis"));
            case "gif" -> a.addAll(List.of("-vf", "fps=10,scale=480:-2:flags=lanczos"));
            case "mp3" -> a.addAll(List.of("-vn", "-c:a", "libmp3lame", "-b:a", opt.audioBitrate()));
            case "wav" -> a.addAll(List.of("-vn", "-c:a", "pcm_s16le"));
            case "aac", "m4a" -> a.addAll(List.of("-vn", "-c:a", "aac", "-b:a", opt.audioBitrate()));
            case "flac" -> a.addAll(List.of("-vn", "-c:a", "flac"));
            case "ogg" -> a.addAll(List.of("-vn", "-c:a", "libvorbis", "-q:a", "5"));
            case "wma" -> a.addAll(List.of("-vn", "-c:a", "wmav2", "-b:a", opt.audioBitrate()));
            case "opus" -> a.addAll(List.of("-vn", "-c:a", "libopus", "-b:a", opt.audioBitrate()));
            case "jpg", "jpeg" -> a.addAll(List.of("-frames:v", "1", "-q:v", "2"));
            case "png", "bmp" -> a.addAll(List.of("-frames:v", "1"));
            case "webp" -> a.addAll(List.of("-frames:v", "1", "-c:v", "libwebp", "-quality", "90"));
            default -> throw new IllegalArgumentException("ffmpeg 不支持输出格式 ." + t);
        }
        a.add(out.getAbsolutePath());
        return a;
    }

    private int run(List<String> args, Options opt, Log log) throws Exception {
        Process p = new ProcessBuilder(args).redirectErrorStream(false).start();
        running = p;
        Thread drain = new Thread(() -> {
            try (var in = p.getInputStream()) {
                in.readAllBytes();
            } catch (IOException ignore) { }
        });
        drain.setDaemon(true);
        drain.start();

        double total = -1;
        double last = -1;
        try (BufferedReader err = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = err.readLine()) != null) {
                if (opt.isCancelled()) {
                    p.destroy();
                    break;
                }
                if (total < 0) {
                    Matcher m = DUR.matcher(line);
                    if (m.find()) total = seconds(m);
                }
                Matcher m = TIME.matcher(line);
                if (m.find() && total > 0) {
                    double frac = Math.max(0, Math.min(0.99, seconds(m) / total));
                    if (frac - last >= 0.02) {
                        last = frac;
                        log.progress(frac);
                    }
                }
            }
        } finally {
            running = null;
        }
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("ffmpeg 执行超时");
        }
        return p.exitValue();
    }

    /** 供取消按钮调用：立刻终止正在运行的 ffmpeg 进程。 */
    public void cancel() {
        Process p = running;
        if (p != null) p.destroy();
    }

    private static double seconds(Matcher m) {
        return Integer.parseInt(m.group(1)) * 3600d
                + Integer.parseInt(m.group(2)) * 60d
                + Integer.parseInt(m.group(3))
                + Integer.parseInt(m.group(4)) / 100d;
    }

    private static boolean valid(File f) {
        return f.isFile() && f.length() > 0;
    }

    private static void requireOutput(File out) throws IOException {
        if (!valid(out)) throw new IOException("未生成输出文件: " + out.getName());
    }

    private static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
    }
}
