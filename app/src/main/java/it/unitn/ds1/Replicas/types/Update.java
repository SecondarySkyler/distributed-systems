package it.unitn.ds1.Replicas.types;

import it.unitn.ds1.MessageIdentifier;

public class Update {
    private MessageIdentifier messageIdentifier;
    private int value;

    public Update(MessageIdentifier messageIdentifier, int value) {
        this.messageIdentifier = messageIdentifier;
        this.value = value;
    }

    public MessageIdentifier getMessageIdentifier() {
        return this.messageIdentifier;
    }

    @Override
    public String toString() {
        return "update " + messageIdentifier.toString() + " " + value;
    }
}