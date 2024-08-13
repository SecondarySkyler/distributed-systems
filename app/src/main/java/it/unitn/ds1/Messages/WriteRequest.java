package it.unitn.ds1.Messages;

import java.io.Serializable;

public class WriteRequest implements Serializable {
    public final int value;

    public WriteRequest(int value) {
        this.value = value;
    }
}