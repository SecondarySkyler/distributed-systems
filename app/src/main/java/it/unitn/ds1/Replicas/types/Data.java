package it.unitn.ds1.Replicas.types;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

public class Data {
    public final List<Boolean> ackBuffers; // ack from all replicas
    public final int value;

    public Data(int value, int size) {
        this.value = value;
        this.ackBuffers = new ArrayList<>(Collections.nCopies(size, false));
    }
}