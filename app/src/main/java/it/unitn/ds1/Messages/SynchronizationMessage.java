package it.unitn.ds1.Messages;

import java.io.Serializable;

import akka.actor.ActorRef;

public class SynchronizationMessage implements Serializable {
    private int coordinatorId;
    private ActorRef coordinatorRef;

    public SynchronizationMessage(int coordinatorId, ActorRef coordinatorRef) {
        this.coordinatorId = coordinatorId;
        this.coordinatorRef = coordinatorRef;
    }

    public int getCoordinatorId() {
        return coordinatorId;
    }

    public ActorRef getCoordinatorRef() {
        return coordinatorRef;
    }
}
