package it.unitn.ds1.Messages;

import java.io.Serializable;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import akka.actor.ActorRef;

public class GroupInfo implements Serializable {
    public final List<ActorRef> group;

    public GroupInfo(List<ActorRef> group) {
        this.group = Collections.unmodifiableList(new ArrayList<ActorRef>(group));
    }

}
