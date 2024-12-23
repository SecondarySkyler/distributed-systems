package it.unitn.ds1;

import java.util.Objects;

/**
 * A class to represent a message identifier, which is a pair of epoch and sequence number.
 */
public class MessageIdentifier implements Comparable<MessageIdentifier> {
    private int epoch;
    private int sequenceNumber;

    /**
     * Constructor
     * @param epoch 
     * @param sequenceNumber
     */
    public MessageIdentifier(int epoch, int sequenceNumber) {
        this.epoch = epoch;
        this.sequenceNumber = sequenceNumber;
    }

    /**
     * Get the epoch
     * @return epoch
     */
    public int getEpoch() {
        return this.epoch;
    }

    /**
     * Get the sequence number
     * @return sequence number
     */
    public int getSequenceNumber() {
        return this.sequenceNumber;
    }

    /**
     * Increment the epoch
     * @return new MessageIdentifier with incremented epoch
     */
    public MessageIdentifier incrementEpoch() {
        // making it immutable
        return new MessageIdentifier(this.epoch + 1, 0);
    }

    /**
     * Increment the sequence number
     * @return new MessageIdentifier with incremented sequence number
     */
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
        return "<" + epoch + ":" + sequenceNumber + ">";
    }

}
