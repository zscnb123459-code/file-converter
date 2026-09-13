package converter.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;

/** 文件类别，以及各类别支持转换的目标格式。 */
public enum FileKind {
    VIDEO("视频", "mp4 mkv avi mov webm flv wmv ts m4v mpg mpeg 3gp m3u8"),
    AUDIO("音频", "mp3 wav aac flac m4a ogg wma opus"),
    NCM("网易云", "ncm"),
    KGM("酷狗", "kgm kgma vpr"),
    IMAGE("图片", "png jpg jpeg webp bmp gif tif tiff ico"),
    PDF("PDF", "pdf"),
    DOCX("Word", "docx"),
    TXT("文本", "txt md"),
    XLSX("Excel", "xlsx xls"),
    CSV("CSV", "csv"),
    UNKNOWN("未知", "");

    public final String label;
    private final Set<String> exts;

    FileKind(String label, String exts) {
        this.label = label;
        this.exts = new LinkedHashSet<>(Arrays.asList(exts.split("\\s+")));
    }

    public static FileKind of(String ext) {
        for (FileKind k : values()) {
            if (k.exts.contains(ext)) return k;
        }
        return UNKNOWN;
    }

    /** 该类别可转换到的目标格式（保持展示顺序）。 */
    public List<String> targets() {
        switch (this) {
            case VIDEO: return List.of("mp4", "mkv", "avi", "mov", "webm", "gif", "mp3", "aac", "wav", "flac", "m4a", "ogg");
            case AUDIO: return List.of("mp3", "wav", "aac", "flac", "m4a", "ogg", "wma");
            case NCM, KGM: return List.of("原格式", "mp3", "flac", "wav");
            case IMAGE: return List.of("jpg", "png", "webp", "bmp", "pdf");
            case PDF:   return List.of("docx", "txt", "png", "jpg");
            case DOCX:  return List.of("pdf", "txt");
            case TXT:   return List.of("pdf", "docx");
            case XLSX:  return List.of("csv");
            case CSV:   return List.of("xlsx");
            default:    return List.of();
        }
    }

    /** 多个类别的目标格式求交集。 */
    public static List<String> commonTargets(List<FileKind> kinds) {
        List<String> acc = null;
        for (FileKind k : kinds) {
            List<String> t = new ArrayList<>(k.targets());
            if (acc == null) acc = t;
            else acc.retainAll(t);
        }
        return acc == null ? List.of() : acc;
    }
}
