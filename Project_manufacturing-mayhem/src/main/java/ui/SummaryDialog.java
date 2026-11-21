package ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import metrics.Summary;

/**
 * Shows the metrics for a finished run as a table, with buttons to save them as JSON or CSV.
 */
class SummaryDialog extends JDialog {
    private final Summary summary;
    private final Path directory;

    SummaryDialog(JFrame owner, Summary summary, Path directory) {
        super(owner, "Simulation Summary — " + summary.run(), false);
        this.summary = summary;
        this.directory = directory;

        JLabel heading = new JLabel(headline());
        heading.setFont(Theme.TITLE.deriveFont(14f));
        heading.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));

        JTable table = new JTable(new Model(summary.rows()));
        table.setFont(Theme.BODY);
        table.setRowHeight(22);
        table.setShowVerticalLines(false);
        table.setGridColor(Theme.BORDER);
        table.getTableHeader().setFont(Theme.HEADER);
        table.getColumnModel().getColumn(0).setPreferredWidth(170);
        table.getColumnModel().getColumn(1).setPreferredWidth(230);
        table.getColumnModel().getColumn(2).setPreferredWidth(330);
        table.getColumnModel().getColumn(3).setPreferredWidth(80);
        table.getColumnModel().getColumn(0).setCellRenderer(new SectionRenderer());
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(3).setCellRenderer(right);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, Theme.BORDER));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        buttons.add(button("Save as JSON…", () -> save("summary.json", summary.toJson())));
        buttons.add(button("Save as CSV…", () -> save("summary.csv", summary.toCsv())));
        buttons.add(button("Close", this::dispose));

        setLayout(new BorderLayout());
        add(heading, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        setSize(new Dimension(860, 640));
        setLocationRelativeTo(owner);
    }

    private String headline() {
        Object scenario = "", makespan = 0, orders = 0;
        for (Summary.Row row : summary.rows()) {
            if (row.entity().equals("scenario") && row.metric().equals("name")) scenario = row.value();
            if (row.metric().equals("makespan (s)")) makespan = row.value();
            if (row.entity().equals("orders") && row.metric().equals("completed")) orders = row.value();
        }
        long millis = Math.round(((Number) makespan).doubleValue() * 1000);
        return scenario + ": all " + orders + " orders fulfilled in " + OrdersPanel.formatDuration(millis);
    }

    private static JButton button(String text, Runnable action) {
        JButton button = new JButton(text);
        button.addActionListener(e -> action.run());
        return button;
    }

    private void save(String defaultName, String content) {
        JFileChooser chooser = new JFileChooser(directory.toFile());
        chooser.setSelectedFile(directory.resolve(defaultName).toFile());
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path file = chooser.getSelectedFile().toPath();
        try {
            Files.writeString(file, content);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Could not save " + file + ":\n" + e.getMessage(), "Save failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static class Model extends AbstractTableModel {
        private static final String[] COLUMNS = {"Section", "Entity", "Metric", "Value"};
        private final List<Summary.Row> rows;

        Model(List<Summary.Row> rows) {
            this.rows = rows;
        }

        public int getRowCount() {
            return rows.size();
        }

        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        public Object getValueAt(int row, int column) {
            Summary.Row r = rows.get(row);
            return switch (column) {
                // Only the first row of each section shows its name
                case 0 -> row > 0 && rows.get(row - 1).section().equals(r.section()) ? "" : r.section();
                case 1 -> r.entity();
                case 2 -> r.metric();
                default -> r.value();
            };
        }
    }

    private static class SectionRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            c.setFont(Theme.HEADER);
            if (!selected) c.setForeground(new Color(0x52606D));
            return c;
        }
    }
}
