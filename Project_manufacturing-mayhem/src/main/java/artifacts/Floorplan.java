package artifacts;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import cartago.Artifact;
import cartago.ArtifactId;
import cartago.LINK;
import cartago.OPERATION;
import cartago.OpFeedbackParam;
import metrics.Metrics;
import model.Layout;
import model.Location;
import ui.PlantViewer;
import ui.RobotPanel;
import ui.ZonePanel;

/**
 * The factory floor: where everything is, which locations are occupied, and the simulation's
 * options (see {@link #configureScenario}). It also feeds the viewer and the run's metrics.
 */
public class Floorplan extends Artifact {
    /** Without travel distance, every move is this many units: 4 s and 100 energy. */
    public static final int FIXED_MOVE_UNITS = 4;
    public static final int MOVE_MILLIS_PER_UNIT = 1000;

    private Map<String, Location> locations = new HashMap<>();
    private PlantViewer viewer;
    private Map<String, ZonePanel> zones = new HashMap<>();
    private Map<String, RobotPanel> robots = new HashMap<>();
    private Metrics metrics;
    private final Layout layout = new Layout();
    private boolean travelDistance;
    private boolean breakdowns;
    private double breakdownProbability;
    private long repairTime;
    private Random breakdownRandom = new Random();

    @OPERATION
    public void init() {
        defineObsProperty("locations", locations.keySet());
        viewer = new PlantViewer();
        metrics = new Metrics(summary -> viewer.showSummary(summary, metrics.directory()));
    }

    /** Sets up the simulation's options for the scenario being run. */
    @OPERATION
    public void configureScenario(String name, String description, long seed, boolean travelDistance,
            boolean orderArrivals, int initialOrders, long meanInterval,
            boolean breakdowns, double breakdownProbability, long repairTime) {
        this.travelDistance = travelDistance;
        this.breakdowns = breakdowns;
        this.breakdownProbability = breakdownProbability;
        this.repairTime = repairTime;
        this.breakdownRandom = new Random(seed + 2);
        viewer.setScenario(name, description);

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("travel distance", travelDistance ? "on" : "off");
        options.put("order arrivals", orderArrivals ? initialOrders + " at the start, then one every " + meanInterval + " ms on average" : "off");
        options.put("breakdowns", breakdowns ? "probability " + breakdownProbability + ", repair time " + repairTime + " ms" : "off");
        metrics.scenario(name, description, seed, options);
    }

    @LINK
    public void install(cartago.ArtifactId id, String[] customFields) {
        locations.put(id.getName(), new Location(id));
        updateObsProperty("locations", locations.keySet());
        ZonePanel zonePanel = viewer.addZone(id.getName(), id.getArtifactType());
        for (String field : customFields) {
            zonePanel.addCustomField(field, "");
        }
        zones.put(id.getName(), zonePanel);
        layout.add(id.getName(), id.getArtifactType());
        metrics.location(id.getName(), id.getArtifactType());
    }

    @LINK
    public void location(String name, OpFeedbackParam<ArtifactId> id) {
        id.set(locations.get(name).id());
    }

    @LINK
    public void setStatus(String name, String status) {
        zones.get(name).setStatus(status);
        metrics.status(name, status);
    }

    @LINK
    public void setRobotField(String name, String key, String value) {
        robots.get(name).setField(key, value);
        metrics.robotField(name, key, value);
    }

    @LINK
    public void setCustomField(String name, String custom, String value) {
        zones.get(name).setCustomField(custom, value);
        metrics.field(name, custom, value);
    }

    @LINK
    public void setOrder(String id, String item, int quantity, String status, String packagingPoint, long duration) {
        viewer.setOrder(id, item, quantity, status, packagingPoint, duration);
        metrics.order(id, item, quantity, status, packagingPoint, duration);
    }

    @LINK
    public void setNextOrder(String id, String item, int quantity, int outstanding, int toArrive) {
        viewer.setNextOrder(id, item, quantity, outstanding, toArrive);
        metrics.nextOrder(id, item, quantity, outstanding, toArrive);
    }

    /** How far a move is, in units: the route length with travel distance on, otherwise fixed. */
    @LINK
    public void travel(String from, String to, OpFeedbackParam<Integer> units) {
        units.set(travelDistance ? layout.distance(from, to) : FIXED_MOVE_UNITS);
    }

    /**
     * Called by a robot or workstation at the start of an operation: with breakdowns on, it
     * sometimes breaks down, and repair is how long it is out of action (otherwise 0).
     */
    @LINK
    public void breakdown(String entity, String operation, OpFeedbackParam<Long> repair) {
        if (breakdowns && breakdownRandom.nextDouble() < breakdownProbability) {
            repair.set(repairTime);
            viewer.log(entity + " broke down while trying to " + operation + "; out of action for " + repairTime / 1000.0 + "s");
            metrics.breakdown(entity, operation, repairTime);
        } else {
            repair.set(0L);
        }
    }

    /** Called by an artifact when an operation an agent asked for fails. */
    @LINK
    public void recordFailure(String entity, String operation, String reason) {
        viewer.log(entity + " failed to " + operation + ": " + reason);
        metrics.failure(entity, operation, reason);
    }

    /**
     * Moves a robot, which is in transit for travelMillis. The target is reserved when the move
     * starts, and the floorplan is released while the robot travels so other robots can move too.
     */
    @LINK
    public void move(String name, String from, String to, long travelMillis) throws Exception {
        Location target = locations.get(to);
        Location source = locations.get(from);

        if (target.isOccupied()) throw new Exception("Target Location is Occupied: " + to);
        metrics.move(name, from, to, travelDistance ? layout.distance(from, to) : FIXED_MOVE_UNITS);
        source.occupancy(null);
        target.occupancy(name);
        zones.get(from).setAvailable("empty");
        await_time(travelMillis);
        zones.get(to).setAvailable(name);
        robots.get(name).setField("at", to);
    }

    @LINK
    public void place(String name, String to, int maxEnergy) throws Exception {
        Location target = locations.get(to);

        if (target.isOccupied()) throw new Exception("Target Location is Occupied: " + to);
        target.occupancy(name);
        zones.get(to).setAvailable(name);
        RobotPanel robotPanel = viewer.addRobot(name);
        robots.put(name, robotPanel);
        robotPanel.setMaxEnergy(maxEnergy);
        robotPanel.setField("energy", String.valueOf(maxEnergy)); // robots start fully charged
        robotPanel.setField("at", to);
        metrics.robot(name, to, maxEnergy);
    }

    @OPERATION
    public void isOccupied(String name, OpFeedbackParam<Boolean> occupied) {
        Location location = locations.get(name);
        occupied.set(location.isOccupied());
    }
}
