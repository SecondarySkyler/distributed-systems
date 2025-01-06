package it.unitn.ds1.Replicas.messages;

import java.io.Serializable;
import java.util.List;
import java.util.Set;
import java.util.Collections;
import it.unitn.ds1.Replicas.types.Update;
import java.util.ArrayList;
import java.util.HashSet;
public class UpdateHistoryMessage implements Serializable {
    private final List<Update> updates;
    private final Set<Update> pendingUpdates;
    
    public UpdateHistoryMessage(List<Update> updates, Set<Update> pendingUpdates) { 
        this.updates = Collections.unmodifiableList(new ArrayList<>(updates));
        this.pendingUpdates = Collections.unmodifiableSet(new HashSet<>(pendingUpdates));
    }

    public List<Update> getUpdates() {
        return this.updates;
    }
    
    public Set<Update> getPendingUpdates() {
        return this.pendingUpdates;
    }
}
