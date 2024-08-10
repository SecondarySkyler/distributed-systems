package it.unitn.ds1.Replicas;
import it.unitn.ds1.Messages.ReadRequest;
import it.unitn.ds1.Messages.WriteRequest;
import it.unitn.ds1.Messages.GroupInfo;
import it.unitn.ds1.Messages.ElectionMessage;
import it.unitn.ds1.Messages.SynchronizationMessage;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

import akka.actor.AbstractActor;
import akka.actor.Props;
import akka.actor.ActorRef;

public class Replica extends AbstractActor {
    private int replicaVariable;
    private int id;
    private int fakeLastUpdate;
    private List<ActorRef> peers;
    boolean isElectionRunning = false;
    private int coordinatorId;
    private ActorRef coordinatorRef;

    public Replica(int id) {
        this.replicaVariable = 0;
        this.id = id;
        this.fakeLastUpdate = 0;
        this.peers = new ArrayList<>();
    }
    
    @Override
    public Receive createReceive() {
        return receiveBuilder()
        .match(WriteRequest.class, this::onWriteRequest)
        .match(ReadRequest.class, this::onReadRequest)
        .match(GroupInfo.class, this::onGroupInfo)
        .match(ElectionMessage.class, this::onElectionMessage)
        .match(SynchronizationMessage.class, this::onSynchronizationMessage)
        .build();
    }
    
    static public Props props(int id) {
        return Props.create(Replica.class, () -> new Replica(id));
    }
    
    private void onWriteRequest(WriteRequest request) {}
    
    private void onReadRequest(ReadRequest request) {
        request.client.tell(replicaVariable, getSelf());
    }
    
    private void onGroupInfo(GroupInfo groupInfo) {
        for (ActorRef peer : groupInfo.group) {
            if (!peer.equals(getSelf())) {
                this.peers.add(peer);
            }
        }
        this.startElection();
    }

    private void onElectionMessage(ElectionMessage electionMessage) {
        if (!this.isElectionRunning) {
            this.isElectionRunning = true;
            electionMessage.addState(id, fakeLastUpdate);
            // get the next ActorRef in the quorum
            ActorRef nextRef = peers.get((id + 1) % peers.size());
            nextRef.tell(electionMessage, getSelf());
            // send ack to the sender
            electionMessage.from.tell("ack", getSelf());
        } else {
            // if Im in the quorum
            if (electionMessage.quorumState.containsKey(id)) {
                // I need to check if I have the most recent update or the highest id
                int maxUpdate = Collections.max(electionMessage.quorumState.values());

                if (maxUpdate > fakeLastUpdate) {
                    // I would lose the election, so I forward to the next replica
                    ActorRef nextRef = peers.get((id + 1) % peers.size());
                    nextRef.tell(electionMessage, getSelf());
                } else if (maxUpdate == fakeLastUpdate) {
                    // I need to check the id
                    int maxId = Collections.max(electionMessage.quorumState.keySet());
                    if (maxId > id) {
                        // I would lose the election, so I forward to the next replica
                        ActorRef nextRef = peers.get((id + 1) % peers.size());
                        nextRef.tell(electionMessage, getSelf());
                    } else {
                        // I would win the election, so I send to all replicas the sychronization message
                        SynchronizationMessage synchronizationMessage = new SynchronizationMessage(id, getSelf());
                        for (int i = 0; i < peers.size(); i++) {
                            if (i != id) {
                                // send the synchronization message to all replicas except me
                                peers.get(i).tell(synchronizationMessage, getSelf());
                            }
                        }
                    }
                } else {
                    // I would win the election, so I send to all replicas the sychronization message
                    SynchronizationMessage synchronizationMessage = new SynchronizationMessage(id, getSelf());
                    for (int i = 0; i < peers.size(); i++) {
                        if (i != id) {
                            // send the synchronization message to all replicas except me
                            peers.get(i).tell(synchronizationMessage, getSelf());
                        }
                    }
                }
            } else {
                // I need to add my state to the message and forward it
                electionMessage.addState(id, fakeLastUpdate);
                ActorRef nextRef = peers.get((id + 1) % peers.size());
                nextRef.tell(electionMessage, getSelf());
                // send ack to the sender
                electionMessage.from.tell("ack", getSelf());
            }
        }
    }

    private void onSynchronizationMessage(SynchronizationMessage synchronizationMessage) {
        this.isElectionRunning = false;
        this.coordinatorId = synchronizationMessage.getCoordinatorId();
        this.coordinatorRef = synchronizationMessage.getCoordinatorRef();
        System.out.println("Replica " + id + " received synchronization message from " + coordinatorId + " with ref " + coordinatorRef);
    }

    private void startElection() {
        // Currently, only the replica with id 0 starts the election
        if (this.id == 0) {
            isElectionRunning = true;
            ElectionMessage electionMessage = new ElectionMessage(id, fakeLastUpdate, getSelf());
            // get the next ActorRef in the quorum
            ActorRef nextRef = peers.get((id + 1) % peers.size());
            nextRef.tell(electionMessage, getSelf());
        }
    }

}