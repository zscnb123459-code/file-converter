package converter.cli;

import converter.core.Config;
import converter.core.Engine;
import converter.core.FileKind;
import converter.core.FfmpegLocator;
import converter.core.InputEntry;
import converter.core.Log;
import converter.core.Options;
import converter.core.MediaConverter;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 命令行模式：java -jar FileConverter.jar <文件...> --to <格式> [-o 输出目录] [--bitrate 192k] [--merge-pdf] */
public final class CliRunner {

    private CliRunner() { }

    public static void run(String[] args) {
        PrintStream out = System.out;
        List<InputEntry> entries = new ArrayList<>();
        String target = null;
        Path outDir = null;
        String bitrate = "192k";
        boolean merge = false;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--to", "-t" -> {
                    if (++i >= args.length) { usage(); return; }
                    target = args[i].toLowerCase();
                }
                case "-o", "--out" -> {
                    if (++i >= args.length) { usage(); return; }
                    outDir = Path.of(args[i]);
                }
                case "--bitrate" -> {
                    if (++i >= args.length) { usage(); return; }
                    bitrate = args[i];
                }
                case "--merge-pdf" -> merge = true;
                case "--help", "-h" -> { usage(); return; }
                default -> {
                    if (a.matches("(?i)^(https?|rtmp|rtsp)://.*")) entries.add(InputEntry.ofUrl(a));
                    else entries.add(InputEntry.ofFile(Path.of(a).toFile()));
                }
            }
        }

        if (entries.isEmpty() || target == null) {
            usage();
            System.exit(2);
        }

        List<String> targets = FileKind.commonTargets(entries.stream().map(e -> e.kind).toList());
        if (!targets.contains(target)) {
            out.println("错误：目标格式 ." + target + " 与所选文件类型不匹配。可选: " + String.join(" / ", targets));
            System.exit(2);
        }

        Config.load();
        FfmpegLocator.Result ff = FfmpegLocator.find();
        boolean needFf = !List.of("pdf", "docx", "txt", "csv", "xlsx").contains(target)
                || (target.equals("pdf") && entries.stream().anyMatch(e -> e.kind == FileKind.IMAGE));
        if (needFf && ff == null) {
            out.println("错误：未检测到 FFmpeg，无法完成该转换。请先安装或把 ffmpeg.exe 所在目录加入 PATH。");
            System.exit(3);
        }

        if (outDir == null) {
            InputEntry first = entries.get(0);
            outDir = first.file != null ? first.file.getParentFile().toPath()
                    : Path.of(System.getProperty("user.dir"), "converted");
        }

        AtomicBoolean cancelled = new AtomicBoolean(false);
        Options opt = new Options(bitrate, merge, cancelled);
        Engine engine = new Engine(ff == null ? null : ff.path());
        Log consoleLog = consoleLog(out, engine, cancelled);

        out.println("输入 " + entries.size() + " 个文件 → 目标格式 ." + target + " → 输出目录 " + outDir.toAbsolutePath());
        engine.convertAll(entries, target, outDir, opt, consoleLog);
    }

    private static Log consoleLog(PrintStream out, Engine engine, AtomicBoolean cancelled) {
        return new Log() {
            int lastPct = -10;

            @Override public void info(String msg) { out.println("  " + msg); }

            @Override public void error(String msg) { out.println("  [错误] " + msg); }

            @Override public void progress(double fraction) {
                if (fraction < 0) {
                    out.println("  进度: 处理中…");
                    return;
                }
                int pct = (int) Math.round(fraction * 100);
                if (pct / 10 > lastPct / 10) {
                    lastPct = pct;
                    out.printf("  进度: %d%%%n", pct);
                }
            }

            @Override public void begin(String name) {
                out.println("▶ " + name);
                lastPct = -10;
                if (Thread.currentThread().isInterrupted()) cancelled.set(true);
            }
        };
    }

    private static void usage() {
        System.out.println("""
                万能格式转换器（命令行）
                用法:
                  java -jar FileConverter.jar <输入文件...> --to <目标格式> [选项]

                常用示例:
                  java -jar FileConverter.jar 视频.mp4 --to mp3
                  java -jar FileConverter.jar playlist.m3u8 --to mp4 -o D:/输出
                  java -jar FileConverter.jar a.png b.png --to pdf --merge-pdf
                  java -jar FileConverter.jar 文档.pdf --to docx
                  java -jar FileConverter.jar 表格.xlsx --to csv

                选项:
                  --to, -t <格式>     目标格式（必填）
                  -o, --out <目录>    输出目录（默认与源文件相同）
                  --bitrate <码率>    音频码率，如 128k / 192k / 320k
                  --merge-pdf         多张图片合并为一个 PDF
                """);
    }
}
