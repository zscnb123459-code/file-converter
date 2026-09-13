package converter.core;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 基于 POI 的 Office 转换：PDF→Word、Word→文本/PDF、Excel→CSV、CSV→Excel。 */
public class OfficeService {

    // ------------------------------------------------------------------
    // PDF → Word（提取文本与图片，按页排版）

    public static void pdfToDocx(File pdf, File out, Log log) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf);
             XWPFDocument xdoc = new XWPFDocument()) {
            PDFTextStripper s = new PDFTextStripper();
            s.setSortByPosition(true);
            int n = doc.getNumberOfPages();
            boolean hasText = false;
            for (int i = 1; i <= n; i++) {
                s.setStartPage(i);
                s.setEndPage(i);
                String text = s.getText(doc);
                int before = paragraphCount(xdoc);
                for (String line : text.split("\\r?\\n")) {
                    if (line.isBlank()) continue;
                    addPara(xdoc, line, false, 11, false);
                    hasText = true;
                }
                List<byte[]> pics = pageImages(doc, i);
                for (byte[] data : pics) {
                    addPicture(xdoc, data);
                    hasText = true;
                }
                if (paragraphCount(xdoc) == before && i == 1 && n > 1) {
                    addPara(xdoc, "[本页未检测到文本或图片，可能是扫描件]", false, 10, false);
                }
                if (i < n) addPara(xdoc, "", false, 11, false);
            }
            if (!hasText) {
                addPara(xdoc, "该 PDF 未检测到可提取的文本与图片（可能为纯扫描件）。", false, 11, false);
            }
            try (FileOutputStream fos = new FileOutputStream(out)) {
                xdoc.write(fos);
            }
        }
    }

    private static int paragraphCount(XWPFDocument d) {
        return d.getParagraphs().size() + d.getTables().size();
    }

    private static List<byte[]> pageImages(PDDocument doc, int pageNo) {
        List<byte[]> result = new ArrayList<>();
        Set<COSName> seen = new HashSet<>();
        try {
            collectImages(doc.getPage(pageNo - 1).getResources(), result, seen, 0);
        } catch (Exception ignore) { }
        return result;
    }

    private static void collectImages(org.apache.pdfbox.pdmodel.PDResources res, List<byte[]> out, Set<COSName> seen, int depth) throws IOException {
        if (res == null || depth > 2) return;
        for (COSName name : res.getXObjectNames()) {
            if (!seen.add(name)) continue;
            try {
                var xo = res.getXObject(name);
                if (xo instanceof PDImageXObject img) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    ImageIO.write(img.getImage(), "png", bos);
                    out.add(bos.toByteArray());
                } else if (xo instanceof PDFormXObject form) {
                    collectImages(form.getResources(), out, seen, depth + 1);
                }
            } catch (Exception ignore) { }
        }
    }

    private static void addPara(XWPFDocument d, String text, boolean bold, float sizePt, boolean gray) {
        XWPFParagraph p = d.createParagraph();
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setFontFamily("微软雅黑");
        r.setFontSize((int) sizePt);
        r.setBold(bold);
        if (gray) r.setColor("808080");
    }

    private static void addPicture(XWPFDocument d, byte[] data) {
        try {
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(data));
            if (bi == null) return;
            int w = bi.getWidth();
            int h = bi.getHeight();
            double scale = Math.min(1.0, Math.min(460.0 / w, 600.0 / h));
            XWPFParagraph p = d.createParagraph();
            p.createRun().addPicture(new ByteArrayInputStream(data), org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG,
                    "image.png", Units.pixelToEMU((int) Math.round(w * scale)), Units.pixelToEMU((int) Math.round(h * scale)));
        } catch (Exception ignore) { }
    }

    // ------------------------------------------------------------------
    // Word → 文本

    public static void docxToText(File docx, File out) throws Exception {
        try (XWPFDocument d = new XWPFDocument(Files.newInputStream(docx.toPath()));
             XWPFWordExtractor ex = new XWPFWordExtractor(d)) {
            Files.writeString(out.toPath(), ex.getText(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------
    // Word → PDF

    public static void docxToPdf(File docx, File out, Log log) throws Exception {
        if (docxToPdfViaLibreOffice(docx, out, log)) return;
        log.info("未检测到 LibreOffice，使用内置简化排版（文字与图片会保留，复杂样式从简）。");
        docxToPdfBasic(docx, out, log);
    }

    private static boolean docxToPdfViaLibreOffice(File docx, File out, Log log) throws Exception {
        String soffice = findSoffice();
        if (soffice == null) return false;
        Path tmp = Files.createTempDirectory("fc_lo");
        try {
            Process p = new ProcessBuilder(soffice, "--headless", "--norestore", "--convert-to", "pdf",
                    "--outdir", tmp.toString(), docx.getAbsolutePath()).redirectErrorStream(true).start();
            try (var in = p.getInputStream()) {
                in.readAllBytes();
            }
            if (!p.waitFor(240, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            Path produced = tmp.resolve(PdfService.stripExt(docx.getName()) + ".pdf");
            if (p.exitValue() != 0 || !Files.isRegularFile(produced)) return false;
            Files.move(produced, out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("已通过 LibreOffice 完成高保真转换。");
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                deleteRecursively(tmp);
            } catch (IOException ignore) { }
        }
    }

    private static String findSoffice() {
        for (String c : new String[]{
                "C:/Program Files/LibreOffice/program/soffice.exe",
                "C:/Program Files (x86)/LibreOffice/program/soffice.exe"}) {
            if (new File(c).isFile()) return c;
        }
        try {
            Process p = new ProcessBuilder("soffice", "--version").redirectErrorStream(true).start();
            if (p.waitFor(6, TimeUnit.SECONDS) && p.exitValue() == 0) return "soffice";
            p.destroyForcibly();
        } catch (Exception ignore) { }
        return null;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) { }
            });
        }
    }

    /** 无 LibreOffice 时的兜底方案：提取文字与图片，用 PDFBox 重排。 */
    static void docxToPdfBasic(File docx, File out, Log log) throws Exception {
        try (XWPFDocument d = new XWPFDocument(Files.newInputStream(docx.toPath()));
             PDDocument pdf = new PDDocument()) {
            PdfService.CjkHandle handle = PdfService.openCjkFont(pdf);
            PDFont font = handle != null ? handle.font() : new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            float margin = 57f;
            float maxW = 595f - 2 * margin;
            float pageH = 842f;
            float y = pageH - margin;
            PDPage page = new PDPage(PDRectangle.A4);
            pdf.addPage(page);
            PDPageContentStream cs = new PDPageContentStream(pdf, page);

            for (IBodyElement el : d.getBodyElements()) {
                if (el instanceof XWPFParagraph p) {
                    int size = headingSize(p);
                    boolean bold = size > 11 || p.getRuns().stream().anyMatch(XWPFRun::isBold);
                    String text = p.getText();
                    for (XWPFRun r : p.getRuns()) {
                        for (var pic : r.getEmbeddedPictures()) {
                            byte[] data = pic.getPictureData().getData();
                            y = drawImage(pdf, cs, data, margin, maxW, y, pageH);
                        }
                    }
                    if (text != null && !text.isBlank()) {
                        for (String line : PdfService.wrap(text, font, size, maxW)) {
                            if (y < margin) {
                                cs.close();
                                page = new PDPage(PDRectangle.A4);
                                pdf.addPage(page);
                                cs = new PDPageContentStream(pdf, page);
                                y = pageH - margin;
                            }
                            cs.beginText();
                            cs.setFont(font, size);
                            if (bold) cs.setNonStrokingColor(0, 0, 0);
                            cs.newLineAtOffset(margin, y);
                            cs.showText(line);
                            cs.endText();
                            y -= size * 1.7f;
                        }
                        y -= size * 0.4f;
                    }
                } else if (el instanceof XWPFTable t) {
                    for (XWPFTableRow row : t.getRows()) {
                        StringBuilder sb = new StringBuilder();
                        for (XWPFTableCell c : row.getTableCells()) {
                            if (sb.length() > 0) sb.append("   |   ");
                            sb.append(c.getText().strip());
                        }
                        for (String line : PdfService.wrap(sb.toString(), font, 10, maxW)) {
                            if (y < margin) {
                                cs.close();
                                page = new PDPage(PDRectangle.A4);
                                pdf.addPage(page);
                                cs = new PDPageContentStream(pdf, page);
                                y = pageH - margin;
                            }
                            cs.beginText();
                            cs.setFont(font, 10);
                            cs.newLineAtOffset(margin, y);
                            cs.showText(line);
                            cs.endText();
                            y -= 10 * 1.6f;
                        }
                    }
                    y -= 8;
                }
            }
            cs.close();
            pdf.save(out);
            if (handle != null) handle.close();
        }
    }

    private static float drawImage(PDDocument pdf, PDPageContentStream cs, byte[] data, float x, float maxW, float y, float pageH) {
        try {
            PDImageXObject img = PDImageXObject.createFromByteArray(pdf, data, null);
            float w = img.getWidth();
            float h = img.getHeight();
            float scale = Math.min(1f, Math.min(maxW / w, 660f / h));
            float dw = w * scale;
            float dh = h * scale;
            if (y - dh < 57f) return y;   // 放不下时留给下一段内容，不强行换页
            cs.drawImage(img, x, y - dh, dw, dh);
            return y - dh - 14f;
        } catch (Exception e) {
            return y;
        }
    }

    private static int headingSize(XWPFParagraph p) {
        String sid = p.getStyleID();
        if (sid == null) return 11;
        if (sid.equalsIgnoreCase("Title")) return 22;
        Matcher m = Pattern.compile("(?:Heading)?(\\d)").matcher(sid);
        if (m.matches()) {
            return switch (Integer.parseInt(m.group(1))) {
                case 1 -> 18;
                case 2 -> 16;
                case 3 -> 14;
                default -> 12;
            };
        }
        return 11;
    }

    // ------------------------------------------------------------------
    // 文本 → Word

    public static void txtToDocx(File txt, File out) throws Exception {
        String text = TextFiles.read(txt.toPath());
        try (XWPFDocument d = new XWPFDocument()) {
            for (String line : text.split("\\r?\\n", -1)) {
                XWPFParagraph p = d.createParagraph();
                XWPFRun r = p.createRun();
                r.setText(line);
                r.setFontFamily("微软雅黑");
                r.setFontSize(11);
            }
            try (FileOutputStream fos = new FileOutputStream(out)) {
                d.write(fos);
            }
        }
    }

    // ------------------------------------------------------------------
    // Excel → CSV

    public static void xlsxToCsv(File in, File out) throws Exception {
        try (Workbook wb = WorkbookFactory.create(in, null, true)) {
            Sheet sh = wb.getSheetAt(0);
            DataFormatter df = new DataFormatter();
            FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();
            StringBuilder sb = new StringBuilder();
            for (int i = sh.getFirstRowNum(); i <= sh.getLastRowNum(); i++) {
                Row row = sh.getRow(i);
                if (row == null) {
                    sb.append('\n');
                    continue;
                }
                int last = row.getLastCellNum();
                for (int c = 0; c < last; c++) {
                    if (c > 0) sb.append(',');
                    Cell cell = row.getCell(c);
                    if (cell != null) sb.append(csvEscape(df.formatCellValue(cell, ev)));
                }
                sb.append('\n');
            }
            // BOM 让 Excel 正确识别 UTF-8
            Files.writeString(out.toPath(), '\uFEFF' + sb.toString(), StandardCharsets.UTF_8);
        }
    }

    private static String csvEscape(String v) {
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    // ------------------------------------------------------------------
    // CSV → Excel

    public static void csvToXlsx(File in, File out) throws Exception {
        List<List<String>> rows = parseCsv(TextFiles.read(in.toPath()));
        try (Workbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            Sheet sh = wb.createSheet("Sheet1");
            CellStyle headerStyle = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);
            for (int i = 0; i < rows.size() && i < 1_048_576; i++) {
                Row row = sh.createRow(i);
                List<String> cells = rows.get(i);
                for (int c = 0; c < cells.size() && c < 16384; c++) {
                    Cell cell = row.createCell(c);
                    String v = cells.get(c);
                    if (v.length() > 32767) v = v.substring(0, 32767);
                    cell.setCellValue(v);
                    if (i == 0) cell.setCellStyle(headerStyle);
                }
            }
            try (FileOutputStream fos = new FileOutputStream(out)) {
                wb.write(fos);
            }
        }
    }

    /** 标准 CSV 解析：支持引号、转义引号、跨行字段。 */
    static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        boolean any = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
                any = true;
            } else if (c == ',') {
                cur.add(cell.toString());
                cell.setLength(0);
                any = true;
            } else if (c == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                cur.add(cell.toString());
                rows.add(cur);
                cur = new ArrayList<>();
                cell.setLength(0);
                any = false;
            } else if (c == '\n') {
                cur.add(cell.toString());
                rows.add(cur);
                cur = new ArrayList<>();
                cell.setLength(0);
                any = false;
            } else {
                cell.append(c);
                any = true;
            }
        }
        if (any || cell.length() > 0 || !cur.isEmpty()) {
            cur.add(cell.toString());
            rows.add(cur);
        }
        return rows;
    }
}
