package it.unitn.ds1.Replicas.messages;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import akka.actor.ActorRef;
import it.unitn.ds1.Replicas.types.Update;

public class SynchronizationMessage implements Serializable {
    private int coordinatorId;
    private ActorRef coordinatorRef;
    private final List<Update> updates;
    private final Set<Update> pendingUpdates;

    public SynchronizationMessage(int coordinatorId, ActorRef coordinatorRef, List<Update> updates, Set<Update> pendingUpdates) {
        this.coordinatorId = coordinatorId;
        this.coordinatorRef = coordinatorRef;
        this.updates = Collections.unmodifiableList(new ArrayList<>(updates));
        this.pendingUpdates = Collections.unmodifiableSet(new HashSet<>(pendingUpdates));
    }

    public int getCoordinatorId() {
        return coordinatorId;
    }

    public ActorRef getCoordinatorRef() {
        return coordinatorRef;
    }

    public List<Update> getUpdates() {
        return this.updates;
    }
    
    public Set<Update> getPendingUpdates() {
        return this.pendingUpdates;
    }
}
