package artifacts;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import cartago.ARTIFACT_INFO;
import cartago.Artifact;
import cartago.ArtifactId;
import cartago.INTERNAL_OPERATION;
import cartago.LINK;
import cartago.OPERATION;
import cartago.OUTPORT;
import cartago.OpFeedbackParam;
import cartago.OperationException;
import metrics.Metrics;

/**
 * A fixed number of random customer orders. For now each order is for one or two of a single
 * item. They all exist from the start or, if order arrivals are on, some exist from the start and
 * the rest arrive one at a time during the run.
 *
 * Orders are assigned to packaging points in the order they were created. An assigned order is
 * COMPLETED, and moved to the archive, when its packaging point delivers it.
 *
 * Observable properties:
 *   nextOrder(id, item, quantity)                       the next order to assign ("none", "none", 0 if there is none)
 *   outstandingOrders(n)                                the number of orders waiting to be assigned
 *   ordersToArrive(n)                                   the number of orders that have not arrived yet
 *   assignedOrders(n)                                   the number of orders assigned but not yet delivered
 *   assignedOrder(id, item, quantity, packagingPoint)   one per assigned order
 *   completedOrder(id, item, quantity, packagingPoint, duration)
 *                                                       one per completed order; duration is the
 *                                                       time from assignment to delivery, in ms
 *   archive                                             the ids of the completed orders
 *   items                                               the item types that orders can be for
 */
@ARTIFACT_INFO(outports = { @OUTPORT(name="out-fp") })
public class Orders extends Artifact {
    public static final String ASSIGNED = "ASSIGNED";
    public static final String COMPLETED = "COMPLETED";
    private static final String NONE = "none";
    private static final String ITEMS = "items";
    private static final String NEXT_ORDER = "nextOrder";
    private static final String OUTSTANDING_ORDERS = "outstandingOrders";
    private static final String ORDERS_TO_ARRIVE = "ordersToArrive";
    private static final String ASSIGNED_ORDERS = "assignedOrders";
    private static final String ASSIGNED_ORDER = "assignedOrder";
    private static final String COMPLETED_ORDER = "completedOrder";
    private static final String ARCHIVE = "archive";
    private static final String PACKAGING_POINT = "artifacts.PackagingPoint";
    private static final int MAX_QUANTITY = 2;

    private static class Order {
        final String id;
        final String item;
        final int quantity;
        String packagingPoint = NONE;
        long arrivedAt;
        long assignedAt;
        long duration;

        Order(String id, String item, int quantity) {
            this.id = id;
            this.item = item;
            this.quantity = quantity;
        }

        List<String> items() {
            return new ArrayList<>(Collections.nCopies(quantity, item));
        }
    }

    private final Deque<Order> toArrive = new ArrayDeque<>();
    private final Deque<Order> waiting = new ArrayDeque<>();
    private final Map<String, Order> assigned = new LinkedHashMap<>();
    private final Map<String, Order> archive = new LinkedHashMap<>();
    private boolean configured;

    @OPERATION
    public void init() {
        defineObsProperty(ITEMS, new ArrayList<String>());
        defineObsProperty(NEXT_ORDER, NONE, NONE, 0);
        defineObsProperty(OUTSTANDING_ORDERS, 0);
        defineObsProperty(ORDERS_TO_ARRIVE, 0);
        defineObsProperty(ASSIGNED_ORDERS, 0);
        defineObsProperty(ARCHIVE, new ArrayList<String>());
    }

    /**
     * Creates {@code count} random orders (order-1, order-2, ...) for the given item types, chosen
     * using {@code seed}. The first {@code initialOrders} exist straight away; the rest arrive one
     * at a time, on average every {@code meanInterval} ms.
     */
    @OPERATION
    public void configure(List<String> items, int count, long seed, int initialOrders, long meanInterval) throws Exception {
        if (configured) throw new Exception("Orders: the orders have already been created");
        if (items.isEmpty()) throw new Exception("Orders: the list of items is empty");
        if (count < 1) throw new Exception("Orders: the number of orders must be at least 1");

        configured = true;
        updateObsProperty(ITEMS, new ArrayList<>(items));
        Random random = new Random(seed);
        long now = System.currentTimeMillis();
        for (int i = 1; i <= count; i++) {
            String item = items.get(random.nextInt(items.size()));
            Order order = new Order("order-" + i, item, 1 + random.nextInt(MAX_QUANTITY));
            if (i <= initialOrders) {
                order.arrivedAt = now;
                waiting.add(order);
            } else {
                toArrive.add(order);
            }
        }
        updateQueue();
        if (!toArrive.isEmpty()) execInternalOp("arrivals", seed + 1, meanInterval);
    }

    /** Releases the remaining orders one at a time, between 0.5 and 1.5 times the mean interval apart. */
    @INTERNAL_OPERATION
    void arrivals(long seed, long meanInterval) throws OperationException {
        Random random = new Random(seed);
        while (!toArrive.isEmpty()) {
            await_time(meanInterval / 2 + (long) (random.nextDouble() * meanInterval));
            Order order = toArrive.removeFirst();
            order.arrivedAt = System.currentTimeMillis();
            waiting.add(order);
            updateQueue();
            signal("newOrder", order.id, order.item, order.quantity);
        }
    }

    /** Gives the next order to a packaging point, which then waits for its items. */
    @OPERATION
    public void assign(String packagingPoint, OpFeedbackParam<String> id) throws Exception {
        try {
            doAssign(packagingPoint, id);
        } catch (Exception e) {
            reportFailure("assign an order to " + packagingPoint, e);
            throw e;
        }
    }

    private void doAssign(String packagingPoint, OpFeedbackParam<String> id) throws Exception {
        Order order = waiting.peekFirst();
        if (order == null) throw new Exception("Orders: there are no orders left to assign");

        OpFeedbackParam<ArtifactId> device = new OpFeedbackParam<>();
        try {
            execLinkedOp("out-fp", "location", packagingPoint, device);
        } catch (OperationException e) {
            throw new Exception("Orders: no location called " + packagingPoint);
        }
        if (!device.get().getArtifactType().equals(PACKAGING_POINT)) {
            throw new Exception("Orders: " + packagingPoint + " is not a packaging point");
        }
        // Fails, leaving the order next in the queue, if the packaging point already has an order.
        execLinkedOp(device.get(), "assign", getId(), order.id, order.items());

        waiting.removeFirst();
        order.packagingPoint = packagingPoint;
        order.assignedAt = System.currentTimeMillis();
        assigned.put(order.id, order);
        defineObsProperty(ASSIGNED_ORDER, order.id, order.item, order.quantity, packagingPoint);
        updateQueue();
        show(order, ASSIGNED, order.assignedAt - order.arrivedAt);
        id.set(order.id);
    }

    /** Called by a packaging point once it has delivered an order. */
    @LINK
    public void completed(String id) throws Exception {
        Order order = assigned.remove(id);
        if (order == null) throw new Exception("Orders: " + id + " is not an assigned order");
        removeObsPropertyByTemplate(ASSIGNED_ORDER, order.id, order.item, order.quantity, order.packagingPoint);
        order.duration = System.currentTimeMillis() - order.assignedAt;
        defineObsProperty(COMPLETED_ORDER, order.id, order.item, order.quantity, order.packagingPoint, order.duration);
        archive.put(id, order);
        updateObsProperty(ARCHIVE, new ArrayList<>(archive.keySet()));
        updateObsProperty(ASSIGNED_ORDERS, assigned.size());
        show(order, COMPLETED, order.duration);
    }

    /** Updates the next order and the counts after an order is created or assigned. */
    private void updateQueue() throws OperationException {
        Order next = waiting.peekFirst();
        if (next == null) updateObsProperty(NEXT_ORDER, NONE, NONE, 0);
        else updateObsProperty(NEXT_ORDER, next.id, next.item, next.quantity);
        updateObsProperty(OUTSTANDING_ORDERS, waiting.size());
        updateObsProperty(ORDERS_TO_ARRIVE, toArrive.size());
        updateObsProperty(ASSIGNED_ORDERS, assigned.size());

        if (next == null) execLinkedOp("out-fp", "setNextOrder", NONE, NONE, 0, 0, toArrive.size());
        else execLinkedOp("out-fp", "setNextOrder", next.id, next.item, next.quantity, waiting.size(), toArrive.size());
    }

    /**
     * Tells the viewer and metrics about an order. duration is how long it waited to be assigned
     * (ASSIGNED) or took from assignment to delivery (COMPLETED), in ms.
     */
    private void show(Order order, String status, long duration) throws OperationException {
        execLinkedOp("out-fp", "setOrder", order.id, order.item, order.quantity, status, order.packagingPoint, duration);
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
