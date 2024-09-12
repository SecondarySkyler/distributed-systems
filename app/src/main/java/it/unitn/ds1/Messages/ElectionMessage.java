package it.unitn.ds1.Messages;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import it.unitn.ds1.MessageIdentifier;

public class ElectionMessage implements Serializable {
    public final Map<Integer, MessageIdentifier> quorumState;

    public ElectionMessage(int id, MessageIdentifier lastUpdate) {
        HashMap<Integer, MessageIdentifier> temp = new HashMap<>();
        temp.put(id, lastUpdate);
        this.quorumState = Collections.unmodifiableMap(temp);
    }

    public ElectionMessage(HashMap<Integer, MessageIdentifier> quorumState) {
        this.quorumState = Collections.unmodifiableMap(quorumState);
    }

    
    public ElectionMessage addState(int id, MessageIdentifier lastUpdate, Map<Integer, MessageIdentifier> quorumState) {
        HashMap<Integer, MessageIdentifier> temp = new HashMap<>(quorumState);
        temp.put(id, lastUpdate);
        return new ElectionMessage(temp);
    }

}
