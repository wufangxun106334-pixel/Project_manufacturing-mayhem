package artifacts;

import java.util.Arrays;
import java.util.List;

import cartago.ARTIFACT_INFO;
import cartago.Artifact;
import cartago.ArtifactId;
import cartago.OPERATION;
import cartago.OUTPORT;
import cartago.OpFeedbackParam;
import metrics.Metrics;
import model.Item;


@ARTIFACT_INFO(outports = { @OUTPORT(name="out-fp")})
public class Robot extends Artifact {
    private static final List<String> LOAD_TYPES = Arrays.asList(new String[] {"artifacts.AccessPoint", "artifacts.StoragePoint", "artifacts.Workstation"});
    private static final List<String> UNLOAD_TYPES = Arrays.asList(new String[] {"artifacts.PackagingPoint", "artifacts.StoragePoint", "artifacts.Workstation"});
    private static final String AT = "at";
    private static final String ENERGY = "energy";
    private static final String MAX_ENERGY = "maxEnergy";
    private static final String HOLDING = "holding";
    private static final String NONE = "none";
    private static final String STATE = "state";
    private static final String IDLE = "idle";
    private static final String MOVING = "moving";
    private static final String LOADING = "loading";
    private static final String UNLOADING = "unloading";
    private static final String CHARGING = "charging";
    private static final String BROKEN = "broken";
    private static final String CHARGE_POINT = "artifacts.RobotChargePoint";

    private static final int MOVE_COST = 100;
    private static final int MOVE_COST_PER_UNIT = 25;
    private static final int LOAD_COST = 50;

    private Item item;
    
    @OPERATION
    public void init(String location, int maxEnergy) {
        defineObsProperty(STATE, IDLE);
        defineObsProperty(HOLDING, NONE);
        defineObsProperty(AT, location);
        defineObsProperty(ENERGY, maxEnergy);
        defineObsProperty(MAX_ENERGY, maxEnergy);
    }

    @OPERATION
    public void move(String location) throws Exception {
        try {
            doMove(location);
        } catch (Exception e) {
            reportFailure("move to " + location, e);
            throw e;
        }
    }

    /** A move costs 25 energy and takes 1 s per unit of distance (see Floorplan.travel). */
    private void doMove(String location) throws Exception {
        String at = getObsProperty(AT).stringValue();
        OpFeedbackParam<Integer> units = new OpFeedbackParam<>();
        execLinkedOp("out-fp", "travel", at, location, units);
        int cost = units.get() * MOVE_COST_PER_UNIT;

        int energy = getObsProperty(ENERGY).intValue();
        if (energy < cost) {
            throw new Exception("Insufficient Energy to move: " + getId().getName() + " needs " + cost + " but has " + energy);
        }

        updateObsProperty(STATE, MOVING);
        execLinkedOp("out-fp", "setRobotField", getId().getName(), "status", MOVING);
        breakDownIfUnlucky("move to " + location, MOVING);

        long millis = (long) units.get() * Floorplan.MOVE_MILLIS_PER_UNIT;
        execLinkedOp("out-fp", "setRobotField", getId().getName(), "moveMillis", String.valueOf(millis));
        execLinkedOp("out-fp", "setRobotField", getId().getName(), "to", location);
        try {
            execLinkedOp("out-fp", "move", getId().getName(), at, location, millis);
        } finally {
            execLinkedOp("out-fp", "setRobotField", getId().getName(), "to", NONE);
        }

        updateObsProperty(STATE, IDLE);
        updateObsProperty(AT, location);
        updateObsProperty(ENERGY, energy - cost);
        execLinkedOp("out-fp", "setRobotField", getId().getName(), "status", IDLE);
        execLinkedOp("out-fp", "setRobotField", getId().getName(), "at", location);
        execLinkedOp("out-fp", "setRobotField", getId().getName(), "energy", getObsProperty(ENERGY).stringValue());
    }

    /**
     * One charging step: adds the charge point's charge rate, up to the robot's maxEnergy. A
     * slow charge point may need several steps to fill the robot.
     */
    @OPERATION
    public void charge() throws Exception {
        try {
            doCharge();
        } catch (Exception e) {
            reportFailure("charge", e);
            throw e;
        }
    }

    private void doCharge() throws Exception {
        String at = getObsProperty(AT).stringValue();
        ArtifactId deviceId = getDeviceArtifactId(at);
        if (!deviceId.getArtifactType().equals(CHARGE_POINT)) {
            throw new Exception("Not at a charge point: " + getId().getName() + " is at " + at);
        }
        int energy = getObsProperty(ENERGY).intValue();
        int maxEnergy = getObsProperty(MAX_ENERGY).intValue();
        if (energy >= maxEnergy) return;

        OpFeedbackParam<Integer> rate = new OpFeedbackParam<>();
        execLinkedOp(deviceId, "chargeRate", rate);

        execLinkedOp("out-fp", "setRobotField", getId().getName(), "status", CHARGING);
        Thread.sleep(1000);
        updateObsProperty(ENERGY, Math.min(maxEnergy, energy + rate.get()));
        execLinkedOp("out-fp", "setRobotField", getId().getName(), "status", IDLE);
        execLinkedOp("out-fp", "setRobotField", getId().getName(), "energy", getObsProperty(ENERGY).stringValue());
    }


    /**
     * With breakdowns on, the robot sometimes breaks down: its state is "broken" until it is
     * repaired, and then it carries on with the operation.
     */
    private void breakDownIfUnlucky(String operation, String state) throws Exception {
        OpFeedbackParam<Long> repair = new OpFeedbackParam<>();
        execLinkedOp("out-fp", "breakdown", getId().getName(), operation, repair);
        if (repair.get() == 0) return;

        updateObsProperty(STATE, BROKEN);
        execLinkedOp("out-fp", "setRobotField", getId().getName(), "status", BROKEN);
        Thread.sleep(repair.get());
        updateObsProperty(STATE, state);
        execLinkedOp("out-fp", "setRobotField", getId().getName(), "status", state);
    }

    /** Records a failed operation in the floorplan's metrics; the failure itself is still thrown. */
    private void reportFailure(String operation, Exception e) {
        try {
            execLinkedOp("out-fp", "recordFailure", getId().getName(), operation, Metrics.reason(e));
        } catch (Exception ignored) {
            // metrics are best effort and must not hide the original failure
        }
    }

    private ArtifactId getDeviceArtifactId(String at) throws Exception {
        OpFeedbackParam<ArtifactId> deviceId = new OpFeedbackParam<>();
        
        // Getting device at location...
        execLinkedOp("out-fp", "location", at, deviceId);

        // Checking Device Type (should be artifacts.AccessPoint)
        return deviceId.get();
    }

    @OPERATION
    public void load() throws Exception {
        try {
            doLoad();
        } catch (Exception e) {
            reportFailure("load", e);
            throw e;
        }
    }

    private void doLoad() throws Exception {
        int energy = getObsProperty(ENERGY).intValue();
        if (energy < MOVE_COST) throw new Exception("Insufficient Energy to load: " + getId().getName());

        String at = getObsProperty(AT).stringValue();
        ArtifactId did = getDeviceArtifactId(at);
        if (!LOAD_TYPES.contains(did.getArtifactType())) {
            throw new Exception("Invalid Location:  " + at + " device: " + did.getArtifactType());
        }

        // Executing Operation on device (hopefully)
        updateObsProperty(STATE, LOADING);
        execLinkedOp("out-fp", "setRobotField", getId().getName(), "status", LOADING);
        breakDownIfUnlucky("load", LOADING);

        Thread.sleep(2000);
        OpFeedbackParam<Item> item = new OpFeedbackParam<>();
        execLinkedOp(did, "take", item);
        this.item = item.get();

        updateObsProperty(HOLDING, this.item.type());
        updateObsProperty(ENERGY, energy - LOAD_COST);
        updateObsProperty(STATE, IDLE);
        execLinkedOp("out-fp", "setRobotField", getId().getName(), "status", IDLE);
        execLinkedOp("out-fp", "setRobotField", getId().getName(), "holding", this.item.type());
        execLinkedOp("out-fp", "setRobotField", getId().getName(), "energy", getObsProperty(ENERGY).stringValue());
    }

    @OPERATION
    public void unload() throws Exception {
        try {
            doUnload();
        } catch (Exception e) {
            reportFailure("unload", e);
            throw e;
        }
    }

    private void doUnload() throws Exception {
        int energy = getObsProperty(ENERGY).intValue();
        if (energy < MOVE_COST) throw new Exception("Insufficient Energy to unload: " + getId().getName());

        String at = getObsProperty(AT).stringValue();
        ArtifactId did = getDeviceArtifactId(at);
        if (!UNLOAD_TYPES.contains(did.getArtifactType())) {
            throw new Exception("Invalid Location:  " + at + " device: " + did.getArtifactType());
        }

        // Executing Operation on device (hopefully)
        updateObsProperty(STATE, UNLOADING);
        execLinkedOp("out-fp", "setRobotField", getId().getName(), "status", UNLOADING);
        breakDownIfUnlucky("unload", UNLOADING);

        Thread.sleep(2000);
        execLinkedOp(did, "give", item);
        updateObsProperty(HOLDING, NONE);
        updateObsProperty(ENERGY, energy - LOAD_COST);
        updateObsProperty(STATE, IDLE);
        execLinkedOp("out-fp", "setRobotField", getId().getName(), "status", IDLE);
        execLinkedOp("out-fp", "setRobotField", getId().getName(), "holding", NONE);
        execLinkedOp("out-fp", "setRobotField", getId().getName(), "energy", getObsProperty(ENERGY).stringValue());
    }
}
