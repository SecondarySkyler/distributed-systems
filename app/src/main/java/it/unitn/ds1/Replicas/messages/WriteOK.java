package it.unitn.ds1.Replicas.messages;

import it.unitn.ds1.MessageIdentifier;
import java.io.Serializable;

public class WriteOK implements Serializable {
    public final MessageIdentifier messageIdentifier;

    public WriteOK(MessageIdentifier id) {
        this.messageIdentifier = id;
    }
}
