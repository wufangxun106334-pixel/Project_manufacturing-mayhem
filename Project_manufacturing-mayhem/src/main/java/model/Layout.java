package model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The logical layout of the factory floor, used to work out how far a robot travels. It matches
 * the viewer: a column each for access points, workstations, storage points and packaging points
 * (in that order, two units apart), each with its locations stacked one unit apart above a main
 * aisle, and the charge points spread along the far side of the aisle, one unit from it.
 *
 * A robot drives along its column's lane to the aisle, along the aisle, and up the other lane,
 * so the distance is how far each location is from the aisle plus the distance along the
 * aisle. Two locations in the same column are reached along the lane.
 */
public class Layout {
    private static final String[] COLUMNS = {"AccessPoint", "Workstation", "StoragePoint", "PackagingPoint"};
    private static final String CHARGER = "RobotChargePoint";
    private static final double COLUMN_SPACING = 2;

    private record Position(double x, double depth, String column) {}

    private final Map<String, String> locations = new LinkedHashMap<>();
    private Map<String, Position> positions;

    /** Adds a location, given its artifact type, in the order it was installed. */
    public synchronized void add(String name, String artifactType) {
        locations.put(name, artifactType.substring(artifactType.lastIndexOf('.') + 1));
        positions = null;
    }

    /** The distance between two locations, in units (at least 1 for different locations). */
    public synchronized int distance(String from, String to) {
        if (from.equals(to)) return 0;
        if (positions == null) positions = place();
        Position a = positions.get(from), b = positions.get(to);
        if (a == null || b == null) return 1;
        double d = a.column().equals(b.column()) && !a.column().equals(CHARGER)
                ? Math.abs(a.depth() - b.depth())
                : a.depth() + Math.abs(a.x() - b.x()) + b.depth();
        return (int) Math.max(1, Math.round(d));
    }

    private Map<String, Position> place() {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        for (String column : COLUMNS) columns.put(column, new ArrayList<>());
        List<String> chargers = new ArrayList<>();
        for (Map.Entry<String, String> location : locations.entrySet()) {
            if (location.getValue().equals(CHARGER)) chargers.add(location.getKey());
            else columns.computeIfAbsent(location.getValue(), k -> new ArrayList<>()).add(location.getKey());
        }
        columns.values().removeIf(List::isEmpty);

        Map<String, Position> placed = new LinkedHashMap<>();
        int c = 0;
        for (Map.Entry<String, List<String>> column : columns.entrySet()) {
            List<String> names = column.getValue();
            for (int i = 0; i < names.size(); i++) {
                // the first location is furthest from the aisle
                placed.put(names.get(i), new Position(c * COLUMN_SPACING, names.size() - i, column.getKey()));
            }
            c++;
        }
        double width = Math.max(0, (columns.size() - 1) * COLUMN_SPACING);
        for (int i = 0; i < chargers.size(); i++) {
            double x = chargers.size() == 1 ? width / 2 : width * i / (chargers.size() - 1);
            placed.put(chargers.get(i), new Position(x, 1, CHARGER));
        }
        return placed;
    }
}
