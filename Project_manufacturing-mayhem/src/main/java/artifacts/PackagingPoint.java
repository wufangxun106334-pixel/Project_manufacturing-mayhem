package artifacts;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import cartago.ARTIFACT_INFO;
import cartago.Artifact;
import cartago.ArtifactId;
import cartago.LINK;
import cartago.OPERATION;
import cartago.OUTPORT;
import cartago.OperationException;
import metrics.Metrics;
import model.Item;

/**
 * Collects the items for one order at a time. An order is assigned by the {@link Orders}
 * artifact; once all of its items have been given, deliver() sends it and tells Orders that it
 * is complete, leaving the packaging point free for the next order.
 *
 * Observable properties: order (the order id, or "none"), items (everything the order needs) and
 * outstanding (the items still to be given).
 */
@ARTIFACT_INFO(outports = { @OUTPORT(name="out-1"), @OUTPORT(name="out-fp") })
public class PackagingPoint extends Artifact {
    public static final String NONE = "none";
    private static final String ORDER = "order";
    private static final String ITEMS = "items";
    private static final String OUTSTANDING = "outstanding";

    private List<Item> items = new LinkedList<>();
    private ArtifactId orders;

    @OPERATION
    public void init() {
        defineObsProperty(ORDER, NONE);
        defineObsProperty(ITEMS, new ArrayList<String>());
        defineObsProperty(OUTSTANDING, new ArrayList<String>());
    }

    @OPERATION
    public void install() throws OperationException {
        execLinkedOp("out-fp", "install", getId(), new String[] {ORDER, ITEMS, OUTSTANDING});
        show();
    }

    /** Called by the Orders artifact to give this packaging point an order to fill. */
    @LINK
    public void assign(ArtifactId orders, String id, List<String> required) throws Exception {
        String current = getObsProperty(ORDER).stringValue();
        if (!current.equals(NONE)) {
            throw new Exception("Packaging point " + getId().getName() + " is already filling " + current);
        }
        this.orders = orders;
        updateObsProperty(ORDER, id);
        updateObsProperty(ITEMS, new ArrayList<>(required));
        updateObsProperty(OUTSTANDING, new ArrayList<>(required));
        show();
    }

    @OPERATION
    public void deliver() throws Exception {
        try {
            doDeliver();
        } catch (Exception e) {
            reportFailure("deliver", e);
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private void doDeliver() throws Exception {
        String id = getObsProperty(ORDER).stringValue();
        if (id.equals(NONE)) throw new Exception("No order to deliver at: " + getId().getName());
        List<String> outstanding = (List<String>) getObsProperty(OUTSTANDING).getValue();
        if (!outstanding.isEmpty()) {
            throw new Exception("Order " + id + " at " + getId().getName() + " is still waiting for: " + outstanding);
        }

        execLinkedOp(orders, "completed", id);
        System.out.println("Delivered " + id + ": " + types(items));
        items.clear();
        orders = null;
        updateObsProperty(ORDER, NONE);
        updateObsProperty(ITEMS, new ArrayList<String>());
        show();
    }

    @SuppressWarnings("unchecked")
    @LINK
    public void give(Item item) throws Exception {
        List<String> outstanding = new ArrayList<>((List<String>) getObsProperty(OUTSTANDING).getValue());
        if (!outstanding.remove(item.type())) {
            throw new Exception("Item: " + item.type() + "  not required by: " + getId().getName());
        }
        items.add(item);
        updateObsProperty(OUTSTANDING, outstanding);
        show();
    }

    private void show() throws OperationException {
        String name = getId().getName();
        execLinkedOp("out-fp", "setCustomField", name, ORDER, getObsProperty(ORDER).stringValue());
        execLinkedOp("out-fp", "setCustomField", name, ITEMS, getObsProperty(ITEMS).getValue().toString());
        execLinkedOp("out-fp", "setCustomField", name, OUTSTANDING, getObsProperty(OUTSTANDING).getValue().toString());
    }

    private static List<String> types(List<Item> items) {
        List<String> types = new ArrayList<>();
        for (Item item : items) types.add(item.type());
        return types;
    }

    /** Records a failed operation in the floorplan's metrics; the failure itself is still thrown. */
    private void reportFailure(String operation, Exception e) {
        try {
            execLinkedOp("out-fp", "recordFailure", getId().getName(), operation, Metrics.reason(e));
        } catch (Exception ignored) {
            // metrics are best effort and must not hide the original failure
        }
    }
}
