package it.unitn.ds1.Replicas.messages;

import java.io.Serializable;

public class StartElectionMessage implements Serializable {
    public final String reason;

    public StartElectionMessage(String reason) {
        this.reason = reason;
    }
};
