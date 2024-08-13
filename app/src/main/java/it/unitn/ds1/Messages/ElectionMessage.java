package it.unitn.ds1.Messages;

import java.io.Serializable;
import java.util.HashMap;
import akka.actor.ActorRef;

public class ElectionMessage implements Serializable {
    // need to be public final or private
    public HashMap<Integer, Integer> quorumState;
    public int senderId;
    public ActorRef from;

    public ElectionMessage(int id, int lastUpdate, ActorRef from) {
        this.quorumState = new HashMap<>();
        this.quorumState.put(id, lastUpdate);
        this.senderId = id;
        this.from = from;// to change using sender
    }

    public void addState(int id, int lastUpdate) {
        this.quorumState.put(id, lastUpdate);
    }
}
