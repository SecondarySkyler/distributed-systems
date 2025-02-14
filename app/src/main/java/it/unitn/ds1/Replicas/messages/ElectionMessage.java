package it.unitn.ds1.Replicas.messages;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import it.unitn.ds1.Replicas.types.Data;
import it.unitn.ds1.Replicas.types.MessageIdentifier;
import it.unitn.ds1.Replicas.types.PendingUpdate;

public class ElectionMessage implements Serializable {
    // key: replica id, value: last update (epoch, sequence number)
    public final Map<Integer, MessageIdentifier> quorumState;

    public final Set<PendingUpdate> pendingUpdates;

    public ElectionMessage(int id, MessageIdentifier lastUpdate, HashMap<MessageIdentifier, Data> tb) {
        HashMap<Integer, MessageIdentifier> temp = new HashMap<>();
        HashSet<PendingUpdate> temp2 = new HashSet<PendingUpdate>();
        for (Map.Entry<MessageIdentifier, Data> entry : tb.entrySet()) {
            temp2.add(new PendingUpdate(entry.getKey(), entry.getValue()));
        }
        
        temp.put(id, lastUpdate);

        this.quorumState = Collections.unmodifiableMap(new HashMap<>(temp));
        this.pendingUpdates = Collections.unmodifiableSet(new HashSet<>(temp2));
    }

    public ElectionMessage(Map<Integer, MessageIdentifier> quorumState, Set<PendingUpdate> pendingUpdates) {
        this.quorumState = Collections.unmodifiableMap(new HashMap<>(quorumState));
        this.pendingUpdates = Collections.unmodifiableSet(new HashSet<>(pendingUpdates));

    }

    public ElectionMessage addState(int id, MessageIdentifier lastUpdate, HashMap<MessageIdentifier, Data> tb) {
        HashMap<Integer, MessageIdentifier> temp = new HashMap<>(new HashMap<>(this.quorumState));
        HashSet<PendingUpdate> temp2 = new HashSet<PendingUpdate>(new HashSet<>(this.pendingUpdates));
        temp.put(id, lastUpdate);
        for (Map.Entry<MessageIdentifier, Data> entry : tb.entrySet()) {
            temp2.add(new PendingUpdate(entry.getKey(), entry.getValue()));
        }
        return new ElectionMessage(temp, temp2);
    }

    @Override
    public String toString() {
        return "ElectionMessage [quorumState = " + quorumState + ", pendingUpdates = " + pendingUpdates + "]";
    }

}
