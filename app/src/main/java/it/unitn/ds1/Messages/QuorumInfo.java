package it.unitn.ds1.Messages;

import java.io.Serializable;
import java.util.List;
import akka.actor.ActorRef;
import java.util.Collections;
import java.util.ArrayList;

public class QuorumInfo implements Serializable {
    public final List<ActorRef> quorum;

    public QuorumInfo(List<ActorRef> quorum) {
        this.quorum = Collections.unmodifiableList(new ArrayList<ActorRef>(quorum));
    }

}
