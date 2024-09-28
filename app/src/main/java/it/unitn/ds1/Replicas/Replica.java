package it.unitn.ds1.Replicas;

import it.unitn.ds1.MessageIdentifier;
import it.unitn.ds1.Messages.ReadRequest;
import it.unitn.ds1.Messages.ReadResponse;
import it.unitn.ds1.Messages.WriteRequest;
import it.unitn.ds1.Replicas.messages.AckElectionMessage;
import it.unitn.ds1.Replicas.messages.WriteOK;
import it.unitn.ds1.Replicas.messages.AcknowledgeUpdate;
import it.unitn.ds1.Replicas.messages.ElectionMessage;
import it.unitn.ds1.Replicas.messages.HeartbeatMessage;
import it.unitn.ds1.Replicas.messages.StartElectionMessage;
import it.unitn.ds1.Replicas.messages.PrintHistory;
import it.unitn.ds1.Replicas.messages.SynchronizationMessage;
import it.unitn.ds1.Replicas.messages.UpdateVariable;
import it.unitn.ds1.Messages.GroupInfo;

import it.unitn.ds1.Replicas.types.Data;
import it.unitn.ds1.Replicas.types.Update;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.io.Serializable;
import java.time.Duration;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.File;
import java.io.IOException;

import akka.actor.AbstractActor;
import akka.actor.Props;
import akka.actor.ActorRef;
import akka.actor.Cancellable;

public class Replica extends AbstractActor {
    private final int coordinatorHeartbeatFrequency = 5000;
    private final int electionTimeoutDuration = 10000;

    // timers
    private int afterUpdateTimer = 5000;
    private int afterForwardTimer = 5000;
    private final int coordinatorHeartbeatTimer = 8000;

    private int id;
    private int replicaVariable;
    private List<ActorRef> peers = new ArrayList<>();
    private boolean isCrashed = false;
    private ActorRef nextRef = null;

    private MessageIdentifier lastUpdate = new MessageIdentifier(-1, 0);;

    private boolean isElectionRunning = false;
    private ActorRef coordinatorRef;

    private Cancellable heartbeatTimeout; // replica timeout for coordinator heartbeat
    private Cancellable sendHeartbeat; // coordinator sends heartbeat to replicas
    private List<Cancellable> afterForwardTimeout = new ArrayList<>(); // after forward to the coordinator
    private List<Cancellable> afterUpdateTimeout = new ArrayList<>();
    private List<Cancellable> acksElectionTimeout = new ArrayList<>(); // this contains all the timeouts that are waiting to receive an ack

    private int quorumSize;
    private HashMap<MessageIdentifier, Data> temporaryBuffer = new HashMap<>();
    private List<Update> history = new ArrayList<>();
    private final BufferedWriter writer;

    // USED TO TEST THE CRASH
    private int heartbeatCounter = 0;

    // -------------------------- REPLICA ---------------------------
    public Replica(int id) throws IOException {
        this.replicaVariable = 0;
        this.id = id;
        this.history.add(new Update(new MessageIdentifier(0, 0), this.replicaVariable));
        String directoryPath = "logs";
        String filePath = directoryPath + File.separator + getSelf().path().name() + ".txt";

        // Create the directory if it doesn't exist
        File directory = new File(directoryPath);
        if (!directory.exists()) {
            directory.mkdirs(); // Create the directory and any necessary parent directories
        }
        writer = new BufferedWriter(new FileWriter(filePath, false));
        log("Created replica ");
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(WriteRequest.class, this::onWriteRequest)
                .match(WriteOK.class, this::onWriteOK)
                .match(UpdateVariable.class, this::onUpdateVariable)
                .match(AcknowledgeUpdate.class, this::onAcknowledgeUpdate)
                .match(ReadRequest.class, this::onReadRequest)
                .match(GroupInfo.class, this::onGroupInfo)
                .match(ElectionMessage.class, this::onElectionMessage)
                .match(SynchronizationMessage.class, this::onSynchronizationMessage)
                .match(HeartbeatMessage.class, this::onHeartbeatMessage)
                .match(AckElectionMessage.class, this::onAckElectionMessage)
                .match(PrintHistory.class, this::onPrintHistory)
                .match(StartElectionMessage.class, this::startElection)
                .build();
    }

    final AbstractActor.Receive crashed() {
        return receiveBuilder()
                .matchAny(msg -> {
                    log("I'm crashed, I cannot process messages");
                })
                .build();
    }

    static public Props props(int id) {
        return Props.create(Replica.class, () -> new Replica(id));
    }

    // ----------------------- BASIC HANDLERS -----------------------
    private void onGroupInfo(GroupInfo groupInfo) {
        for (ActorRef peer : groupInfo.group) {
            this.peers.add(peer);
        }
        this.quorumSize = (int) Math.floor(peers.size() / 2) + 1;
        this.nextRef = peers.get((peers.indexOf(getSelf()) + 1) % peers.size());
        StartElectionMessage startElectionMessage = new StartElectionMessage();
        this.startElection(startElectionMessage);
    }

    private void onReadRequest(ReadRequest request) {
        log("received read request");
        getSender().tell(new ReadResponse(replicaVariable), getSelf());
    }

    private void onPrintHistory(PrintHistory printHistory) {
        String historyMessage = "#################HISTORY########################\n";
        for (Update update : history) {
            historyMessage += update.toString() + "\n";
        }
        historyMessage += "################################################\n";
        log(historyMessage);
    }

    // ----------------------- 2 PHASE BROADCAST ---------------------
    private void onWriteRequest(WriteRequest request) {
        if (this.coordinatorRef == null || isElectionRunning) {
            String reasonMessage = this.coordinatorRef == null ? "coordinator is null" : "election is running";
            log("Cannot process write request now: " + reasonMessage + ", retrying after 500ms" + isElectionRunning);
            // retry after 500ms
            getContext()
                    .getSystem()
                    .scheduler()
                    .scheduleOnce(java.time.Duration.ofMillis(500), getSelf(),
                            new WriteRequest(request.value), getContext().getSystem().dispatcher(), getSelf());
            return;
        }

        // crash(2);
        // if (isCrashed)
        // return;
        if (getSelf().equals(coordinatorRef)) {
            log("Received write request from client, starting 2 phase broadcast protocol");
            // step 1 of 2 phase broadcast protocol
            lastUpdate = lastUpdate.incrementSequenceNumber();
            UpdateVariable update = new UpdateVariable(lastUpdate, request.value);
            multicast(update);

            // initialize the toBeDelivered list and set the coordinator as received
            temporaryBuffer.put(lastUpdate, new Data(request.value, this.peers.size()));
            temporaryBuffer.get(lastUpdate).ackBuffers.set(id, true);
            log("acknowledged message id " + lastUpdate.toString());

        } else {
            // forward the write request to the coordinator
            log("forwarding write request to coordinator " + coordinatorRef.path().name());
            coordinatorRef.tell(request, getSelf());
            this.afterForwardTimeout.add(this.timeoutScheduler(afterForwardTimer));

        }
    }

    private void onUpdateVariable(UpdateVariable update) {

        if (this.afterForwardTimeout.size() > 0) {
            log("canceling afterForwardTimeout because received update from coordinator");
            this.afterForwardTimeout.get(0).cancel(); // the coordinator is alive
            this.afterForwardTimeout.remove(0);
        }
        this.lastUpdate = update.messageIdentifier;
        log("Received update " + update.messageIdentifier + " from the coordinator " + coordinatorRef.path().name());

        temporaryBuffer.put(update.messageIdentifier, new Data(update.value, this.peers.size()));
        AcknowledgeUpdate ack = new AcknowledgeUpdate(update.messageIdentifier, this.id);
        coordinatorRef.tell(ack, getSelf());

        afterUpdateTimeout.add(this.timeoutScheduler(afterUpdateTimer));
        // this.toBeDelivered.putIfAbsent(lastUpdate, null)

    }

    private void onAcknowledgeUpdate(AcknowledgeUpdate ack) {
        // if (getSelf().equals(coordinatorRef)) {
        // log("Received ack from replica, but i'm not a coordinator");
        // return;
        // }
        if (!temporaryBuffer.containsKey(ack.messageIdentifier)) {
            log("slow ack from replica_" + ack.senderId + ", " + ack.messageIdentifier + " has been already confirmed");
            return;
        }
        log("Received ack from replica_" + ack.senderId + " for message " + ack.messageIdentifier);
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
            log(this.getLastUpdate().toString());

        }
    }

    private void onWriteOK(WriteOK confirmMessage) {
        if (afterUpdateTimeout.size() > 0) { // 0, the assumption is that the communication channel is fifo, so whenever
                                             // arrive,i have to delete the oldest
            log("canceling afterUpdateTimeout because received confirm from coordinator");
            afterUpdateTimeout.get(0).cancel();// the coordinator is alive
            afterUpdateTimeout.remove(0);
        }
        log("Received confirm to deliver from the coordinator");
        this.replicaVariable = temporaryBuffer.get(confirmMessage.messageIdentifier).value;
        temporaryBuffer.remove(confirmMessage.messageIdentifier);
        history.add(new Update(confirmMessage.messageIdentifier, this.replicaVariable));
        log(this.getLastUpdate().toString());
        // request.client.tell("ack", getSelf());
    }


    // ----------------------- ELECTION HANDLERS -----------------------
    private void startElection(StartElectionMessage startElectionMessage) {
        this.isElectionRunning = true;
        ElectionMessage electionMessage = new ElectionMessage(
                id, this.getLastUpdate().getMessageIdentifier());
        // get the next ActorRef in the quorum
        this.forwardElectionMessage(electionMessage, false);
    }

    private void onElectionMessage(ElectionMessage electionMessage) {
        log("Received election message from " + getSender().path().name());

        // if (this.id == 2) {
        //     crash(2);
        //     return;
        // }
        // if I'm the coordinator and I receive an election message
        // I ack the sender but I don't start a new election. 
        if (this.coordinatorRef != null && this.coordinatorRef.equals(getSelf())) {
            log("I'm the coordinator, sending synchronization message again");
            this.isElectionRunning = false;
            SynchronizationMessage synchronizationMessage = new SynchronizationMessage(id, getSelf());
            multicast(synchronizationMessage);
            getSender().tell(new AckElectionMessage(), getSelf());
            return;
        }

        if (!this.isElectionRunning) {
            this.isElectionRunning = true;
            Update lastUpdate = this.getLastUpdate();
            electionMessage = electionMessage.addState(id, lastUpdate.getMessageIdentifier(),
                    electionMessage.quorumState);
            // get the next ActorRef in the quorum
            forwardElectionMessage(electionMessage);
        } else {
            // if Im in the quorum
            if (electionMessage.quorumState.containsKey(id)) {
                // I need to check if I have the most recent update or the highest id
                MessageIdentifier maxUpdate = Collections.max(electionMessage.quorumState.values());
                MessageIdentifier lastUpdate = this.getLastUpdate().getMessageIdentifier();

                if (maxUpdate.compareTo(lastUpdate) > 0) {
                    // I would lose the election, so I forward to the next replica
                    forwardElectionMessage(electionMessage);
                } else { 
                    // if maxUpdate is not greater than lastUpdate, then it must be equal
                    // so we check who has the highest id with the latest update
                    ArrayList<Integer> ids = new ArrayList<>();
                    electionMessage.quorumState.forEach((k, v) -> {
                        if (maxUpdate.compareTo(v) == 0) {
                            ids.add(k);
                        }
                    });
                    int maxId = Collections.max(ids);

                    if (maxId > id) {
                        // I would lose the election, so I forward to the next replica
                        forwardElectionMessage(electionMessage);
                    } else {
                        // I would win the election, so I send to all replicas the sychronization message
                        getSender().tell(new AckElectionMessage(), getSelf()); // is this the right place?
                        SynchronizationMessage synchronizationMessage = new SynchronizationMessage(id, getSelf());
                        multicast(synchronizationMessage);
                        this.coordinatorRef = getSelf();
                        this.isElectionRunning = false;
                        this.startHeartbeat();
                        this.lastUpdate = this.lastUpdate.incrementEpoch();
                        // for (Cancellable ack : this.acksElectionTimeout) {
                        // log("ENRICO");
                        // ack.cancel();
                        // }
                        String s = "";
                        for (ActorRef peer : peers) {
                            s += "" + peer.path().name() + " | ";
                        }
                        log("Alive peers: " + s);
                        log("I won the election");
                    }
                }
            } else {
                // I need to add my state to the message and forward it
                Update lastUpdate = this.getLastUpdate();
                electionMessage = electionMessage.addState(id, lastUpdate.getMessageIdentifier(),
                        electionMessage.quorumState);
                forwardElectionMessage(electionMessage);
            }
        }
    }

    private void onAckElectionMessage(AckElectionMessage ackElectionMessage) {
        log("Received election ack from " + getSender().path().name());

        if (this.acksElectionTimeout.size() > 0) {
            this.acksElectionTimeout.get(0).cancel();
            this.acksElectionTimeout.remove(0);
        }
    }

    private void onSynchronizationMessage(SynchronizationMessage synchronizationMessage) {
        this.isElectionRunning = false;
        this.coordinatorRef = synchronizationMessage.getCoordinatorRef();
        log(" received synchronization message from " + coordinatorRef.path().name());
    }

    /**
     * Start the heartbeat mechanism, the coordinator sends a heartbeat message to
     * all replicas every 5 seconds
     */
    private void startHeartbeat() {

        this.sendHeartbeat = getContext()
                .getSystem()
                .scheduler()
                .scheduleWithFixedDelay(
                    Duration.ZERO, 
                    Duration.ofMillis(coordinatorHeartbeatFrequency),
                    new Runnable() {
                        @Override
                        public void run() {
                                // log(Replica.this.coordinatorRef.path().name() + " is sending heartbeat
                                // message");
                                if (Replica.this.coordinatorRef != getSelf()) {
                                    log("Im no longer the coordinator");
                                    Replica.this.sendHeartbeat.cancel();
                                } else {
                                    // this crash seems to work
                                    if (heartbeatCounter == 1 && id == 4) {
                                        heartbeatCounter = 0;
                                        crash(4);
                                        return;
                                    }

                                    if (heartbeatCounter == 1
                                            && Replica.this.coordinatorRef.path().name().equals("replica_3")) {
                                        heartbeatCounter = 0;
                                        crash(3);
                                        return;
                                    }
                                    heartbeatCounter++;
                                    multicast(new HeartbeatMessage());
                                }

                        }
                    }, 
                    getContext().getSystem().dispatcher()
                );
    }

    private void onHeartbeatMessage(HeartbeatMessage heartbeatMessage) {
        String message = "Received heartbeat message from coordinator " + getSender().path().name()
                + "\nmy coordinator is " + this.coordinatorRef.path().name();

        log(message);
        
        if (this.heartbeatTimeout != null) {
            this.heartbeatTimeout.cancel();
        }

        heartbeatTimeout = getContext()
                .getSystem()
                .scheduler()
                .scheduleOnce(
                        Duration.ofMillis(coordinatorHeartbeatTimer),
                        new Runnable() {
                        @Override
                        public void run() {
                            log("Coordinator is dead, starting election");
                            // remove crashed replica from the peers list
                                removePeer(coordinatorRef, new ArrayList<>());
                                StartElectionMessage startElectionMessage = new StartElectionMessage();
                                startElection(startElectionMessage);
                        }
                    }, 
                    getContext().getSystem().dispatcher()
                );
    }

    private Cancellable scheduleElectionTimeout(final ElectionMessage electionMessage) {
        
        return getContext()
                .getSystem()
                .scheduler()
                .scheduleOnce(
                    Duration.ofMillis(electionTimeoutDuration), 
                    new Runnable() {
                        @Override
                        public void run() {
                                log("Election timeout "+nextRef.path().name()+", sending election message to the next replica");
                                // remove nextRef from the peers list and cancel all the acks relative to
                                // nextRef
                                removePeer(nextRef, acksElectionTimeout);
                                // no need to ack achain, since im not crashed and i have already sent the ack
                                // to the previous node
                                forwardElectionMessage(electionMessage, false);
                        }
                    }, 
                    getContext().getSystem().dispatcher()
                );
    }

    // --------------------- UTILITY FUNCTION ---------------------
    private Update getLastUpdate() {
        return history.get(history.size() - 1);
    }

    private void multicast(Serializable message) {

        for (ActorRef peer : peers) {
            if (peer != getSelf()) {
                peer.tell(message, getSelf());
            }
        }
    }

    private void removePeer(ActorRef peer, List<Cancellable> toBeRemoveAcks) {
        this.peers.remove(peer);
        // this.quorumSize = (int) Math.floor(peers.size() / 2) + 1;
        int myIndex = peers.indexOf(getSelf());
        this.nextRef = peers.get((myIndex + 1) % peers.size());
        log("Removed peer " + peer.path().name() + " my new next replica is " + this.nextRef.path().name());
        for (Cancellable ack : toBeRemoveAcks) {
            ack.cancel();
        }

    }

    private void forwardElectionMessage(ElectionMessage electionMessage) {
        forwardElectionMessage(electionMessage, true);
    }

    private void forwardElectionMessage(ElectionMessage electionMessage, boolean ack) {
        this.nextRef.tell(electionMessage, getSelf());
        log("Sent election message to replica " + this.nextRef.path().name());
        if (ack)
            getSender().tell(new AckElectionMessage(), getSelf());
        Cancellable electionTimeout = scheduleElectionTimeout(electionMessage);
        this.acksElectionTimeout.add(electionTimeout);
    }

    private Cancellable timeoutScheduler(int ms) {
        return getContext()
                .getSystem()
                .scheduler()
                .scheduleOnce(
                        Duration.ofMillis(ms),
                        getSelf(),
                        new StartElectionMessage(),
                        getContext().getSystem().dispatcher(),
                        getSelf());
    }

    private void crash(int id) {
        // -1 is for the coordinator
        // if (id == -1 && coordinatorRef.equals(getSelf())) {
        // isCrashed = true;
        // log("i'm crashing");
        // getContext().become(crashed());

        // if (sendHeartbeat != null && this.coordinatorRef.equals(getSelf())) {
        // sendHeartbeat.cancel();
        // }

        // for (Cancellable ack : this.acksElectionTimeout) {
        // ack.cancel();
        // }
        // return;
        // }
        if (this.id != id)
            return;

        if (sendHeartbeat != null && this.coordinatorRef.equals(getSelf())) {
            sendHeartbeat.cancel();
        }

        for (Cancellable ack : this.acksElectionTimeout) {
            ack.cancel();
        }
        for (Cancellable ack : this.afterForwardTimeout) {
            ack.cancel();
        }
        for (Cancellable ack : this.afterUpdateTimeout) {
            ack.cancel();
        }

        isCrashed = true;
        log("i'm crashing " + id);
        getContext().become(crashed());

    }

    private void log(String message) {
        String msg = getSelf().path().name() + ": " + message;
        try {
            writer.write(msg + System.lineSeparator());
            writer.flush();
            System.out.println(msg);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // --------------------------- END ----------------------------
}

// TODO share the crash of a replica with all the other replicas
// during election only the previous node of the crashed one will modify the peer list
// is this a problem for other replicas? (multicast/quorum)
