package it.unitn.ds1.Replicas.messages;

import java.io.Serializable;
import java.util.List;

import it.unitn.ds1.Replicas.types.Update;

public class UpdateHistoryMessage implements Serializable {
    private List<Update> updates;

    public UpdateHistoryMessage(List<Update> updates) {
        this.updates = updates;
    }

    public List<Update> getUpdates() {
        return this.updates;
    }
}
