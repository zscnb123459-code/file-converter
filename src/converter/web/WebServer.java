package converter.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import converter.core.Config;
import converter.core.Engine;
import converter.core.FileKind;
import converter.core.FfmpegLocator;
import converter.core.InputEntry;
import converter.core.Log;
import converter.core.Options;
import converter.core.TextFiles;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 网页版服务：浏览器上传 → 复用现有 Engine 完成转换 → 通过 /api/download 下载。
 * 任务以 UUID 标识，磁盘路径永不暴露给前端，杜绝路径穿越。
 */
public final class WebServer {

    private static final long MAX_UPLOAD = 4L * 1024 * 1024 * 1024;   // 单文件上限 4GB
    private static final Duration RETENTION = Duration.ofHours(24);    // 结果保留时长
    private static final Map<String, Job> JOBS = new ConcurrentHashMap<>();
    private static final ExecutorService CONVERT_POOL = Executors.newFixedThreadPool(3);
    private static final ExecutorService HTTP_POOL = Executors.newFixedThreadPool(16);

    private static Path jobsRoot;
    private static boolean ffmpegOk = false;
    private static String ffmpegPath;

    /** 任务状态机：READY(已上传待选格式) → RUNNING(转换中) → DONE / FAILED。 */
    static final class Job {
        final String id = UUID.randomUUID().toString().replace("-", "");
        final Path dir;
        final String uploadName;     // 原始显示文件名
        final String ext;            // 小写扩展名
        final long createdAt = System.currentTimeMillis();
        volatile String state = "READY";
        volatile double progress = 0;
        volatile String error;
        volatile Path outputFile;    // 转换产物（多产物时为 zip）
        volatile String outputName;
        final List<String> logLines = java.util.Collections.synchronizedList(new ArrayList<>());

        Job(Path dir, String uploadName, String ext) {
            this.dir = dir;
            this.uploadName = uploadName;
            this.ext = ext;
        }
    }

    private WebServer() { }

    public static void start(int port) throws IOException {
        jobsRoot = Path.of(System.getProperty("java.io.tmpdir"), "fileconverter-web");
        Files.createDirectories(jobsRoot);
        cleanExpired();

        Config.load();
        FfmpegLocator.Result ff = FfmpegLocator.find();
        ffmpegOk = ff != null;
        ffmpegPath = ff == null ? null : ff.path();

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 64);
        server.createContext("/", WebServer::handleStatic);
        server.createContext("/api/upload", WebServer::handleUpload);
        server.createContext("/api/convert", WebServer::handleConvert);
        server.createContext("/api/status", WebServer::handleStatus);
        server.createContext("/api/download", WebServer::handleDownload);
        server.setExecutor(HTTP_POOL);
        server.start();

        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(WebServer::cleanExpired, 30, 30, java.util.concurrent.TimeUnit.MINUTES);

        System.out.println("网页版转换器已启动 ✓");
        System.out.println("  本机访问:   http://127.0.0.1:" + port + "/");
        for (String ip : lanIps()) System.out.println("  手机/局域网: http://" + ip + ":" + port + "/");
        System.out.println("  FFmpeg: " + (ffmpegOk ? "已检测到，音视频转换可用" : "未检测到，仅支持 PDF/Word/Excel/文本转换"));
        System.out.println("  关闭本窗口或按 Ctrl+C 即可停止服务。转换结果保留 24 小时。");
    }

    private static List<String> lanIps() {
        List<String> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
                Enumeration<java.net.InetAddress> addrs = nics.nextElement().getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress() && a.isSiteLocalAddress()) {
                        out.add(a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignore) { }
        return out;
    }

    // ------------------------------------------------------------------
    // 静态页面（白名单分发，杜绝任意文件读取）

    private static void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if ("/".equals(path) || "/index.html".equals(path)) {
            serveResource(ex, "/converter/web/index.html", "text/html; charset=utf-8");
        } else if ("/app.js".equals(path)) {
            serveResource(ex, "/converter/web/app.js", "application/javascript; charset=utf-8");
        } else if ("/style.css".equals(path)) {
            serveResource(ex, "/converter/web/style.css", "text/css; charset=utf-8");
        } else if ("/bg.jpg".equals(path)) {
            serveResource(ex, "/converter/web/bg.jpg", "image/jpeg");
        } else {
            sendJson(ex, 404, "{\"success\":false,\"error\":\"页面不存在\"}");
        }
    }

    private static void serveResource(HttpExchange ex, String res, String contentType) throws IOException {
        URL url = WebServer.class.getResource(res);
        if (url == null) {
            sendJson(ex, 500, "{\"success\":false,\"error\":\"资源缺失\"}");
            return;
        }
        byte[] body;
        try (InputStream in = WebServer.class.getResourceAsStream(res)) {
            body = in.readAllBytes();
        }
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        send(ex, 200, body);
    }

    // ------------------------------------------------------------------
    // 上传：原始文件体 + ?name=文件名，流式落盘，不进内存

    private static void handleUpload(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, "{\"success\":false,\"error\":\"请使用 POST\"}");
            return;
        }
        String name = queryParam(ex, "name");
        if (name == null || name.isBlank()) name = "未命名文件";
        name = sanitizeName(name);
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
        if (ext.isBlank() || ext.length() > 12 || !ext.matches("[a-z0-9]+")) {
            sendJson(ex, 400, "{\"success\":false,\"error\":\"无法识别文件扩展名\"}");
            return;
        }
        FileKind kind = FileKind.of(ext);
        if (kind == FileKind.UNKNOWN) {
            sendJson(ex, 400, "{\"success\":false,\"error\":\"不支持的文件类型: ." + esc(ext) + "\"}");
            return;
        }

        Job job = new Job(Files.createDirectories(jobsRoot.resolve(jobDirName())), name, ext);
        Path source = job.dir.resolve("source." + ext);
        try (InputStream in = ex.getRequestBody(); OutputStream out = Files.newOutputStream(source)) {
            long total = 0;
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_UPLOAD) {
                    job.state = "FAILED";
                    job.error = "文件超过 4GB 上限";
                    sendJson(ex, 413, "{\"success\":false,\"error\":\"文件超过 4GB 上限\"}");
                    return;
                }
                out.write(buf, 0, n);
            }
        }
        if (Files.size(source) == 0) {
            sendJson(ex, 400, "{\"success\":false,\"error\":\"上传内容为空\"}");
            return;
        }
        JOBS.put(job.id, job);

        List<String> targets = new ArrayList<>();
        for (String t : kind.targets()) {
            if (!t.equals(job.ext)) targets.add(t);   // 隐藏与源格式相同的目标
        }
        sendJson(ex, 200, "{\"success\":true,\"fileId\":\"" + job.id + "\",\"fileName\":\"" + esc(job.uploadName)
                + "\",\"size\":" + Files.size(source) + ",\"kind\":\"" + esc(kind.label)
                + "\",\"targets\":" + toJsonList(targets) + ",\"ffmpeg\":" + ffmpegOk + "}");
    }

    // ------------------------------------------------------------------
    // 转换：提交到线程池，立即返回；进度走 /api/status 轮询

    private static void handleConvert(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, "{\"success\":false,\"error\":\"请使用 POST\"}");
            return;
        }
        String body;
        try (InputStream in = ex.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String fileId = jsonField(body, "fileId");
        String target = jsonField(body, "target");
        Job job = fileId == null ? null : JOBS.get(fileId);
        if (job == null) {
            sendJson(ex, 404, "{\"success\":false,\"error\":\"任务不存在或已过期，请重新上传\"}");
            return;
        }
        if (!"READY".equals(job.state)) {
            sendJson(ex, 409, "{\"success\":false,\"error\":\"当前任务状态不允许开始转换\"}");
            return;
        }
        List<String> targets = new ArrayList<>();
        for (String t : FileKind.of(job.ext).targets()) {
            if (!t.equals(job.ext)) targets.add(t);
        }
        if (target == null || !targets.contains(target)) {
            sendJson(ex, 400, "{\"success\":false,\"error\":\"不支持的目标格式: " + esc(String.valueOf(target)) + "\"}");
            return;
        }
        job.state = "RUNNING";
        job.progress = 0;
        CONVERT_POOL.submit(() -> runJob(job, target));
        sendJson(ex, 200, "{\"success\":true,\"jobId\":\"" + job.id + "\",\"state\":\"RUNNING\"}");
    }

    private static void runJob(Job job, String target) {
        try {
            Path outDir = job.dir.resolve("output");
            Files.createDirectories(outDir);
            InputEntry entry = InputEntry.ofFile(job.dir.resolve("source." + job.ext).toFile());
            Options opt = new Options("192k", false, new AtomicBoolean(false));
            Engine engine = new Engine(ffmpegPath);
            Log jobLog = new Log() {
                @Override public void info(String msg) {
                    job.logLines.add(msg);
                    if (job.logLines.size() > 8) job.logLines.remove(0);
                }

                @Override public void error(String msg) { job.error = msg; }

                @Override public void progress(double fraction) {
                    job.progress = Math.max(0, Math.min(1, fraction));
                }
            };
            engine.convertAll(List.of(entry), target, outDir, opt, jobLog);

            // 收集产物：单个直接用；多个（如 PDF 按页导出图片）打包为 zip
            List<Path> produced;
            try (var walk = Files.walk(outDir)) {
                produced = walk.filter(Files::isRegularFile)
                        .filter(WebServer::nonEmpty)
                        .sorted()
                        .toList();
            }
            if (produced.isEmpty()) throw new IOException("转换未生成输出文件");
            String base = InputEntry.ofFile(new File(job.uploadName)).baseName;
            if (produced.size() == 1) {
                job.outputFile = produced.get(0);
                job.outputName = produced.get(0).getFileName().toString();   // 使用引擎生成的实际文件名（含元数据命名）
            } else {
                job.outputFile = outDir.resolve("转换结果.zip");
                try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(job.outputFile))) {
                    for (Path p : produced) {
                        zos.putNextEntry(new ZipEntry(p.getFileName().toString()));
                        Files.copy(p, zos);
                        zos.closeEntry();
                    }
                }
                job.outputName = base + "_转换结果.zip";
            }
            job.progress = 1.0;
            job.state = "DONE";
        } catch (Exception e) {
            job.state = "FAILED";
            if (job.error == null || job.error.isBlank()) job.error = Engine.rootMsg(e);
        }
    }

    // ------------------------------------------------------------------
    // 状态查询

    private static void handleStatus(HttpExchange ex) throws IOException {
        String id = queryParam(ex, "id");
        Job job = id == null ? null : JOBS.get(id);
        if (job == null) {
            sendJson(ex, 404, "{\"success\":false,\"error\":\"任务不存在或已过期\"}");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"success\":true,\"state\":\"").append(job.state).append("\"");
        sb.append(",\"progress\":").append(String.format(java.util.Locale.ROOT, "%.3f", job.progress));
        sb.append(",\"fileName\":\"").append(esc(job.uploadName)).append("\"");
        if (job.error != null) sb.append(",\"error\":\"").append(esc(job.error)).append("\"");
        if ("DONE".equals(job.state)) {
            sb.append(",\"outputName\":\"").append(esc(job.outputName)).append("\"");
            try {
                sb.append(",\"size\":").append(Files.size(job.outputFile));
            } catch (IOException ignore) { }
            sb.append(",\"downloadUrl\":\"/api/download/").append(job.id).append("\"");
        }
        sb.append("}");
        sendJson(ex, 200, sb.toString());
    }

    // ------------------------------------------------------------------
    // 下载：流式输出，Content-Disposition 兼容中文名（RFC 5987）

    private static void handleDownload(HttpExchange ex) throws IOException {
        String id = ex.getRequestURI().getPath().substring("/api/download/".length());
        Job job = JOBS.get(id);
        if (job == null || !"DONE".equals(job.state) || job.outputFile == null || !Files.isRegularFile(job.outputFile)) {
            sendJson(ex, 404, "{\"success\":false,\"error\":\"文件不存在或尚未转换完成\"}");
            return;
        }
        String name = job.outputName == null ? "output" : job.outputName;
        String mime = URLConnection.guessContentTypeFromName(name.toLowerCase());
        if (mime == null) mime = "application/octet-stream";
        long size;
        try {
            size = Files.size(job.outputFile);
        } catch (IOException e) {
            sendJson(ex, 404, "{\"success\":false,\"error\":\"文件不存在\"}");
            return;
        }
        String asciiFallback = name.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "_");
        ex.getResponseHeaders().set("Content-Type", mime);
        ex.getResponseHeaders().set("Content-Disposition",
                "attachment; filename=\"" + asciiFallback + "\"; filename*=UTF-8''"
                        + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20"));
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(200, size);
        try (InputStream in = Files.newInputStream(job.outputFile); OutputStream out = ex.getResponseBody()) {
            in.transferTo(out);   // 64KB 内部缓冲流式写出，不整读进内存
        }
    }

    // ------------------------------------------------------------------
    // 临时文件清理：只清自己的任务目录；先删过期任务，再删除空目录

    private static void cleanExpired() {
        try (var dirs = Files.list(jobsRoot)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                boolean expired;
                try {
                    expired = Duration.ofMillis(System.currentTimeMillis() - Files.getLastModifiedTime(dir).toMillis())
                            .compareTo(RETENTION) > 0;
                } catch (IOException ignore) {
                    continue;
                }
                if (!expired) continue;
                JOBS.values().removeIf(j -> j.dir.equals(dir));
                deleteTree(dir);
            }
        } catch (Exception ignore) { }
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) { }
            });
        } catch (IOException ignore) { }
    }

    private static boolean nonEmpty(Path p) {
        try {
            return Files.size(p) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 工具

    private static String jobDirName() {
        return System.currentTimeMillis() + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** 去掉路径成分与非法字符，限制长度。 */
    private static String sanitizeName(String s) {
        String n = s.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        n = n.replaceAll("[\\r\\n\\t\"<>|:*?\u0000]", "_").trim();
        if (n.length() > 120) {
            int dot = n.lastIndexOf('.');
            String ext = dot > 0 ? n.substring(dot) : "";
            n = n.substring(0, 120 - ext.length()) + ext;
        }
        return n;
    }

    private static String extOf(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot >= 0 ? n.substring(dot + 1) : "bin";
    }

    private static String queryParam(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return null;
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** 极简 JSON 字段提取（请求体由本项目前端生成，格式可控）。 */
    private static String jsonField(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String toJsonList(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(esc(items.get(i))).append('"');
        }
        return sb.append(']').toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        send(ex, code, json.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int code, byte[] body) throws IOException {
        ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        } else {
            ex.close();
        }
    }
}
