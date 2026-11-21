package ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import metrics.Summary;

/**
 * A visual view of the simulated plant: a floor with the stations and the robots moving between
 * them, a sidebar of robot status cards and a log of events.
 *
 * {@code artifacts.Floorplan} calls this from CArtAgO threads; the Swing work is passed to the
 * event dispatch thread.
 */
public class PlantViewer extends JFrame {
    private static final int MAX_LOG_LINES = 500;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final List<ZonePanel> zones = new CopyOnWriteArrayList<>();
    private final List<RobotPanel> robots = new CopyOnWriteArrayList<>();
    private FloorCanvas canvas;
    private JPanel robotList;
    private OrdersPanel orders;
    private JLabel scenario;
    private JTextArea log;

    public PlantViewer() {
        super("Manufacturing Mayhem — Plant Viewer");
        onEdt(this::buildUi);
    }

    private void buildUi() {
        setLayout(new BorderLayout());
        add(legend(), BorderLayout.NORTH);

        canvas = new FloorCanvas(this);

        robotList = new JPanel();
        robotList.setLayout(new BoxLayout(robotList, BoxLayout.PAGE_AXIS));
        robotList.setBackground(Theme.FLOOR);
        robotList.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setBackground(Theme.FLOOR);
        sidebar.add(heading("ROBOTS"), BorderLayout.NORTH);
        JPanel listHolder = new JPanel(new BorderLayout());
        listHolder.setBackground(Theme.FLOOR);
        listHolder.add(robotList, BorderLayout.NORTH);
        JScrollPane robotScroll = new JScrollPane(listHolder);
        robotScroll.setBorder(null);
        orders = new OrdersPanel();
        JScrollPane orderScroll = new JScrollPane(orders);
        orderScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER));
        orderScroll.getViewport().setBackground(Theme.FLOOR);
        JSplitPane robotsAndOrders = new JSplitPane(JSplitPane.VERTICAL_SPLIT, robotScroll, orderScroll);
        robotsAndOrders.setResizeWeight(0.7);
        robotsAndOrders.setBorder(null);
        sidebar.add(robotsAndOrders, BorderLayout.CENTER);
        sidebar.setPreferredSize(new Dimension(260, 0));
        sidebar.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Theme.BORDER));

        log = new JTextArea(6, 80);
        log.setEditable(false);
        log.setFont(Theme.MONO);
        log.setForeground(Theme.TEXT);
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.add(heading("EVENTS"), BorderLayout.NORTH);
        JScrollPane logScroll = new JScrollPane(log);
        logScroll.setBorder(null);
        logPanel.add(logScroll, BorderLayout.CENTER);

        JPanel main = new JPanel(new BorderLayout());
        main.add(canvas, BorderLayout.CENTER);
        main.add(sidebar, BorderLayout.EAST);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, main, logPanel);
        split.setResizeWeight(0.82);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);

        setMinimumSize(new Dimension(900, 620));
        setSize(1280, 820);
        setLocationByPlatform(true);
        setVisible(true);
    }

    public ZonePanel addZone(String name, String type) {
        ZonePanel zone = new ZonePanel(this, name, type);
        zones.add(zone);
        log("installed " + name + " [" + zone.kind().label + "]");
        return zone;
    }

    public RobotPanel addRobot(String name) {
        RobotPanel robot = new RobotPanel(this, name);
        robots.add(robot);
        log("robot " + name + " created");
        SwingUtilities.invokeLater(() -> {
            robotList.add(robot);
            robotList.revalidate();
            robotList.repaint();
        });
        return robot;
    }

    List<ZonePanel> zones() {
        return zones;
    }

    List<RobotPanel> robots() {
        return robots;
    }

    /**
     * Shows the next order to be assigned ("none" if there is none), how many are waiting to be
     * assigned and how many have still to arrive.
     */
    public void setNextOrder(String id, String item, int quantity, int outstanding, int toArrive) {
        if (!Theme.isNone(id)) log("next order is " + id + " (" + quantity + " x " + item + "), " + outstanding + " to assign");
        orders.setNextOrder(id, item, quantity, outstanding, toArrive);
    }

    /** Shows which scenario is being run. */
    public void setScenario(String name, String description) {
        log("scenario: " + name + (description.isEmpty() ? "" : " — " + description));
        SwingUtilities.invokeLater(() -> {
            setTitle("Manufacturing Mayhem — " + name);
            scenario.setText(name);
            scenario.setToolTipText(description);
        });
    }

    /**
     * Shows an assigned order; a COMPLETED order moves to the archive. duration is how long the
     * order waited to be assigned (ASSIGNED) or took from assignment to delivery (COMPLETED), in ms.
     */
    public void setOrder(String id, String item, int quantity, String status, String packagingPoint, long duration) {
        String where = Theme.isNone(packagingPoint) ? "" : " at " + packagingPoint;
        String took = status.equals("COMPLETED") ? " in " + OrdersPanel.formatDuration(duration)
                : duration > 0 ? " after waiting " + OrdersPanel.formatDuration(duration) : "";
        log(id + " (" + quantity + " x " + item + ") is " + status.toLowerCase() + where + took);
        orders.setOrder(id, item, quantity, status, packagingPoint, duration);
    }

    /** Shows the metrics for the run once every order has been fulfilled. */
    public void showSummary(Summary summary, Path directory) {
        log("all orders fulfilled; event log in " + directory);
        SwingUtilities.invokeLater(() -> new SummaryDialog(this, summary, directory).setVisible(true));
    }

    void itemUnloaded(String robot, String location, String item) {
        log(robot + " dropped " + item + " at " + location);
    }

    public void log(String message) {
        String line = LocalTime.now().format(TIME) + "  " + message + "\n";
        SwingUtilities.invokeLater(() -> {
            if (log == null) return;
            log.append(line);
            int excess = log.getLineCount() - MAX_LOG_LINES;
            if (excess > 0) {
                try {
                    log.replaceRange("", 0, log.getLineEndOffset(excess - 1));
                } catch (javax.swing.text.BadLocationException e) {
                    // cannot happen: excess is within the line count
                }
            }
            log.setCaretPosition(log.getDocument().getLength());
        });
    }

    private static JLabel heading(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.HEADER);
        label.setForeground(Theme.MUTED);
        label.setBorder(BorderFactory.createEmptyBorder(8, 12, 4, 12));
        return label;
    }

    private JPanel legend() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 6));
        bar.setBackground(Color.WHITE);
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER));
        JLabel title = new JLabel("Manufacturing Mayhem");
        title.setFont(Theme.TITLE.deriveFont(14f));
        bar.add(title);
        scenario = new JLabel();
        scenario.setFont(Theme.BODY);
        scenario.setForeground(Theme.MUTED);
        bar.add(scenario);
        bar.add(Box.createHorizontalStrut(12));
        String[][] entries = {
            {"idle", "idle"}, {"moving", "moving"}, {"loading", "loading / unloading / retrieving"},
            {"charging", "charging"}, {"broken", "broken down"}, {"ready", "ready"}, {"active", "active"}, {"done", "done"}
        };
        for (String[] entry : entries) {
            JLabel label = new JLabel(entry[1], swatch(Theme.status(entry[0])), JLabel.LEFT);
            label.setFont(Theme.SMALL);
            label.setForeground(Theme.MUTED);
            bar.add(label);
        }
        return bar;
    }

    private static Icon swatch(Color colour) {
        return new Icon() {
            public void paintIcon(Component c, Graphics g0, int x, int y) {
                Graphics2D g = (Graphics2D) g0.create();
                Theme.antialias(g);
                g.setColor(colour);
                g.fillRoundRect(x, y, 12, 12, 4, 4);
                g.dispose();
            }

            public int getIconWidth() {
                return 12;
            }

            public int getIconHeight() {
                return 12;
            }
        };
    }

    private static void onEdt(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        }
    }
}
