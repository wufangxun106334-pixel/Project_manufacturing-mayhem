package metrics;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import model.Project;

/**
 * Measures how well the agents coordinate to fulfil the orders. It is fed every change that the
 * Floorplan artifact sees, writes each one to results/run-<time>/events.csv, and when the last
 * order has been delivered produces a {@link Summary} of:
 *
 * - idle while work is available: robots idle while orders are unfinished, packaging points with
 *   no order while orders wait to be assigned, and workstations idle while a current order
 *   (the next one or an assigned one) is for their output;
 * - hand-off delays: how long work waits between one agent's step and the next;
 * - load balance: how evenly the work is spread over robots, packaging points and workstations;
 * - conflicts and waste: failed operations, moves and distance travelled carrying nothing,
 *   charging visits and stalls in which nothing happens;
 * - breakdowns: how often robots and workstations broke down and how long they were out of action.
 *
 * Time spent in each state is measured by sampling every entity every {@value #SAMPLE_MS} ms,
 * from when the orders are created until the last one is delivered (the run).
 */
public class Metrics {
    private static final long SAMPLE_MS = 100;
    private static final long STALL_MS = 10_000;
    private static final String NONE = "none";

    private static final String ORDER_QUEUE = "order waiting to be assigned";
    private static final String WS_READY = "workstation waiting to be operated";
    private static final String WS_DONE = "output waiting to be collected";
    private static final String AP_ITEM = "item waiting at access point";
    private static final String SP_ITEM = "item waiting in storage";
    private static final String PP_COMPLETE = "complete order waiting to be delivered";
    private static final String[] DELAYS = {ORDER_QUEUE, WS_READY, WS_DONE, AP_ITEM, SP_ITEM, PP_COMPLETE};

    private static final String BUSY = "busy";
    private static final String IDLE = "idle";
    private static final String IDLE_WITH_WORK = "idle while work available";
    private static final String BLOCKED = "blocked";
    private static final String CHARGING = "charging";
    private static final String BROKEN = "broken";

    private enum Kind { ACCESS, WORKSTATION, STORAGE, PACKAGING, CHARGER, OTHER }

    private final Consumer<Summary> onFinished;
    private final long created = System.currentTimeMillis();
    private final Path directory;
    private BufferedWriter events;

    private final Map<String, Kind> locations = new LinkedHashMap<>();
    private final List<String> robots = new ArrayList<>();
    private final Map<String, Map<String, String>> fields = new HashMap<>();
    private final Map<String, Integer> maxEnergy = new HashMap<>();
    private final Map<String, Map<String, Long>> time = new HashMap<>();

    private final Map<String, Long> waitingSince = new HashMap<>();
    private final Map<String, List<Long>> delays = new LinkedHashMap<>();
    private final Map<String, Integer> failures = new TreeMap<>();
    private final Map<String, int[]> moves = new HashMap<>();
    private final Map<String, int[]> distance = new HashMap<>();
    private final Map<String, Integer> breakdowns = new LinkedHashMap<>();
    private String scenario = "";
    private String description = "";
    private long seed;
    private Map<String, Object> options = Map.of();
    private final Map<String, List<Double>> chargeArrivals = new HashMap<>();
    private final Set<String> arrivingAtCharger = new HashSet<>();
    private final Map<String, Integer> ordersHandled = new LinkedHashMap<>();
    private final Map<String, String> assigned = new HashMap<>();
    private final List<Long> orderTimes = new ArrayList<>();

    private String nextItem = NONE;
    private int outstanding;
    private int toArrive;
    private long runStart = -1, runEnd = -1, lastSample, lastEvent;
    private int stalls;
    private long stallTime;
    private boolean stalled;

    /** @param onFinished called with the summary once every order has been delivered */
    public Metrics(Consumer<Summary> onFinished) {
        this.onFinished = onFinished;
        String run = "run-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss"));
        directory = Project.directory().resolve("results").resolve(run);
        try {
            Files.createDirectories(directory);
            events = Files.newBufferedWriter(directory.resolve("events.csv"));
            events.write("elapsed_ms,event,entity,key,value\n");
        } catch (IOException e) {
            System.err.println("Metrics: cannot write the event log in " + directory + ": " + e.getMessage());
            events = null;
        }
        for (String delay : DELAYS) delays.put(delay, new ArrayList<>());

        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "metrics");
            thread.setDaemon(true);
            return thread;
        });
        sampler.scheduleAtFixedRate(this::sample, SAMPLE_MS, SAMPLE_MS, TimeUnit.MILLISECONDS);
    }

    /** Where this run's event log is written and its summary is saved by default. */
    public Path directory() {
        return directory;
    }

    // ------------------------------------------------------------------------------------------
    // Events (from the Floorplan artifact)
    // ------------------------------------------------------------------------------------------

    public synchronized void scenario(String name, String description, long seed, Map<String, Object> options) {
        this.scenario = name;
        this.description = description;
        this.seed = seed;
        this.options = options;
        log("scenario", name, "seed", String.valueOf(seed));
    }

    public synchronized void location(String name, String artifactType) {
        locations.put(name, kind(artifactType));
        fields.put(name, new HashMap<>());
        if (kind(artifactType) == Kind.PACKAGING) ordersHandled.put(name, 0);
        log("location", name, "type", artifactType);
    }

    public synchronized void robot(String name, String location, int max) {
        robots.add(name);
        maxEnergy.put(name, max);
        Map<String, String> robot = new HashMap<>();
        robot.put("at", location);
        robot.put("status", "idle");
        robot.put("holding", NONE);
        robot.put("energy", String.valueOf(max));
        fields.put(name, robot);
        moves.put(name, new int[2]);
        distance.put(name, new int[2]);
        chargeArrivals.put(name, new ArrayList<>());
        log("robot", name, "maxEnergy", String.valueOf(max));
    }

    public synchronized void robotField(String name, String key, String value) {
        fields.computeIfAbsent(name, k -> new HashMap<>()).put(key, value);
        if (key.equals("at") && locations.get(value) == Kind.CHARGER) {
            arrivingAtCharger.add(name);
        } else if (key.equals("energy") && arrivingAtCharger.remove(name)) {
            // The energy update after arriving at a charger: how much charge was left
            Integer max = maxEnergy.get(name);
            if (max != null && max > 0) chargeArrivals.get(name).add(100.0 * parseInt(value) / max);
        }
        log("robot", name, key, value);
    }

    public synchronized void status(String name, String status) {
        String old = fields.computeIfAbsent(name, k -> new HashMap<>()).put("status", status);
        if (status.equals(old)) return;
        if (locations.get(name) == Kind.WORKSTATION) {
            if ("ready".equals(old)) endWait(name, WS_READY);
            if ("done".equals(old)) endWait(name, WS_DONE);
            if (status.equals("ready")) startWait(name, WS_READY);
            if (status.equals("done")) startWait(name, WS_DONE);
        }
        log("status", name, "status", status);
    }

    public synchronized void field(String name, String key, String value) {
        Map<String, String> location = fields.computeIfAbsent(name, k -> new HashMap<>());
        location.put(key, value);
        Kind kind = locations.get(name);
        if (key.equals("available") && (kind == Kind.ACCESS || kind == Kind.STORAGE)) {
            String delay = kind == Kind.ACCESS ? AP_ITEM : SP_ITEM;
            if (isNone(value)) endWait(name, delay);
            else startWait(name, delay);
        } else if (kind == Kind.PACKAGING && key.equals("outstanding")) {
            if (isEmptyList(value) && !isNone(location.get("order"))) startWait(name, PP_COMPLETE);
        } else if (kind == Kind.PACKAGING && key.equals("order") && isNone(value)) {
            endWait(name, PP_COMPLETE);
        }
        log("field", name, key, value);
    }

    /** A robot sets off on a move of the given distance (in units). */
    public synchronized void move(String robot, String from, String to, int units) {
        int[] counts = moves.computeIfAbsent(robot, k -> new int[2]);
        int[] travelled = distance.computeIfAbsent(robot, k -> new int[2]);
        boolean empty = isNone(fields.getOrDefault(robot, Map.of()).get("holding"));
        counts[0]++;
        travelled[0] += units;
        if (empty) {
            counts[1]++;
            travelled[1] += units;
        }
        log("move", robot, from + " -> " + to, String.valueOf(units));
    }

    public synchronized void breakdown(String entity, String operation, long repairTime) {
        breakdowns.merge(entity, 1, Integer::sum);
        log("breakdown", entity, operation, String.valueOf(repairTime));
    }

    public synchronized void nextOrder(String id, String item, int quantity, int outstanding, int toArrive) {
        if (runStart < 0 && !isNone(id)) {
            runStart = System.currentTimeMillis();
            lastSample = runStart;
            lastEvent = runStart;
        }
        this.nextItem = isNone(id) ? NONE : item;
        this.outstanding = outstanding;
        this.toArrive = toArrive;
        log("nextOrder", id, item, quantity + " (" + outstanding + " to assign, " + toArrive + " to arrive)");
    }

    /**
     * An order is assigned or completed. duration is how long it waited to be assigned
     * (ASSIGNED) or took from assignment to delivery (COMPLETED), in ms.
     */
    public synchronized void order(String id, String item, int quantity, String status, String packagingPoint, long duration) {
        log("order", id, status, quantity + " x " + item + " at " + packagingPoint);
        if (status.equals("ASSIGNED")) {
            assigned.put(id, item);
            ordersHandled.merge(packagingPoint, 1, Integer::sum);
            delays.get(ORDER_QUEUE).add(duration);
        } else if (status.equals("COMPLETED")) {
            // The packaging point reports that it is empty only after this, so end its wait here
            endWait(packagingPoint, PP_COMPLETE);
            assigned.remove(id);
            orderTimes.add(duration);
            if (assigned.isEmpty() && outstanding == 0 && toArrive == 0 && isNone(nextItem)) finish();
        }
    }

    public synchronized void failure(String entity, String operation, String reason) {
        failures.merge(entity + "|" + operation + ": " + reason, 1, Integer::sum);
        log("failure", entity, operation, reason);
    }

    /** The most specific message in an exception and its causes. */
    public static String reason(Throwable e) {
        String reason = e.toString();
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && !t.getMessage().isBlank()) reason = t.getMessage();
        }
        return reason;
    }

    // ------------------------------------------------------------------------------------------
    // Sampling
    // ------------------------------------------------------------------------------------------

    private synchronized void sample() {
        if (runStart < 0 || runEnd >= 0) return;
        long now = System.currentTimeMillis();
        long dt = now - lastSample;
        lastSample = now;

        for (String robot : robots) {
            String status = fields.get(robot).getOrDefault("status", "idle");
            add(robot, switch (status) {
                case "idle" -> IDLE;
                case "charging" -> CHARGING;
                case "broken" -> BROKEN;
                default -> BUSY;
            }, dt);
        }
        for (Map.Entry<String, Kind> location : locations.entrySet()) {
            String name = location.getKey();
            Map<String, String> f = fields.get(name);
            if (location.getValue() == Kind.PACKAGING) {
                if (!isNone(f.get("order"))) add(name, BUSY, dt);
                else add(name, outstanding > 0 ? IDLE_WITH_WORK : IDLE, dt);
            } else if (location.getValue() == Kind.WORKSTATION) {
                String status = f.getOrDefault("status", "idle");
                String output = f.get("outputs");
                boolean wanted = output != null && (output.equals(nextItem) || assigned.containsValue(output));
                switch (status) {
                    case "active" -> add(name, BUSY, dt);
                    case "broken" -> add(name, BROKEN, dt);
                    case "ready", "done" -> add(name, BLOCKED, dt);
                    default -> add(name, wanted ? IDLE_WITH_WORK : IDLE, dt);
                }
            }
        }

        if (now - lastEvent > STALL_MS) {
            stallTime += dt;
            if (!stalled) stalls++;
            stalled = true;
        } else {
            stalled = false;
        }
    }

    private void add(String entity, String state, long dt) {
        time.computeIfAbsent(entity, k -> new HashMap<>()).merge(state, dt, Long::sum);
    }

    private long time(String entity, String state) {
        return time.getOrDefault(entity, Map.of()).getOrDefault(state, 0L);
    }

    private void startWait(String entity, String delay) {
        waitingSince.putIfAbsent(entity + "|" + delay, System.currentTimeMillis());
    }

    private void endWait(String entity, String delay) {
        Long since = waitingSince.remove(entity + "|" + delay);
        if (since != null && runStart >= 0) delays.get(delay).add(System.currentTimeMillis() - since);
    }

    // ------------------------------------------------------------------------------------------
    // Summary
    // ------------------------------------------------------------------------------------------

    private void finish() {
        sample();
        runEnd = System.currentTimeMillis();
        closeLog();
        Summary summary = summarise();
        new Thread(() -> onFinished.accept(summary), "metrics-summary").start();
    }

    private Summary summarise() {
        Summary s = new Summary(directory.getFileName().toString());
        long run = runEnd - runStart;

        String about = "Scenario";
        s.add(about, "scenario", "name", scenario);
        s.add(about, "scenario", "description", description);
        s.add(about, "scenario", "seed", seed);
        for (Map.Entry<String, Object> option : options.entrySet()) s.add(about, "options", option.getKey(), option.getValue());

        String overall = "Overall";
        s.add(overall, "run", "makespan (s)", seconds(run));
        s.add(overall, "orders", "completed", orderTimes.size());
        s.add(overall, "orders", "mean time from assignment to delivery (s)", seconds(mean(orderTimes)));
        s.add(overall, "orders", "longest time from assignment to delivery (s)", seconds(max(orderTimes)));

        String idle = "Idle while work available";
        for (String robot : robots) {
            s.add(idle, robot, "idle while orders unfinished (s)", seconds(time(robot, IDLE)));
            s.add(idle, robot, "idle while orders unfinished (% of run)", percent(time(robot, IDLE), run));
        }
        for (String name : names(Kind.PACKAGING)) {
            s.add(idle, name, "no order while orders wait to be assigned (s)", seconds(time(name, IDLE_WITH_WORK)));
            s.add(idle, name, "no order while orders wait to be assigned (% of run)", percent(time(name, IDLE_WITH_WORK), run));
        }
        for (String name : names(Kind.WORKSTATION)) {
            s.add(idle, name, "idle while its output is ordered (s)", seconds(time(name, IDLE_WITH_WORK)));
            s.add(idle, name, "idle while its output is ordered (% of run)", percent(time(name, IDLE_WITH_WORK), run));
        }

        String handoffs = "Hand-off delays";
        for (Map.Entry<String, List<Long>> delay : delays.entrySet()) {
            List<Long> waits = delay.getValue();
            s.add(handoffs, delay.getKey(), "count", waits.size());
            s.add(handoffs, delay.getKey(), "mean (s)", seconds(mean(waits)));
            s.add(handoffs, delay.getKey(), "longest (s)", seconds(max(waits)));
            s.add(handoffs, delay.getKey(), "total (s)", seconds(sum(waits)));
        }

        String balance = "Load balance";
        List<Long> robotBusy = new ArrayList<>();
        for (String robot : robots) {
            robotBusy.add(time(robot, BUSY));
            s.add(balance, robot, "busy (s)", seconds(time(robot, BUSY)));
            s.add(balance, robot, "busy (% of run)", percent(time(robot, BUSY), run));
            s.add(balance, robot, "charging (s)", seconds(time(robot, CHARGING)));
        }
        s.add(balance, "robots", "coefficient of variation of busy time", round(cv(robotBusy)));
        List<Long> handled = new ArrayList<>();
        for (Map.Entry<String, Integer> pp : ordersHandled.entrySet()) {
            handled.add((long) pp.getValue());
            s.add(balance, pp.getKey(), "orders handled", pp.getValue());
            s.add(balance, pp.getKey(), "filling an order (% of run)", percent(time(pp.getKey(), BUSY), run));
        }
        s.add(balance, "packaging points", "coefficient of variation of orders handled", round(cv(handled)));
        List<Long> active = new ArrayList<>();
        for (String name : names(Kind.WORKSTATION)) {
            active.add(time(name, BUSY));
            s.add(balance, name, "operating (s)", seconds(time(name, BUSY)));
            s.add(balance, name, "blocked: ready or done, waiting for an agent (s)", seconds(time(name, BLOCKED)));
        }
        s.add(balance, "workstations", "coefficient of variation of operating time", round(cv(active)));

        String waste = "Conflicts and waste";
        int totalFailures = 0;
        for (Map.Entry<String, Integer> failure : failures.entrySet()) {
            String[] parts = failure.getKey().split("\\|", 2);
            s.add(waste, parts[0], "failed " + parts[1], failure.getValue());
            totalFailures += failure.getValue();
        }
        s.add(waste, "all", "failed operations", totalFailures);
        for (String robot : robots) {
            int[] counts = moves.get(robot);
            s.add(waste, robot, "moves", counts[0]);
            s.add(waste, robot, "moves carrying nothing", counts[1]);
            s.add(waste, robot, "moves carrying nothing (%)", percent(counts[1], counts[0]));
            int[] travelled = distance.get(robot);
            s.add(waste, robot, "distance travelled (units)", travelled[0]);
            s.add(waste, robot, "distance travelled carrying nothing (%)", percent(travelled[1], travelled[0]));
            List<Double> arrivals = chargeArrivals.get(robot);
            long aboveHalf = arrivals.stream().filter(p -> p > 50).count();
            s.add(waste, robot, "charging visits", arrivals.size());
            s.add(waste, robot, "mean charge on arrival at charger (%)", round(arrivals.stream().mapToDouble(d -> d).average().orElse(0)));
            s.add(waste, robot, "charging visits with over 50% charge", aboveHalf);
        }
        s.add(waste, "all", "stalls (nothing happened for " + STALL_MS / 1000 + "s or more)", stalls);
        s.add(waste, "all", "time stalled (s)", seconds(stallTime));

        String broken = "Breakdowns";
        int total = 0;
        List<String> entities = new ArrayList<>(robots);
        entities.addAll(names(Kind.WORKSTATION));
        for (String entity : entities) {
            int count = breakdowns.getOrDefault(entity, 0);
            total += count;
            if (count == 0) continue;
            s.add(broken, entity, "breakdowns", count);
            s.add(broken, entity, "out of action (s)", seconds(time(entity, BROKEN)));
            s.add(broken, entity, "out of action (% of run)", percent(time(entity, BROKEN), run));
        }
        s.add(broken, "all", "breakdowns", total);
        return s;
    }

    private List<String> names(Kind kind) {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Kind> location : locations.entrySet()) {
            if (location.getValue() == kind) names.add(location.getKey());
        }
        return names;
    }

    // ------------------------------------------------------------------------------------------
    // Event log
    // ------------------------------------------------------------------------------------------

    private void log(String event, String entity, String key, String value) {
        lastEvent = System.currentTimeMillis();
        if (events == null) return;
        try {
            events.write((lastEvent - created) + "," + csv(event) + "," + csv(entity) + "," + csv(key) + "," + csv(value) + "\n");
            events.flush();
        } catch (IOException e) {
            System.err.println("Metrics: cannot write the event log: " + e.getMessage());
            events = null;
        }
    }

    private void closeLog() {
        if (events == null) return;
        try {
            events.close();
        } catch (IOException e) {
            // the log has been flushed after every line
        }
        events = null;
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private static Kind kind(String artifactType) {
        return switch (artifactType.substring(artifactType.lastIndexOf('.') + 1)) {
            case "AccessPoint" -> Kind.ACCESS;
            case "Workstation" -> Kind.WORKSTATION;
            case "StoragePoint" -> Kind.STORAGE;
            case "PackagingPoint" -> Kind.PACKAGING;
            case "RobotChargePoint" -> Kind.CHARGER;
            default -> Kind.OTHER;
        };
    }

    private static boolean isNone(String value) {
        return value == null || value.isEmpty() || value.equals(NONE) || value.equals("empty");
    }

    private static boolean isEmptyList(String value) {
        return value != null && value.replaceAll("[\\[\\]\\s]", "").isEmpty();
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String csv(String value) {
        if (value == null) return "";
        if (!value.contains(",") && !value.contains("\"") && !value.contains("\n")) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static long sum(List<Long> values) {
        long sum = 0;
        for (long v : values) sum += v;
        return sum;
    }

    private static double mean(List<Long> values) {
        return values.isEmpty() ? 0 : sum(values) / (double) values.size();
    }

    private static long max(List<Long> values) {
        long max = 0;
        for (long v : values) max = Math.max(max, v);
        return max;
    }

    /** Coefficient of variation: standard deviation / mean (0 is perfectly even). */
    private static double cv(List<Long> values) {
        double mean = mean(values);
        if (values.size() < 2 || mean == 0) return 0;
        double variance = 0;
        for (long v : values) variance += (v - mean) * (v - mean);
        return Math.sqrt(variance / values.size()) / mean;
    }

    private static double seconds(double millis) {
        return round(millis / 1000.0);
    }

    private static double percent(double part, double whole) {
        return whole == 0 ? 0 : round(100.0 * part / whole);
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
