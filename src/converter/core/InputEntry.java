package converter.core;

import java.io.File;

/** 一个待转换的来源：本地文件或网络地址（m3u8 / 视频）。 */
public final class InputEntry {
    public final File file;          // 本地文件；网络来源时为 null
    public final String url;         // 网络地址；本地文件时为 null
    public final String ext;         // 小写扩展名（不含点）
    public final FileKind kind;
    public final String displayName;
    public final String baseName;    // 用于输出文件命名

    private InputEntry(File file, String url, String ext, FileKind kind, String displayName, String baseName) {
        this.file = file;
        this.url = url;
        this.ext = ext;
        this.kind = kind;
        this.displayName = displayName;
        this.baseName = baseName;
    }

    public static InputEntry ofFile(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
        return new InputEntry(f, null, ext, FileKind.of(ext), name,
                dot > 0 ? name.substring(0, dot) : name);
    }

    public static InputEntry ofUrl(String url) {
        String clean = url.trim();
        String path = clean;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        String base;
        String ext;
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            base = name.substring(0, dot);
            ext = name.substring(dot + 1).toLowerCase();
        } else {
            base = name;
            ext = "";
        }
        if (base.isBlank()) base = "网络视频";
        FileKind kind = FileKind.of(ext);
        if (kind == FileKind.UNKNOWN) kind = FileKind.VIDEO;   // 网络流按视频处理
        String display = name.isBlank() ? clean : name;
        if (display.length() > 60) display = display.substring(0, 57) + "...";
        return new InputEntry(null, clean, ext, kind, display, base);
    }

    /** 交给 ffmpeg 等外部工具的输入（路径或地址）。 */
    public String input() {
        return file != null ? file.getAbsolutePath() : url;
    }

    @Override
    public String toString() {
        return displayName + " [" + kind.label + "]";
    }
}
