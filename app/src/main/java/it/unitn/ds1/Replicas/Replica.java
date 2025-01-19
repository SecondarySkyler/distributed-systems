package it.unitn.ds1.Replicas;

import it.unitn.ds1.Messages.ReadRequest;
import it.unitn.ds1.Messages.ReadResponse;
import it.unitn.ds1.Messages.WriteRequest;
import it.unitn.ds1.Replicas.messages.AckElectionMessage;
import it.unitn.ds1.Replicas.messages.WriteOK;
import it.unitn.ds1.Replicas.messages.AcknowledgeUpdate;
import it.unitn.ds1.Replicas.messages.CoordinatorCrashedMessage;
import it.unitn.ds1.Replicas.messages.CrashedNextReplicaMessage;
import it.unitn.ds1.Replicas.messages.ElectionMessage;
import it.unitn.ds1.Replicas.messages.ReceiveHeartbeatMessage;
import it.unitn.ds1.Replicas.messages.StartElectionMessage;
import it.unitn.ds1.Replicas.messages.PrintHistory;
import it.unitn.ds1.Replicas.messages.SendHeartbeatMessage;
import it.unitn.ds1.Replicas.messages.SynchronizationMessage;
import it.unitn.ds1.Replicas.messages.UpdateVariable;
import it.unitn.ds1.Messages.GroupInfo;
import it.unitn.ds1.Replicas.types.Crash;

import it.unitn.ds1.Replicas.types.Data;
import it.unitn.ds1.Replicas.types.MessageIdentifier;
import it.unitn.ds1.Replicas.types.PendingUpdate;
import it.unitn.ds1.Replicas.types.Update;
import scala.util.Random;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.Set;
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

    // Timers durations
    private static final int coordinatorHeartbeatFrequency = 1000; // Frequency at which the coordinator sends heartbeat messages to other nodes

    private static int electionTimeoutDuration; // Global timer related to the duration of the election
    private static final int ackElectionMessageDuration = 1000; // If the replica doesn't receive an ack from the next replica
    private static final int afterForwardTimeoutDuration = 1000; // If the replica doesn't receive an update message after having forwarded a write request
    private static final int afterUpdateTimeoutDuration = 3000; // If the replica doesn't receive a WriteOK message from the coordinator
    private int coordinatorHeartbeatTimeoutDuration = 5000; // If the replica doesn't receive a heartbeat message from the coordinator

    private static final int messageMaxDelay = 150;
    static Random rnd = new Random();

    private final int id;
    private int replicaVariable;
    private final List<ActorRef> peers = new ArrayList<>();

    private ActorRef nextRef = null;
    private final List<WriteRequest> writeRequestMessageQueue = new ArrayList<>(); // Messages that i have to send to the coordinator

    private MessageIdentifier lastUpdate = new MessageIdentifier(-1, 0);

    private ActorRef coordinatorRef;

    private Cancellable heartbeatTimeout; // Replica timeout for coordinator heartbeat
    private Cancellable sendHeartbeat; // Coordinator sends heartbeat to replicas
    private Cancellable electionTimeout; // Election timeout for the next replica
    private final List<Cancellable> afterForwardTimeout = new ArrayList<>(); // After forward to the coordinator
    private final List<Cancellable> afterUpdateTimeout = new ArrayList<>();
    private final List<Cancellable> acksElectionTimeout = new ArrayList<>(); // This contains all the timeouts that are waiting to receive an ack

    private int quorumSize;
    private final HashMap<MessageIdentifier, Data> temporaryBuffer = new HashMap<>();
    private final List<Update> history = new ArrayList<>();
    private final BufferedWriter writer;

    // Crash flag
    private Crash crash_type = Crash.NO_CRASH;

    // USED TO TEST THE CRASH
    private int nWriteOk = 0;
    private static final int N_WRITE_OK = 3;

    // -------------------------- REPLICA ---------------------------
    public Replica(int id, String logFolderName, Crash crash_type) throws IOException {
        this.replicaVariable = -1;
        this.id = id;
        String directoryPath = logFolderName;
        String filePath = directoryPath + File.separator + getSelf().path().name() + ".txt";
        this.crash_type = crash_type;

        if (crash_type == Crash.NO_HEARTBEAT) {
            this.coordinatorHeartbeatTimeoutDuration = 50000;
        }
        // Create the directory if it doesn't exist
        File directory = new File(directoryPath);
        if (!directory.exists()) {
            directory.mkdirs(); // Create the directory and any necessary parent directories
        }
        this.writer = new BufferedWriter(new FileWriter(filePath, false));
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
                .match(ElectionMessage.class, this::onFirstElectionMessage) // This will trigger the behavior change to inElection
                .match(ReceiveHeartbeatMessage.class, this::onReceiveHeartbeatMessage)
                .match(AckElectionMessage.class, this::onAckElectionMessage)
                .match(PrintHistory.class, this::onPrintHistory)
                .match(StartElectionMessage.class, this::startElection)
                .match(CoordinatorCrashedMessage.class, this::onCoordinatorCrashed)
                .match(SendHeartbeatMessage.class, this::onSendHeartbeat)
                .build();
    }

    final AbstractActor.Receive crashed() {
        return receiveBuilder()
                .match(PrintHistory.class, this::onPrintHistory)
                .match(WriteRequest.class, this::testWriteRequestWhileCrashed)
                .matchAny(msg -> {
                    log("I'm crashed, I cannot process messages");
                })
                .build();
    }

    final AbstractActor.Receive inElection() {
        return receiveBuilder()
                .match(ReadRequest.class, this::onReadRequest)
                .match(WriteRequest.class, this::onWriteRequestOnElection) 
                .match(ElectionMessage.class, this::onElectionMessage)
                .match(AckElectionMessage.class, this::onAckElectionMessage)
                .match(SynchronizationMessage.class, this::onSynchronizationMessage)
                .match(CrashedNextReplicaMessage.class, this::onNextReplicaCrashed)
                .match(StartElectionMessage.class, this::startElection)
                .matchAny(msg -> {
                    log("I'm in election, I cannot process messages");
                })
                .build();
    }

    static public Props props(int id, String logFolderName,Crash crash_type) {
        return Props.create(Replica.class, () -> new Replica(id, logFolderName, crash_type));
    }

    
    // ----------------------- BASIC HANDLERS -----------------------

    /**
     * Handler method for the GroupInfo message
     * This message contains the list of all the replicas in the system
     * Here we calculate the quorum size and the next replica
     * Thus we start the first election
     * @param groupInfo the group info message
     */
    private void onGroupInfo(GroupInfo groupInfo) {
        for (ActorRef peer : groupInfo.group) {
            this.peers.add(peer);
        }
        this.quorumSize = (int) Math.floor(this.peers.size() / 2) + 1;
        this.nextRef = this.peers.get((this.peers.indexOf(getSelf()) + 1) % this.peers.size());
        Replica.electionTimeoutDuration = this.peers.size() * Replica.ackElectionMessageDuration;
        
        StartElectionMessage startElectionMessage = new StartElectionMessage("First election start");
        this.startElection(startElectionMessage);
    }

    /**
     * Handler method for the ReadRequest message.
     * This message is sent by the client to read the value of the variable
     * It sends back a ReadResponse message with the value of the variable
     * @param request the read request message
     */
    private void onReadRequest(ReadRequest request) {
        log("received read request");
        this.tellWithDelay(getSender(), getSelf(), new ReadResponse(replicaVariable));
    }

    private void onPrintHistory(PrintHistory printHistory) {
        String historyMessage = "\n#################HISTORY########################\n";
        for (Update update : this.history) {
            historyMessage += update.toString() + "\n";
        }
        historyMessage += "################################################\n";
        log(historyMessage + "\nTemporary buffer: " + temporaryBuffer.toString()+"\nWriteMessage Queue "+writeRequestMessageQueue.toString() + "\n\n");

    }

    private void testWriteRequestWhileCrashed(WriteRequest request) {
        log("Received write request from " + getSender().path().name() + " with value " + request.value + " while crashed");
    }

    // ----------------------- 2 PHASE BROADCAST ---------------------
    /**
     * Method used to handle the write request when an election is running
     * It adds the write request to the queue
     * @param request the write request
     */
    private void onWriteRequestOnElection(WriteRequest request) {
        String reasonMessage = "election is running";
        log(reasonMessage + ", adding the write request to the queue with value: " + request.value);
        this.writeRequestMessageQueue.add(request);
    }
    
    /**
     * Handler method for the WriteRequest message.
     * This message is sent by the client to write a new value to the variable
     * @param request the write request message, containing the new value
     */
    private void onWriteRequest(WriteRequest request) {
        if (this.coordinatorRef == null) {
            String reasonMessage = "coordinator is null";
            log(reasonMessage + ", adding the write request to the queue");
            this.writeRequestMessageQueue.add(request);
            return;
        }

        // If we are the coordinator, we start the 2 phase broadcast protocol
        if (getSelf().equals(coordinatorRef)) {
            log("Received write request from, " + getSender().path().name() + " with value: " + request.value + " starting 2 phase broadcast protocol, by sending an UPDATE message");
            if (this.crash_type == Crash.COORDINATOR_BEFORE_UPDATE_MESSAGE && this.coordinatorRef.equals(getSelf())) {
                crash();
                return;
            }
            // Step 1 of 2 phase broadcast protocol
            // If the sender is a replica, then the senderReplicaId is the id of the sender, otherwise it is the id of the current (coordinator) replica
            int senderReplicaId = getSender().path().name().contains("replica") ? Integer.parseInt(getSender().path().name().split("_")[1]) : this.id;
            log("sender id is: "+ senderReplicaId);
            UpdateVariable update = new UpdateVariable(this.lastUpdate, request.value, senderReplicaId);
            multicast(update);
            this.temporaryBuffer.put(this.lastUpdate, new Data(request.value, this.peers.size(), senderReplicaId));
            this.temporaryBuffer.get(this.lastUpdate).ackBuffers.add(id);
            log("acknowledged message id " + this.lastUpdate.toString() + " value: " + request.value);

            this.lastUpdate = this.lastUpdate.incrementSequenceNumber();
            // The coordinator crashes after sending the update message
            if (this.crash_type == Crash.COORDINATOR_AFTER_UPDATE_MESSAGE && this.coordinatorRef.equals(getSelf())) {
                crash();
                return;
            }
        } else {
            log("Forwarding write request to coordinator " + coordinatorRef.path().name());
            // Store the write request in the queue
            this.writeRequestMessageQueue.add(request);
            log("Write request queue: " + writeRequestMessageQueue.toString());
            this.tellWithDelay(this.coordinatorRef, getSelf(), request);

            // If I don't receive the ack, I have to resend the message and also start a new election.
            this.afterForwardTimeout.add(
                    this.timeoutScheduler(afterForwardTimeoutDuration, new StartElectionMessage(
                            "forwarded message of " + getSender().path().name() + " with value: " + request.value
                                    + ", but didn't receive update from the coordinator")));
            
            if (this.crash_type == Crash.REPLICA_AFTER_FORWARD_MESSAGE) {
                crash();
                return;
            }
        }
    }

    /**
     * Handler method for the UpdateVariable message.
     * This message is sent by the coordinator to all replicas to update the variable
     * A replica should acknowledge the update by sending an AcknowledgeUpdate message to the coordinator
     * @param update the update message containing the new value
     */
    private void onUpdateVariable(UpdateVariable update) {
        if (this.afterForwardTimeout.size() > 0) {
            log("canceling afterForwardTimeout because received update from coordinator");
            this.afterForwardTimeout.get(0).cancel();
            this.afterForwardTimeout.remove(0);
        }
        log("Received update " + update.messageIdentifier + " with value: "+ update.value+  " from the coordinator " + coordinatorRef.path().name());

        if (this.crash_type == Crash.REPLICA_ON_UPDATE_MESSAGE) {
            crash();
            return;
        }

        this.changeQueue(update.messageIdentifier, new Data(update.value, this.peers.size(), update.replicaId));
        AcknowledgeUpdate ack = new AcknowledgeUpdate(update.messageIdentifier, this.id);
        this.tellWithDelay(coordinatorRef, getSelf(), ack);

        this.afterUpdateTimeout.add(
            this.timeoutScheduler(
                afterUpdateTimeoutDuration,
                new StartElectionMessage("didn't receive writeOK message from coordinator for message " + update.messageIdentifier + " value: " + update.value)
            ));

    }

    /**
     * Handler method for the AcknowledgeUpdate message.
     * This message is sent by the replicas to acknowledge the update
     * The coordinator waits for at least quorumSize acks before sending the WriteOK message
     * @param ack the acknowledge update message
     */
    private void onAcknowledgeUpdate(AcknowledgeUpdate ack) {
        if (!this.temporaryBuffer.containsKey(ack.messageIdentifier)) {
            log("slow ack from replica_" + ack.senderId + ", " + ack.messageIdentifier + " has been already confirmed");
            return;
        }
        log("Received ack from replica_" + ack.senderId + " for message " + ack.messageIdentifier);
        // Step 2 of 2 phase broadcast protocol
        this.temporaryBuffer.get(ack.messageIdentifier).ackBuffers.add(ack.senderId);
        boolean reachedQuorum = this.temporaryBuffer.get(ack.messageIdentifier).ackBuffers.size() >= quorumSize;
        if (reachedQuorum) {
            // Send WriteOK to the other replicas
            log("Reached quorum for message " + ack.messageIdentifier);
            if (this.crash_type == Crash.COORDINATOR_BEFORE_WRITEOK_MESSAGE && this.coordinatorRef.equals(getSelf())) {
                crash();
                return;
            }
            WriteOK confirmDelivery = new WriteOK(ack.messageIdentifier);
            multicast(confirmDelivery);
            this.nWriteOk++;
            if (this.nWriteOk == N_WRITE_OK && this.crash_type == Crash.COORDINATOR_AFTER_N_WRITE_OK
                    && this.coordinatorRef.equals(getSelf())) {
                this.crash();
                return;
            }

            // Deliver the message
            this.deliverUpdate(ack.messageIdentifier);
        }
    }

    /**
     * Handler method for the WriteOK message.
     * This message is sent by the coordinator to confirm the delivery of an update
     * The replica can now deliver the update
     * @param confirmMessage the writeOK message
     */
    private void onWriteOK(WriteOK confirmMessage) {
        log("Received writeOK from, the size of writeOK timeout is: " + this.afterUpdateTimeout.size());
        if (!this.afterUpdateTimeout.isEmpty()) {
            log("canceling afterUpdateTimeout because received confirm from coordinator");
            this.afterUpdateTimeout.get(0).cancel();
            this.afterUpdateTimeout.remove(0);
        }

        if (this.crash_type == Crash.NO_WRITE && this.history.size() >= 1) {
            log("I'm not writing the value (testing)");
            return;
        }
        log("Received confirm to deliver " + confirmMessage.messageIdentifier.toString() + " from the coordinator");
        this.deliverUpdate(confirmMessage.messageIdentifier);
    }


    // ----------------------- ELECTION HANDLERS -----------------------

    /**
     * Method used to start an election
     * @param startElectionMessage the start election message, used just as a trigger
     */
    private void startElection(StartElectionMessage startElectionMessage) {
        getContext().become(inElection()); // Switch to the inElection behavior
        this.cancelAllTimeouts(); // Cancel all the timeouts
        log("Starting election, reason: " + startElectionMessage.reason);
        ElectionMessage electionMessage = new ElectionMessage(id, this.getLastUpdate().getMessageIdentifier(), this.temporaryBuffer);
        this.coordinatorRef = null;
        this.electionTimeout = this.timeoutScheduler(electionTimeoutDuration, new StartElectionMessage("Global election timer expired"));
        this.forwardElectionMessageWithoutAck(electionMessage); // I don't ack the previous replica, since I'm the one starting the election
    }

    /**
     * Method used to handle ElectionMessage when the replica is NOT in the inElection state.
     * This method switches the behavior to inElection
     * @param electionMessage the election message
     */
    private void onFirstElectionMessage(ElectionMessage electionMessage) {
        getContext().become(inElection()); // Switch to the inElection behavior
        log("Received first election message from " + getSender().path().name() + " electionMessage: " + electionMessage.toString());
        this.cancelAllTimeouts();
        this.coordinatorRef = null;
        
        // Add myself in the state and forward the message, acking the sender
        electionMessage = electionMessage.addState(this.id, this.getLastUpdate().getMessageIdentifier(), this.temporaryBuffer);
        this.forwardElectionMessageWithAck(electionMessage);

        // Create the gloabl election timeout
        this.electionTimeout = this.timeoutScheduler(electionTimeoutDuration, new StartElectionMessage("Global timer expired"));
    }

    /**
     * Method used to handle the ElectionMessage.
     * Represents the main logic of the election process
     * @param electionMessage the election message, containing the state of the replicas in the quorum
     */
    private void onElectionMessage(ElectionMessage electionMessage) {
        log("Received election message from " + getSender().path().name() + " electionMessage: " + electionMessage.toString());

        if (crash_type == Crash.REPLICA_ON_ELECTION_MESSAGE) {
            crash();
            return;
        }

        // If the received message contains my id, it is the second round for me, so I need to check whether I would become the coordinator or not.
        // If not, I forward the message that will eventually reach the new coordinator
        if (electionMessage.quorumState.containsKey(id)) {
            boolean won = haveWonTheElection(electionMessage);
            if (won) {
                // Here we know that we are the most updated replica, so i become the new coordinator
                this.lastUpdate = this.lastUpdate.incrementEpoch();
                int oldSize = this.temporaryBuffer.size();
                if (electionMessage.pendingUpdates.size() != oldSize){
                    log("Missing pending updates: " + this.temporaryBuffer.toString() + " " +
                            electionMessage.pendingUpdates.toString());
                }
                // Insert the pending updates from the electionMessage to the temporaryBuffer if needed
                log("Initial write message queue: " + this.writeRequestMessageQueue.toString());
                for (PendingUpdate update : electionMessage.pendingUpdates) {
                    //if an update is contained in the history or in the temporaryBuffer, we cant skip it
                    if (this.history.contains(new Update(update.messageIdentifier, update.data.value)) || this.temporaryBuffer.containsKey(update.messageIdentifier)) {
                        continue;
                    }

                    this.changeQueue(update.messageIdentifier, new Data(update.data.value, this.peers.size(), update.data.replicaId));
                }

                if (electionMessage.pendingUpdates.size() != oldSize){
                    log("Adjusted Pending updates: TB: " + this.temporaryBuffer.toString() + " PU: " + electionMessage.pendingUpdates.toString());
                }
                
                // Change the message identifier of the pending message, this leader will handle it
                // List<MessageIdentifier> buffer = this.temporaryBuffer.keySet().stream().collect(Collectors.toList());
                // buffer.sort((o1, o2) -> o1.compareTo(o2));
                // for (MessageIdentifier key : buffer) {
                //     Data data = this.temporaryBuffer.get(key);
                //     this.temporaryBuffer.put(this.lastUpdate, data);
                //     this.temporaryBuffer.remove(key);
                //     this.temporaryBuffer.get(this.lastUpdate).ackBuffers.add(id);//ack to this message
                //     this.lastUpdate = this.lastUpdate.incrementSequenceNumber();
                // }

                // Handle the previous epoch before starting mine
                for (MessageIdentifier key : this.temporaryBuffer.keySet()) {
                    this.temporaryBuffer.get(key).ackBuffers.clear();
                    this.temporaryBuffer.get(key).ackBuffers.add(id);
                }

                // Send the synchronization message to all replicas; this message contains the updates and the pending updates
                log("multicasting sychronization, i won this election" + electionMessage.toString());
                this.sendSynchronizationMessage(electionMessage.quorumState, electionMessage.pendingUpdates);
                
                // Send the ack to the previous replica
                this.tellWithDelay(getSender(), getSelf(), new AckElectionMessage());
                this.coordinatorRef = getSelf(); // Set myself as the coordinator
                getContext().become(createReceive()); // Switch back to the normal behavior
                
                // Cancel the global election timeout
                if (this.electionTimeout != null) {
                    this.electionTimeout.cancel();
                }
                
                for (WriteRequest writeRequest : this.writeRequestMessageQueue) {
                    if (this.crash_type == Crash.COORDINATOR_BEFORE_UPDATE_MESSAGE && this.coordinatorRef.equals(getSelf())) {
                        crash();
                        return;
                    }
                    this.emptyCoordinatorQueue(writeRequest.value); // Send all the write requests that were stored in the queue during the election
                }
                this.writeRequestMessageQueue.clear();
                this.tellWithDelay(getSelf(), getSelf(), new SendHeartbeatMessage()); // Start the heartbeat mechanism
                
            } else {
                // Before forwarding the message, I need to check if the future coordinator is still alive
                List<Integer> peersId = this.peers.stream().map(ar -> Integer.parseInt(ar.path().name().split("_")[1])).collect(Collectors.toList());

                int max_id = getWinnerId(electionMessage);
                if (!peersId.contains(max_id)) {
                    log("Replica_" + max_id + " is crashed, but a message for it is still circulating");
                    this.tellWithDelay(getSender(), getSelf(), new AckElectionMessage());
                    return;
                }
                
                electionMessage = new ElectionMessage(electionMessage.quorumState, electionMessage.pendingUpdates);
                this.forwardElectionMessageWithAck(electionMessage);
            }

        } else {
            // The received message does not contain my id, it means it's the first time I see this message
            // but, since I'm in election state, I know that there is a message originated by me, that is already circulating.
            // The idea here, is to keep forwarding only the messages that contain replicas which are more up to date than me (or with an higher id than mine).
            boolean won = haveWonTheElection(electionMessage);
            if (won) {
                log("Not forwarding because can't win, waiting for my message to do the second round and acking the sender "
                        + getSender().path().name() + " quorum state:" + electionMessage.quorumState.toString());
                this.tellWithDelay(getSender(), getSelf(), new AckElectionMessage());
                if (crash_type == Crash.REPLICA_AFTER_ACK_ELECTION_MESSAGE) { // 2 node failure crash
                    crash();
                    return;
                }
            } else {
                // I would lose the election, so I add my state to the message and forward it to the next replica
                electionMessage = electionMessage.addState(id, this.getLastUpdate().getMessageIdentifier(), this.temporaryBuffer);
                this.forwardElectionMessageWithAck(electionMessage);
            }
        }
    }

    /**
     * Handler method for the AckElectionMessage.
     * Once a replica receives an ack, it cancels the timeout associated with the ack
     * @param ackElectionMessage the ack election message
     */
    private void onAckElectionMessage(AckElectionMessage ackElectionMessage) {
        log("Received election ack from " + getSender().path().name());
        if (this.acksElectionTimeout.size() > 0) {
            Cancellable toCancel = this.acksElectionTimeout.get(0);
            toCancel.cancel();
            this.acksElectionTimeout.remove(0);
        }
    }

    /**
     * Handler for the SynchronizationMessage.
     * This message is sent by the new coordinator to all replicas to notify them the end of the election
     * and the new coordinator.
     * It cancels the election timeout and starts the heartbeat timer.
     * Moreover, the SYNCH message contains all the missing Update, plus the missing pending updates.
     * @param synchronizationMessage the synchronization message
     */
    private void onSynchronizationMessage(SynchronizationMessage synchronizationMessage) {
        getContext().become(createReceive()); // Election is finished, so I switch back to the normal behavior
        this.coordinatorRef = synchronizationMessage.getCoordinatorRef();
        log("Received synchronization message from " + coordinatorRef.path().name());
        this.lastUpdate = this.lastUpdate.incrementEpoch();
        // Election is finished, so I cancel the election timeout
        if (this.electionTimeout != null) {
            this.electionTimeout.cancel();
        }

        if (this.heartbeatTimeout != null) {
            this.heartbeatTimeout.cancel();
        }

        this.heartbeatTimeout = timeoutScheduler(coordinatorHeartbeatTimeoutDuration, new CoordinatorCrashedMessage());
        this.updateHistory(synchronizationMessage.getUpdates(), synchronizationMessage.getPendingUpdates());
        this.emptyQueue();
    }

    /**
     * Method to handle the CoordinatorCrashedMessage.
     * This message is sent by the replica to itself when the coordinator crashes.
     * It removes the coordinator from the peers list and starts a new election
     * @param message the coordinator crashed message
     */
    private void onCoordinatorCrashed(CoordinatorCrashedMessage message) {
        // Remove crashed replica from the peers list
        this.removePeer(coordinatorRef);
        StartElectionMessage startElectionMessage = new StartElectionMessage("Didn't receive heartbeat from coordinator");
        this.startElection(startElectionMessage);
    }

    /**
     * Handler method for the CrashedNextReplicaMessage.
     * When the next replica doesn't send an ack, the current replica sends the election message to the next replica
     * It removes the next replica from the peers list and cancels all the acks related to the next replica
     * @param message the crashed next replica message
     */
    private void onNextReplicaCrashed(CrashedNextReplicaMessage message) {
        log("Didn't receive ACK, sending election message to the next replica");
        // Remove nextRef from the peers list and cancel all the acks related to nextRef
        this.removePeer(message.nextRef);
        
        for (Cancellable timer : this.acksElectionTimeout) {
            timer.cancel();
        }
        this.acksElectionTimeout.clear();
        // No need to ack again, since I'm not crashed and I have already sent the ack to the previous replica
        this.forwardElectionMessageWithoutAck(message.electionMessage);
    }

    /**
     * Start the heartbeat mechanism, the coordinator sends a heartbeat message to
     * all replicas every 1 second.
     * @param message the send heartbeat message
     */
    private void onSendHeartbeat(SendHeartbeatMessage message) {
        multicast(new ReceiveHeartbeatMessage());

        if (this.crash_type == Crash.COORDINATOR_AFTER_HEARTBEAT && this.coordinatorRef.equals(getSelf())) {
            crash();
            return;
        }

        this.sendHeartbeat = timeoutScheduler(coordinatorHeartbeatFrequency, new SendHeartbeatMessage());
    }

    /**
     * Method handling the heartbeat message.
     * When a replica receives a heartbeat message from the coordinator, it reset the heartbeat timeout.
     * @param heartbeatMessage the receive heartbeat message
     */
    private void onReceiveHeartbeatMessage(ReceiveHeartbeatMessage heartbeatMessage) {
        String message = "Received HB from coordinator " + getSender().path().name()
                + " coordinator is " + this.coordinatorRef.path().name();

        log(message);
        
        if (this.heartbeatTimeout != null) {
            this.heartbeatTimeout.cancel();
        }

        this.heartbeatTimeout = timeoutScheduler(coordinatorHeartbeatTimeoutDuration, new CoordinatorCrashedMessage());
    }

    /**
     * Method used to create a Cancellable object for the ackElection timeout
     * @param electionMessage the election message, that could be sent to the next next replica in case of timeout
     * @param nextRef the next replica used to refer to it in case of timeout to eliminate it from the peers list
     * @return the Cancellable object
     */
    private Cancellable scheduleElectionTimeout(final ElectionMessage electionMessage, final ActorRef nextRef) {
        log("creating election timeout for " + nextRef.path().name());
        Cancellable temp = timeoutScheduler(ackElectionMessageDuration, new CrashedNextReplicaMessage(electionMessage, nextRef));
        return temp;
    }

    /**
     * Method used by a replica to update the history with the potential missing updates.
     * It also updates the temporary buffer with the pending updates.
     * Furthermore, it sends an ack to the coordinator for each pending updates.
     * @param updates the potential missing updates
     * @param pendingUpdates the potential missing pending updates
     */
    private void updateHistory(List<Update> updates, Set<PendingUpdate> pendingUpdates) {
        int oldSize = this.temporaryBuffer.size();
        if (pendingUpdates.size() != oldSize){
            log("Missing pending updates: " + this.temporaryBuffer.toString() + " "
                    + pendingUpdates.toString());
        }
        log("Initial write message queue: " + this.writeRequestMessageQueue.toString());
        for (PendingUpdate pu : pendingUpdates) {
            //if an update is contained in the history or in the temporaryBuffer, we cant skip it
            if (this.history.contains(new Update(pu.messageIdentifier, pu.data.value))
                    || this.temporaryBuffer.containsKey(pu.messageIdentifier)) {
                continue;
            }
            // Otherwise we add it to the temporaryBuffer and check if it comes fom our message queue (partial update: some replicas rerceived the update from this message queue)
            this.changeQueue(pu.messageIdentifier, new Data(pu.data.value, this.peers.size(), pu.data.replicaId));
        }
        if (pendingUpdates.size() != oldSize){
            log("Adjusted Pending updates: " + this.temporaryBuffer.toString() + " "
                    + pendingUpdates.toString());
        }
       
        
        log("my history" + this.history.toString() + "\nReceived update history message from "
                + getSender().path().name() + " " + updates.toString());
        for (Update update : updates) {
            if (this.temporaryBuffer.containsKey(update.getMessageIdentifier())) {
                this.deliverUpdate(update.getMessageIdentifier());
            } 
        }

        // Ack the messages that are still pending in the buffer
        List<MessageIdentifier> buffer = this.temporaryBuffer.keySet().stream().collect(Collectors.toList());
        buffer.sort((o1, o2) -> o1.compareTo(o2));
        
        // Handle the previous epoch before starting mine
        for (MessageIdentifier key : buffer) {
            Data data = this.temporaryBuffer.get(key);
            // this.temporaryBuffer.put(lastUpdate, data);
            // this.temporaryBuffer.remove(key);
            AcknowledgeUpdate ack = new AcknowledgeUpdate(key, this.id);
            this.tellWithDelay(coordinatorRef, getSelf(), ack);

            this.afterUpdateTimeout.add(
                    this.timeoutScheduler(
                            afterUpdateTimeoutDuration,
                            new StartElectionMessage("didn't receive writeOK message from coordinator for message "
                                    + lastUpdate + " value: " + data.value)));
            // lastUpdate = lastUpdate.incrementSequenceNumber();
        }
    }

    // --------------------- UTILITY FUNCTION ---------------------

    /**
     * Return the last update of the replica
     * @return the last update
     */
    private Update getLastUpdate() {
        if (history.size() == 0) {
            return new Update(new MessageIdentifier(0, -1), -1);
        }

        return history.get(history.size() - 1);
    }

    /**
     * This method is called after the coordinator sent the writeOK message.
     * The replica can now deliver the update.
     * @param messageIdentifier the message identifier of the update
     */
    private void deliverUpdate(MessageIdentifier messageIdentifier) {
        this.replicaVariable = this.temporaryBuffer.get(messageIdentifier).value;
        this.temporaryBuffer.remove(messageIdentifier);
        history.add(new Update(messageIdentifier, this.replicaVariable));
        log(this.getLastUpdate().toString());
        standardLog(this.getLastUpdate().toString());
    }

    /**
     * Utility method used to send a message to all replicas except the one that sent the message
     * @param message the message to send
     */
    private void multicast(Serializable message) {
        try {
            Thread.sleep(rnd.nextInt(messageMaxDelay));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        for (int i = 0; i < this.peers.size(); i++) {
            // Coordinator will send only one update message and crash
            if (i == 1 && message.getClass() == UpdateVariable.class && this.crash_type == Crash.COORDINATOR_CRASH_MULTICASTING_UPDATE) {
                crash();
                return;
            }

            if (!this.peers.get(i).equals(getSelf())) {
                this.peers.get(i).tell(message, getSelf());
            }
        }
    }

    /**
     * Utility method used to remove a peer from the peers list
     * @param peer the peer to remove
     */
    private void removePeer(ActorRef peer) {
        boolean removed = this.peers.remove(peer);
        if (!removed) {
            log("Peer " + peer.path().name() + " already removed");
            return;
        }

        int myIndex = this.peers.indexOf(getSelf());
        this.nextRef = this.peers.get((myIndex + 1) % this.peers.size());
        log("Removed peer " + peer.path().name() + " my new next replica is " + this.nextRef.path().name());
    }

    /**
     * Wrapper around the forwardElectionMessage method.
     * This method sends the election message to the next replica and sends an ack to the previous replica
     * @param electionMessage the election message
     * @param oldAckIdentifier the ack identifier of the previous replica
     */
    private void forwardElectionMessageWithAck(ElectionMessage electionMessage) {
        forwardElectionMessage(electionMessage, true);
    }

    /**
     * Wrapper around the forwardElectionMessage method.
     * This method sends the election message to the next replica without sending an ack to the previous replica
     * @param electionMessage the election message
     */
    private void forwardElectionMessageWithoutAck(ElectionMessage electionMessage) {
        forwardElectionMessage(electionMessage, false);
    }

    /**
     * Method used to forward the election message to the next replica
     * @param electionMessage the election message
     * @param oldAckIdentifier the ack identifier of the previous replica
     */
    private void forwardElectionMessage(ElectionMessage electionMessage, boolean toAck) {

        if (this.crash_type == Crash.REPLICA_BEFORE_FORWARD_ELECTION_MESSAGE) {
            crash();
            return;
        }

        this.tellWithDelay(this.nextRef, getSelf(), electionMessage);
        log("Sent election message to " + this.nextRef.path().name() + " : " + electionMessage.toString());

        if (toAck) {
            log("Sent ACK to previous " + getSender().path().name());
            this.tellWithDelay(getSender(), getSelf(), new AckElectionMessage());
        }
        Cancellable ackElectionTimeout = scheduleElectionTimeout(electionMessage, this.nextRef);
        this.acksElectionTimeout.add(ackElectionTimeout);
    }
    
    /**
     * Check if the replica has won/might the election.
     * @param electionMessage
     * @return true if the replica has won the election, false otherwise
     */
    private boolean haveWonTheElection(ElectionMessage electionMessage) {
        // I need to check if I have the most recent update and the highest id
        MessageIdentifier maxUpdate = Collections.max(electionMessage.quorumState.values());
        MessageIdentifier lastUpdate = this.getLastUpdate().getMessageIdentifier();
        int amIMoreUpdated = lastUpdate.compareTo(maxUpdate);

        // If Im not the most updated replica, I forward the election message
        if (amIMoreUpdated < 0) {
            // I would lose the election, so I forward to the next replica
            return false;
        } else if(amIMoreUpdated > 0){ // This case happens only when the replica is not in the quorum state
            return true;
        } else {
            // The updates are equal, so I check the id
            ArrayList<Integer> ids = new ArrayList<>();
            electionMessage.quorumState.forEach((k, v) -> {
                if (maxUpdate.compareTo(v) == 0) {
                    ids.add(k);
                }
            });
            int maxId = Collections.max(ids);
            return maxId <= this.id;// == if I'm in the quorum state, the < is needed when i have to add myself
        }      
    }

    /**
     * Method used to get the winner id of the election.
     * This method is used when a replica receives an election message and knows that it has lost the election.
     * The replica needs to know the id of the replica that has won the election, to see if it is still alive.
     * @param electionMessage the election message
     * @return the id of the winner of the election
     */
    private int getWinnerId(ElectionMessage electionMessage) {
        MessageIdentifier maxUpdate = Collections.max(electionMessage.quorumState.values());

        int max_id = -1;
        for (var entry : electionMessage.quorumState.entrySet()) {
                if ( entry.getValue().compareTo(maxUpdate) == 0) {
                    if (entry.getKey() > max_id) {
                        max_id = entry.getKey();
                    }
                }
            }

        return max_id;
    }

    /**
     * Generic method to schedule a timeout
     * @param ms the duration of the timeout
     * @param message the message to send if the timer expires
     * @return the Cancellable object
     */
    private Cancellable timeoutScheduler(int ms, Serializable message) {
        return getContext()
                .getSystem()
                .scheduler()
                .scheduleOnce(
                        Duration.ofMillis(ms),
                        getSelf(),
                        message,
                        getContext().getSystem().dispatcher(),
                        getSelf());
    }

    /**
     * Method used to crash the replica
     * It cancels all the timeouts and switches the behavior to the crashed state
     */
    private void crash() {
        this.cancelAllTimeouts();
        
        log("i'm crashing " + id);
        getContext().become(crashed());
    }

    /**
     * Method used to cancel all the timeouts
     */
    private void cancelAllTimeouts() {
        // Replica timer to receive the heartbeat from the coordinator
        if (this.heartbeatTimeout != null) {
            this.heartbeatTimeout.cancel();
        }
        
        // if it is a coordinator cancel the heartbeat
        if (this.sendHeartbeat != null && this.coordinatorRef.equals(getSelf())) {
            this.sendHeartbeat.cancel();
        }

        // Global election timer
        if (this.electionTimeout != null) {
            this.electionTimeout.cancel();
        }

        // election's Ack timer
        for (Cancellable timer : this.acksElectionTimeout) {
            timer.cancel();
        }
        this.acksElectionTimeout.clear();

        for (Cancellable timer : this.afterForwardTimeout) {
            timer.cancel();
        }
        this.afterForwardTimeout.clear();

        for (Cancellable timer : this.afterUpdateTimeout) {
            timer.cancel();
        }
        this.afterUpdateTimeout.clear();
    }
    
    /**
     * Wrapper method around the tell method that adds a delay to the message
     * @param receiver the actor that will receive the message
     * @param sender the actor that sends the message
     * @param message the message to send
     */
    private void tellWithDelay(ActorRef receiver, ActorRef sender, Serializable message) {
        try {
            Thread.sleep(rnd.nextInt(messageMaxDelay));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        receiver.tell(message, sender);
    }

    /**
     * Method to print custom log to the console
     * @param message the message to log
     */
    private void log(String message) {

        String msg = getSelf().path().name() + ": " + message;
        try {
            System.out.println(msg);
            if (msg.contains("\u001B[0m")){
                msg = msg.replace("\u001B[0m", "");
                msg = msg.replace("\u001B[32m","");
            }
            this.writer.write(msg + System.lineSeparator());
            this.writer.flush();    
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * This method is used to log messages in a standard format (as specified in the requirements document)
     * @param message the message to log
     */
    private void standardLog(String message) {
        String[] actorName = getSelf().path().name().split("_");
        String msg = actorName[0] + " " + actorName[1] + " " + message;
        try {
            System.out.println(msg);
            if (msg.contains("\u001B[0m")){
                msg = msg.replace("\u001B[0m", "");
                msg = msg.replace("\u001B[32m","");
            }
            this.writer.write(msg + System.lineSeparator());
            this.writer.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Empty the queue of write requests if not empty
     */
    private void emptyQueue() {
        log("Emptying the queue" + this.writeRequestMessageQueue.toString());
        if (!this.writeRequestMessageQueue.isEmpty()) {
            for (WriteRequest writeRequest : this.writeRequestMessageQueue) {
                this.tellWithDelay(this.coordinatorRef, getSelf(), writeRequest);

                this.afterForwardTimeout.add(
                        this.timeoutScheduler(afterForwardTimeoutDuration, new StartElectionMessage(
                                "forwarded message of " + getSender().path().name() + " with value: " + writeRequest.value
                                        + ", but didn't receive update from the coordinator")));
            }
        }

        if (this.crash_type == Crash.REPLICA_AFTER_FORWARD_MESSAGE) {
            crash();
            return;
        }
    }
    

    /**
     * Method used to empty the coordinator queue of write requests,by sending update messages to all replicas.
     * @param value the value of the write request
     */
    private void emptyCoordinatorQueue(int value) {
        log("Emptying the coordinator queue by starting 2 phase broadcast protocol, by sending an UPDATE message");
        
        // Step 1 of 2 phase broadcast protocol
        UpdateVariable update = new UpdateVariable(this.lastUpdate, value, this.id);
        multicast(update);

        this.temporaryBuffer.put(this.lastUpdate, new Data(value, this.peers.size(), this.id));
        this.temporaryBuffer.get(this.lastUpdate).ackBuffers.add(this.id);
        log("acknowledged message id " + this.lastUpdate.toString() + " value: " + value);

        this.lastUpdate = this.lastUpdate.incrementSequenceNumber();
        // The coordinator crashes after sending the update message
        if (this.crash_type == Crash.COORDINATOR_AFTER_UPDATE_MESSAGE && this.coordinatorRef.equals(getSelf())) {
            crash();
            return;
        }
    }


    /**
     * Method used to send the Synchronization Message to each replica.
     * It also handle the update of outdated replicas with the missing updates.
     * The coordinator sends the missing updates to the replicas.
     * @param quorumState the state of the replicas
     * @param pendingUpdates the pending updates, that are not yet delivered
     */
    private void sendSynchronizationMessage(Map<Integer, MessageIdentifier> quorumState, Set<PendingUpdate> pendingUpdates) {
        // Not multicasting because each replica may have different updates
        for (var entry : quorumState.entrySet()) { // For each replica send its missing updates
            if (entry.getKey() == this.id) { // Skip myself
                continue;
            }

            // Take the updates that the replica is missing
            MessageIdentifier replicaLastUpdate = entry.getValue();
            List<Update> listOfUpdates = this.history.stream()
                    .filter(update -> update.getMessageIdentifier().compareTo(replicaLastUpdate) > 0)
                .collect(Collectors.toList());
            
            // Send the missing updates (and pending updates) to the replica
            ActorRef replica = getReplicaActorRefById(entry.getKey());
            log(listOfUpdates.toString() + "Sending updates to " + replica.path().name());
            if (replica != null) {
                this.tellWithDelay(replica, getSelf(), new SynchronizationMessage(this.id, getSelf(), listOfUpdates, pendingUpdates));
            }
        }
    }

    /**
     * Method used to move messages from the write request queue to the temporary buffer.
     * @param messageIdentifier the message identifier of the update
     * @param data the data of the update
     */
    private void changeQueue(MessageIdentifier messageIdentifier, Data data) {
        if (this.writeRequestMessageQueue.size() > 0 && data.replicaId == this.id) {
            WriteRequest w = this.writeRequestMessageQueue.remove(0);
            log("Removing write request from queue: " + w.toString() + " " + data.toString());
        }
        this.temporaryBuffer.put(messageIdentifier, new Data(data.value, this.peers.size(), data.replicaId));

    }

    /**
     * Method used to get the ActorRef of a replica by its id
     * @param id the id of the replica
     * @return the ActorRef of the replica
     */
    private ActorRef getReplicaActorRefById(int id) {
        for (ActorRef peer : this.peers) {
            if (peer.path().name().equals("replica_" + id)) {
                return peer;
            }
        }
        return null;
    }
    // --------------------------- END ----------------------------
}
