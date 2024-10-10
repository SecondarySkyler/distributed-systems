package it.unitn.ds1.Replicas.messages;

import java.util.UUID;
import java.io.Serializable;

public class AckElectionMessage implements Serializable {
    public final UUID id;

    public AckElectionMessage(UUID id) {
        this.id = id;
    }

}
