package it.unitn.ds1.Messages;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import akka.actor.ActorRef;
import it.unitn.ds1.MessageIdentifier;

public class ElectionMessage implements Serializable {
    public final Map<Integer, MessageIdentifier> quorumState;
    public final ActorRef from; 

    public ElectionMessage(int id, MessageIdentifier lastUpdate, ActorRef from) {
        HashMap<Integer, MessageIdentifier> temp = new HashMap<>();
        temp.put(id, lastUpdate);
        this.quorumState = Collections.unmodifiableMap(temp);
        this.from = from;
    }

    public ElectionMessage(ActorRef from, HashMap<Integer, MessageIdentifier> quorumState) {
        this.quorumState = Collections.unmodifiableMap(quorumState);
        this.from = from;
    }

    public ElectionMessage addState(int id, MessageIdentifier lastUpdate, ActorRef from, Map<Integer, MessageIdentifier> quorumState) {
        HashMap<Integer, MessageIdentifier> temp = new HashMap<>(quorumState);
        temp.put(id, lastUpdate);
        return new ElectionMessage(from, temp);
    }

}
