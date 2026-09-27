import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.TextAttribute;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Simple Radio 채널 아트 생성기 (Java2D만 사용, 외부 의존성 없음).
 *
 * 600×600 PNG 다섯 장을 만든다. 팔레트는 앱의 YouTube Music 스타일 다크 테마
 * (app/.../ui/theme/Color.kt)와 같다: 타일 #212121 + #282828→#0F0F0F 대각 그라데이션,
 * 흰색 그래픽, 강조색은 #FF0033 하나(타일당 한 번), 보조 텍스트 #AAAAAA.
 * 방송사 로고/상표는 그리지 않고 타이포 + 단순 도형만 쓴다.
 *
 * 다섯 장이 한 가족으로 보이도록 같은 그리드를 쓴다:
 *   - 모티프: 상단(우측 영역)
 *   - 워드마크: 좌하단, 같은 기준선·같은 왼쪽 여백
 *   - 캡션: 워드마크 아래 좌하단
 *
 * 사용법: java ChannelArt <출력 디렉터리>... [-sheet <contact_sheet.png>] [-font <파일.ttf>]
 */
public final class ChannelArt {
    static final int S = 600;          // 타일 한 변
    static final int PAD = 48;         // 좌우 여백
    static final int RADIUS = 72;      // 모서리 반지름(12%)
    static final int WORD_BASELINE = 466;
    static final int CAPTION_BASELINE = 524;
    static final int MAX_WORD_WIDTH = S - PAD * 2;

    static final Color APP_BG = new Color(0x0F0F0F);       // 타일 바깥(모서리) — 앱 배경색과 동일
    static final Color TILE = new Color(0x212121);
    static final Color GRAD_HI = new Color(0x2A2A2A);
    static final Color GRAD_LO = new Color(0x0F0F0F);
    static final Color GRAD_SOFT_HI = new Color(0x272727);  // 발라드: 대비를 줄인 부드러운 그라데이션
    static final Color GRAD_SOFT_LO = new Color(0x161616);
    static final Color WHITE = new Color(0xFFFFFF);
    static final Color RED = new Color(0xFF0033);
    static final Color GRAY = new Color(0xAAAAAA);

    static Font baseFont;

    interface Motif { void draw(Graphics2D g); }

    record Tile(String file, String label, String word, String caption, boolean soft, Motif motif) {}

    static final List<Tile> TILES = List.of(
        new Tile("art_ch_kbs", "1 · KBS Cool FM", "COOL", "KBS COOL FM", false, ChannelArt::radioWaves),
        new Tile("art_ch_sbs", "2 · SBS 파워FM", "POWER FM", "SBS POWER FM", false, ChannelArt::lightning),
        new Tile("art_ch_newhit", "3 · KPOP NEW HIT", "NEW HIT", "24/7 K-POP", false, ChannelArt::starBurst),
        new Tile("art_ch_ballad", "4 · KPOP BALLAD", "BALLAD", "24/7 K-POP", true, ChannelArt::moonHeart),
        new Tile("art_ch_8090", "5 · KPOP 8090 HIT", "8090", "HIT · 24/7", false, ChannelArt::cassette)
    );

    public static void main(String[] args) throws Exception {
        List<File> outDirs = new ArrayList<>();
        File sheet = null;
        String fontPath = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-sheet" -> sheet = new File(args[++i]);
                case "-font" -> fontPath = args[++i];
                default -> outDirs.add(new File(args[i]));
            }
        }
        if (outDirs.isEmpty() && sheet == null) {
            System.err.println("usage: java ChannelArt <outDir>... [-sheet <file.png>] [-font <file.ttf>]");
            System.exit(2);
        }
        baseFont = pickFont(fontPath);
        System.out.println("font: " + baseFont.getFontName());

        List<BufferedImage> rendered = new ArrayList<>();
        for (Tile t : TILES) {
            BufferedImage img = render(t);
            rendered.add(img);
            for (File dir : outDirs) {
                dir.mkdirs();
                File f = new File(dir, t.file() + ".png");
                ImageIO.write(img, "png", f);
                System.out.println("wrote " + f);
            }
        }
        if (sheet != null) {
            sheet.getAbsoluteFile().getParentFile().mkdirs();
            ImageIO.write(contactSheet(rendered), "png", sheet);
            System.out.println("wrote " + sheet);
        }
    }

    // Roboto가 있으면 Roboto, 없으면 시스템 산세리프(macOS: Helvetica Neue) 볼드.
    static Font pickFont(String ttfPath) throws Exception {
        if (ttfPath != null) {
            Font f = Font.createFont(Font.TRUETYPE_FONT, new File(ttfPath)).deriveFont(100f);
            return f.isBold() ? f : f.deriveFont(Font.BOLD);
        }
        Set<String> families = new HashSet<>(List.of(
            GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String cand : List.of("Roboto", "Helvetica Neue", "Helvetica", "Arial")) {
            if (families.contains(cand)) return new Font(cand, Font.BOLD, 100);
        }
        return new Font(Font.SANS_SERIF, Font.BOLD, 100);
    }

    static Graphics2D graphics(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    static BufferedImage render(Tile t) {
        BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(img);

        // 모서리 바깥은 앱 배경색으로 불투명하게 채운다. 알파가 있으면 서비스가 JPEG로 축소해
        // 차량/블루투스에 보낼 때 검게 깨질 수 있어 처음부터 불투명 PNG로 만든다.
        g.setColor(APP_BG);
        g.fillRect(0, 0, S, S);

        Shape tile = new RoundRectangle2D.Float(0, 0, S, S, RADIUS * 2, RADIUS * 2);
        g.setColor(TILE);
        g.fill(tile);
        g.setPaint(new GradientPaint(0, 0, t.soft() ? GRAD_SOFT_HI : GRAD_HI, S, S, t.soft() ? GRAD_SOFT_LO : GRAD_LO));
        g.fill(tile);
        g.setClip(tile);
        // 우상단에 아주 옅은 방사형 하이라이트로 깊이감을 준다.
        g.setPaint(new RadialGradientPaint(new Point2D.Float(440, 110), 380,
            new float[] {0f, 1f},
            new Color[] {new Color(255, 255, 255, t.soft() ? 12 : 20), new Color(255, 255, 255, 0)}));
        g.fillRect(0, 0, S, S);

        t.motif().draw(g);
        drawWordmark(g, t.word());
        drawCaption(g, t.caption());

        g.dispose();
        return img;
    }

    // 워드마크: 좌하단, 최대 128px, 폭이 넘치면 줄인다(모든 타일이 같은 왼쪽 여백·기준선).
    static void drawWordmark(Graphics2D g, String text) {
        Map<TextAttribute, Object> attrs = new HashMap<>();
        attrs.put(TextAttribute.TRACKING, -0.025f);
        Font f = baseFont.deriveFont(128f).deriveFont(attrs);
        double w = f.getStringBounds(text, g.getFontRenderContext()).getWidth();
        if (w > MAX_WORD_WIDTH) f = f.deriveFont((float) (128.0 * MAX_WORD_WIDTH / w));
        g.setFont(f);
        g.setColor(WHITE);
        g.drawString(text, PAD, WORD_BASELINE);
    }

    static void drawCaption(Graphics2D g, String text) {
        Map<TextAttribute, Object> attrs = new HashMap<>();
        attrs.put(TextAttribute.TRACKING, 0.16f);
        g.setFont(baseFont.deriveFont(26f).deriveFont(attrs));
        g.setColor(GRAY);
        g.drawString(text, PAD, CAPTION_BASELINE);
    }

    static BasicStroke stroke(float w) {
        return new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    }

    // ---- 모티프 (상단 우측 영역: 대략 x 220..552, y 56..330) ----

    // 1 KBS Cool FM: 빨간 점에서 퍼져 나가는 전파 호 세 개.
    static void radioWaves(Graphics2D g) {
        float cx = 262, cy = 306;
        g.setColor(WHITE);
        g.setStroke(stroke(16f));
        for (int r : new int[] {84, 152, 220}) {
            g.draw(new Arc2D.Float(cx - r, cy - r, r * 2, r * 2, 6, 78, Arc2D.OPEN));
        }
        g.setColor(RED);
        g.fill(new Ellipse2D.Float(cx - 18, cy - 18, 36, 36));
    }

    // 2 SBS 파워FM: 흰 링 안의 빨간 번개.
    static void lightning(Graphics2D g) {
        float cx = 420, cy = 190;
        g.setColor(WHITE);
        g.setStroke(stroke(12f));
        g.draw(new Ellipse2D.Float(cx - 118, cy - 118, 236, 236));
        Path2D.Float bolt = new Path2D.Float();
        float[][] pts = {{22, -112}, {-52, 14}, {-2, 14}, {-26, 112}, {56, -22}, {6, -22}, {36, -112}};
        for (int i = 0; i < pts.length; i++) {
            if (i == 0) bolt.moveTo(cx + pts[i][0], cy + pts[i][1]);
            else bolt.lineTo(cx + pts[i][0], cy + pts[i][1]);
        }
        bolt.closePath();
        g.setColor(RED);
        g.fill(bolt);
    }

    // 3 KPOP NEW HIT: 흰 방사선 + 빨간 4각 스파크.
    static void starBurst(Graphics2D g) {
        float cx = 420, cy = 190;
        g.setColor(WHITE);
        g.setStroke(stroke(9f));
        for (int i = 0; i < 16; i++) {
            double a = Math.toRadians(i * 22.5);
            float r0 = (i % 2 == 0) ? 96 : 104;
            float r1 = (i % 2 == 0) ? 138 : 118;
            g.draw(new Line2D.Double(cx + Math.cos(a) * r0, cy + Math.sin(a) * r0,
                cx + Math.cos(a) * r1, cy + Math.sin(a) * r1));
        }
        g.setColor(RED);
        g.fill(sparkle(cx, cy, 78, 0.16f));
    }

    static Shape sparkle(float cx, float cy, float r, float pinch) {
        float k = r * pinch;
        Path2D.Float p = new Path2D.Float();
        p.moveTo(cx, cy - r);
        p.quadTo(cx + k, cy - k, cx + r, cy);
        p.quadTo(cx + k, cy + k, cx, cy + r);
        p.quadTo(cx - k, cy + k, cx - r, cy);
        p.quadTo(cx - k, cy - k, cx, cy - r);
        p.closePath();
        return p;
    }

    // 4 KPOP BALLAD: 흰 초승달 선화 + 빨간 작은 하트.
    static void moonHeart(Graphics2D g) {
        float cx = 400, cy = 186;
        Area moon = new Area(new Ellipse2D.Float(cx - 104, cy - 104, 208, 208));
        moon.subtract(new Area(new Ellipse2D.Float(cx - 104 + 62, cy - 104 - 40, 208, 208)));
        g.setColor(WHITE);
        g.setStroke(stroke(12f));
        g.draw(moon);
        g.setColor(RED);
        g.fill(heart(474, 262, 46));
    }

    static Shape heart(float cx, float cy, float size) {
        float w = size, h = size;
        Path2D.Float p = new Path2D.Float();
        p.moveTo(cx, cy + h * 0.55f);
        p.curveTo(cx - w * 0.95f, cy - h * 0.05f, cx - w * 0.55f, cy - h * 0.75f, cx, cy - h * 0.28f);
        p.curveTo(cx + w * 0.55f, cy - h * 0.75f, cx + w * 0.95f, cy - h * 0.05f, cx, cy + h * 0.55f);
        p.closePath();
        return p;
    }

    // 5 KPOP 8090 HIT: 카세트테이프 선화, 릴 사이 테이프만 빨강.
    static void cassette(Graphics2D g) {
        float x = 232, y = 92, w = 312, h = 196;
        g.setColor(WHITE);
        g.setStroke(stroke(10f));
        g.draw(new RoundRectangle2D.Float(x, y, w, h, 36, 36));
        // 창
        float wx = x + 52, wy = y + 44, ww = w - 104, wh = 78;
        g.draw(new RoundRectangle2D.Float(wx, wy, ww, wh, 24, 24));
        // 릴
        float ry = wy + wh / 2, r = 24;
        float lx = wx + 58, rx = wx + ww - 58;
        g.setStroke(stroke(8f));
        g.draw(new Ellipse2D.Float(lx - r, ry - r, r * 2, r * 2));
        g.draw(new Ellipse2D.Float(rx - r, ry - r, r * 2, r * 2));
        g.fill(new Ellipse2D.Float(lx - 6, ry - 6, 12, 12));
        g.fill(new Ellipse2D.Float(rx - 6, ry - 6, 12, 12));
        // 릴 사이 테이프(강조색)
        g.setColor(RED);
        g.setStroke(stroke(10f));
        g.draw(new Line2D.Float(lx + r + 6, ry, rx - r - 6, ry));
        // 하단 헤드 가드
        g.setColor(WHITE);
        g.setStroke(stroke(10f));
        Path2D.Float guard = new Path2D.Float();
        guard.moveTo(x + 66, y + h);
        guard.lineTo(x + 84, y + h - 38);
        guard.lineTo(x + w - 84, y + h - 38);
        guard.lineTo(x + w - 66, y + h);
        g.draw(guard);
        // 라벨 구멍 두 개
        g.fill(new Ellipse2D.Float(x + 28, y + h - 30, 10, 10));
        g.fill(new Ellipse2D.Float(x + w - 38, y + h - 30, 10, 10));
    }

    // ---- 검수용 컨택트 시트 ----
    static BufferedImage contactSheet(List<BufferedImage> tiles) {
        int tile = 260, gap = 24, pad = 32, labelH = 40;
        int w = pad * 2 + tiles.size() * tile + (tiles.size() - 1) * gap;
        int h = pad * 2 + tile + labelH;
        BufferedImage sheet = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = graphics(sheet);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(APP_BG);
        g.fillRect(0, 0, w, h);
        g.setFont(baseFont.deriveFont(Font.PLAIN, 17f));
        FontMetrics fm = g.getFontMetrics();
        for (int i = 0; i < tiles.size(); i++) {
            int x = pad + i * (tile + gap);
            g.drawImage(tiles.get(i), x, pad, tile, tile, null);
            String label = TILES.get(i).label();
            g.setColor(GRAY);
            g.drawString(label, x + (tile - fm.stringWidth(label)) / 2f, pad + tile + 28);
        }
        g.dispose();
        return sheet;
    }
}
