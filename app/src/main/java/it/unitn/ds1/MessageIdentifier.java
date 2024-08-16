package it.unitn.ds1;

import java.util.Objects;

public class MessageIdentifier implements Comparable<MessageIdentifier> {
    private int epoch;
    private int sequenceNumber;

    public MessageIdentifier(int epoch, int sequenceNumber) {
        this.epoch = epoch;
        this.sequenceNumber = sequenceNumber;
    }

    public int getEpoch() {
        return this.epoch;
    }

    public int getSequenceNumber() {
        return this.sequenceNumber;
    }

    public MessageIdentifier incrementEpoch() {
        // making it immutable
        return new MessageIdentifier(this.epoch + 1, 0);
    }

    public MessageIdentifier incrementSequenceNumber() {
        return new MessageIdentifier(this.epoch, this.sequenceNumber + 1);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof MessageIdentifier)) {
            return false;
        }
        MessageIdentifier other = (MessageIdentifier) obj;
        return other.epoch == epoch && other.sequenceNumber == sequenceNumber;
    }

    @Override
    public int hashCode() {
        return Objects.hash(epoch, sequenceNumber);
    }

    @Override
    public int compareTo(MessageIdentifier other) {
        if (epoch < other.epoch) {
            return -1;
        } else if (epoch > other.epoch) {
            return 1;
        } else {
            return Integer.compare(sequenceNumber, other.sequenceNumber);
        }
    }

    @Override
    public String toString() {
        return "" + epoch + ":" + sequenceNumber;
    }

}
