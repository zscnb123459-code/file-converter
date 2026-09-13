package converter.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 转换任务调度：判断类别、选择目标格式、逐个执行并汇报进度。 */
public class Engine {

    private final MediaConverter media;   // 可能为 null（未检测到 FFmpeg）

    public Engine(String ffmpegPath) {
        this.media = ffmpegPath == null ? null : new MediaConverter(ffmpegPath);
    }

    public void convertAll(List<InputEntry> entries, String target, Path outDir, Options opt, Log log) {
        FilesHelper.mkdirs(outDir);
        // 多张图片 → 合并为一个 PDF
        if (opt.mergeImagesPdf() && target.equals("pdf")
                && entries.size() > 1 && entries.stream().allMatch(e -> e.kind == FileKind.IMAGE)) {
            List<File> imgs = entries.stream().map(e -> e.file).toList();
            String name = PdfService.stripExt(entries.get(0).baseName) + "_合并.pdf";
            Path out = outDir.resolve(name);
            log.info("将 " + imgs.size() + " 张图片合并为一个 PDF…");
            try {
                PdfService.imagesToPdf(imgs, out.toFile(), media, log);
                log.progress(1.0);
                log.info("完成 → " + name + "（" + humanSize(out.toFile()) + "）");
            } catch (Exception ex) {
                log.error("合并失败: " + rootMsg(ex));
            }
            return;
        }

        int ok = 0;
        for (InputEntry e : entries) {
            if (opt.isCancelled()) break;
            log.begin(e.displayName);
            try {
                convertOne(e, target, outDir, opt, log);
                ok++;
            } catch (Exception ex) {
                if (opt.isCancelled()) break;
                log.error("× " + e.displayName + " 转换失败: " + rootMsg(ex));
            }
        }
        if (opt.isCancelled()) log.info("—— 已取消 ——");
        else log.info("—— 完成：" + ok + "/" + entries.size() + " 个文件转换成功 ——");
    }

    private void convertOne(InputEntry e, String target, Path outDir, Options opt, Log log) throws Exception {
        if (e.ext.equals(target)) {
            log.info("跳过 " + e.displayName + "（目标格式与源格式相同）");
            return;
        }
        // 网易云 / 酷狗 加密歌曲：解锁后按需转码
        if (e.kind == FileKind.NCM || e.kind == FileKind.KGM) {
            convertLockedMusic(e, target, outDir, opt, log);
            return;
        }
        switch (target) {
            case "pdf" -> {
                Path out = outDir.resolve(e.baseName + ".pdf");
                if (e.kind == FileKind.IMAGE) {
                    PdfService.imagesToPdf(List.of(e.file), out.toFile(), media, log);
                } else if (e.kind == FileKind.TXT) {
                    PdfService.textToPdf(e.file, out.toFile(), log);
                } else if (e.kind == FileKind.DOCX) {
                    OfficeService.docxToPdf(e.file, out.toFile(), log);
                } else {
                    throw new IOException("无法把 " + e.kind.label + " 转为 PDF");
                }
                log.progress(1.0);
                log.info("完成 → " + out.getFileName() + "（" + humanSize(out.toFile()) + "）");
            }
            case "docx" -> {
                Path out = outDir.resolve(e.baseName + ".docx");
                if (e.kind == FileKind.PDF) {
                    new OfficeService().pdfToDocx(e.file, out.toFile(), log);
                } else if (e.kind == FileKind.TXT) {
                    OfficeService.txtToDocx(e.file, out.toFile());
                } else {
                    throw new IOException("无法把 " + e.kind.label + " 转为 Word");
                }
                log.progress(1.0);
                log.info("完成 → " + out.getFileName() + "（" + humanSize(out.toFile()) + "）");
            }
            case "txt" -> {
                Path out = outDir.resolve(e.baseName + ".txt");
                if (e.kind == FileKind.PDF) {
                    PdfService.pdfToText(e.file, out.toFile());
                } else if (e.kind == FileKind.DOCX) {
                    OfficeService.docxToText(e.file, out.toFile());
                } else {
                    throw new IOException("无法把 " + e.kind.label + " 转为文本");
                }
                log.progress(1.0);
                log.info("完成 → " + out.getFileName() + "（" + humanSize(out.toFile()) + "）");
            }
            case "png", "jpg" -> {
                if (e.kind == FileKind.PDF) {
                    int n = PdfService.pdfToImages(e.file, outDir, target, 150);
                    log.progress(1.0);
                    log.info("完成 → 按页导出 " + n + " 张图片到 " + outDir.getFileName());
                } else {
                    Path out = outDir.resolve(e.baseName + "." + target);
                    requireMedia();
                    media.convert(e.input(), out.toFile(), opt, log);
                    log.progress(1.0);
                    log.info("完成 → " + out.getFileName() + "（" + humanSize(out.toFile()) + "）");
                }
            }
            case "csv" -> {
                Path out = outDir.resolve(e.baseName + ".csv");
                OfficeService.xlsxToCsv(e.file, out.toFile());
                log.progress(1.0);
                log.info("完成 → " + out.getFileName() + "（" + humanSize(out.toFile()) + "）");
            }
            case "xlsx" -> {
                Path out = outDir.resolve(e.baseName + ".xlsx");
                OfficeService.csvToXlsx(e.file, out.toFile());
                log.progress(1.0);
                log.info("完成 → " + out.getFileName() + "（" + humanSize(out.toFile()) + "）");
            }
            default -> {
                // 视频互转、视频提取音频、音频互转、图片格式互转
                Path out = outDir.resolve(e.baseName + "." + target);
                requireMedia();
                if (e.ext.equals("m3u8") && target.equals("mp4")) {
                    media.convertM3u8ToMp4(e.input(), out.toFile(), opt, log);
                } else {
                    media.convert(e.input(), out.toFile(), opt, log);
                }
                log.progress(1.0);
                log.info("完成 → " + out.getFileName() + "（" + humanSize(out.toFile()) + "）");
            }
        }
    }

    /** 解锁加密音乐：目标=原格式 时直接导出无损/原始流，否则经 FFmpeg 转码。 */
    private void convertLockedMusic(InputEntry e, String target, Path outDir, Options opt, Log log) throws Exception {
        log.info("解锁 " + e.kind.label + " 加密文件（" + e.displayName + "）…");
        UnlockService.Unlocked u = UnlockService.unlock(e.file.toPath());
        String name = suggestedMusicName(e, u);
        log.info("识别为 " + u.ext().toUpperCase() + (u.title() != null ? "：" + u.artist() + " - " + u.title() : ""));
        if ("原格式".equals(target)) {
            Path out = outDir.resolve(name + "." + u.ext());
            Files.write(out, u.audio());
            log.progress(1.0);
            log.info("完成 → " + out.getFileName() + "（" + humanSize(out.toFile()) + "）");
            return;
        }
        requireMedia();
        Path tmp = Files.createTempFile("unlock_", "." + u.ext());
        try {
            Files.write(tmp, u.audio());
            Path out = outDir.resolve(name + "." + target);
            media.convert(tmp.toString(), out.toFile(), opt, log);
            log.progress(1.0);
            log.info("完成 → " + out.getFileName() + "（" + humanSize(out.toFile()) + "）");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** 优先用歌曲元数据命名：歌手 - 标题。 */
    private static String suggestedMusicName(InputEntry e, UnlockService.Unlocked u) {
        String base = null;
        if (u.artist() != null && !u.artist().isBlank() && u.title() != null && !u.title().isBlank()) {
            base = u.artist().trim() + " - " + u.title().trim();
        } else if (u.title() != null && !u.title().isBlank()) {
            base = u.title().trim();
        }
        if (base == null || base.isBlank()) base = e.baseName;
        base = base.replaceAll("[\\\\/:*?\"<>|\r\n\t]", "_");
        return base.length() > 120 ? base.substring(0, 120) : base;
    }

    private void requireMedia() throws IOException {
        if (media == null) {
            throw new IOException("未检测到 FFmpeg，无法转换音视频/图片格式。可在界面右上角设置 ffmpeg 路径。");
        }
    }

    public MediaConverter media() {
        return media;
    }

    public static String humanSize(File f) {
        long n = f.length();
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format("%.1f KB", n / 1024.0);
        if (n < 1024L * 1024 * 1024) return String.format("%.1f MB", n / 1024.0 / 1024);
        return String.format("%.2f GB", n / 1024.0 / 1024 / 1024);
    }

    public static String rootMsg(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String m = c.getMessage();
            if (m != null && !m.isBlank()) return m;
        }
        return t.getClass().getSimpleName();
    }

    private static final class FilesHelper {
        static void mkdirs(Path dir) {
            try {
                Files.createDirectories(dir);
            } catch (IOException ignore) { }
        }
    }
}
