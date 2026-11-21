package ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared colours, fonts and small drawing helpers used by the plant viewer.
 */
final class Theme {
    private Theme() {}

    static final Color FLOOR = new Color(0xF1F3F6);
    static final Color GRID = new Color(0xE4E7EC);
    static final Color LANE = new Color(0xE3E7ED);
    static final Color AISLE = new Color(0xD5DAE1);
    static final Color HAZARD = new Color(0xF2C94C);
    static final Color BAY = new Color(0xE5F4EC);
    static final Color BAY_BORDER = new Color(0xA7D7BC);
    static final Color WAREHOUSE = new Color(0xD3D9E1);
    static final Color CRATE = new Color(0xC8A27A);
    static final Color CONVEYOR = new Color(0xB4BCC8);
    static final Color CARD = Color.WHITE;
    static final Color BORDER = new Color(0xCBD2D9);
    static final Color TEXT = new Color(0x1F2933);
    static final Color MUTED = new Color(0x7B8794);
    static final Color SHADOW = new Color(0, 0, 0, 28);

    static final Font TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 13);
    static final Font BODY = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    static final Font SMALL = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
    static final Font SMALL_BOLD = new Font(Font.SANS_SERIF, Font.BOLD, 10);
    static final Font HEADER = new Font(Font.SANS_SERIF, Font.BOLD, 11);
    static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 11);

    static final Color IDLE = new Color(0x9AA5B1);
    static final Color MOVING = new Color(0x3B82F6);
    static final Color BUSY = new Color(0xF59E0B);
    static final Color CHARGING = new Color(0x10B981);
    static final Color ACTIVE = new Color(0x6366F1);
    static final Color READY = new Color(0x14B8A6);
    static final Color DONE = new Color(0x22C55E);
    static final Color BROKEN = new Color(0xDC2626);

    private static final Color[] ITEM_COLOURS = {
        new Color(0xEF4444), new Color(0xF59E0B), new Color(0x84CC16), new Color(0x06B6D4),
        new Color(0x8B5CF6), new Color(0xEC4899), new Color(0x14B8A6), new Color(0xF97316),
        new Color(0x3B82F6), new Color(0xA16207)
    };

    static Color status(String status) {
        if (status == null) return IDLE;
        return switch (status) {
            case "moving" -> MOVING;
            case "loading", "unloading", "retrieving" -> BUSY;
            case "charging" -> CHARGING;
            case "active" -> ACTIVE;
            case "ready" -> READY;
            case "done" -> DONE;
            case "broken" -> BROKEN;
            default -> IDLE;
        };
    }

    static Color kind(ZonePanel.Kind kind) {
        return switch (kind) {
            case ACCESS -> new Color(0x0EA5E9);
            case WORKSTATION -> new Color(0x8B5CF6);
            case STORAGE -> new Color(0xF97316);
            case PACKAGING -> new Color(0xEC4899);
            case CHARGER -> CHARGING;
            default -> IDLE;
        };
    }

    /** A stable colour per item type, so the same item looks the same everywhere. */
    static Color item(String type) {
        return ITEM_COLOURS[Math.floorMod(type.hashCode(), ITEM_COLOURS.length)];
    }

    static Color lighten(Color c, double amount) {
        return new Color(
            (int) (c.getRed() + (255 - c.getRed()) * amount),
            (int) (c.getGreen() + (255 - c.getGreen()) * amount),
            (int) (c.getBlue() + (255 - c.getBlue()) * amount));
    }

    static Color alpha(Color c, double alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.max(0, Math.min(255, alpha * 255)));
    }

    static void antialias(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    static boolean isNone(String value) {
        return value == null || value.isEmpty() || value.equals("none") || value.equals("empty");
    }

    /** Parses the {@code List.toString()} format used by the artifacts, e.g. "[apples, pastry]". */
    static List<String> parseList(String value) {
        List<String> list = new ArrayList<>();
        if (value == null) return list;
        String s = value.trim();
        if (s.startsWith("[")) s = s.substring(1);
        if (s.endsWith("]")) s = s.substring(0, s.length() - 1);
        for (String part : s.split(",")) {
            if (!part.isBlank()) list.add(part.trim());
        }
        return list;
    }

    static int baseline(FontMetrics fm, int top, int height) {
        return top + (height - fm.getHeight()) / 2 + fm.getAscent();
    }

    static int pillWidth(Graphics2D g, String text) {
        return g.getFontMetrics(SMALL_BOLD).stringWidth(text) + 12;
    }

    /** Draws a rounded status pill with its top-left corner at (x, y); returns its width. */
    static int pill(Graphics2D g, String text, int x, int y, Color bg) {
        g.setFont(SMALL_BOLD);
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(text) + 12, h = 16;
        g.setColor(bg);
        g.fillRoundRect(x, y, w, h, h, h);
        g.setColor(Color.WHITE);
        g.drawString(text, x + 6, baseline(fm, y, h));
        return w;
    }

    /**
     * Draws an item chip with its top-left corner at (x, y); returns its width. A chip that is not
     * {@code present} is drawn dashed, meaning the item is expected but not there yet.
     */
    static int chip(Graphics2D g, String text, int x, int y, boolean present) {
        Color c = item(text);
        g.setFont(SMALL);
        FontMetrics fm = g.getFontMetrics();
        int h = 18, w = 5 + 8 + 5 + fm.stringWidth(text) + 7;
        if (present) {
            g.setColor(lighten(c, 0.8));
            g.fillRoundRect(x, y, w, h, 8, 8);
            g.setColor(c.darker());
            g.drawRoundRect(x, y, w, h, 8, 8);
            g.setColor(c);
            g.fillRect(x + 5, y + 5, 8, 8);
            g.setColor(TEXT);
        } else {
            Stroke old = g.getStroke();
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[] {3f, 3f}, 0f));
            g.setColor(MUTED);
            g.drawRoundRect(x, y, w, h, 8, 8);
            g.setStroke(old);
            g.setColor(lighten(c, 0.3));
            g.drawRect(x + 5, y + 5, 7, 7);
            g.setColor(MUTED);
        }
        g.drawString(text, x + 18, baseline(fm, y, h));
        return w;
    }

    static void energyBar(Graphics2D g, int x, int y, int w, int h, Integer energy, int max) {
        g.setColor(new Color(0xE5E7EB));
        g.fillRoundRect(x, y, w, h, h, h);
        if (energy != null) {
            double frac = Math.max(0, Math.min(1, energy / (double) max));
            g.setColor(frac > 0.5 ? DONE : frac > 0.25 ? BUSY : new Color(0xEF4444));
            g.fillRoundRect(x, y, (int) Math.round(w * frac), h, h, h);
        }
        g.setColor(new Color(0, 0, 0, 40));
        g.drawRoundRect(x, y, w, h, h, h);
    }

    static void bolt(Graphics2D g, double x, double y, double size, Color c) {
        double[][] pts = {{0.60, 0}, {0.15, 0.58}, {0.45, 0.58}, {0.35, 1}, {0.85, 0.40}, {0.55, 0.40}, {0.72, 0}};
        Path2D.Double p = new Path2D.Double();
        p.moveTo(x + pts[0][0] * size, y + pts[0][1] * size);
        for (int i = 1; i < pts.length; i++) p.lineTo(x + pts[i][0] * size, y + pts[i][1] * size);
        p.closePath();
        g.setColor(c);
        g.fill(p);
    }
}
