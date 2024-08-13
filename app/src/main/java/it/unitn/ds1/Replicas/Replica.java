package it.unitn.ds1.Replicas;

import it.unitn.ds1.MessageIdentifier;
import it.unitn.ds1.Messages.ReadRequest;
import it.unitn.ds1.Messages.ReadResponse;
import it.unitn.ds1.Messages.WriteRequest;
import it.unitn.ds1.Replicas.messages.WriteOK;
import it.unitn.ds1.Replicas.messages.acknowledgeUpdate;
import it.unitn.ds1.Replicas.messages.updateVariable;
import it.unitn.ds1.Messages.GroupInfo;
import it.unitn.ds1.Messages.ElectionMessage;
import it.unitn.ds1.Messages.SynchronizationMessage;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.io.Serializable;

import akka.actor.AbstractActor;
import akka.actor.Props;
import akka.actor.ActorRef;

public class Replica extends AbstractActor {
    private int id;
    private int replicaVariable;
    private List<ActorRef> peers = new ArrayList<>();

    private MessageIdentifier lastUpdate = new MessageIdentifier(0, 0);// remove
    private int fakeLastUpdate;// remove

    private boolean isElectionRunning = false;
    private int coordinatorId; // remove
    private ActorRef coordinatorRef;

    private int quorumSize;
    private HashMap<MessageIdentifier, Data> temporaryBuffer = new HashMap<>();
    private List<Update> history = new ArrayList<>();

    class Data {
        protected List<Boolean> ackBuffers;
        protected int value;

        public Data(int value, int size) {
            this.value = value;
            this.ackBuffers = new ArrayList<>(Collections.nCopies(size, false));
        }
    }

    class Update {
        MessageIdentifier messageIdentifier;
        int value;

        public Update(MessageIdentifier messageIdentifier, int value) {
            this.messageIdentifier = messageIdentifier;
            this.value = value;
        }

        @Override
        public String toString() {
            return "update " + messageIdentifier.toString() + " " + value;
        }
    }

    // private HashMap<pair, Integer> history;
    public Replica(int id) {
        this.replicaVariable = 0;
        this.id = id;
        this.fakeLastUpdate = 0;
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(WriteRequest.class, this::onWriteRequest)
                .match(WriteOK.class, this::onWriteOK)
                .match(updateVariable.class, this::onUpdateVariable)
                .match(acknowledgeUpdate.class, this::onAcknowledgeUpdate)
                .match(ReadRequest.class, this::onReadRequest)
                .match(GroupInfo.class, this::onGroupInfo)
                .match(ElectionMessage.class, this::onElectionMessage)
                .match(SynchronizationMessage.class, this::onSynchronizationMessage)
                .build();
    }

    static public Props props(int id) {
        return Props.create(Replica.class, () -> new Replica(id));
    }

    private void multicast(Serializable message) {

        for (ActorRef peer : peers) {
            peer.tell(message, getSelf());
        }
    }

    private void onWriteRequest(WriteRequest request) {
        if (coordinatorRef == null || isElectionRunning) {
            String reasonMessage = coordinatorRef == null ? "coordinator is null" : "election is running";
            log("Cannot process write request now: " + reasonMessage + ", retrying after 500ms");
            // retry after 500ms
            getContext()
                    .getSystem()
                    .scheduler()
                    .scheduleOnce(java.time.Duration.ofMillis(500), getSelf(),
                            new WriteRequest(request.value), getContext().getSystem().dispatcher(), getSelf());
            return;
        }
        if (getSelf().equals(coordinatorRef)) {
            log("Received write request from client, starting 2 phase broadcast protocol");
            // step 1 of 2 phase broadcast protocol
            lastUpdate = lastUpdate.incrementSequenceNumber();
            updateVariable update = new updateVariable(lastUpdate, request.value);
            multicast(update);

            // initialize the toBeDelivered list and set the coordinator as received
            temporaryBuffer.put(lastUpdate, new Data(request.value, this.peers.size() + 1));
            temporaryBuffer.get(lastUpdate).ackBuffers.set(id, true);
            log("acknowledged message id " + lastUpdate.toString());

        } else {
            // forward the write request to the coordinator
            log("forwarding write request to coordinator " + coordinatorRef.path().name());
            coordinatorRef.tell(request, getSelf());

        }
    }

    private void onUpdateVariable(updateVariable update) {

        // lastUpdate = lastUpdate.incrementSequenceNumber();
        // if (lastUpdate.compareTo(update.messageIdentifier) != 0) {// MITGH BE REMOVED
        // LATER TODO need to decide if use last update or the one received
        // log("THERE IS A PROBLEM");
        // return;
        // }
        log("Received update from the coordinator " + coordinatorRef.path().name());

        temporaryBuffer.put(update.messageIdentifier, new Data(update.value, this.peers.size() + 1));
        acknowledgeUpdate ack = new acknowledgeUpdate(update.messageIdentifier, this.id);
        coordinatorRef.tell(ack, getSelf());
        // this.toBeDelivered.putIfAbsent(lastUpdate, null)

    }

    private void onAcknowledgeUpdate(acknowledgeUpdate ack) {
        // if (getSelf().equals(coordinatorRef)) {
        // log("Received ack from replica, but i'm not a coordinator");
        // return;
        // }
        log("Received ack from replica " + ack.senderId + " for message " + ack.messageIdentifier);
        // step 2 of 2 phase broadcast protocol
        temporaryBuffer.get(ack.messageIdentifier).ackBuffers.set(ack.senderId, true);
        boolean reachedQuorum = temporaryBuffer.get(ack.messageIdentifier).ackBuffers.stream()
                .filter(Boolean::booleanValue)
                .count() >= quorumSize;

        if (reachedQuorum) {
            // send confirm to the other replicas
            log("Reached quorum for message " + ack.messageIdentifier);
            WriteOK confirmDelivery = new WriteOK(ack.messageIdentifier);
            multicast(confirmDelivery);

            // deliver the message
            this.replicaVariable = temporaryBuffer.get(ack.messageIdentifier).value;
            temporaryBuffer.remove(ack.messageIdentifier);
            history.add(new Update(ack.messageIdentifier, this.replicaVariable));
            log(history.get(history.size() - 1).toString());

        }
    }

    private void onWriteOK(WriteOK confirmMessage) {
        log("Received confirm to deliver from the coordinator");
        this.replicaVariable = temporaryBuffer.get(confirmMessage.messageIdentifier).value;
        temporaryBuffer.remove(confirmMessage.messageIdentifier);
        history.add(new Update(confirmMessage.messageIdentifier, this.replicaVariable));
        log(history.get(history.size() - 1).toString());
        // request.client.tell("ack", getSelf());
    }

    private void onReadRequest(ReadRequest request) {
        log("received read request");
        request.client.tell(new ReadResponse(replicaVariable), getSelf());
    }

    private void onGroupInfo(GroupInfo groupInfo) {
        for (ActorRef peer : groupInfo.group) {
            if (!peer.equals(getSelf())) {
                this.peers.add(peer);
            }
        }
        this.quorumSize = (int) Math.ceil(peers.size() / 2);
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
                        // I would win the election, so I send to all replicas the sychronization
                        // message
                        SynchronizationMessage synchronizationMessage = new SynchronizationMessage(id, getSelf());
                        for (int i = 0; i < peers.size(); i++) {
                            // send the synchronization message to all replicas except me
                            peers.get(i).tell(synchronizationMessage, getSelf());
                        }
                        this.coordinatorRef = getSelf();
                        this.isElectionRunning = false;
                        log("I won the election");
                    }
                } else {
                    // I would win the election, so I send to all replicas the sychronization
                    // message
                    SynchronizationMessage synchronizationMessage = new SynchronizationMessage(id, getSelf());
                    for (int i = 0; i < peers.size(); i++) {
                        // send the synchronization message to all replicas except me
                        peers.get(i).tell(synchronizationMessage, getSelf());
                    }
                    this.coordinatorRef = getSelf();
                    this.isElectionRunning = false;
                    log("I won the election");
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
        System.out.println("Replica " + id + " received synchronization message from " + coordinatorId + " with ref "
                + coordinatorRef);
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

    private void log(String message) {
        System.out.println(getSelf().path().name() + ": " + message);
    }
    // private void findIndex(){
    // it may be needed in the future
    // }

}