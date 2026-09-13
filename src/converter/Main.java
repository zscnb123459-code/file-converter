package converter;

import converter.cli.CliRunner;
import converter.core.Config;
import converter.ui.ConverterFrame;
import converter.web.WebServer;

import javax.swing.SwingUtilities;

/**
 * 入口：
 *   无参数        → 图形界面（Swing）
 *   --web [端口]  → 网页版服务（浏览器访问，默认 8080）
 *   其他参数      → 命令行转换
 */
public final class Main {

    public static void main(String[] args) {
        if (args.length > 0 && "--web".equals(args[0])) {
            int port = 8080;
            for (int i = 1; i < args.length - 1; i++) {
                if ("--port".equals(args[i])) {
                    try {
                        port = Integer.parseInt(args[i + 1]);
                    } catch (NumberFormatException ignore) { }
                }
            }
            Config.load();
            try {
                WebServer.start(port);
            } catch (Exception e) {
                System.err.println("网页服务启动失败: " + e.getMessage()
                        + "（端口可能被占用，可换端口：--web --port 9090）");
            }
            return;
        }
        if (args.length > 0) {
            CliRunner.run(args);
            return;
        }
        Config.load();
        SwingUtilities.invokeLater(() -> new ConverterFrame().setVisible(true));
    }

    private Main() { }
}
