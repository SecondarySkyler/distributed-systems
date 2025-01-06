package it.unitn.ds1.TestMessages;

import java.io.Serializable;

public class SendReadRequestMessage implements Serializable {
    public final int replicaIndex;

    public SendReadRequestMessage(int replicaIndex) {
        this.replicaIndex = replicaIndex;
    }
}