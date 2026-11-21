package ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/**
 * The sidebar view of the orders: the next order to be assigned and how many are still to be
 * assigned, the orders assigned to packaging points, and the archive of completed orders, newest
 * first, with how long each took from assignment to delivery.
 *
 * The setters are called from CArtAgO threads; the state is only touched on the event dispatch
 * thread.
 */
class OrdersPanel extends JComponent {
    private static final String COMPLETED = "COMPLETED";
    private static final int HEADING_H = 26;
    private static final int ROW_H = 30;
    private static final int GAP = 10;

    private record Order(String id, String item, int quantity, String packagingPoint, long duration) {}

    private Order next;
    private int outstanding;
    private int toArrive;
    private final Map<String, Order> assigned = new LinkedHashMap<>();
    private final List<Order> archive = new ArrayList<>();

    OrdersPanel() {
        setAlignmentX(LEFT_ALIGNMENT);
        updateSize();
    }

    void setNextOrder(String id, String item, int quantity, int outstanding, int toArrive) {
        Order order = Theme.isNone(id) ? null : new Order(id, item, quantity, "none", 0);
        update(() -> {
            next = order;
            this.outstanding = outstanding;
            this.toArrive = toArrive;
        });
    }

    void setOrder(String id, String item, int quantity, String status, String packagingPoint, long duration) {
        Order order = new Order(id, item, quantity, packagingPoint, duration);
        update(() -> {
            if (status.equals(COMPLETED)) {
                assigned.remove(id);
                archive.add(0, order);
            } else {
                assigned.put(id, order);
            }
        });
    }

    private void update(Runnable change) {
        SwingUtilities.invokeLater(() -> {
            change.run();
            updateSize();
            revalidate();
            repaint();
        });
    }

    private void updateSize() {
        int rows = 1 + Math.max(1, assigned.size()) + Math.max(1, archive.size());
        setPreferredSize(new Dimension(240, 3 * HEADING_H + rows * ROW_H + 2 * GAP));
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.antialias(g);
        g.setColor(Theme.FLOOR);
        g.fillRect(0, 0, getWidth(), getHeight());

        List<String> notes = new ArrayList<>();
        if (outstanding > 0) notes.add(outstanding + " to assign");
        if (toArrive > 0) notes.add(toArrive + " to arrive");
        int y = section(g, "NEXT ORDER", String.join(" · ", notes), 0);
        if (next == null) {
            empty(g, toArrive > 0 ? "waiting for orders to arrive" : "all orders assigned", y);
        } else {
            row(g, next, y, "next", Theme.IDLE, false);
        }
        y += ROW_H;

        y = section(g, "ASSIGNED", count(assigned.size()), y + GAP);
        y = rows(g, new ArrayList<>(assigned.values()), y, false, "none assigned");
        y = section(g, "ARCHIVE", count(archive.size()), y + GAP);
        rows(g, archive, y, true, "nothing completed yet");
        g.dispose();
    }

    /** e.g. "48s" or "2m 05s". */
    static String formatDuration(long millis) {
        long seconds = Math.round(millis / 1000.0);
        if (seconds < 60) return seconds + "s";
        return String.format("%dm %02ds", seconds / 60, seconds % 60);
    }

    private static String count(int n) {
        return n == 0 ? "" : String.valueOf(n);
    }

    /** A section heading, with a note (such as a count) on the right. */
    private int section(Graphics2D g, String title, String note, int y) {
        g.setFont(Theme.HEADER);
        g.setColor(Theme.MUTED);
        g.drawString(title, 12, y + 18);
        if (!note.isEmpty()) {
            g.setFont(Theme.SMALL);
            g.drawString(note, getWidth() - 12 - g.getFontMetrics().stringWidth(note), y + 18);
        }
        return y + HEADING_H;
    }

    private int rows(Graphics2D g, List<Order> orders, int y, boolean completed, String empty) {
        if (orders.isEmpty()) {
            empty(g, empty, y);
            return y + ROW_H;
        }
        for (Order order : orders) {
            if (completed) row(g, order, y, formatDuration(order.duration()), Theme.DONE, true);
            else row(g, order, y, "→ " + order.packagingPoint(), Theme.MOVING, false);
            y += ROW_H;
        }
        return y;
    }

    private static void empty(Graphics2D g, String text, int y) {
        g.setFont(Theme.SMALL);
        g.setColor(Theme.MUTED);
        g.drawString(text, 12, y + 18);
    }

    /** One order: its id (muted once completed), quantity × item, and a pill on the right. */
    private void row(Graphics2D g, Order order, int y, String pill, Color pillColour, boolean done) {
        int x = 8, w = getWidth() - 16, h = ROW_H - 4;
        g.setColor(Theme.CARD);
        g.fillRoundRect(x, y, w, h, 8, 8);
        g.setColor(Theme.BORDER);
        g.drawRoundRect(x, y, w, h, 8, 8);

        g.setFont(Theme.SMALL_BOLD);
        FontMetrics fm = g.getFontMetrics();
        g.setColor(done ? Theme.MUTED : Theme.TEXT);
        g.drawString(order.id(), x + 8, Theme.baseline(fm, y, h));
        int cx = x + 8 + Math.max(fm.stringWidth(order.id()), fm.stringWidth("order-00")) + 8;

        String label = order.quantity() + " ×";
        g.setFont(Theme.SMALL);
        fm = g.getFontMetrics();
        g.setColor(Theme.MUTED);
        g.drawString(label, cx, Theme.baseline(fm, y, h));
        Theme.chip(g, order.item(), cx + fm.stringWidth(label) + 4, y + (h - 18) / 2, true);

        Theme.pill(g, pill, x + w - 6 - Theme.pillWidth(g, pill), y + (h - 16) / 2, pillColour);
    }
}
