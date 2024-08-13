package it.unitn.ds1.Messages;

import java.io.Serializable;

public class ReadResponse implements Serializable {
    public final int value;

    public ReadResponse(int value) {
        this.value = value;
    }
}
