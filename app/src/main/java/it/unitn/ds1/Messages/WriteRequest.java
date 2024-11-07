package it.unitn.ds1.Messages;

import java.io.Serializable;

public class WriteRequest implements Serializable {
    public final int value;
    public final boolean addToQueue;

    public WriteRequest(int value) {
        this.value = value;
        this.addToQueue = true;
    }

    public WriteRequest(int value, boolean addToQueue) {
        this.value = value;
        this.addToQueue = addToQueue;
    }
}