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

public class Client extends AbstractActor {
    private int id;
    List<ActorRef> replicas = new ArrayList<>();

    public Client(int id) {
        this.id = id;
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
        // sendWriteRequest();
        try {
            Thread.sleep(5000);
        } catch (Exception ignored) {
        }
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
        log("read done " + response.value);
    }

    private void log(String message) {
        System.out.println(getSelf().path().name() + ": " + message);
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(GroupInfo.class, this::onReplicasInfo)
                .match(ReadResponse.class, this::onReceiveReadResponse)
                .build();
    }

}