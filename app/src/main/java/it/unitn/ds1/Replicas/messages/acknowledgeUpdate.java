package it.unitn.ds1.Replicas.messages;

import java.io.Serializable;

import it.unitn.ds1.MessageIdentifier;

public class acknowledgeUpdate implements Serializable {
    public final MessageIdentifier messageIdentifier;
    public final int senderId;

    public acknowledgeUpdate(MessageIdentifier m, int replicaId) {
        this.messageIdentifier = m;
        this.senderId = replicaId;
    }

}
