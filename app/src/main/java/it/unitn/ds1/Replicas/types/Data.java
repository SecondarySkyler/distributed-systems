package it.unitn.ds1.Replicas.types;

import java.util.Set;

import java.util.HashSet;

public class Data {
    public final Set<Integer> ackBuffers; //id of the replica that acked
    public final int value;

    public Data(int value, int size) {
        this.value = value;
        this.ackBuffers = new HashSet<Integer>(size);
    }

    @Override
    public String toString() {
        return "Data(value=" + value + ", ackBuffers=" + ackBuffers + ")";
    }
}