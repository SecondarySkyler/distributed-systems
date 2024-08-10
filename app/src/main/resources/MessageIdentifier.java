public class MessageIdentifier implements Comparable<MessageIdentifier> {
    private int epoch;
    private int sequenceNumber;

    public MessageIdentifier(int epoch, int sequenceNumber) {
        this.epoch = epoch;
        this.sequenceNumber = sequenceNumber;
    }

    public int getEpoch() {
        return epoch;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public void incrementEpoch() {
        this.epoch++;
        this.sequenceNumber = 0;
    }

    public void incrementSequenceNumber() {
        this.sequenceNumber++;
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
