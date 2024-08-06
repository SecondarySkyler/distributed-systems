package it.unitn.ds1.Messages;
import akka.actor.ActorRef;
import java.io.Serializable;

public class ReadRequest implements Serializable {
    public final ActorRef client;

    public ReadRequest(ActorRef client) {
        this.client = client;
    }
}