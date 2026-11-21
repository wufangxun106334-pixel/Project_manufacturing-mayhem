package ui;

import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.JComponent;

/**
 * The viewer's state for one robot, drawn as a status card in the sidebar. The robot itself is
 * drawn moving around the floor by {@link FloorCanvas}. The class name and {@link #setField} are
 * kept from the original Swing panel so that {@code artifacts.Floorplan} can use it unchanged.
 *
 * Known fields: "at", "status", "holding" and "energy".
 */
public class RobotPanel extends JComponent {
    /** Matches the maxEnergy that Base.astra gives each robot's charge point. */
    static final int DEFAULT_MAX_ENERGY = 800;

    private final PlantViewer viewer;
    private final String name;
    private final Map<String, String> fields = new ConcurrentHashMap<>();
    private volatile int maxEnergySeen;
    private volatile int maxEnergy;

    public RobotPanel(PlantViewer viewer, String name) {
        this.viewer = viewer;
        this.name = name;
        fields.put("at", "none");
        fields.put("status", "idle");
        fields.put("holding", "none");
        fields.put("energy", "");
        setPreferredSize(new Dimension(240, 100));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 100));
        setAlignmentX(LEFT_ALIGNMENT);
    }

    public void setField(String key, String value) {
        String old = fields.put(key, value);
        if (!value.equals(old)) {
            switch (key) {
                case "energy" -> {
                    Integer energy = energy();
                    if (energy != null) maxEnergySeen = Math.max(maxEnergySeen, energy);
                }
                case "holding" -> {
                    if (Theme.isNone(value) && !Theme.isNone(old)) {
                        viewer.itemUnloaded(name, field("at"), old);
                    } else if (!Theme.isNone(value)) {
                        viewer.log(name + " picked up " + value + " at " + field("at"));
                    }
                }
                case "at" -> viewer.log(name + " arrived at " + value);
                case "to", "moveMillis" -> { }   // animation hints only
                case "status" -> {
                    if (!value.equals("idle")) viewer.log(name + " is " + value);
                }
                default -> viewer.log(name + "." + key + " = " + value);
            }
        }
        repaint();
    }

    /** The robot's battery capacity, for its energy bars. */
    public void setMaxEnergy(int maxEnergy) {
        this.maxEnergy = maxEnergy;
        repaint();
    }

    String name() {
        return name;
    }

    String field(String key) {
        return fields.get(key);
    }

    String status() {
        return fields.get("status");
    }

    Integer energy() {
        try {
            return Integer.valueOf(fields.get("energy").trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    int maxEnergy() {
        if (maxEnergy > 0) return maxEnergy;
        return Math.max(DEFAULT_MAX_ENERGY, maxEnergySeen);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.antialias(g);
        int x = 8, y = 4, w = getWidth() - 16, h = getHeight() - 8;
        String status = status();

        g.setColor(Theme.SHADOW);
        g.fillRoundRect(x + 1, y + 2, w, h, 12, 12);
        g.setColor(Theme.CARD);
        g.fillRoundRect(x, y, w, h, 12, 12);
        g.setColor(Theme.BORDER);
        g.drawRoundRect(x, y, w, h, 12, 12);
        g.setColor(Theme.status(status));
        g.fillRoundRect(x + 10, y + 9, 14, 14, 5, 5);

        g.setFont(Theme.TITLE);
        g.setColor(Theme.TEXT);
        g.drawString(name, x + 32, y + 21);
        Theme.pill(g, status, x + w - 10 - Theme.pillWidth(g, status), y + 8, Theme.status(status));

        g.setFont(Theme.SMALL);
        FontMetrics fm = g.getFontMetrics();
        int labelX = x + 12, valueX = x + 70;
        g.setColor(Theme.MUTED);
        g.drawString("location", labelX, y + 44);
        g.drawString("holding", labelX, y + 66);
        g.drawString("energy", labelX, y + 87);

        g.setColor(Theme.TEXT);
        g.drawString(field("at"), valueX, y + 44);

        String holding = field("holding");
        if (Theme.isNone(holding)) {
            g.setColor(Theme.MUTED);
            g.drawString("—", valueX, y + 66);
        } else {
            Theme.chip(g, holding, valueX, y + 53, true);
        }

        Integer energy = energy();
        String label = energy == null ? "?" : energy + " / " + maxEnergy();
        int labelW = fm.stringWidth(label);
        g.setFont(Theme.SMALL);
        g.setColor(Theme.MUTED);
        g.drawString(label, x + w - 10 - labelW, y + 87);
        Theme.energyBar(g, valueX, y + 79, Math.max(20, w - 10 - labelW - 8 - (valueX - x)), 8, energy, maxEnergy());
        g.dispose();
    }
}
