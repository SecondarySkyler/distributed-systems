package it.unitn.ds1.Messages;
import akka.actor.ActorRef;
import java.io.Serializable;

public class WriteRequest implements Serializable {
    public final int value;
    public final ActorRef client;

    public WriteRequest(int value, ActorRef client) {
        this.value = value;
        this.client = client;
    }
}