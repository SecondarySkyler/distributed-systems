package it.unitn.ds1.TestMessages;
import java.io.Serializable;


public class SendWriteRequestMessage implements Serializable {
    public final int value;
    public final int replicaIndex;

    public SendWriteRequestMessage(int value, int replicaIndex) {
        this.value = value;
        this.replicaIndex = replicaIndex;
    }
}
