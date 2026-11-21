package ui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The viewer's state for one location on the floorplan (access point, workstation, charger, ...).
 * It is drawn by {@link FloorCanvas}. The class name and public methods are kept from the original
 * Swing panel so that {@code artifacts.Floorplan} can use it unchanged.
 *
 * Setters are called from CArtAgO threads, so all state is thread-safe and the canvas just reads it
 * on each frame.
 */
public class ZonePanel {
    public enum Kind {
        ACCESS("Access Points"),
        WORKSTATION("Workstations"),
        STORAGE("Storage"),
        PACKAGING("Packaging"),
        CHARGER("Charging Bay"),
        OTHER("Other");

        final String label;

        Kind(String label) {
            this.label = label;
        }

        static Kind of(String type) {
            String simple = type.substring(type.lastIndexOf('.') + 1);
            return switch (simple) {
                case "AccessPoint" -> ACCESS;
                case "Workstation" -> WORKSTATION;
                case "StoragePoint" -> STORAGE;
                case "PackagingPoint" -> PACKAGING;
                case "RobotChargePoint" -> CHARGER;
                default -> OTHER;
            };
        }
    }

    private final PlantViewer viewer;
    private final String name;
    private final String type;
    private final Kind kind;
    private final Map<String, String> customFields = Collections.synchronizedMap(new LinkedHashMap<>());
    private volatile String status = "idle";
    private volatile String occupant;
    private volatile long lastChange;

    public ZonePanel(PlantViewer viewer, String name, String type) {
        this.viewer = viewer;
        this.name = name;
        this.type = type;
        this.kind = Kind.of(type);
    }

    /** Records which robot (if any) is parked at this location; "empty" means none. */
    public void setAvailable(String item) {
        occupant = Theme.isNone(item) ? null : item;
    }

    public void setStatus(String status) {
        if (status.equals(this.status)) return;
        this.status = status;
        changed(name + " is " + status);
    }

    public void addCustomField(String name, String initial) {
        customFields.put(name, initial);
    }

    public void setCustomField(String custom, String value) {
        String old = customFields.put(custom, value);
        if (!value.equals(old)) changed(name + "." + custom + " = " + value);
    }

    private void changed(String message) {
        lastChange = System.currentTimeMillis();
        viewer.log(message);
    }

    String name() {
        return name;
    }

    String type() {
        return type;
    }

    Kind kind() {
        return kind;
    }

    String status() {
        return status;
    }

    String occupant() {
        return occupant;
    }

    long lastChange() {
        return lastChange;
    }

    String field(String key) {
        return customFields.get(key);
    }

    Map<String, String> fields() {
        synchronized (customFields) {
            return new LinkedHashMap<>(customFields);
        }
    }
}
