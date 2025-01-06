package it.unitn.ds1.Replicas.types;

import java.util.Objects;

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
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof Update)) {
            return false;
        }
        Update other = (Update) obj;
        return other.messageIdentifier.equals(this.messageIdentifier) && other.value == this.value;
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageIdentifier, value);
    }

    @Override
    public String toString() {
        return "\u001B[32mupdate " + messageIdentifier.toString() + " " + value+"\u001B[0m";
    }
}