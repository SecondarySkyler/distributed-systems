package it.unitn.ds1.Replicas;
import it.unitn.ds1.Messages.ReadRequest;
import it.unitn.ds1.Messages.WriteRequest;

import akka.actor.AbstractActor;
import akka.actor.Props;

public class Replica extends AbstractActor {
    private int sharedVariable;
    private int id;

    public Replica(int id) {
        this.sharedVariable = 0;
        this.id = id;
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(WriteRequest.class, this::onWriteRequest)
                .match(ReadRequest.class, this::onReadRequest)
                .build();
    }

    static public Props props(int id) {
        return Props.create(Replica.class, () -> new Replica(id));
    }

    private void onWriteRequest(WriteRequest request) {}
    
    private void onReadRequest(ReadRequest request) {
        request.client.tell(sharedVariable, getSelf());
    }
}