package it.unitn.ds1.Replicas.messages;

import java.io.Serializable;

import it.unitn.ds1.Replicas.types.MessageIdentifier;

public class AcknowledgeUpdate implements Serializable {
    public final MessageIdentifier messageIdentifier;
    public final int senderId;

    public AcknowledgeUpdate(MessageIdentifier m, int replicaId) {
        this.messageIdentifier = m;
        this.senderId = replicaId;
    }

}
