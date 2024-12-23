package it.unitn.ds1.Client;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.Props;
import it.unitn.ds1.Client.messages.CrashedReplica;
import it.unitn.ds1.Client.messages.StartRequest;
import it.unitn.ds1.Messages.GroupInfo;
import it.unitn.ds1.Messages.ReadRequest;
import it.unitn.ds1.Messages.ReadResponse;
import it.unitn.ds1.Messages.WriteRequest;
import it.unitn.ds1.TestMessages.SendWriteRequestMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.File;
import java.io.IOException;
import java.time.Duration;

/**
 * This class represents the client actor
 * It sends read and write requests to the replicas
 * It also schedules timers on read operations to check if the replicas crash
 */
public class Client extends AbstractActor {
    @SuppressWarnings("unused")
    private int id;
    private double maxRequests = 10;
    List<ActorRef> replicas = new ArrayList<>();
    private final BufferedWriter writer;
    private Random random = new Random();
    private HashMap<Integer, ArrayList<Cancellable>> readRequestsTimers = new HashMap<>();
    private boolean manualWrites;
    private int valueToSend; //just for testing purposes, we are not assuming anything on the value to send

    /**
     * Constructor of the Client class
     * @param id the id of the client
     * @param logFolderName the name of the folder where the logs will be saved
     * @param manualWrites a boolean that indicates if the writes are manual or automatic
     * - if manualWrites is true, the client will wait for a message from the SimulationController to send a write request
     * @throws IOException
     */
    public Client(int id, String logFolderName, boolean manualWrites) throws IOException {
        int min = 15;
        int max = 20;
        this.id = id;
        valueToSend = id * 1000;
        this.manualWrites = manualWrites;
        this.maxRequests = random.nextInt(max - min + 1) + min;
        String directoryPath = logFolderName;
        String filePath = directoryPath + File.separator + getSelf().path().name() + ".txt";

        // Create the directory if it doesn't exist
        File directory = new File(directoryPath);
        if (!directory.exists()) {
            directory.mkdirs(); // Create the directory and any necessary parent directories
        }
        writer = new BufferedWriter(new FileWriter(filePath, false));
        log(getSelf().path().name() + " created " + " with max requests: " + maxRequests);

    }

    static public Props props(int id, String logFolderName, boolean manualWrites) {
        return Props.create(Client.class, () -> new Client(id, logFolderName, manualWrites));
    }

    /**
     * This method is called when the client receives a StartRequest message.
     * It sends this.maxRequests requests to random replicas
     * The requests can be read or write requests, there is a 50% chance for each
     * @param request the message that triggers the sending of the requests
     */
    private void onSendRequest(StartRequest request) {
        if (maxRequests <= 0) {
            log("max requests reached");
            return;
        }
        int randomValue = (int) (Math.random() * 100);
        if (randomValue < 50)
            sendReadRequest();
        else
            sendWriteRequest();

        maxRequests--;
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // Schedule the next request
        getSelf().tell(new StartRequest(), getSelf());
    }

    
    /**
     * This method is used to send a read request to a random replica
     * It also schedules a timer to check if the replica crashes
     */
    private void sendReadRequest() {
        int randomReplica = (int) (Math.random() * replicas.size());
        ActorRef replica = replicas.get(randomReplica);
        log("read req to " + replica.path().name());
        replica.tell(new ReadRequest(getSelf()), getSelf());
        this.readRequestsTimers.get(randomReplica).add(
            getContext().system().scheduler().scheduleOnce(
                Duration.ofSeconds(10),
                getSelf(),
                new CrashedReplica(replica),
                getContext().system().dispatcher(),
                getSelf()
            )
        );
    }

    /**
     * This method is called when the client receives the response to a read request
     * It logs the response and cancels the timer related to the replica that sent the response
     * @param response the message containing the value read
     */
    private void onReadResponse(ReadResponse response) {
        String msg = response.value == -1 ? "value not initialized"
                    : response.value + " from " + getSender().path().name();
        log("read completed: " + msg);
        int id = Integer.parseInt(getSender().path().name().split("_")[1]);
        this.readRequestsTimers.get(id).get(0).cancel();
        this.readRequestsTimers.get(id).remove(0);  
    }

    /**
     * This method is used to send a write request to a random replica
     */
    private void sendWriteRequest() {
        // Choose a random replica
        int randomReplica = (int) (Math.random() * replicas.size());
        ActorRef replica = replicas.get(randomReplica);
        log("write req to replica " + replica.path().name() + " with value " + this.valueToSend);
        replica.tell(new WriteRequest(this.valueToSend), getSelf());
        this.valueToSend++;
    }

    /**
     * This method is used to send a specific write request
     * @param msg the message containing the value and the replica index to which the write request should be sent
     */
    private void testWriteRequest(SendWriteRequestMessage msg) {
        ActorRef targetReplica = this.replicas.get(msg.replicaIndex);
        log("write req to replica " + targetReplica.path().name() + " with value " + msg.value);
        targetReplica.tell(new WriteRequest(msg.value), getSelf());
    }

    /**
     * This method is called when the client receives the replicas info
     * It stores the replicas in this.replicas
     * @param replicasInfo the message containing the ActoreRef of the replicas
     */
    private void onReplicasInfo(GroupInfo replicasInfo) {
        for (ActorRef replica : replicasInfo.group) {
            this.replicas.add(replica);
            int id = Integer.parseInt(replica.path().name().split("_")[1]);
            this.readRequestsTimers.put(id, new ArrayList<>());
        }
        log("received replicas info");
        log("Replicas size: " + replicas.size());
        // Schedule the first request
        if (!manualWrites) {
            getSelf().tell(new StartRequest(), getSelf());
        }
    }

    /**
     * This method is used to log messages
     * @param message the message to log
     */
    private void log(String message) {
        String msg = getSelf().path().name() + ": " + message;
        try {
            writer.write(msg + System.lineSeparator());
            writer.flush();
            System.out.println(msg);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * This method is called when a replica crashes
     * It removes the crashed replica from the list of replicas
     * It also cancels all the timers related to the crashed replica
     * @param crashedReplica the message containing the ActorRef of the crashed replica
     */
    private void onCrashedReplica(CrashedReplica crashedReplica) {
        ActorRef crashedReplicaRef = crashedReplica.crashedReplica;
        int id = Integer.parseInt(crashedReplicaRef.path().name().split("_")[1]);
        log("Replica " + id + " crashed");
        for (Cancellable timer : this.readRequestsTimers.get(id)) {
            timer.cancel();
        }
        this.readRequestsTimers.get(id).clear();
        this.replicas.remove(crashedReplicaRef);
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(GroupInfo.class, this::onReplicasInfo)
                .match(StartRequest.class, this::onSendRequest)
                .match(SendWriteRequestMessage.class, this::testWriteRequest)
                .match(CrashedReplica.class, this::onCrashedReplica)
                .match(ReadResponse.class, this::onReadResponse)
                .build();
    }

    

}