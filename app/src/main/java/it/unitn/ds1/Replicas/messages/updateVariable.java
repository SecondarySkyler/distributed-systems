package it.unitn.ds1.Replicas.messages;

import java.io.Serializable;

import it.unitn.ds1.MessageIdentifier;

public class updateVariable implements Serializable {
    public final int value;
    public final MessageIdentifier messageIdentifier;

    public updateVariable(MessageIdentifier m, int value) {
        this.value = value;
        this.messageIdentifier = new MessageIdentifier(m.getEpoch(), m.getSequenceNumber());
    }
}
