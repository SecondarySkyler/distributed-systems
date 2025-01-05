package it.unitn.ds1.Replicas.messages;

import java.io.Serializable;

import it.unitn.ds1.Replicas.types.MessageIdentifier;

public class UpdateVariable implements Serializable {
    public final int value;
    public final MessageIdentifier messageIdentifier;
    public final int replicaId;

    public UpdateVariable(MessageIdentifier m, int value,int replicaId) {
        this.value = value;
        this.messageIdentifier = new MessageIdentifier(m.getEpoch(), m.getSequenceNumber());
        this.replicaId = replicaId;
    }
}
