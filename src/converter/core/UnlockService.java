package converter.core;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 音乐加密容器解锁：网易云 .ncm、酷狗 .kgm/.kgma/.vpr。
 * 算法来自公开的开源实现（ncmdump / unlock-music / KgmWasm / MusicDecrypto），
 * 仅用于处理用户本人合法下载的歌曲文件。
 */
public final class UnlockService {

    /** 解锁结果：音频字节 + 实际格式 + 元数据（可能为 null）。 */
    public record Unlocked(byte[] audio, String ext, String title, String artist, String album) { }

    private static final byte[] NCM_MAGIC = "CTENFDAM".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NCM_CORE_KEY = "hzHRAmso5kInbaxW".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NCM_META_KEY = "#14ljk_!\\]&0U<'(".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KGM_SLOT_KEY = {0x6C, 0x2C, 0x2F, 0x27};   // "l,/'"
    private static final int KGM_AUDIO_MAGIC_LIMIT = 32;                   // 尝试识别音频头的范围

    private UnlockService() { }

    /** 按扩展名/文件内容自动识别并解锁。 */
    public static Unlocked unlock(Path file) throws IOException {
        String ext = extOf(file.getFileName().toString());
        byte[] all = Files.readAllBytes(file);
        return switch (ext) {
            case "ncm" -> unlockNcm(all);
            case "kgm", "kgma", "vpr" -> unlockKgm(all, ext);
            default -> throw new IOException("不支持解锁的格式: " + ext);
        };
    }

    // ==================================================================
    // 网易云 NCM

    private static Unlocked unlockNcm(byte[] all) throws IOException {
        if (all.length < 64 || !startsWith(all, NCM_MAGIC, 0)) {
            throw new IOException("不是有效的网易云音乐 .ncm 文件");
        }
        ByteBuffer buf = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(10);

        // 1. RC4 密钥（AES-ECB 解出 "neteasecloudmusic" 前缀后的部分）
        int keyLen = buf.getInt();
        byte[] keyData = new byte[keyLen];
        buf.get(keyData);
        xorInPlace(keyData, (byte) 0x64);
        byte[] key = aesEcbDecrypt(NCM_CORE_KEY, keyData);
        byte[] rc4Key = slice(key, 17, key.length);
        byte[] box = buildKeyBox(rc4Key);

        // 2. 元数据（可选）
        String title = null;
        String artist = null;
        String album = null;
        int metaLen = buf.getInt();
        if (metaLen > 0) {
            byte[] metaData = new byte[metaLen];
            buf.get(metaData);
            xorInPlace(metaData, (byte) 0x64);
            try {
                String b64 = new String(metaData, StandardCharsets.UTF_8);
                if (b64.startsWith("163 key(Don't modify):")) {
                    b64 = b64.substring("163 key(Don't modify):".length());
                }
                byte[] metaJson = aesEcbDecrypt(NCM_META_KEY, Base64.getDecoder().decode(b64.trim()));
                String json = new String(metaJson, StandardCharsets.UTF_8);
                title = jsonValue(json, "musicName");
                artist = jsonValue(json, "artist");
                album = jsonValue(json, "album");
            } catch (Exception ignore) {
                // 元数据损坏不影响音频解锁
            }
        }

        // 3. 跳过 CRC(4) + 间隔(5) + 封面
        buf.getInt();
        buf.position(buf.position() + 5);
        int imgSize = buf.getInt();
        buf.position(buf.position() + imgSize);

        // 4. 音频体解密（部分文件在封面后还有 4 字节长度字段，靠音频魔数自动对齐）
        byte[] audio = new byte[buf.remaining()];
        buf.get(audio);
        decryptNcmStream(audio, box);
        byte[] aligned = alignToAudioMagic(audio);
        if (aligned != null && aligned.length > 0) audio = aligned;
        if (audio.length == 0) throw new IOException("NCM 中未找到有效音频数据");

        String ext = detectAudioExt(audio);
        return new Unlocked(audio, ext, title, artist, album);
    }

    private static byte[] buildKeyBox(byte[] key) {
        byte[] box = new byte[256];
        for (int i = 0; i < 256; i++) box[i] = (byte) i;
        int lastByte = 0;
        int keyOffset = 0;
        for (int i = 0; i < 256; i++) {
            int swap = box[i] & 0xff;
            int c = (swap + lastByte + (key[keyOffset] & 0xff)) & 0xff;
            keyOffset = (keyOffset + 1) % key.length;
            box[i] = box[c];
            box[c] = (byte) swap;
            lastByte = c;
        }
        return box;
    }

    private static void decryptNcmStream(byte[] data, byte[] box) {
        for (int i = 0; i < data.length; i++) {
            int j = (i + 1) & 0xff;
            data[i] ^= box[((box[j] & 0xff) + (box[box[j] & 0xff] & 0xff)) & 0xff];
        }
    }

    // ==================================================================
    // 酷狗 KGM / KGMA / VPR
    // 三代加密方案并存，按头部信息依次尝试，以解密结果的音频魔数判定正确方案。

    private static Unlocked unlockKgm(byte[] all, String ext) throws IOException {
        if (all.length < 0x40) throw new IOException("文件太小，不是有效的酷狗加密文件");
        boolean vpr = matches(KgmTables.VPR_MAGIC, all, 0);
        boolean kgm = matches(KgmTables.KGM_MAGIC, all, 0);
        if (!vpr && !kgm && ext.equals("vpr")) throw new IOException("不是有效的 .vpr 文件");

        int headerLen = le32(all, 0x10);            // 音频数据起始（通常 0x3C）
        int cipherType = le32(all, 0x14);           // 2=T2 / 3=T3
        byte[] fileKey16 = slice(all, 0x1C, 0x2C);  // 方案 A 的文件密钥
        byte[] fileKey2C = slice(all, 0x2C, 0x3C);  // T3 的文件密钥
        byte[] audio = slice(all, headerLen, all.length);
        byte[] work = new byte[audio.length];
        byte[] result = null;

        // 方案顺序：先按头部标注的加密代数，失败再逐一尝试
        int[] order = cipherType == 3 ? new int[]{3, 1, 2}
                : cipherType == 2 ? new int[]{2, 1, 3}
                : new int[]{1, 2, 3};
        for (int scheme : order) {
            System.arraycopy(audio, 0, work, 0, audio.length);
            switch (scheme) {
                case 1 -> decryptKgmV2(work, fileKey16);
                case 2 -> decryptKgmT2(work);
                case 3 -> decryptKgmT3(work, fileKey2C);
            }
            byte[] aligned = alignToAudioMagic(work);
            if (aligned != null && aligned.length > 0) {
                result = aligned;
                break;
            }
        }
        if (result == null) {
            throw new IOException("酷狗加密方案无法识别（可能是需要密钥库的新格式 .kgg，暂不支持）");
        }
        if (vpr) {
            for (int i = 0; i < result.length; i++) {
                result[i] ^= (byte) KgmTables.VPR_MASK_DIFF[i % 17];
            }
        }
        String outExt = detectAudioExt(result);
        return new Unlocked(result, outExt, null, null, null);
    }

    /** 方案 A（KgmWasm / MaskV2PreDef）：文件密钥取自头部 0x1C，补 0x00 到 17 字节。 */
    private static void decryptKgmV2(byte[] data, byte[] fileKey16) {
        byte[] key = new byte[17];
        System.arraycopy(fileKey16, 0, key, 0, 16);
        key[16] = 0;
        for (int i = 0; i < data.length; i++) {
            int med = (data[i] & 0xff) ^ (key[i % 17] & 0xff);
            med ^= (med & 0xf) << 4;
            int msk = getMaskV2(i);
            msk ^= (msk & 0xf) << 4;
            data[i] = (byte) (med ^ msk);
        }
    }

    private static int getMaskV2(long pos) {
        long off = pos >> 4;
        int value = 0;
        while (off >= 17) {
            value ^= KgmTables.TABLE1[(int) (off % 272)];
            off >>= 4;
            value ^= KgmTables.TABLE2[(int) (off % 272)];
            off >>= 4;
        }
        return KgmTables.MASK_V2_PRE_DEF[(int) (pos % 272)] ^ value;
    }

    /** 方案 B（T2）：逐字节 nibble-XOR + 4 字节槽位密钥。 */
    private static void decryptKgmT2(byte[] data) {
        for (int i = 0; i < data.length; i++) {
            int b = data[i] & 0xff;
            b = b ^ ((b & 0xf) << 4);
            data[i] = (byte) (b ^ KGM_SLOT_KEY[i % 4]);
        }
    }

    /** 方案 C（T3）：MD5 派生密钥 + 位置掩码。 */
    private static void decryptKgmT3(byte[] data, byte[] fileKey16) {
        byte[] slot = md5Swap(KGM_SLOT_KEY);   // 16 字节
        byte[] fk = md5Swap(fileKey16);        // 16 字节
        byte[] fileKey = new byte[17];
        System.arraycopy(fk, 0, fileKey, 0, 16);
        fileKey[16] = 0x6b;
        for (int i = 0; i < data.length; i++) {
            int pos = i;
            int b = data[i] & 0xff;
            int f = fileKey[pos % 17] & 0xff;
            int x = b ^ f;
            x = x ^ ((x & 0xf) << 4);
            int s = slot[pos % 16] & 0xff;
            int m = pos ^ (pos >> 8) ^ (pos >> 16) ^ (pos >> 24);
            data[i] = (byte) (x ^ s ^ m);
        }
    }

    /** MD5 + 首尾字节两两交换（T3 密钥派生）。 */
    private static byte[] md5Swap(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(data);
            for (int i = 0; i < 8; i += 2) {
                byte t = d[i];
                d[i] = d[14 - i];
                d[14 - i] = t;
                t = d[i + 1];
                d[i + 1] = d[15 - i];
                d[15 - i] = t;
            }
            return d;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ==================================================================
    // 通用工具

    private static byte[] aesEcbDecrypt(byte[] key, byte[] data) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new IOException("解密失败（数据可能已损坏）: " + Engine.rootMsg(e));
        }
    }

    private static void xorInPlace(byte[] data, byte mask) {
        for (int i = 0; i < data.length; i++) data[i] ^= mask;
    }

    private static byte[] slice(byte[] src, int from, int to) {
        int realFrom = Math.max(0, from);
        int realTo = Math.min(src.length, to);
        if (realTo <= realFrom) return new byte[0];
        return java.util.Arrays.copyOfRange(src, realFrom, realTo);
    }

    private static int le32(byte[] b, int pos) {
        return (b[pos] & 0xff) | ((b[pos + 1] & 0xff) << 8) | ((b[pos + 2] & 0xff) << 16) | ((b[pos + 3] & 0xff) << 24);
    }

    private static boolean startsWith(byte[] data, byte[] prefix, int off) {
        if (data.length - off < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[off + i] != prefix[i]) return false;
        }
        return true;
    }

    private static boolean matches(int[] magic, byte[] data, int off) {
        if (data.length - off < magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if ((data[off + i] & 0xff) != magic[i]) return false;
        }
        return true;
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    /** 找到真实音频数据的起始位置（跳过个别文件头部的残留长度字段）。 */
    private static byte[] alignToAudioMagic(byte[] data) {
        int limit = Math.min(data.length, KGM_AUDIO_MAGIC_LIMIT);
        for (int i = 0; i < limit; i++) {
            if (detectAudioExt(data, i) != null) {
                return i == 0 ? data : slice(data, i, data.length);
            }
        }
        return null;
    }

    private static String detectAudioExt(byte[] data) {
        String ext = detectAudioExt(data, 0);
        return ext != null ? ext : "mp3";
    }

    /** 依据魔数判断音频格式。 */
    private static String detectAudioExt(byte[] d, int off) {
        if (d.length - off < 12) return null;
        if (d[off] == 'f' && d[off + 1] == 'L' && d[off + 2] == 'a' && d[off + 3] == 'C') return "flac";
        if (d[off] == 'I' && d[off + 1] == 'D' && d[off + 2] == '3') return "mp3";
        if (d[off] == 'O' && d[off + 1] == 'g' && d[off + 2] == 'g' && d[off + 3] == 'S') return "ogg";
        if (d[off] == 'f' && d[off + 1] == 't' && d[off + 2] == 'y' && d[off + 3] == 'p') return "m4a";
        if (d[off] == 'R' && d[off + 1] == 'I' && d[off + 2] == 'F' && d[off + 3] == 'F') return "wav";
        if ((d[off] & 0xff) == 0xff && (d[off + 1] & 0xff) == 0xfb) return "mp3";
        if (d[off] == 'M' && d[off + 1] == 'A' && d[off + 2] == 'C') return "ape";
        return null;
    }

    /** 从 NCM 元数据 JSON 提取字段（artist 为嵌套数组，取第一个名字）。 */
    private static String jsonValue(String json, String key) {
        if (key.equals("artist")) {
            Matcher m = Pattern.compile("\"artist\"\\s*:\\s*\\[\\s*\\[\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
            if (m.find()) return unescapeJson(m.group(1));
            return null;
        }
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (m.find()) return unescapeJson(m.group(1));
        return null;
    }

    private static String unescapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (++i >= s.length()) break;
            char e = s.charAt(i);
            switch (e) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    if (i + 4 < s.length()) {
                        sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                }
                default -> sb.append(e);
            }
        }
        return sb.toString();
    }
}
