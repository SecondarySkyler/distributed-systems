package it.unitn.ds1.Messages;

import java.io.Serializable;
import java.util.HashMap;
import akka.actor.ActorRef;

public class ElectionMessage implements Serializable {
    // need to be public final or private
    public final HashMap<Integer, Integer> quorumState;
    private int senderId;
    private ActorRef from; // should we remove this? and use getSender() instead?

    public ElectionMessage(int id, int lastUpdate, ActorRef from) {
        this.quorumState = new HashMap<>();
        this.quorumState.put(id, lastUpdate);
        this.senderId = id;
        this.from = from;// to change using sender
    }

    public void addState(int id, int lastUpdate) {
        this.quorumState.put(id, lastUpdate);
    }

    public ActorRef getSender() {
        return this.from;
    }
}
