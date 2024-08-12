package it.unitn.ds1.Replicas;
import it.unitn.ds1.Messages.ReadRequest;
import it.unitn.ds1.Messages.WriteRequest;
import it.unitn.ds1.Messages.QuorumInfo;

import java.util.HashMap;
import java.util.List;

import akka.actor.AbstractActor;
import akka.actor.Props;
import akka.actor.ActorRef;

public class Replica extends AbstractActor {
    private int replicaVariable;
    private int id;
    private List<ActorRef> quorum;
    // private HashMap<pair, Integer> history;

    public Replica(int id) {
        this.replicaVariable = 0;
        this.id = id;
        // this.history = new HashMap<>();
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(WriteRequest.class, this::onWriteRequest)
                .match(ReadRequest.class, this::onReadRequest)
                .match(QuorumInfo.class, this::onQuorumInfo)
                .build();
    }

    static public Props props(int id) {
        return Props.create(Replica.class, () -> new Replica(id));
    }

    private void onWriteRequest(WriteRequest request) {}
    
    private void onReadRequest(ReadRequest request) {
        request.client.tell(replicaVariable, getSelf());
    }

    private void onQuorumInfo(QuorumInfo quorumInfo) {
        this.quorum = quorumInfo.quorum;// TODO: NEED remove itself
        System.out.println("Replica " + id + " received quorum info");
        System.out.println("Quorum size: " + quorum.size());
    }

}