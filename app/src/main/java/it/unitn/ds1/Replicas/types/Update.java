package it.unitn.ds1.Replicas.types;

public class Update {
    private MessageIdentifier messageIdentifier;
    private int value;

    public Update(MessageIdentifier messageIdentifier, int value) {
        this.messageIdentifier = messageIdentifier;
        this.value = value;
    }

    public int getValue() {
        return this.value;
    }
    public MessageIdentifier getMessageIdentifier() {
        return this.messageIdentifier;
    }

    @Override
    public String toString() {
        return "\u001B[32mupdate " + messageIdentifier.toString() + " " + value+"\u001B[0m";
    }
}