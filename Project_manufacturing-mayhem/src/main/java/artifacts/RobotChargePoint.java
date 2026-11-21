package artifacts;

import cartago.ARTIFACT_INFO;
import cartago.Artifact;
import cartago.ArtifactConfig;
import cartago.LINK;
import cartago.OPERATION;
import cartago.OUTPORT;
import cartago.OpFeedbackParam;
import cartago.OperationException;

@ARTIFACT_INFO(outports = { @OUTPORT(name="out-fp") })
public class RobotChargePoint extends Artifact {
    public static final String NONE = "none";
    private static final String CHARGE_RATE = "chargeRate";
    private int maxEnergy;

    /**
     * @param maxEnergy  the battery capacity of the robot this charge point creates
     * @param chargeRate how much energy one charging step adds
     */
    @OPERATION
    public void init(int maxEnergy, int chargeRate) {
        this.maxEnergy = maxEnergy;
        defineObsProperty("available", NONE);
        defineObsProperty(CHARGE_RATE, chargeRate);
    }

    @OPERATION
    public void install(String name) throws OperationException{
        execLinkedOp("out-fp", "install", getId(), new String[] {});
        
        try {
            makeArtifact(name, "artifacts.Robot", new ArtifactConfig(getId().getName(), maxEnergy));
            execLinkedOp("out-fp", "place", name, getId().getName(), maxEnergy);
        } catch (OperationException e) {
            e.printStackTrace();
        }
    }

    /** Called by a robot to find out how much one charging step gives it. */
    @LINK
    public void chargeRate(OpFeedbackParam<Integer> rate) {
        rate.set(getObsProperty(CHARGE_RATE).intValue());
    }
}