package it.unitn.ds1.Replicas.messages;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HashSet;

import it.unitn.ds1.Replicas.types.Data;
import it.unitn.ds1.Replicas.types.MessageIdentifier;
import it.unitn.ds1.Replicas.types.Update;

public class ElectionMessage implements Serializable {
    // key: replica id, value: last update (epoch, sequence number)
    public final Map<Integer, MessageIdentifier> quorumState;
    public final UUID ackIdentifier;   
    public final Set<Update> pendingUpdates;

    public ElectionMessage(int id, MessageIdentifier lastUpdate, HashMap<MessageIdentifier, Data> tb) {
        HashMap<Integer, MessageIdentifier> temp = new HashMap<>();
        HashSet<Update> temp2 = new HashSet<Update>();
        for (Map.Entry<MessageIdentifier, Data> entry : tb.entrySet()) {
            temp2.add(new Update(entry.getKey(), entry.getValue().value));
        }
        
        temp.put(id, lastUpdate);
        this.ackIdentifier = UUID.randomUUID();
        this.quorumState = Collections.unmodifiableMap(new HashMap<>(temp));
        this.pendingUpdates = Collections.unmodifiableSet(new HashSet<>(temp2));
    }

    public ElectionMessage(Map<Integer, MessageIdentifier> quorumState, Set<Update> pendingUpdates) {
        this.quorumState = Collections.unmodifiableMap(new HashMap<>(quorumState));//TODO: modify this new hashampa....
        this.pendingUpdates = Collections.unmodifiableSet(new HashSet<>(pendingUpdates));
        this.ackIdentifier = UUID.randomUUID();
    }

    public ElectionMessage addState(int id, MessageIdentifier lastUpdate, HashMap<MessageIdentifier, Data> tb) {
        HashMap<Integer, MessageIdentifier> temp = new HashMap<>(new HashMap<>(this.quorumState));
        HashSet<Update> temp2 = new HashSet<Update>(new HashSet<>(this.pendingUpdates));
        temp.put(id, lastUpdate);
        for (Map.Entry<MessageIdentifier, Data> entry : tb.entrySet()) {
            temp2.add(new Update(entry.getKey(), entry.getValue().value));
        }
        return new ElectionMessage(temp, temp2);
    }

    @Override
    public String toString() {
        return "ElectionMessage [ID=" + ackIdentifier.toString() + ", quorumState=" + quorumState + "]" + ", pendingUpdates=" + pendingUpdates;
    }

}
