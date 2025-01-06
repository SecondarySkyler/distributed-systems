package it.unitn.ds1.Replicas.messages;

import java.io.Serializable;

import akka.actor.ActorRef;

public class CrashedNextReplicaMessage implements Serializable{
    
    public final ElectionMessage electionMessage;
    public final ActorRef nextRef;

    public CrashedNextReplicaMessage(ElectionMessage electionMessage, ActorRef nextRef) {
        this.electionMessage = electionMessage;
        this.nextRef = nextRef;
    }
}
