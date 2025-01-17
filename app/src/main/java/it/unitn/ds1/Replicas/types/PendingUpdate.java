package it.unitn.ds1.Replicas.types;

import java.util.Objects;

public class PendingUpdate {
    public final MessageIdentifier messageIdentifier;
    public final Data data;

    public PendingUpdate(MessageIdentifier m, Data data) {
        this.messageIdentifier = new MessageIdentifier(m.getEpoch(), m.getSequenceNumber());
        this.data = new Data(data.value, data.ackBuffers.size(), data.replicaId);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof PendingUpdate)) {
            return false;
        }
        PendingUpdate other = (PendingUpdate) obj;
        return other.messageIdentifier.equals(this.messageIdentifier) && other.data.value == this.data.value
                && other.data.replicaId == this.data.replicaId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageIdentifier, data.replicaId, data.value);
    }

    @Override
    public String toString() {
        return "pending update " + messageIdentifier.toString() + " " + data.toString();
    }
}
