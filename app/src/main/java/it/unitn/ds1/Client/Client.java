package it.unitn.ds1.Client;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import it.unitn.ds1.Messages.GroupInfo;
import it.unitn.ds1.Messages.ReadRequest;
import it.unitn.ds1.Messages.ReadResponse;
import it.unitn.ds1.Messages.WriteRequest;

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

    private void sendRequest() {
        sendWriteRequest();
        try {
            Thread.sleep(5000);
        } catch (Exception ignored) {
        }
        sendWriteRequest();
        sendReadRequest();
        sendReadRequest();
        sendReadRequest();
        // sendWriteRequest();
        try {
            Thread.sleep(5000);
        } catch (Exception ignored) {
        }
        sendReadRequest();
        sendReadRequest();
        sendReadRequest();

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
        log("write req to replica " + replica.path().name());
        int randomValue = (int) (Math.random() * 100);
        replica.tell(new WriteRequest(randomValue), getSelf());
    }

    // store the replica that the client will send the request to
    private void onReplicasInfo(GroupInfo replicasInfo) {
        this.replicas = replicasInfo.group;
        log("received replicas info");
        log("Replicas size: " + replicas.size());
        sendRequest();
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
                .build();
    }

}