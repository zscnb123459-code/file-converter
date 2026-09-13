package converter.core;

import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 基于 PDFBox 的 PDF 转换：图片⇄PDF、PDF⇄文本、PDF 转图片。 */
public class PdfService {

    private static final String FONT_DIR = "C:/Windows/Fonts/";

    /** 一个已加载的中文字体；ttc 来源时需要在使用完（save 之后）再 close。 */
    public record CjkHandle(PDFont font, TrueTypeCollection ttc) implements AutoCloseable {
        @Override
        public void close() {
            if (ttc != null) {
                try {
                    ttc.close();
                } catch (IOException ignore) { }
            }
        }
    }

    /** 按优先级加载 Windows 自带中文字体（微软雅黑 → 等线 → 黑体 → 宋体）。 */
    public static CjkHandle openCjkFont(PDDocument doc) {
        for (String name : new String[]{"msyh.ttc", "Deng.ttf", "simhei.ttf", "msyh.ttf", "simsun.ttc"}) {
            File f = new File(FONT_DIR + name);
            if (!f.isFile()) continue;
            if (name.endsWith(".ttc")) {
                try {
                    byte[] data = Files.readAllBytes(f.toPath());
                    TrueTypeCollection ttc = new TrueTypeCollection(new ByteArrayInputStream(data));
                    final TrueTypeFont[] picked = new TrueTypeFont[1];
                    ttc.processAllFonts(ttf -> {
                        if (picked[0] == null && hasCjkGlyph(ttf)) picked[0] = ttf;
                    });
                    if (picked[0] != null) {
                        return new CjkHandle(PDType0Font.load(doc, picked[0], true), ttc);
                    }
                    ttc.close();
                } catch (Exception ignore) { }
            } else {
                try {
                    byte[] data = Files.readAllBytes(f.toPath());
                    PDFont font = PDType0Font.load(doc, new ByteArrayInputStream(data));
                    font.getStringWidth("中文字体测试");
                    return new CjkHandle(font, null);
                } catch (Exception ignore) { }
            }
        }
        return null;
    }

    private static boolean hasCjkGlyph(TrueTypeFont ttf) {
        try {
            return ttf.getUnicodeCmapLookup(false).getGlyphId(0x4E2D) > 0;   // “中”
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 图片 → PDF（支持多张合并）

    public static void imagesToPdf(List<File> images, File out, MediaConverter media, Log log) throws IOException {
        List<File> temps = new ArrayList<>();
        try (PDDocument doc = new PDDocument()) {
            for (File img : images) {
                PDImageXObject x = embedImage(doc, img, media, temps);
                PDPage page = new PDPage(new PDRectangle(x.getWidth(), x.getHeight()));
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(x, 0, 0, x.getWidth(), x.getHeight());
                }
            }
            doc.save(out);
        } finally {
            for (File t : temps) {
                try {
                    Files.deleteIfExists(t.toPath());
                } catch (IOException ignore) { }
            }
        }
    }

    private static PDImageXObject embedImage(PDDocument doc, File img, MediaConverter media, List<File> temps) throws IOException {
        try {
            return PDImageXObject.createFromFileByExtension(img, doc);
        } catch (Exception tryNext) {
            try {
                BufferedImage bi = ImageIO.read(img);
                if (bi != null) {
                    try {
                        return PDImageXObject.createFromFileByContent(img, doc);
                    } catch (Exception ignore) { }
                }
            } catch (Exception ignore) { }
        }
        if (media == null) {
            throw new IOException("该图片格式无法直接嵌入 PDF（需要 FFmpeg 支持）: " + img.getName());
        }
        File png = Files.createTempFile("conv_", ".png").toFile();
        media.convertQuietly(img.getAbsolutePath(), png);
        temps.add(png);
        return PDImageXObject.createFromFileByContent(png, doc);
    }

    // ------------------------------------------------------------------
    // PDF → 文本

    public static void pdfToText(File pdf, File out) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper s = new PDFTextStripper();
            s.setSortByPosition(true);
            Files.writeString(out.toPath(), s.getText(doc), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------
    // PDF → 图片（逐页导出）

    public static int pdfToImages(File pdf, Path outDir, String fmt, int dpi) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFRenderer r = new PDFRenderer(doc);
            String base = stripExt(pdf.getName());
            int n = doc.getNumberOfPages();
            for (int i = 0; i < n; i++) {
                BufferedImage bi = r.renderImageWithDPI(i, dpi);
                File f = outDir.resolve(String.format("%s_第%d页.%s", base, i + 1, fmt)).toFile();
                ImageIO.write(bi, fmt.equals("jpg") ? "jpeg" : fmt, f);
            }
            return n;
        }
    }

    // ------------------------------------------------------------------
    // 文本 → PDF

    public static void textToPdf(File txt, File out, Log log) throws IOException {
        String text = TextFiles.read(txt.toPath());
        try (PDDocument doc = new PDDocument()) {
            CjkHandle handle = openCjkFont(doc);
            PDFont font;
            if (handle != null) {
                font = handle.font();
            } else if (text.chars().allMatch(c -> c < 128)) {
                font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                log.info("未找到中文字体，内容为纯英文，使用内置 Helvetica。");
            } else {
                throw new IOException("未找到可用的中文字体（C:\\Windows\\Fonts）");
            }
            float size = 11f;
            float leading = size * 1.6f;
            float margin = 56f;
            float maxW = 595f - 2 * margin;
            float y = 842f - margin;
            PDPage page = null;
            PDPageContentStream cs = null;
            try {
                for (String raw : text.split("\\r?\\n", -1)) {
                    if (cs == null) {
                        page = new PDPage(PDRectangle.A4);
                        doc.addPage(page);
                        cs = new PDPageContentStream(doc, page);
                    }
                    if (raw.isBlank()) {
                        y -= leading * 0.5f;
                        continue;
                    }
                    for (String line : wrap(raw, font, size, maxW)) {
                        if (y < margin) {
                            cs.close();
                            page = new PDPage(PDRectangle.A4);
                            doc.addPage(page);
                            cs = new PDPageContentStream(doc, page);
                            y = 842f - margin;
                        }
                        cs.beginText();
                        cs.setFont(font, size);
                        cs.newLineAtOffset(margin, y);
                        cs.showText(line);
                        cs.endText();
                        y -= leading;
                    }
                }
            } finally {
                if (cs != null) cs.close();
            }
            doc.save(out);
            if (handle != null) handle.close();
        }
    }

    /**
     * 按字体实际宽度折行；遇到当前字体无法显示的字符统一替换为 “?”，
     * 以保证 showText 一定成功。
     */
    public static List<String> wrap(String s, PDFont font, float size, float maxW) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        float w = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            i += ch.length();
            if (cp == '\t') {
                ch = "    ";
            }
            float cw;
            try {
                cw = font.getStringWidth(ch) / 1000f * size;
            } catch (Exception e) {
                ch = "?";
                cw = size * 0.5f;
            }
            if (w + cw > maxW && cur.length() > 0) {
                lines.add(cur.toString());
                cur.setLength(0);
                w = 0;
            }
            cur.append(ch);
            w += cw;
        }
        if (cur.length() > 0 || lines.isEmpty()) lines.add(cur.toString());
        return lines;
    }

    public static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
