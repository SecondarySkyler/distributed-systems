package it.unitn.ds1.Replicas.types;

import java.util.Set;

import java.util.HashSet;

public class Data {
    public final Set<Integer> ackBuffers; // ack from all replicas
    public final int value;

    public Data(int value, int size) {
        this.value = value;
        this.ackBuffers = new HashSet<Integer>(size);
    }
}