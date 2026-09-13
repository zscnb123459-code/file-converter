package converter.core;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/** 应用目录下的 converter.properties：记录 FFmpeg 路径、输出目录等。 */
public final class Config {
    private static final Properties PROPS = new Properties();
    private static Path file;

    private Config() { }

    public static Path appDir() {
        try {
            Path p = Paths.get(Config.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dir = p.getParent();
            if (dir != null && Files.isDirectory(dir)) return dir;
        } catch (URISyntaxException | IllegalArgumentException ignore) { }
        return Paths.get(System.getProperty("user.dir"));
    }

    public static synchronized void load() {
        file = appDir().resolve("converter.properties");
        if (Files.isRegularFile(file)) {
            try (Reader r = Files.newBufferedReader(file)) {
                PROPS.load(r);
            } catch (IOException ignore) { }
        }
    }

    public static synchronized String get(String key, String def) {
        return PROPS.getProperty(key, def);
    }

    public static synchronized void set(String key, String value) {
        if (value == null || value.isBlank()) PROPS.remove(key);
        else PROPS.setProperty(key, value);
        try (Writer w = Files.newBufferedWriter(file)) {
            PROPS.store(w, "FileConverter");
        } catch (IOException ignore) { }
    }
}
