package converter.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import converter.core.Config;
import converter.core.Engine;
import converter.core.FileKind;
import converter.core.FfmpegLocator;
import converter.core.InputEntry;
import converter.core.Log;
import converter.core.Options;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicBoolean;

/** 主界面：梦幻天空插画背景 + 毛玻璃卡片，简洁不花哨。 */
public class ConverterFrame extends JFrame {

    private static final Color ACCENT_TEXT = new Color(0xBFD4FF);      // 日志/标题点缀
    private static final Color ACCENT_LINE = new Color(0x8FB4F0, true); // 分隔线（半透明）
    private static final Color GLASS = new Color(10, 15, 30, 190);      // 毛玻璃卡片
    private static final Color GLASS_FIELD = new Color(8, 12, 26, 205); // 输入控件
    private static final Color GLASS_BTN = new Color(16, 24, 46, 205);  // 普通按钮
    private static final Color WHITE = new Color(0xF4F7FC);
    private static final Color WHITE_DIM = new Color(255, 255, 255, 175);
    private static final Color GREEN = new Color(0x9FE0A5);
    private static final Color AMBER = new Color(0xF2D28B);
    private static final Color ERR = new Color(0xFF9AA4);

    private static final BufferedImage BG = loadBg();

    private final DefaultListModel<InputEntry> model = new DefaultListModel<>();
    private final JList<InputEntry> list = new JList<>(model);
    private final JComboBox<String> targetBox = new JComboBox<>();
    private final JCheckBox mergeCheck = new JCheckBox("多张图片合并为一个 PDF");
    private final JCheckBox openCheck = new JCheckBox("完成后打开目录");
    private final JComboBox<String> bitrateBox = new JComboBox<>(new String[]{"320k（高音质）", "256k", "192k（推荐）", "128k（体积小）"});

    {
        bitrateBox.setSelectedItem("192k（推荐）");
    }

    private final JTextField outField = new JTextField(24);
    private final JTextPane logPane = new JTextPane();
    private final JButton convertBtn = new JButton("开始转换");
    private final JButton cancelBtn = new JButton("取消");
    private final JButton browseBtn = new JButton("浏览…");
    private final JButton addBtn = new JButton("添加文件");
    private final JButton addUrlBtn = new JButton("添加链接");
    private final JButton rmBtn = new JButton("移除选中");
    private final JButton clrBtn = new JButton("清空");
    private final JProgressBar bar = new JProgressBar(0, 1000);
    private final JLabel status = new JLabel("就绪");
    private final JLabel countLabel = new JLabel(" ");
    private final JLabel ffLabel = new JLabel("正在检测 FFmpeg…");

    private String ffmpegPath;
    private Engine currentEngine;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private final SimpleAttributeSet attrInfo = attr(new Color(0xE6ECF7));
    private final SimpleAttributeSet attrHead = attr(ACCENT_TEXT);
    private final SimpleAttributeSet attrErr = attr(ERR);

    static {
        FlatLaf.setGlobalExtraDefaults(Map.of("@accentColor", "#5B8DEF"));
        try {
            UIManager.setLookAndFeel(new FlatDarkLaf());
        } catch (Exception ignore) { }
        UIManager.put("defaultFont", new Font("Microsoft YaHei", Font.PLAIN, 13));
    }

    private static BufferedImage loadBg() {
        try (InputStream in = ConverterFrame.class.getResourceAsStream("/converter/resources/bg.jpg")) {
            if (in != null) return ImageIO.read(in);
        } catch (Exception ignore) { }
        return null;
    }

    public ConverterFrame() {
        setTitle("万能格式转换器");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(820, 700);
        setMinimumSize(new Dimension(680, 560));
        setLocationRelativeTo(null);

        JPanel root = new BackgroundPanel();
        root.setLayout(new BorderLayout());
        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildBody(), BorderLayout.CENTER);
        root.add(buildStatusBar(), BorderLayout.SOUTH);
        setContentPane(root);

        bindActions();
        enableDragDrop();
        refreshTargets();

        // 后台检测 ffmpeg，避免阻塞界面
        new Thread(() -> {
            FfmpegLocator.Result r = FfmpegLocator.find();
            SwingUtilities.invokeLater(() -> setFfmpeg(r));
        }, "ffmpeg-detect").start();
    }

    // ------------------------------------------------------------------
    // 背景与玻璃容器

    /** 绘制整幅背景插画（等比 cover），上下加轻微软渐变保证文字可读。 */
    private static final class BackgroundPanel extends JPanel {
        BackgroundPanel() {
            setOpaque(true);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            if (BG == null) {
                g2.setColor(new Color(0x141A28));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
                return;
            }
            int iw = BG.getWidth(null), ih = BG.getHeight(null);
            double s = Math.max(getWidth() / (double) iw, getHeight() / (double) ih);
            int w = (int) Math.round(iw * s), h = (int) Math.round(ih * s);
            g2.drawImage(BG, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
            g2.setPaint(new GradientPaint(0, 0, new Color(6, 10, 24, 170),
                    0, getHeight() / 5, new Color(6, 10, 24, 0)));
            g2.fillRect(0, 0, getWidth(), getHeight() / 5);
            g2.setPaint(new GradientPaint(0, getHeight() - 110, new Color(6, 10, 24, 0),
                    0, getHeight(), new Color(6, 10, 24, 160)));
            g2.fillRect(0, getHeight() - 110, getWidth(), 110);
            g2.dispose();
        }
    }

    /** 圆角毛玻璃卡片。 */
    private static final class GlassCard extends JPanel {
        GlassCard(JComponent content) {
            setLayout(new BorderLayout());
            add(content);
            setBackground(GLASS);
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(getBackground());
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 18, 18);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    // ------------------------------------------------------------------
    // 界面搭建

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, ACCENT_LINE), new EmptyBorder(16, 20, 12, 20)));

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("万能格式转换器");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        title.setForeground(WHITE);
        JLabel sub = new JLabel("视频 · 音频 · 图片 · PDF · Word · Excel   一站互转");
        sub.setForeground(WHITE_DIM);
        sub.setFont(sub.getFont().deriveFont(11.5f));
        left.add(title);
        left.add(Box.createVerticalStrut(3));
        left.add(sub);
        header.add(left, BorderLayout.WEST);

        ffLabel.setForeground(WHITE_DIM);
        ffLabel.setToolTipText("点击可手动指定 ffmpeg.exe 位置");
        ffLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        header.add(ffLabel, BorderLayout.EAST);
        return header;
    }

    private JPanel buildBody() {
        JPanel body = new JPanel(new GridBagLayout());
        body.setOpaque(false);
        body.setBorder(new EmptyBorder(10, 16, 6, 16));

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        toolbar.setOpaque(false);
        for (JButton b : new JButton[]{addBtn, addUrlBtn, rmBtn, clrBtn}) {
            b.setBackground(GLASS_BTN);
            b.setOpaque(true);
            toolbar.add(b);
        }

        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setFixedCellHeight(28);
        list.setOpaque(false);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, v, i, sel, foc);
                InputEntry e = (InputEntry) v;
                c.setText(e.displayName + "    [" + e.kind.label + "]");
                c.setBorder(new EmptyBorder(2, 10, 2, 8));
                c.setOpaque(true);
                if (sel) {
                    c.setBackground(new Color(91, 141, 239, 200));
                    c.setForeground(Color.WHITE);
                } else {
                    c.setBackground(i % 2 == 0 ? new Color(10, 15, 30, 130) : new Color(10, 15, 30, 70));
                    c.setForeground(WHITE);
                }
                return c;
            }
        });
        JScrollPane listScroll = new JScrollPane(list);
        listScroll.setOpaque(false);
        listScroll.getViewport().setOpaque(false);
        listScroll.setPreferredSize(new Dimension(10, 190));
        listScroll.getVerticalScrollBar().setOpaque(false);
        GlassCard listCard = new GlassCard(listScroll);
        listCard.setBorder(new EmptyBorder(6, 6, 6, 6));

        JPanel opts = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        opts.setOpaque(false);
        JLabel toLabel = new JLabel("转换为");
        toLabel.setForeground(WHITE);
        opts.add(toLabel);
        styleCombo(targetBox);
        targetBox.setPrototypeDisplayValue("webmXXXX");
        opts.add(targetBox);
        styleCheck(mergeCheck);
        opts.add(mergeCheck);
        opts.add(Box.createHorizontalStrut(14));
        JLabel brLabel = new JLabel("音频码率");
        brLabel.setForeground(WHITE);
        opts.add(brLabel);
        styleCombo(bitrateBox);
        opts.add(bitrateBox);

        JPanel outRow = new JPanel(new GridBagLayout());
        outRow.setOpaque(false);
        JLabel outLabel = new JLabel("输出目录");
        outLabel.setForeground(WHITE);
        addGbc(outRow, outLabel, 0, 0, 0, 0, GridBagConstraints.NONE, GridBagConstraints.WEST);
        outField.putClientProperty("JTextField.placeholderText", "留空 = 与源文件相同的目录");
        styleField(outField);
        addGbc(outRow, outField, 1, 0, 1, 0, GridBagConstraints.HORIZONTAL, GridBagConstraints.WEST);
        browseBtn.setBackground(GLASS_BTN);
        browseBtn.setOpaque(true);
        addGbc(outRow, browseBtn, 2, 0, 0, 0, GridBagConstraints.NONE, GridBagConstraints.WEST);
        styleCheck(openCheck);
        addGbc(outRow, openCheck, 3, 0, 0, 0, GridBagConstraints.NONE, GridBagConstraints.WEST);

        bar.setStringPainted(true);
        bar.setVisible(false);
        cancelBtn.setVisible(false);
        cancelBtn.setBackground(GLASS_BTN);
        cancelBtn.setOpaque(true);
        JPanel actionRow = new JPanel(new GridBagLayout());
        actionRow.setOpaque(false);
        addGbc(actionRow, convertBtn, 0, 0, 0, 0, GridBagConstraints.NONE, GridBagConstraints.WEST);
        addGbc(actionRow, cancelBtn, 1, 0, 0, 0, GridBagConstraints.NONE, GridBagConstraints.WEST);
        addGbc(actionRow, bar, 2, 0, 1, 0, GridBagConstraints.HORIZONTAL, GridBagConstraints.WEST);

        logPane.setEditable(false);
        logPane.setFont(new Font("Consolas", Font.PLAIN, 12));
        logPane.setBackground(new Color(7, 11, 24, 200));
        logPane.setOpaque(true);
        logPane.setCaretColor(WHITE);
        JScrollPane logScroll = new JScrollPane(logPane);
        logScroll.setOpaque(false);
        logScroll.getViewport().setOpaque(false);
        logScroll.setPreferredSize(new Dimension(10, 150));
        GlassCard logCard = new GlassCard(logScroll);
        logCard.setBorder(new EmptyBorder(6, 6, 6, 6));

        addGbc(body, toolbar, 0, 0, 1, 0, GridBagConstraints.HORIZONTAL, GridBagConstraints.WEST);
        addGbc(body, listCard, 0, 1, 1, 0.55, GridBagConstraints.BOTH, GridBagConstraints.CENTER);
        addGbc(body, opts, 0, 2, 1, 0, GridBagConstraints.HORIZONTAL, GridBagConstraints.WEST);
        addGbc(body, outRow, 0, 3, 1, 0, GridBagConstraints.HORIZONTAL, GridBagConstraints.WEST);
        addGbc(body, actionRow, 0, 4, 1, 0, GridBagConstraints.HORIZONTAL, GridBagConstraints.WEST);
        addGbc(body, logCard, 0, 5, 1, 0.45, GridBagConstraints.BOTH, GridBagConstraints.CENTER);
        return body;
    }

    private JPanel buildStatusBar() {
        JPanel p = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(new Color(6, 10, 24, 150));
                g.fillRect(0, 0, getWidth(), getHeight());
                super.paintComponent(g);
            }
        };
        p.setOpaque(false);
        p.setBorder(new CompoundBorder(new MatteBorder(1, 0, 0, 0, ACCENT_LINE), new EmptyBorder(5, 18, 5, 18)));
        status.setForeground(WHITE_DIM);
        countLabel.setForeground(WHITE_DIM);
        p.add(status, BorderLayout.WEST);
        p.add(countLabel, BorderLayout.EAST);
        return p;
    }

    private void styleCombo(JComboBox<?> combo) {
        combo.setBackground(GLASS_FIELD);
        combo.setOpaque(true);
    }

    private void styleField(JTextField field) {
        field.setBackground(GLASS_FIELD);
        field.setOpaque(true);
        field.setForeground(WHITE);
        field.setCaretColor(WHITE);
    }

    private void styleCheck(JCheckBox check) {
        check.setOpaque(false);
        check.setForeground(WHITE);
    }

    private void addGbc(JPanel p, Component c, int x, int y, double wx, double wy, int fill, int anchor) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = x;
        g.gridy = y;
        g.weightx = wx;
        g.weighty = wy;
        g.fill = fill;
        g.anchor = anchor;
        g.insets = new Insets(5, 5, 5, 5);
        p.add(c, g);
    }

    // ------------------------------------------------------------------
    // 交互逻辑

    private void bindActions() {
        getRootPane().setDefaultButton(convertBtn);

        addBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(lastDir());
            fc.setMultiSelectionEnabled(true);
            fc.setDialogTitle("选择要转换的文件");
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                addFiles(List.of(fc.getSelectedFiles()));
            }
        });

        addUrlBtn.addActionListener(e -> {
            String url = JOptionPane.showInputDialog(this,
                    "输入网络地址（支持 m3u8 / mp4 等在线视频，也可用于本地 ts 直播流）:", "https://");
            if (url == null) return;
            if (url.trim().matches("(?i)^(https?|rtmp|rtsp)://.+")) {
                model.addElement(InputEntry.ofUrl(url.trim()));
                refreshAfterListChange();
            } else {
                JOptionPane.showMessageDialog(this, "地址格式不正确，需要以 http:// 或 https:// 开头");
            }
        });

        rmBtn.addActionListener(e -> {
            for (InputEntry e2 : list.getSelectedValuesList()) model.removeElement(e2);
            refreshAfterListChange();
        });
        clrBtn.addActionListener(e -> {
            model.clear();
            refreshAfterListChange();
        });

        browseBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(lastDir());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                outField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });

        targetBox.addItemListener(e -> updateMergeState());
        mergeCheck.addActionListener(e -> updateMergeState());

        convertBtn.addActionListener(e -> startConvert());
        cancelBtn.addActionListener(e -> {
            cancelled.set(true);
            cancelCurrentEngine();
            status.setText("正在取消…");
        });

        ffLabel.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { promptFfmpeg(); }
        });
    }

    private void cancelCurrentEngine() {
        if (currentEngine != null && currentEngine.media() != null) currentEngine.media().cancel();
    }

    private void enableDragDrop() {
        list.setTransferHandler(new TransferHandler() {
            @Override public boolean canImport(TransferSupport s) {
                return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override public boolean importData(TransferSupport s) {
                try {
                    Transferable t = s.getTransferable();
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                    addFiles(files);
                    return true;
                } catch (Exception ex) {
                    return false;
                }
            }
        });
    }

    private File lastDir() {
        for (int i = model.size() - 1; i >= 0; i--) {
            InputEntry e = model.get(i);
            if (e.file != null) return e.file.getParentFile();
        }
        String d = Config.get("output.dir", "");
        return d.isBlank() ? null : Path.of(d).toFile();
    }

    private void addFiles(List<File> files) {
        boolean changed = false;
        for (File f : files) {
            if (f.isFile()) {
                model.addElement(InputEntry.ofFile(f));
                changed = true;
            }
        }
        if (changed) refreshAfterListChange();
    }

    private void refreshAfterListChange() {
        if (outField.getText().isBlank() && model.size() > 0 && model.get(0).file != null) {
            outField.setText(model.get(0).file.getParent());
        }
        refreshTargets();
        countLabel.setText("已添加 " + model.size() + " 个文件");
    }

    private void refreshTargets() {
        List<String> ts = FileKind.commonTargets(entries().stream().map(e -> e.kind).toList());
        String prev = (String) targetBox.getSelectedItem();
        if (ts.isEmpty()) {
            targetBox.setModel(new DefaultComboBoxModel<>(new Vector<>(List.of("—（所选文件类型不一致，请分批转换）—"))));
            targetBox.setEnabled(false);
        } else {
            targetBox.setModel(new DefaultComboBoxModel<>(new Vector<>(ts)));
            targetBox.setEnabled(true);
            int keep = ts.indexOf(prev);
            targetBox.setSelectedIndex(keep >= 0 ? keep : 0);
        }
        countLabel.setText("已添加 " + model.size() + " 个文件");
        updateMergeState();
    }

    private void updateMergeState() {
        String t = (String) targetBox.getSelectedItem();
        boolean ok = t != null && t.equals("pdf")
                && model.size() > 1
                && entries().stream().allMatch(e -> e.kind == FileKind.IMAGE);
        if (ok) {
            mergeCheck.setEnabled(true);
            mergeCheck.setText("合并这 " + model.size() + " 张图片为一个 PDF");
        } else {
            mergeCheck.setEnabled(false);
            mergeCheck.setSelected(false);
            mergeCheck.setText("多张图片合并为一个 PDF");
        }
    }

    private List<InputEntry> entries() {
        return Collections.list(model.elements());
    }

    private void setFfmpeg(FfmpegLocator.Result r) {
        if (r != null) {
            ffmpegPath = r.path();
            ffLabel.setText("FFmpeg ✓ " + r.how());
            ffLabel.setForeground(GREEN);
        } else {
            ffmpegPath = null;
            ffLabel.setText("FFmpeg 未检测到（点击设置）");
            ffLabel.setForeground(AMBER);
        }
    }

    private boolean promptFfmpeg() {
        if (ffmpegPath != null) return true;
        int ans = JOptionPane.showConfirmDialog(this,
                "未检测到 FFmpeg，无法转换音视频与图片格式。\n\n是否手动选择 ffmpeg.exe？\n（选“否”仍可转换 PDF / Word / Excel / 文本）",
                "FFmpeg", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (ans != JOptionPane.YES_OPTION) return false;
        JFileChooser fc = new JFileChooser("C:/");
        fc.setDialogTitle("选择 ffmpeg.exe");
        fc.setFileFilter(new FileNameExtensionFilter("ffmpeg.exe", "exe"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile();
            if (FfmpegLocator.works(f.getAbsolutePath())) {
                Config.set("ffmpeg.path", f.getAbsolutePath());
                setFfmpeg(new FfmpegLocator.Result(f.getAbsolutePath(), "手动设置"));
                append("FFmpeg 已就绪: " + f.getAbsolutePath(), attrHead);
                return true;
            }
            JOptionPane.showMessageDialog(this, "所选文件不是有效的 ffmpeg.exe");
        }
        return false;
    }

    private boolean needsFfmpeg(String target, List<InputEntry> es) {
        boolean anyMedia = es.stream().anyMatch(e -> e.kind == FileKind.VIDEO || e.kind == FileKind.AUDIO);
        boolean anyImage = es.stream().anyMatch(e -> e.kind == FileKind.IMAGE);
        boolean anySpecialImage = es.stream().anyMatch(e -> Set.of("webp", "ico", "heic").contains(e.ext));
        return switch (target) {
            case "docx", "txt", "csv", "xlsx" -> false;
            case "pdf" -> anySpecialImage;
            case "png", "jpg" -> anyMedia || anyImage;
            default -> true;
        };
    }

    // ------------------------------------------------------------------
    // 转换执行

    private void startConvert() {
        List<InputEntry> es = entries();
        if (es.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请先添加要转换的文件");
            return;
        }
        String target = (String) targetBox.getSelectedItem();
        if (target == null || target.startsWith("—")) {
            JOptionPane.showMessageDialog(this, "所选文件类型不一致，无法使用同一目标格式，请分批转换");
            return;
        }
        if (needsFfmpeg(target, es) && ffmpegPath == null && !promptFfmpeg()) return;

        Path outDir;
        String txt = outField.getText().trim();
        if (txt.isBlank()) {
            File f0 = es.get(0).file;
            outDir = f0 != null ? f0.getParentFile().toPath()
                    : Path.of(System.getProperty("user.home"), "Desktop", "转换输出");
        } else {
            outDir = Path.of(txt);
        }
        Config.set("output.dir", outDir.toString());

        String bitrate = ((String) bitrateBox.getSelectedItem()).split("（")[0];
        Options opt = new Options(bitrate, mergeCheck.isSelected(), cancelled);
        append("──────── 新任务：目标格式 ." + target + " ────────", attrHead);

        setBusy(true);
        cancelled.set(false);
        bar.setVisible(true);
        bar.setValue(0);

        Engine engine = new Engine(ffmpegPath);
        currentEngine = engine;
        Log uiLog = uiLog();
        Thread worker = new Thread(() -> {
            try {
                engine.convertAll(es, target, outDir, opt, uiLog);
                if (openCheck.isSelected() && Files.isDirectory(outDir)) {
                    Desktop.getDesktop().open(outDir.toFile());
                }
            } catch (Exception ex) {
                uiLog.error("任务中断: " + Engine.rootMsg(ex));
            } finally {
                SwingUtilities.invokeLater(() -> {
                    setBusy(false);
                    currentEngine = null;
                });
            }
        }, "convert-worker");
        worker.start();
    }

    private void setBusy(boolean busy) {
        convertBtn.setEnabled(!busy);
        addBtn.setEnabled(!busy);
        addUrlBtn.setEnabled(!busy);
        rmBtn.setEnabled(!busy);
        clrBtn.setEnabled(!busy);
        targetBox.setEnabled(!busy && !entries().isEmpty()
                && !FileKind.commonTargets(entries().stream().map(e -> e.kind).toList()).isEmpty());
        bitrateBox.setEnabled(!busy);
        outField.setEnabled(!busy);
        browseBtn.setEnabled(!busy);
        cancelBtn.setVisible(busy);
        bar.setVisible(busy);
        if (!busy) {
            bar.setIndeterminate(false);
            bar.setValue(0);
            status.setText("就绪");
        }
    }

    private Log uiLog() {
        return new Log() {
            @Override public void info(String msg) { append(msg, attrInfo); }

            @Override public void error(String msg) { append("× " + msg, attrErr); }

            @Override public void progress(double f) {
                SwingUtilities.invokeLater(() -> {
                    if (f < 0) {
                        bar.setIndeterminate(true);
                        return;
                    }
                    bar.setIndeterminate(false);
                    bar.setValue((int) Math.round(f * 1000));
                    bar.setString((int) Math.round(f * 100) + "%");
                });
            }

            @Override public void begin(String name) {
                append("▶ " + name, attrHead);
                SwingUtilities.invokeLater(() -> {
                    bar.setIndeterminate(false);
                    bar.setValue(0);
                    status.setText("正在转换: " + name);
                });
            }
        };
    }

    private void append(String msg, SimpleAttributeSet attrs) {
        SwingUtilities.invokeLater(() -> {
            StyledDocument doc = logPane.getStyledDocument();
            try {
                doc.insertString(doc.getLength(), msg + "\n", attrs);
                logPane.setCaretPosition(doc.getLength());
            } catch (Exception ignore) { }
        });
    }

    private static SimpleAttributeSet attr(Color c) {
        SimpleAttributeSet s = new SimpleAttributeSet();
        StyleConstants.setForeground(s, c);
        return s;
    }
}
