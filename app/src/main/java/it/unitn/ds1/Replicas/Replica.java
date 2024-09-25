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
import it.unitn.ds1.Replicas.messages.PrintHistory;
import it.unitn.ds1.Replicas.messages.SynchronizationMessage;
import it.unitn.ds1.Replicas.messages.UpdateVariable;
import it.unitn.ds1.Messages.GroupInfo;

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
    private final int electionTimeoutDuration = 8000;

    // timers
    private int afterUpdateTimer = 5000;
    private int afterForwardTimer = 5000;
    private final int coordinatorHeartbeatTimer = 8000;

    private int id;
    private int replicaVariable;
    private List<ActorRef> peers = new ArrayList<>();
    private List<ActorRef> crashedReplicas = new ArrayList<>();
    private boolean isCrashed = false;

    private MessageIdentifier lastUpdate = new MessageIdentifier(0, 0);// remove

    private boolean isElectionRunning = false;
    private ActorRef coordinatorRef;

    private Cancellable heartbeatTimeout; // replica timeout for coordinator heartbeat
    private Cancellable sendHeartbeat; // coordinator sends heartbeat to replicas
    private Cancellable electionTimeout;
    private List<Cancellable> afterForwardTimeout = new ArrayList<>(); // after forward to the coordinator
    private List<Cancellable> afterUpdateTimeout = new ArrayList<>();

    private int quorumSize;
    private HashMap<MessageIdentifier, Data> temporaryBuffer = new HashMap<>();
    private List<Update> history = new ArrayList<>();
    private final BufferedWriter writer;

    // USED TO TEST THE CRASH
    private int heartbeatCounter = 0;

    class Data {
        private List<Boolean> ackBuffers; // ack from all replicas
        private int value;

        public Data(int value, int size) {
            this.value = value;
            this.ackBuffers = new ArrayList<>(Collections.nCopies(size, false));
        }
    }

    class Update {
        private MessageIdentifier messageIdentifier;
        private int value;

        public Update(MessageIdentifier messageIdentifier, int value) {
            this.messageIdentifier = messageIdentifier;
            this.value = value;
        }

        public MessageIdentifier getMessageIdentifier() {
            return this.messageIdentifier;
        }

        @Override
        public String toString() {
            return "update " + messageIdentifier.toString() + " " + value;
        }
    }

    // private HashMap<pair, Integer> history;
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
                .match(fakeMessage.class, this::OnFakeMessage)
                // .match(StartElectionMessage.class, this::startElection)
                .build();
    }

    final AbstractActor.Receive crashed() {
        return receiveBuilder()
                .matchAny(msg -> {
                    log("I'm crashed, I cannot process messages");
                })
                .build();
    }

    private void crash(int id) {
        // -1 is for the coordinator
        if (id == -1 && coordinatorRef.equals(getSelf())) {
            isCrashed = true;
            log("i'm crashing");
            getContext().become(crashed());

            if (sendHeartbeat != null && this.coordinatorRef.equals(getSelf())) {
                sendHeartbeat.cancel();
            }

            if (electionTimeout != null) {
                electionTimeout.cancel();
            }
            return;
        }
        if (this.id != id)
            return;
        isCrashed = true;
        log("i'm crashing");
        getContext().become(crashed());

        if (sendHeartbeat != null && this.coordinatorRef.equals(getSelf())) {
            sendHeartbeat.cancel();
        }

        if (electionTimeout != null) {
            electionTimeout.cancel();
        }

    }

    static public Props props(int id) {
        return Props.create(Replica.class, () -> new Replica(id));
    }

    private void multicast(Serializable message) {

        for (ActorRef peer : peers) {
            peer.tell(message, getSelf());
        }
    }

    private void onPrintHistory(PrintHistory printHistory) {
        String historyMessage = "#################HISTORY########################\n";
        for (Update update : history) {
            historyMessage += update.toString();
        }
        historyMessage += "################################################\n";
        log(historyMessage);
    }

    private void OnFakeMessage(fakeMessage f) {
        log("fake message");
    }
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
            temporaryBuffer.put(lastUpdate, new Data(request.value, this.peers.size() + 1));
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
        lastUpdate = lastUpdate.incrementSequenceNumber();
        if (lastUpdate.compareTo(update.messageIdentifier) != 0) {// MITGH BE REMOVED
            // LATER TODO need to decide if use last update or the one received
            log("THERE IS A PROBLEM");
            return;
        }
        log("Received update " + update.messageIdentifier + " from the coordinator " + coordinatorRef.path().name());

        temporaryBuffer.put(update.messageIdentifier, new Data(update.value, this.peers.size() + 1));
        AcknowledgeUpdate ack = new AcknowledgeUpdate(update.messageIdentifier, this.id);
        coordinatorRef.tell(ack, getSelf());

        afterUpdateTimeout.add(this.timeoutScheduler(afterUpdateTimer));
        // this.toBeDelivered.putIfAbsent(lastUpdate, null)

    }

    private Cancellable timeoutScheduler(int ms) {
        return getContext()
                .getSystem()
                .scheduler()
                .scheduleOnce(
                        Duration.ofMillis(ms),
                        getSelf(),
                        new fakeMessage(),
                        getContext().getSystem().dispatcher(),
                        getSelf());
    }

    class fakeMessage {
    };
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
            log(history.get(history.size() - 1).toString());

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
        log(history.get(history.size() - 1).toString());
        // request.client.tell("ack", getSelf());
    }

    private void onReadRequest(ReadRequest request) {
        log("received read request");
        getSender().tell(new ReadResponse(replicaVariable), getSelf());
    }

    private void onGroupInfo(GroupInfo groupInfo) {
        for (ActorRef peer : groupInfo.group) {
            if (!peer.equals(getSelf())) {
                this.peers.add(peer);
            }
        }
        this.quorumSize = (int) Math.floor(peers.size() / 2) + 1;
        this.startElection();
    }

    private void onElectionMessage(ElectionMessage electionMessage) {
        log("Received election message from " + getSender().path().name());

        if (this.id == 2) {
            crash(2);
            return;
        }
        // if I'm the coordinator and I receive an election message
        // I ack the sender but I don't start a new election. 
        if (this.coordinatorRef != null && this.coordinatorRef.equals(getSelf())) {
            log("I'm the coordinator, synchronization message already sent");
            getSender().tell(new AckElectionMessage(), getSelf());
            return;
        }

        if (!this.isElectionRunning) {
            this.isElectionRunning = true;
            Update lastUpdate = this.history.get(this.history.size() - 1);
            electionMessage = electionMessage.addState(id, lastUpdate.getMessageIdentifier(),
                    electionMessage.quorumState);
            // get the next ActorRef in the quorum
            ActorRef nextRef = peers.get((id) % peers.size());
            nextRef.tell(electionMessage, getSelf());
            log("Sent election message to replica " + nextRef.path().name());
            getSender().tell(new AckElectionMessage(), getSelf());
            electionTimeout = scheduleElectionTimeout(electionMessage);
        } else {
            // if Im in the quorum
            if (electionMessage.quorumState.containsKey(id)) {
                // I need to check if I have the most recent update or the highest id
                MessageIdentifier maxUpdate = Collections.max(electionMessage.quorumState.values());
                MessageIdentifier lastUpdate = this.history.get(this.history.size() - 1).getMessageIdentifier();

                if (maxUpdate.compareTo(lastUpdate) > 0) {
                    // I would lose the election, so I forward to the next replica
                    ActorRef nextRef = peers.get((id) % peers.size());
                    nextRef.tell(electionMessage, getSelf());
                    log("Sent election message to replica " + nextRef.path().name());
                    getSender().tell(new AckElectionMessage(), getSelf());
                    electionTimeout = scheduleElectionTimeout(electionMessage);
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
                        ActorRef nextRef = peers.get((id) % peers.size());
                        nextRef.tell(electionMessage, getSelf());
                        log("Sent election message to replica " + nextRef.path().name());
                        getSender().tell(new AckElectionMessage(), getSelf());
                        electionTimeout = scheduleElectionTimeout(electionMessage);
                    } else {
                        // I would win the election, so I send to all replicas the sychronization message
                        getSender().tell(new AckElectionMessage(), getSelf()); // is this the right place?
                        SynchronizationMessage synchronizationMessage = new SynchronizationMessage(id, getSelf());
                        for (int i = 0; i < peers.size(); i++) {
                            // send the synchronization message to all replicas except me
                            peers.get(i).tell(synchronizationMessage, getSelf());
                        }
                        this.coordinatorRef = getSelf();
                        this.isElectionRunning = false;
                        this.startHeartbeat();
                        this.electionTimeout.cancel();
                        log("I won the election");
                    }
                }
            } else {
                // I need to add my state to the message and forward it
                Update lastUpdate = this.history.get(this.history.size() - 1);
                electionMessage = electionMessage.addState(id, lastUpdate.getMessageIdentifier(),
                        electionMessage.quorumState);
                // get the next ActorRef which is id (since in peers i'm not present)
                ActorRef nextRef = peers.get((id) % peers.size());
                nextRef.tell(electionMessage, getSelf());
                log("Sent election message to replica " + nextRef.path().name());
                getSender().tell(new AckElectionMessage(), getSelf());
                electionTimeout = scheduleElectionTimeout(electionMessage);
            }
        }
    }

    private void onSynchronizationMessage(SynchronizationMessage synchronizationMessage) {
        this.isElectionRunning = false;
        this.coordinatorRef = synchronizationMessage.getCoordinatorRef();
        this.electionTimeout.cancel();
        log(" received synchronization message from " + coordinatorRef.path().name());
    }

    private void startElection() {
        isElectionRunning = true;
        ElectionMessage electionMessage = new ElectionMessage(
            id, 
            this.history.get(this.history.size() - 1).getMessageIdentifier()
        );
        // remove crashed replicas from the peers list
        peers = peers.stream().filter(peer -> !crashedReplicas.contains(peer)).toList();
        // get the next ActorRef in the quorum
        ActorRef nextRef = peers.get((id) % peers.size());
        nextRef.tell(electionMessage, getSelf());
        log("Sent election message to replica " + nextRef.path().name());

        final ElectionMessage finalElectionMessage = electionMessage;
        electionTimeout = scheduleElectionTimeout(finalElectionMessage);
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
                                    log("Im no logner the coordinator");
                                    Replica.this.sendHeartbeat.cancel();
                                } else {
                                    // this crash seems to work
                                    // if (heartbeatCounter == 2 && id == 2) {
                                    //     log("I'm crashing");
                                    //     heartbeatCounter = 0;
                                    //     crash(2);
                                    //     return;
                                    // }
                                    // heartbeatCounter++;
                                    multicast(new HeartbeatMessage());
                                }

                        }
                    }, 
                    getContext().getSystem().dispatcher()
                );
    }

    private void onHeartbeatMessage(HeartbeatMessage heartbeatMessage) {
        log("Received heartbeat message from coordinator");
        
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
                            crashedReplicas.add(coordinatorRef);
                            startElection();
                        }
                    }, 
                    getContext().getSystem().dispatcher()
                );
    }

    private void onAckElectionMessage(AckElectionMessage ackElectionMessage) {
        log("Received election ack from " + getSender().path().name());
        
        if (this.electionTimeout != null) {
            this.electionTimeout.cancel();
        }
    }

    private Cancellable scheduleElectionTimeout(final ElectionMessage electionMessage) {
        // Need to doublecheck 
        if (electionTimeout != null) {
            electionTimeout.cancel();
        }
        return getContext()
                .getSystem()
                .scheduler()
                .scheduleOnce(
                    Duration.ofMillis(electionTimeoutDuration), 
                    new Runnable() {
                        @Override
                        public void run() {
                            log("Election timeout, sending election message to the next replica");
                            // Here coordinatorRef is cleary wrong, instead i should add the id of the next replica
                            // which is id or id + 1???
                            crashedReplicas.add(coordinatorRef); 
                            ActorRef nextRef = peers.get((id + 1) % peers.size());
                            nextRef.tell(electionMessage, getSelf());
                        }
                    }, 
                    getContext().getSystem().dispatcher()
                );
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

    // private void findIndex(){
    // it may be needed in the future
    // }

}