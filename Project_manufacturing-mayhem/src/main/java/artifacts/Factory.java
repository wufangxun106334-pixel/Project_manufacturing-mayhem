package artifacts;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import cartago.Artifact;
import cartago.OPERATION;
import cartago.OpFeedbackParam;
import model.Project;

/**
 * Loads a factory configuration file (see src/main/resources/schema/factory.schema.json) and makes it available to
 * the agent that builds the factory.
 *
 * A configuration is checked against the schema, and then for the things a schema cannot check:
 * that every name is unique and that every workstation input and order item can be obtained,
 * either from the warehouse or as the output of a workstation.
 *
 * Once a configuration is loaded, these observable properties hold lists of names:
 * warehouse (item types in stock), accessPoints, storagePoints, packagingPoints, workstations,
 * robots and orderItems. The details are read with the stock, workstation, robot and
 * orderCount operations.
 */
public class Factory extends Artifact {
    private static final String NONE = "none";
    private static final int DEFAULT_MAX_ENERGY = 800;
    private static final int DEFAULT_CHARGE_RATE = 300;
    private static final String[] LIST_PROPERTIES = {
        "warehouse", "accessPoints", "storagePoints", "packagingPoints", "workstations", "robots", "orderItems"
    };

    private final ObjectMapper mapper = new ObjectMapper();
    private JsonSchema schema;
    private JsonNode config;
    private String configPath;
    private long seed;

    @OPERATION
    public void init(String schemaPath) throws IOException {
        try (InputStream in = open(schemaPath)) {
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
        }

        defineObsProperty("configuration", NONE);
        for (String name : LIST_PROPERTIES) defineObsProperty(name, new ArrayList<String>());
    }

    /** Loads and validates a configuration file; fails, listing every problem, if it is invalid. */
    @OPERATION
    public void load(String configPath) throws Exception {
        JsonNode node;
        try (InputStream in = open(configPath)) {
            node = mapper.readTree(in);
        } catch (IOException e) {
            throw new Exception("Factory: cannot read " + configPath + ": " + e.getMessage());
        }

        List<String> problems = new ArrayList<>();
        for (ValidationMessage message : schema.validate(node)) problems.add(message.getMessage());
        if (problems.isEmpty()) problems.addAll(crossCheck(node));
        if (!problems.isEmpty()) {
            throw new Exception("Factory: " + configPath + " is not a valid configuration:\n  - " + String.join("\n  - ", problems));
        }

        config = node;
        this.configPath = configPath;
        // Without a seed, choose one (short enough to copy into a configuration to repeat the run)
        seed = node.has("seed") ? node.get("seed").asLong() : new Random().nextInt(1_000_000);
        updateObsProperty("configuration", configPath);
        updateObsProperty("warehouse", fieldNames(node.get("warehouse")));
        updateObsProperty("accessPoints", strings(node.get("accessPoints")));
        updateObsProperty("storagePoints", strings(node.path("storagePoints")));
        updateObsProperty("packagingPoints", strings(node.get("packagingPoints")));
        updateObsProperty("workstations", names(node.get("workstations")));
        updateObsProperty("robots", names(node.get("robots")));
        updateObsProperty("orderItems", strings(node.get("orders").get("items")));
    }

    @OPERATION
    public void stock(String item, OpFeedbackParam<Integer> quantity) throws Exception {
        JsonNode value = loaded().get("warehouse").get(item);
        if (value == null) throw new Exception("Factory: the warehouse has no " + item);
        quantity.set(value.asInt());
    }

    @OPERATION
    public void workstation(String name, OpFeedbackParam<List<String>> inputs, OpFeedbackParam<String> output,
            OpFeedbackParam<Long> duration) throws Exception {
        JsonNode workstation = named(loaded().get("workstations"), "workstation", name);
        inputs.set(strings(workstation.get("inputs")));
        output.set(workstation.get("output").asText());
        duration.set(workstation.get("duration").asLong());
    }

    /** The robot's battery capacity, its charge point's charge rate, and its agent class or "none". */
    @OPERATION
    public void robot(String name, OpFeedbackParam<Integer> maxEnergy, OpFeedbackParam<Integer> chargeRate,
            OpFeedbackParam<String> agent) throws Exception {
        JsonNode robot = named(loaded().get("robots"), "robot", name);
        maxEnergy.set(robot.path("maxEnergy").asInt(DEFAULT_MAX_ENERGY));
        chargeRate.set(robot.path("chargeRate").asInt(DEFAULT_CHARGE_RATE));
        agent.set(robot.path("agent").asText(NONE));
    }

    /** How many orders to create. */
    @OPERATION
    public void orderCount(OpFeedbackParam<Integer> count) throws Exception {
        count.set(loaded().get("orders").get("count").asInt());
    }

    /**
     * Opens a file, or failing that a classpath resource (such as the schema, which is in
     * src/main/resources). A relative file path is resolved against the project directory, not the
     * working directory, because the simulation runs in target/.
     */
    private static InputStream open(String path) throws IOException {
        Path file = Project.directory().resolve(path);
        if (Files.isReadable(file)) return Files.newInputStream(file);
        InputStream in = Factory.class.getClassLoader().getResourceAsStream(path);
        if (in == null) throw new IOException("Factory: cannot find " + file + " or " + path + " on the classpath");
        return in;
    }

    /** The scenario's name (or its file name), description and random seed. */
    @OPERATION
    public void scenario(OpFeedbackParam<String> name, OpFeedbackParam<String> description, OpFeedbackParam<Long> seed)
            throws Exception {
        JsonNode config = loaded();
        name.set(config.path("name").asText(Path.of(configPath).getFileName().toString()));
        description.set(config.path("description").asText(""));
        seed.set(this.seed);
    }

    /**
     * The scenario's options. Without order arrivals, every order exists at the start
     * (initialOrders is the number of orders).
     */
    @OPERATION
    public void options(OpFeedbackParam<Boolean> travelDistance, OpFeedbackParam<Boolean> orderArrivals,
            OpFeedbackParam<Integer> initialOrders, OpFeedbackParam<Long> meanInterval,
            OpFeedbackParam<Boolean> breakdowns, OpFeedbackParam<Double> probability, OpFeedbackParam<Long> repairTime)
            throws Exception {
        JsonNode options = loaded().path("options");
        travelDistance.set(enabled(options.path("travelDistance")));

        JsonNode arrivals = options.path("orderArrivals");
        orderArrivals.set(enabled(arrivals));
        initialOrders.set(enabled(arrivals) ? arrivals.get("initialOrders").asInt() : config.get("orders").get("count").asInt());
        meanInterval.set(arrivals.path("meanInterval").asLong(0));

        JsonNode failures = options.path("breakdowns");
        breakdowns.set(enabled(failures));
        probability.set(failures.path("probability").asDouble(0));
        repairTime.set(failures.path("repairTime").asLong(0));
    }

    private static boolean enabled(JsonNode option) {
        return option.path("enabled").asBoolean(false);
    }

    private JsonNode loaded() throws Exception {
        if (config == null) throw new Exception("Factory: no configuration has been loaded");
        return config;
    }

    /** The checks that cannot be expressed in the schema. */
    private static List<String> crossCheck(JsonNode node) {
        List<String> problems = new ArrayList<>();

        Set<String> seen = new HashSet<>();
        List<String> all = new ArrayList<>();
        all.addAll(strings(node.get("accessPoints")));
        all.addAll(strings(node.path("storagePoints")));
        all.addAll(strings(node.get("packagingPoints")));
        all.addAll(names(node.get("workstations")));
        all.addAll(names(node.get("robots")));
        for (String name : all) {
            if (!seen.add(name)) problems.add("the name " + name + " is used more than once");
        }

        Set<String> obtainable = new HashSet<>(fieldNames(node.get("warehouse")));
        for (JsonNode workstation : node.get("workstations")) obtainable.add(workstation.get("output").asText());
        for (JsonNode workstation : node.get("workstations")) {
            for (String input : strings(workstation.get("inputs"))) {
                if (!obtainable.contains(input)) {
                    problems.add("workstation " + workstation.get("name").asText() + " needs " + input
                            + ", which is not in the warehouse or made by any workstation");
                }
            }
        }
        JsonNode arrivals = node.path("options").path("orderArrivals");
        int count = node.get("orders").get("count").asInt();
        if (enabled(arrivals) && arrivals.get("initialOrders").asInt() > count) {
            problems.add("orderArrivals.initialOrders (" + arrivals.get("initialOrders").asInt()
                    + ") is more than the number of orders (" + count + ")");
        }

        for (String item : strings(node.get("orders").get("items"))) {
            if (!obtainable.contains(item)) {
                problems.add("orders can be for " + item + ", which is not in the warehouse or made by any workstation");
            }
        }
        return problems;
    }

    private static JsonNode named(JsonNode array, String kind, String name) throws Exception {
        for (JsonNode element : array) {
            if (element.get("name").asText().equals(name)) return element;
        }
        throw new Exception("Factory: no " + kind + " called " + name);
    }

    private static List<String> strings(JsonNode array) {
        List<String> list = new ArrayList<>();
        for (JsonNode element : array) list.add(element.asText());
        return list;
    }

    private static List<String> names(JsonNode array) {
        List<String> list = new ArrayList<>();
        for (JsonNode element : array) list.add(element.get("name").asText());
        return list;
    }

    private static List<String> fieldNames(JsonNode object) {
        List<String> list = new ArrayList<>();
        object.fieldNames().forEachRemaining(list::add);
        return list;
    }
}
