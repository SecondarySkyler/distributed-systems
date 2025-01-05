package it.unitn.ds1.Replicas.messages;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import it.unitn.ds1.Replicas.types.MessageIdentifier;

public class ElectionMessage implements Serializable {
    // key: replica id, value: last update (epoch, sequence number)
    public final Map<Integer, MessageIdentifier> quorumState;
    public final UUID ackIdentifier;

    public ElectionMessage(int id, MessageIdentifier lastUpdate) {
        HashMap<Integer, MessageIdentifier> temp = new HashMap<>();
        temp.put(id, lastUpdate);
        this.ackIdentifier = UUID.randomUUID();
        this.quorumState = Collections.unmodifiableMap(temp);
    }

    public ElectionMessage(Map<Integer, MessageIdentifier> quorumState) {
        this.quorumState = Collections.unmodifiableMap(quorumState);
        this.ackIdentifier = UUID.randomUUID();
    }

    public ElectionMessage addState(int id, MessageIdentifier lastUpdate, Map<Integer, MessageIdentifier> quorumState) {
        HashMap<Integer, MessageIdentifier> temp = new HashMap<>(quorumState);
        temp.put(id, lastUpdate);
        return new ElectionMessage(temp);
    }

    @Override
    public String toString() {
        return "ElectionMessage [ID=" + ackIdentifier.toString() + ", quorumState=" + quorumState + "]";
    }

}
