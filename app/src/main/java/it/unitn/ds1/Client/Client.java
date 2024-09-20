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
import scala.concurrent.duration.Duration;

import java.util.ArrayList;
import java.util.List;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.File;
import java.io.IOException;

public class Client extends AbstractActor {
    private int id;
    List<ActorRef> replicas = new ArrayList<>();
    private final BufferedWriter writer;

    public Client(int id) throws IOException {
        this.id = id;
        String directoryPath = "logs";
        String filePath = directoryPath + File.separator + getSelf().path().name() + ".txt";

        // Create the directory if it doesn't exist
        File directory = new File(directoryPath);
        if (!directory.exists()) {
            directory.mkdirs(); // Create the directory and any necessary parent directories
        }
        writer = new BufferedWriter(new FileWriter(filePath, false));

    }

    static public Props props(int id) {
        return Props.create(Client.class, () -> new Client(id));
    }

    private void onSendRequest(StartRequest request) {
        int randomValue = (int) (Math.random() * 100);
        if (randomValue < 50)
            sendReadRequest();
        else
            sendWriteRequest();
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
        Cancellable timer = getContext().system().scheduler().scheduleWithFixedDelay(
                Duration.create(1, TimeUnit.SECONDS), // when to start generating messages
                Duration.create(1, TimeUnit.SECONDS), // how frequently generate them
                getSelf(), // destination actor reference
                new StartRequest(), // the message to send
                getContext().system().dispatcher(), // system dispatcher
                getSelf() // source of the message (myself)
        );

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