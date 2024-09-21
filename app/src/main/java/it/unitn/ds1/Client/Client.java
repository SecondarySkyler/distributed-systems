package it.unitn.ds1.Client;

import java.util.concurrent.TimeUnit;
import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.Props;
import it.unitn.ds1.Client.messages.StartRequest;
import it.unitn.ds1.Messages.GroupInfo;
import it.unitn.ds1.Messages.ReadRequest;
import it.unitn.ds1.Messages.ReadResponse;
import it.unitn.ds1.Messages.WriteRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.File;
import java.io.IOException;

public class Client extends AbstractActor {
    private int id;
    private double maxRequests = 10;
    List<ActorRef> replicas = new ArrayList<>();
    private final BufferedWriter writer;
    private Random random = new Random();

    public Client(int id) throws IOException {
        int min = 5;
        int max = 10;
        this.id = id;
        this.maxRequests = random.nextInt(max - min + 1) + min;
        String directoryPath = "logs";
        String filePath = directoryPath + File.separator + getSelf().path().name() + ".txt";

        // Create the directory if it doesn't exist
        File directory = new File(directoryPath);
        if (!directory.exists()) {
            directory.mkdirs(); // Create the directory and any necessary parent directories
        }
        writer = new BufferedWriter(new FileWriter(filePath, false));
        log(getSelf().path().name() + " created " + " with max requests: " + maxRequests);

    }

    static public Props props(int id) {
        return Props.create(Client.class, () -> new Client(id));
    }

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
        // network delay
        try {
            Thread.sleep(random.nextInt(2000));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        // Schedule the next request
        getSelf().tell(new StartRequest(), getSelf());
    }

    private void sendReadRequest() {
        // Choose a random replica
        int randomReplica = (int) (Math.random() * replicas.size());
        ActorRef replica = replicas.get(randomReplica);
        // TODO: get replica actual id
        log("read req to " + replica.path().name());
        replica.tell(new ReadRequest(getSelf()), getSelf());
    }

    private void sendWriteRequest() {
        // Choose a random replica
        int randomReplica = (int) (Math.random() * replicas.size());
        ActorRef replica = replicas.get(randomReplica);
        int randomValue = (int) (Math.random() * 100);
        log("write req to replica " + replica.path().name() + " with value " + randomValue);
        replica.tell(new WriteRequest(randomValue), getSelf());
    }

    // store the replica that the client will send the request to
    private void onReplicasInfo(GroupInfo replicasInfo) {
        this.replicas = replicasInfo.group;
        log("received replicas info");
        log("Replicas size: " + replicas.size());
        // Schedule the first request
        getSelf().tell(new StartRequest(), getSelf());
    }

    private void onReceiveReadResponse(ReadResponse response) {
        log("read done " + response.value + " from " + getSender().path().name());
    }

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

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(GroupInfo.class, this::onReplicasInfo)
                .match(ReadResponse.class, this::onReceiveReadResponse)
                .match(StartRequest.class, this::onSendRequest)
                .build();
    }

}