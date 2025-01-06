package it.unitn.ds1.Client.messages;

import java.io.Serializable;
import akka.actor.ActorRef;

public class CrashedReplica implements Serializable {
    public final ActorRef crashedReplica;

    public CrashedReplica(ActorRef crashedReplica) {
        this.crashedReplica = crashedReplica;
    }
    
}
