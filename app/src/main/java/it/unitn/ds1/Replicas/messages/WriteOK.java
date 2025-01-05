package it.unitn.ds1.Replicas.messages;

import java.io.Serializable;

import it.unitn.ds1.Replicas.types.MessageIdentifier;

public class WriteOK implements Serializable {
    public final MessageIdentifier messageIdentifier;

    public WriteOK(MessageIdentifier id) {
        this.messageIdentifier = id;
    }
}
