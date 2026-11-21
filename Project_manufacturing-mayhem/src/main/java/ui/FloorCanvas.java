package ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.JComponent;
import javax.swing.Timer;

import ui.ZonePanel.Kind;

/**
 * Draws a representative (not to scale) factory floor. A warehouse on the left feeds the access
 * points by conveyor, then come columns for workstations, storage and packaging. A main aisle runs
 * across the floor and the charging bay is along the bottom.
 *
 * Each location has a docking pad. Robots drive from their pad along the lane beside their
 * column, along the main aisle, and up or down the lane to the next pad.
 */
class FloorCanvas extends JComponent {
    private static final int MARGIN = 16;
    private static final int WAREHOUSE_W = 96;
    private static final int AISLE_H = 40;
    private static final int BAY_H = 112;
    private static final int HEADER_H = 30;
    private static final int LANE_W = 46;
    private static final int PAD_R = 18;
    private static final int ROBOT_SIZE = 30;
    private static final int BAY_LABEL_W = 110;
    private static final Kind[] FLOOR_ORDER = {Kind.ACCESS, Kind.WORKSTATION, Kind.STORAGE, Kind.PACKAGING, Kind.OTHER};
    private static final String MOVING = "moving";

    private final PlantViewer viewer;
    private final Map<String, Mover> movers = new HashMap<>();
    private Layout layout = new Layout();
    private long lastTick = System.nanoTime();

    /** Where a robot is drawn, and the waypoints it still has to drive through. */
    private static class Mover {
        boolean placed;
        double x, y;
        double speed;
        String at;
        String to;
        String lastStatus;
        final Deque<Point2D.Double> path = new ArrayDeque<>();
    }

    private static class Layout {
        int w = -1, h = -1;
        Rectangle warehouse, floor, bay;
        int aisleTop;
        double aisleY;
        final Map<Kind, Rectangle> columns = new LinkedHashMap<>();
        final Map<ZonePanel, Rectangle> cards = new LinkedHashMap<>();
        final Map<String, Point2D.Double> docks = new HashMap<>();
    }

    FloorCanvas(PlantViewer viewer) {
        this.viewer = viewer;
        setPreferredSize(new Dimension(900, 640));
        setOpaque(true);
        new Timer(33, e -> tick()).start();
    }

    // ------------------------------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------------------------------

    private Layout computeLayout() {
        Layout l = new Layout();
        l.w = getWidth();
        l.h = getHeight();
        int floorBottom = Math.max(MARGIN + HEADER_H + 60, l.h - MARGIN - BAY_H - AISLE_H);
        l.aisleTop = floorBottom;
        l.aisleY = floorBottom + AISLE_H / 2.0;
        l.warehouse = new Rectangle(MARGIN, MARGIN + HEADER_H, WAREHOUSE_W, floorBottom - MARGIN - HEADER_H - 12);
        int fx = MARGIN + WAREHOUSE_W + MARGIN;
        l.floor = new Rectangle(fx, MARGIN, Math.max(100, l.w - fx - MARGIN), floorBottom - MARGIN);
        l.bay = new Rectangle(MARGIN, floorBottom + AISLE_H + 6, Math.max(100, l.w - 2 * MARGIN), BAY_H - 6);

        Map<Kind, List<ZonePanel>> groups = new EnumMap<>(Kind.class);
        for (ZonePanel z : viewer.zones()) groups.computeIfAbsent(z.kind(), k -> new ArrayList<>()).add(z);

        List<Kind> kinds = new ArrayList<>();
        for (Kind k : FLOOR_ORDER) if (groups.containsKey(k)) kinds.add(k);
        if (!kinds.isEmpty()) {
            int colW = l.floor.width / kinds.size();
            for (int i = 0; i < kinds.size(); i++) {
                Rectangle col = new Rectangle(l.floor.x + i * colW, l.floor.y, colW, l.floor.height);
                l.columns.put(kinds.get(i), col);
                List<ZonePanel> zs = groups.get(kinds.get(i));
                int slotH = (col.height - HEADER_H) / zs.size();
                int cardW = Math.max(60, Math.min(col.width - LANE_W - 14, 240));
                for (int j = 0; j < zs.size(); j++) {
                    ZonePanel z = zs.get(j);
                    int cardH = Math.max(34, Math.min(slotH - 12, preferredCardHeight(z)));
                    int cy = col.y + HEADER_H + slotH * j + slotH / 2;
                    l.cards.put(z, new Rectangle(col.x + LANE_W, cy - cardH / 2, cardW, cardH));
                    l.docks.put(z.name(), new Point2D.Double(col.x + LANE_W / 2.0, cy));
                }
            }
        }

        List<ZonePanel> chargers = groups.getOrDefault(Kind.CHARGER, List.of());
        if (!chargers.isEmpty()) {
            int slotW = (l.bay.width - BAY_LABEL_W) / chargers.size();
            int tileW = Math.max(60, Math.min(160, slotW - 16));
            for (int i = 0; i < chargers.size(); i++) {
                ZonePanel z = chargers.get(i);
                int cx = l.bay.x + BAY_LABEL_W + slotW * i + slotW / 2;
                l.cards.put(z, new Rectangle(cx - tileW / 2, l.bay.y + 46, tileW, l.bay.height - 54));
                l.docks.put(z.name(), new Point2D.Double(cx, l.bay.y + 24));
            }
        }
        return l;
    }

    private static int preferredCardHeight(ZonePanel z) {
        return switch (z.kind()) {
            case WORKSTATION -> 90;
            case ACCESS, STORAGE, PACKAGING -> 62;
            default -> 36 + 16 * z.fields().size();
        };
    }

    // ------------------------------------------------------------------------------------------
    // Animation
    // ------------------------------------------------------------------------------------------

    private void tick() {
        long now = System.nanoTime();
        double dt = (now - lastTick) / 1e9;
        lastTick = now;
        layout = computeLayout();

        for (RobotPanel robot : viewer.robots()) {
            Mover m = movers.computeIfAbsent(robot.name(), k -> new Mover());
            String at = robot.field("at");
            String to = robot.field("to");
            boolean heading = to != null && !Theme.isNone(to);
            String status = robot.status();
            Point2D.Double dock = layout.docks.get(at);
            if (dock == null) {
                m.lastStatus = status;
                continue;
            }

            if (!m.placed) {
                m.x = dock.x;
                m.y = dock.y;
                m.at = at;
                m.placed = true;
            } else if (heading && !to.equals(m.to)) {
                Point2D.Double target = layout.docks.get(to);
                if (target != null) {
                    routeTo(m, target, moveSeconds(robot));   // drive for the whole move
                    m.at = to;                               // so arrival doesn't re-route
                }
                m.to = to;
            } else if (!heading && !at.equals(m.at)) {
                routeTo(m, dock, 0);
                m.at = at;
            } else if (MOVING.equals(status) && !MOVING.equals(m.lastStatus) && m.path.isEmpty()) {
                // The destination is only reported on arrival, so pull out into the aisle and wait.
                m.path.add(new Point2D.Double(m.x, layout.aisleY));
                m.speed = 120;
            }

            advance(m, dt);
            if (m.path.isEmpty() && !MOVING.equals(status)) {
                // Parked: keep it on its pad, even if the window has been resized.
                m.x = dock.x;
                m.y = dock.y;
            }
            if (!heading) m.to = null;
            m.lastStatus = status;
        }
        repaint();
    }

    private void routeTo(Mover m, Point2D.Double dock, double seconds) {
        m.path.clear();
        if (Math.abs(m.x - dock.x) > 1) {
            if (Math.abs(m.y - layout.aisleY) > 1) m.path.add(new Point2D.Double(m.x, layout.aisleY));
            m.path.add(new Point2D.Double(dock.x, layout.aisleY));
        }
        m.path.add(new Point2D.Double(dock.x, dock.y));

        double length = 0, px = m.x, py = m.y;
        for (Point2D.Double p : m.path) {
            length += Math.hypot(p.x - px, p.y - py);
            px = p.x;
            py = p.y;
        }
        m.speed = seconds > 0 ? length / seconds : Math.max(220, length / 1.4);
    }

    private static double moveSeconds(RobotPanel robot) {
        try {
            return Long.parseLong(robot.field("moveMillis")) / 1000.0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static void advance(Mover m, double dt) {
        double step = m.speed * dt;
        while (step > 0 && !m.path.isEmpty()) {
            Point2D.Double p = m.path.peekFirst();
            double dx = p.x - m.x, dy = p.y - m.y, d = Math.hypot(dx, dy);
            if (d <= step) {
                m.x = p.x;
                m.y = p.y;
                step -= d;
                m.path.removeFirst();
            } else {
                m.x += dx / d * step;
                m.y += dy / d * step;
                step = 0;
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Painting
    // ------------------------------------------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g0) {
        if (layout.w != getWidth() || layout.h != getHeight()) layout = computeLayout();
        Layout l = layout;
        Graphics2D g = (Graphics2D) g0.create();
        Theme.antialias(g);
        long now = System.currentTimeMillis();

        paintFloor(g, l);
        paintWarehouse(g, l);
        paintColumns(g, l);
        paintAisle(g, l);
        paintBay(g, l);

        for (Map.Entry<ZonePanel, Rectangle> e : l.cards.entrySet()) {
            ZonePanel z = e.getKey();
            Point2D.Double dock = l.docks.get(z.name());
            paintPad(g, dock, z.occupant() != null);
            if (z.kind() == Kind.CHARGER) paintCharger(g, z, e.getValue(), now);
            else paintCard(g, z, e.getValue(), now);
        }

        for (RobotPanel robot : viewer.robots()) {
            Mover m = movers.get(robot.name());
            if (m != null && m.placed) paintRobot(g, robot, m, now);
        }

        if (viewer.zones().isEmpty()) {
            g.setFont(Theme.BODY);
            g.setColor(Theme.MUTED);
            String msg = "Waiting for the plant to be set up…";
            FontMetrics fm = g.getFontMetrics();
            g.drawString(msg, (l.w - fm.stringWidth(msg)) / 2, l.h / 2);
        }
        g.dispose();
    }

    private void paintFloor(Graphics2D g, Layout l) {
        g.setColor(Theme.FLOOR);
        g.fillRect(0, 0, l.w, l.h);
        g.setColor(Theme.GRID);
        for (int x = 0; x < l.w; x += 24) g.drawLine(x, 0, x, l.aisleTop);
        for (int y = 0; y < l.aisleTop; y += 24) g.drawLine(0, y, l.w, y);
    }

    private void paintWarehouse(Graphics2D g, Layout l) {
        Rectangle r = l.warehouse;
        header(g, "WAREHOUSE", r.x, MARGIN, Theme.MUTED);

        // Conveyors from the warehouse to each access point.
        for (Map.Entry<ZonePanel, Rectangle> e : l.cards.entrySet()) {
            if (e.getKey().kind() != Kind.ACCESS) continue;
            Rectangle card = e.getValue();
            int y = card.y + 8;
            int x0 = r.x + r.width, x1 = card.x;
            g.setColor(Theme.CONVEYOR);
            g.fillRect(x0, y, x1 - x0, 10);
            g.setColor(Theme.CONVEYOR.darker());
            for (int x = x0 + 3; x < x1; x += 7) g.drawLine(x, y + 1, x, y + 9);
            if ("retrieving".equals(e.getKey().status())) {
                // a crate travelling along the conveyor
                double t = (System.currentTimeMillis() % 1200) / 1200.0;
                int cx = (int) (x0 + (x1 - x0 - 10) * t);
                g.setColor(Theme.CRATE);
                g.fillRect(cx, y - 2, 10, 10);
                g.setColor(Theme.CRATE.darker());
                g.drawRect(cx, y - 2, 10, 10);
            }
        }

        g.setColor(Theme.WAREHOUSE);
        g.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
        g.setColor(Theme.BORDER.darker());
        g.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10);
        // shelving with crates
        int shelf = 0;
        for (int y = r.y + 22; y < r.y + r.height - 6; y += 22, shelf++) {
            g.setColor(Theme.BORDER.darker());
            g.drawLine(r.x + 6, y, r.x + r.width - 6, y);
            for (int x = r.x + 10, i = 0; x + 14 < r.x + r.width - 6; x += 18, i++) {
                if ((i + shelf) % 3 == 2) continue;
                g.setColor(Theme.CRATE);
                g.fillRect(x, y - 13, 14, 13);
                g.setColor(Theme.CRATE.darker());
                g.drawRect(x, y - 13, 14, 13);
            }
        }
    }

    private void paintColumns(Graphics2D g, Layout l) {
        boolean first = true;
        for (Map.Entry<Kind, Rectangle> e : l.columns.entrySet()) {
            Rectangle col = e.getValue();
            // the lane robots drive along
            g.setColor(Theme.LANE);
            g.fillRect(col.x + 6, col.y + HEADER_H - 4, LANE_W - 12, l.aisleTop - col.y - HEADER_H + 4);
            if (!first) {
                Stroke old = g.getStroke();
                g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[] {4f, 4f}, 0f));
                g.setColor(Theme.BORDER);
                g.drawLine(col.x, col.y, col.x, l.aisleTop - 4);
                g.setStroke(old);
            }
            header(g, e.getKey().label.toUpperCase(), col.x + 8, col.y, Theme.kind(e.getKey()));
            first = false;
        }
    }

    private void paintAisle(Graphics2D g, Layout l) {
        g.setColor(Theme.AISLE);
        g.fillRect(0, l.aisleTop, l.w, AISLE_H);
        Stroke old = g.getStroke();
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[] {14f, 10f}, 0f));
        g.setColor(Theme.HAZARD);
        g.drawLine(0, l.aisleTop + 2, l.w, l.aisleTop + 2);
        g.drawLine(0, l.aisleTop + AISLE_H - 2, l.w, l.aisleTop + AISLE_H - 2);
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[] {8f, 8f}, 0f));
        g.setColor(Color.WHITE);
        g.drawLine(0, (int) l.aisleY, l.w, (int) l.aisleY);
        g.setStroke(old);
    }

    private void paintBay(Graphics2D g, Layout l) {
        Rectangle b = l.bay;
        g.setColor(Theme.BAY);
        g.fillRoundRect(b.x, b.y, b.width, b.height, 12, 12);
        g.setColor(Theme.BAY_BORDER);
        g.drawRoundRect(b.x, b.y, b.width, b.height, 12, 12);
        Theme.bolt(g, b.x + 14, b.y + b.height / 2.0 - 22, 26, Theme.CHARGING);
        g.setFont(Theme.HEADER);
        g.setColor(Theme.CHARGING.darker());
        g.drawString("CHARGING", b.x + 42, b.y + b.height / 2 - 4);
        g.drawString("BAY", b.x + 42, b.y + b.height / 2 + 10);
    }

    private void paintPad(Graphics2D g, Point2D.Double p, boolean occupied) {
        int x = (int) p.x - PAD_R, y = (int) p.y - PAD_R, d = PAD_R * 2;
        g.setColor(occupied ? new Color(0xFFFFFF) : Theme.alpha(Color.WHITE, 0.6));
        g.fillOval(x, y, d, d);
        Stroke old = g.getStroke();
        g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[] {4f, 3f}, 0f));
        g.setColor(Theme.MUTED);
        g.drawOval(x, y, d, d);
        g.setStroke(old);
    }

    private void paintCard(Graphics2D g, ZonePanel z, Rectangle r, long now) {
        Color accent = Theme.kind(z.kind());
        RoundRectangle2D shape = new RoundRectangle2D.Double(r.x, r.y, r.width, r.height, 12, 12);

        g.setColor(Theme.SHADOW);
        g.fillRoundRect(r.x + 1, r.y + 2, r.width, r.height, 12, 12);
        g.setColor(Theme.CARD);
        g.fill(shape);
        long age = now - z.lastChange();
        if (age < 900) {
            g.setColor(Theme.alpha(accent, 0.22 * (1 - age / 900.0)));
            g.fill(shape);
        }

        Shape oldClip = g.getClip();
        g.clip(shape);
        g.setColor(accent);
        g.fillRect(r.x, r.y, 5, r.height);

        String status = z.status();
        int pillX = r.x + r.width;
        if (z.kind() == Kind.WORKSTATION || !"idle".equals(status)) {
            pillX = r.x + r.width - 8 - Theme.pillWidth(g, status);
            Theme.pill(g, status, pillX, r.y + 7, Theme.status(status));
        }
        g.setFont(Theme.TITLE);
        g.setColor(Theme.TEXT);
        g.drawString(z.name(), r.x + 13, r.y + 20);

        int x = r.x + 13, y = r.y + 32;
        switch (z.kind()) {
            case ACCESS, STORAGE -> paintHolding(g, z, x, y, now);
            case WORKSTATION -> paintWorkstation(g, z, r, x, y, now);
            case PACKAGING -> paintPackaging(g, z, x, y, r.x + r.width - 8);
            default -> paintFields(g, z, x, y);
        }
        g.setClip(oldClip);
        g.setColor(Theme.BORDER);
        g.draw(shape);
    }

    private void paintHolding(Graphics2D g, ZonePanel z, int x, int y, long now) {
        String item = z.field("available");
        if ("retrieving".equals(z.status())) {
            label(g, "fetching from warehouse" + ".".repeat((int) (now / 300 % 4)), x, y);
        } else if (Theme.isNone(item)) {
            label(g, "empty", x, y);
        } else {
            Theme.chip(g, item, x, y, true);
        }
    }

    private void paintWorkstation(Graphics2D g, ZonePanel z, Rectangle r, int x, int y, long now) {
        List<String> inputs = Theme.parseList(z.field("inputs"));
        String output = z.field("outputs");
        if (inputs.isEmpty() && Theme.isNone(output)) {
            label(g, "not configured", x, y);
            return;
        }
        List<String> needed = Theme.parseList(z.field("inputsNeeded"));

        int cx = x;
        for (String input : inputs) {
            boolean loaded = !needed.remove(input);
            cx += Theme.chip(g, input, cx, y, loaded) + 4;
        }

        int y2 = y + 26;
        g.setFont(Theme.BODY);
        g.setColor(Theme.MUTED);
        g.drawString("→", x, y2 + 14);
        if (!Theme.isNone(output)) Theme.chip(g, output, x + 18, y2, !Theme.isNone(z.field("available")));

        if ("active".equals(z.status())) {
            int s = 18, sx = r.x + r.width - s - 10, sy = r.y + r.height - s - 8;
            Stroke old = g.getStroke();
            g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Theme.alpha(Theme.ACTIVE, 0.2));
            g.drawOval(sx, sy, s, s);
            g.setColor(Theme.ACTIVE);
            g.drawArc(sx, sy, s, s, (int) (-(now / 3) % 360), 100);
            g.setStroke(old);
        }
    }

    private void paintPackaging(Graphics2D g, ZonePanel z, int x, int y, int right) {
        String order = z.field("order");
        if (Theme.isNone(order)) {
            label(g, "no order assigned", x, y);
            return;
        }
        g.setFont(Theme.SMALL_BOLD);
        g.setColor(Theme.kind(Kind.PACKAGING));
        g.drawString(order, right - g.getFontMetrics().stringWidth(order), y - 12);

        List<String> outstanding = Theme.parseList(z.field("outstanding"));
        int cx = x;
        for (String item : Theme.parseList(z.field("items"))) {
            if (cx > right) break;
            cx += Theme.chip(g, item, cx, y, !outstanding.remove(item)) + 4;
        }
    }

    private void paintFields(Graphics2D g, ZonePanel z, int x, int y) {
        g.setFont(Theme.SMALL);
        for (Map.Entry<String, String> e : z.fields().entrySet()) {
            g.setColor(Theme.MUTED);
            g.drawString(e.getKey() + ":", x, y + 12);
            g.setColor(Theme.TEXT);
            g.drawString(e.getValue(), x + 80, y + 12);
            y += 16;
        }
    }

    private void paintCharger(Graphics2D g, ZonePanel z, Rectangle r, long now) {
        boolean charging = false;
        for (RobotPanel robot : viewer.robots()) {
            if (z.name().equals(robot.field("at")) && "charging".equals(robot.status())) charging = true;
        }
        g.setColor(Theme.SHADOW);
        g.fillRoundRect(r.x + 1, r.y + 2, r.width, r.height, 10, 10);
        g.setColor(Theme.CARD);
        g.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
        if (charging) {
            double pulse = 0.5 + 0.5 * Math.sin(now / 150.0);
            g.setColor(Theme.alpha(Theme.CHARGING, 0.15 + 0.2 * pulse));
            g.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
        }
        g.setColor(charging ? Theme.CHARGING : Theme.BORDER);
        g.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10);

        Shape oldClip = g.getClip();
        g.clipRect(r.x, r.y, r.width, r.height);
        Theme.bolt(g, r.x + 8, r.y + (r.height - 22) / 2.0, 22, charging ? Theme.CHARGING : Theme.IDLE);
        g.setFont(Theme.TITLE);
        g.setColor(Theme.TEXT);
        g.drawString(z.name(), r.x + 32, r.y + r.height / 2 - 2);
        g.setFont(Theme.SMALL);
        g.setColor(Theme.MUTED);
        g.drawString(charging ? "charging" : z.occupant() != null ? "docked" : "free", r.x + 32, r.y + r.height / 2 + 13);
        g.setClip(oldClip);
    }

    private void paintRobot(Graphics2D g, RobotPanel robot, Mover m, long now) {
        int s = ROBOT_SIZE;
        int x = (int) Math.round(m.x - s / 2.0), y = (int) Math.round(m.y - s / 2.0);
        String status = robot.status();
        Color c = Theme.status(status);

        if ("charging".equals(status)) {
            double pulse = (now % 1000) / 1000.0;
            int grow = (int) (pulse * 14);
            g.setColor(Theme.alpha(Theme.CHARGING, 0.5 * (1 - pulse)));
            g.setStroke(new BasicStroke(2f));
            g.drawOval(x - grow, y - grow, s + 2 * grow, s + 2 * grow);
        }

        g.setColor(Theme.SHADOW);
        g.fillRoundRect(x + 1, y + 3, s, s, 10, 10);
        // wheels
        g.setColor(new Color(0x374151));
        g.fillRoundRect(x - 3, y + 5, 5, 8, 2, 2);
        g.fillRoundRect(x - 3, y + s - 13, 5, 8, 2, 2);
        g.fillRoundRect(x + s - 2, y + 5, 5, 8, 2, 2);
        g.fillRoundRect(x + s - 2, y + s - 13, 5, 8, 2, 2);
        // body
        g.setColor(c);
        g.fillRoundRect(x, y, s, s, 10, 10);
        g.setColor(c.darker());
        g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(x, y, s, s, 10, 10);

        g.setFont(Theme.SMALL_BOLD);
        FontMetrics fm = g.getFontMetrics();
        String name = robot.name();
        while (name.length() > 1 && fm.stringWidth(name) > s - 4) name = name.substring(0, name.length() - 1);
        g.setColor(Color.WHITE);
        g.drawString(name, x + (s - fm.stringWidth(name)) / 2, Theme.baseline(fm, y, s));

        Theme.energyBar(g, x, y + s + 4, s, 5, robot.energy(), robot.maxEnergy());

        String holding = robot.field("holding");
        if (!Theme.isNone(holding)) {
            Color ic = Theme.item(holding);
            int bx = x + s - 8, by = y - 9;
            g.setColor(ic);
            g.fillRect(bx, by, 14, 14);
            g.setColor(ic.darker());
            g.drawRect(bx, by, 14, 14);
            g.drawLine(bx, by + 7, bx + 14, by + 7);
        }
    }

    private static void header(Graphics2D g, String text, int x, int y, Color accent) {
        g.setColor(accent);
        g.fillRoundRect(x, y + 6, 4, 12, 2, 2);
        g.setFont(Theme.HEADER);
        g.setColor(Theme.MUTED);
        g.drawString(text, x + 10, y + 17);
    }

    private static void label(Graphics2D g, String text, int x, int y) {
        g.setFont(Theme.SMALL);
        g.setColor(Theme.MUTED);
        g.drawString(text, x, y + 13);
    }
}
