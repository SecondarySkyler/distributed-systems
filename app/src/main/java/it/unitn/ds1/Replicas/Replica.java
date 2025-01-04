package it.unitn.ds1.Replicas;

import it.unitn.ds1.MessageIdentifier;
import it.unitn.ds1.Messages.ReadRequest;
import it.unitn.ds1.Messages.ReadResponse;
import it.unitn.ds1.Messages.WriteRequest;
import it.unitn.ds1.Replicas.messages.AckElectionMessage;
import it.unitn.ds1.Replicas.messages.WriteOK;
import it.unitn.ds1.Replicas.messages.AcknowledgeUpdate;
import it.unitn.ds1.Replicas.messages.CoordinatorCrashedMessage;
import it.unitn.ds1.Replicas.messages.CrashedNextReplicaMessage;
import it.unitn.ds1.Replicas.messages.ElectionMessage;
import it.unitn.ds1.Replicas.messages.EmptyReplicaWriteMessageQueue;
import it.unitn.ds1.Replicas.messages.ReceiveHeartbeatMessage;
import it.unitn.ds1.Replicas.messages.StartElectionMessage;
import it.unitn.ds1.Replicas.messages.PrintHistory;
import it.unitn.ds1.Replicas.messages.SendHeartbeatMessage;
import it.unitn.ds1.Replicas.messages.SynchronizationMessage;
import it.unitn.ds1.Replicas.messages.UpdateHistoryMessage;
import it.unitn.ds1.Replicas.messages.UpdateVariable;
import it.unitn.ds1.Messages.GroupInfo;
import it.unitn.ds1.Replicas.types.Crash;

import it.unitn.ds1.Replicas.types.Data;
import it.unitn.ds1.Replicas.types.Update;
import scala.util.Random;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.Map;
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

    // recurrent timers
    private static final int coordinatorHeartbeatFrequency = 1000;// Frequency at which the coordinator sends heartbeat messages to other nodes

    // Timeout duration for initiating an new election
    private static int electionTimeoutDuration;// if during the leader election, the replica doesn't receive any synchronization message
    private static final int ackElectionMessageDuration = 1000;// if the replica doesn't receive an ack from the next replica
    private static final int afterForwardTimeoutDuration = 1000;// if the replica doesn't receive an update message after forward it to the coordinator(waiting update mes)
    private static final int afterUpdateTimeoutDuration = 3000;// if the replica doesn't receive  confirm update message from the coordinator(waiting writeOK mes)
    private static final int coordinatorHeartbeatTimeoutDuration = 5000; //if the replica doesn't receive a heartbeat from the coordinator

    private static final int messageMaxDelay = 150;
    static Random rnd = new Random();

    private int id;
    private int replicaVariable;
    private List<ActorRef> peers = new ArrayList<>();
    @SuppressWarnings("unused")
    private boolean isCrashed = false;
    private ActorRef nextRef = null;
    private List<WriteRequest> writeRequestMessageQueue = new ArrayList<>(); //message that i have to send to the coordinator

    private MessageIdentifier lastUpdate = new MessageIdentifier(-1, 0);

    @SuppressWarnings("unused")
    private boolean isElectionRunning = false;
    private boolean coordinatorIsEmptyingQueue = false;
    private ActorRef coordinatorRef;

    private Cancellable heartbeatTimeout; // replica timeout for coordinator heartbeat
    private Cancellable sendHeartbeat; // coordinator sends heartbeat to replicas
    private Cancellable electionTimeout; // election timeout for the next replica
    private List<Cancellable> afterForwardTimeout = new ArrayList<>(); // after forward to the coordinator
    private List<Cancellable> afterUpdateTimeout = new ArrayList<>();
    private HashMap<UUID, Cancellable> acksElectionTimeout = new HashMap<>(); // this contains all the timeouts that are
                                                                              // waiting to receive an ack

    private int quorumSize;
    private HashMap<MessageIdentifier, Data> temporaryBuffer = new HashMap<>();
    private List<Update> history = new ArrayList<>();
    private final BufferedWriter writer;


    //crash flag
    private Crash crash_type = Crash.NO_CRASH;


    // USED TO TEST THE CRASH
    @SuppressWarnings("unused")
    private int heartbeatCounter = 0;
    @SuppressWarnings("unused")
    private int maxCrash = 1;
    @SuppressWarnings("unused")
    private int totalCrash = 0;
    private int nWriteOk = 0;
    private static final int N_WRITE_OK = 3;

    // -------------------------- REPLICA ---------------------------
    public Replica(int id, String logFolderName,Crash crash_type) throws IOException {
        this.replicaVariable = -1;
        this.id = id;
        // this.history.add(new Update(new MessageIdentifier(0, 0),
        // this.replicaVariable));
        String directoryPath = logFolderName;
        String filePath = directoryPath + File.separator + getSelf().path().name() + ".txt";
        this.crash_type = crash_type;
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
                .match(ElectionMessage.class, this::onFirstElectionMessage) // This will trigger the behavior change to inElection
                .match(ReceiveHeartbeatMessage.class, this::onReceiveHeartbeatMessage)
                .match(AckElectionMessage.class, this::onAckElectionMessage) // To keep because the coordinator multicast the synchronization message, which trigger the normal state, and then send the ack, which will be received by the previous replica in the normal state and not the election state
                .match(PrintHistory.class, this::onPrintHistory)
                .match(StartElectionMessage.class, this::startElection)
                .match(CoordinatorCrashedMessage.class, this::onCoordinatorCrashed)
                .match(SendHeartbeatMessage.class, this::onSendHeartbeat)
                .match(UpdateHistoryMessage.class, this::onUpdateHistory)
                .match(EmptyReplicaWriteMessageQueue.class, this::emptyReplicaQueue)
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
        return Props.create(Replica.class, () -> new Replica(id, logFolderName,crash_type));
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
        this.quorumSize = (int) Math.floor(peers.size() / 2) + 1;
        this.nextRef = peers.get((peers.indexOf(getSelf()) + 1) % peers.size());
        Replica.electionTimeoutDuration = peers.size() * Replica.ackElectionMessageDuration;
        
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
        tellWithDelay(getSender(), getSelf(), new ReadResponse(replicaVariable));
    }

    private void onPrintHistory(PrintHistory printHistory) {
        String historyMessage = "\n#################HISTORY########################\n";
        for (Update update : history) {
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
        log(reasonMessage + ", adding the write request to the queue");
        writeRequestMessageQueue.add(request);
    }
    
    /**
     * Handler method for the WriteRequest message.
     * This message is sent by the client to write a new value to the variable
     * @param request the write request message, containing the new value
     */
    private void onWriteRequest(WriteRequest request) {
        // This is needed in the case in which a client is able to send (almost) immediately a write request to the replica
        // while replicas are still in the default behavior (createReceive) even before starting the first election
        if (this.coordinatorRef == null || this.coordinatorIsEmptyingQueue) {
            String reasonMessage = this.coordinatorRef == null ? "coordinator is null": "coordinator is emptying the queue";
            log(reasonMessage + ", adding the write request to the queue");
            writeRequestMessageQueue.add(request);
            return;
        }

        // If we are the coordinator, we start the 2 phase broadcast protocol
        if (getSelf().equals(coordinatorRef)) {
            log("Received write request from, " + getSender().path().name() + " with value: " + request.value + " starting 2 phase broadcast protocol, by sending an UPDATE message");
            if (this.crash_type == Crash.COORDINATOR_BEFORE_UPDATE_MESSAGE && this.coordinatorRef.equals(getSelf())) {
                crash();
                return;
            }
            // step 1 of 2 phase broadcast protocol
            //if the sender is a replica, then the senderReplicaId is the id of the sender, otherwise it is the id of the current (coordinator) replica
            int senderReplicaId = getSender().path().name().contains("replica") ? Integer.parseInt(getSender().path().name().split("_")[1]) : this.id;
            log("sender id is: "+ senderReplicaId);
            UpdateVariable update = new UpdateVariable(this.lastUpdate, request.value, senderReplicaId);
            multicast(update);
            if (senderReplicaId == this.id && this.writeRequestMessageQueue.size() > 0) {//the > 0 is needed because, the normal write reqeust is not added to the queue, but directly written, so i dont have to remove anything, while when a coordinator crash, my quee may still be non empty and i have to remove the message that i have forwarded
                this.writeRequestMessageQueue.remove(0); //remove the message from the queue I HAVE TO TENERE IN CONSIDERAZIONE ANCHE QUELLI CHE STANNO NEL BUFFER; VISTO CHE VANNO IN TESTA A QEUSTI
            }
            temporaryBuffer.put(this.lastUpdate, new Data(request.value, this.peers.size()));
            temporaryBuffer.get(this.lastUpdate).ackBuffers.add(id);
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
        }
    }

    /**
     * Handler method for the UpdateVariable message.
     * This message is sent by the coordinator to all replicas to update the variable
     * A replica should acknowledge the update by sending an AcknowledgeUpdate message
     * @param update the update message containing the new value
     */
    private void onUpdateVariable(UpdateVariable update) {
        if (this.afterForwardTimeout.size() > 0) {
            log("canceling afterForwardTimeout because received update from coordinator");
            this.afterForwardTimeout.get(0).cancel(); // the coordinator is alive
            this.afterForwardTimeout.remove(0);
        }
        log("Received update " + update.messageIdentifier + " with value: "+ update.value+  " from the coordinator " + coordinatorRef.path().name());

        if (this.crash_type == Crash.REPLICA_ON_UPDATE_MESSAGE) {
            crash();
            return;
        }

        if (update.replicaId == this.id) {
            this.writeRequestMessageQueue.remove(0); //remove the message from the queue
        }
        temporaryBuffer.put(update.messageIdentifier, new Data(update.value, this.peers.size()));

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
        if (!temporaryBuffer.containsKey(ack.messageIdentifier)) {
            log("slow ack from replica_" + ack.senderId + ", " + ack.messageIdentifier + " has been already confirmed");
            return;
        }
        log("Received ack from replica_" + ack.senderId + " for message " + ack.messageIdentifier);
        // step 2 of 2 phase broadcast protocol
        temporaryBuffer.get(ack.messageIdentifier).ackBuffers.add(ack.senderId);
        boolean reachedQuorum = temporaryBuffer.get(ack.messageIdentifier).ackBuffers.size() >= quorumSize;
        if (reachedQuorum) {
            // send confirm to the other replicas
            log("Reached quorum for message " + ack.messageIdentifier);
            if (this.crash_type == Crash.COORDINATOR_BEFORE_WRITEOK_MESSAGE && this.coordinatorRef.equals(getSelf())) {
                crash();
                return;
            }
            WriteOK confirmDelivery = new WriteOK(ack.messageIdentifier);
            multicast(confirmDelivery);
            nWriteOk++;
            if (nWriteOk == N_WRITE_OK && this.crash_type == Crash.COORDINATOR_AFTER_N_WRITE_OK
                    && this.coordinatorRef.equals(getSelf())) {
                this.crash();
                return;
            }

            // deliver the message
            this.deliverUpdate(ack.messageIdentifier);
        }
    }

    /**
     * Handler method for the WriteOK message.
     * This message is sent by the coordinator to confirm the delivery of the update
     * The replica can now deliver the update
     * @param confirmMessage the writeOK message
     */
    private void onWriteOK(WriteOK confirmMessage) {
        log("Received writeOK from, the size of writeOK timeout is: " + this.afterUpdateTimeout.size());
        if (!this.afterUpdateTimeout.isEmpty()) { // 0, the assumption is that the communication channel is fifo, so whenever
            // arrive,i have to delete the oldest
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
        ElectionMessage electionMessage = new ElectionMessage(id, this.getLastUpdate().getMessageIdentifier());
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
        // Add myself in the state and forward the message
        UUID oldAckIdentifier = electionMessage.ackIdentifier;
        electionMessage = electionMessage.addState(this.id, this.getLastUpdate().getMessageIdentifier(), electionMessage.quorumState);
        this.forwardElectionMessageWithAck(electionMessage, oldAckIdentifier);

        // Create the gloabl election timeout
        this.electionTimeout = this.timeoutScheduler(electionTimeoutDuration, new StartElectionMessage("Global timer expired"));
    }

    /**
     * Method used to handle the ElectionMessage.
     * Represents the main logic of the election process
     * @param electionMessage the election message, possibly containing the state of the replicas
     */
    private void onElectionMessage(ElectionMessage electionMessage) {
        log("Received election message from " + getSender().path().name() + " electionMessage: "
                + electionMessage.toString());

        if (crash_type == Crash.REPLICA_ON_ELECTION_MESSAGE) {
            crash();
            return;
        }

        if (this.coordinatorRef != null) {
            log("I'm the coordinator, sending synchronization message again, thee eleciton is running" + ", "
                    + this.electionTimeout.isCancelled());
            // // To keep the cancellation of the election timeout
            if (this.electionTimeout != null) {
                log("Somebody restarted the election " + electionMessage.quorumState.toString()+"  "+getSender().path().name());
                this.electionTimeout.cancel();
            }
            return;
        }

        // If the received message contains my id, it is the second round for me, so I need to check whether I would become the coordinator or not.
        // If not, I forward the message that will eventually reach the new coordinator
        if (electionMessage.quorumState.containsKey(id)) {
            UUID oldAckIdentifier = electionMessage.ackIdentifier;
            boolean won = haveWonTheElection(electionMessage);
            if (won) {
                // Here we know that we are the most updated replica, so i become the LEADER
                this.lastUpdate = this.lastUpdate.incrementEpoch(); // Increment the epoch
                // Change the message identifier of the pending message, this leader will handle it
                List<MessageIdentifier> buffer = this.temporaryBuffer.keySet().stream().collect(Collectors.toList());
                buffer.sort((o1, o2) -> o1.compareTo(o2));
                for (MessageIdentifier key : buffer) {
                    Data data = this.temporaryBuffer.get(key);
                    this.temporaryBuffer.put(lastUpdate, data);
                    this.temporaryBuffer.remove(key);
                    this.temporaryBuffer.get(lastUpdate).ackBuffers.add(id);//ack to this message
                    lastUpdate = lastUpdate.incrementSequenceNumber();
                }
                SynchronizationMessage synchronizationMessage = new SynchronizationMessage(id, getSelf());
                multicast(synchronizationMessage); // Send to all replicas (except me) the Sync message
                log("multicasting sychronization, i won this election" + electionMessage.toString());
                //update the replicas that are outdated and tell them to ack the pending messages
                this.updateOutdatedReplicas(electionMessage.quorumState); // Take care of the replicas that are outdated

                // Send the ack to the previous replica
                this.tellWithDelay(getSender(), getSelf(), new AckElectionMessage(oldAckIdentifier));
                this.coordinatorRef = getSelf(); // Set myself as the coordinator
                getContext().become(createReceive()); // Switch back to the normal behavior
                
                // Cancel the global election timeout
                if (this.electionTimeout != null) {
                    this.electionTimeout.cancel();
                }

                this.emptyCoordinatorQueue(); // Send all the write requests that were stored in the queue during the election
                this.tellWithDelay(getSelf(), getSelf(), new SendHeartbeatMessage()); // Start the heartbeat mechanism
                
            } else {
                // Before forwarding the message, I need to check if the future coordinator is still alive
                List<Integer> peersId = this.peers.stream().map(ar -> Integer.parseInt(ar.path().name().split("_")[1])).collect(Collectors.toList());

                // maybe we can merge it with the haveWonTheElection
                int max_id = getWinnerId(electionMessage);
                if (!peersId.contains(max_id)) {
                    log("Replica_" + max_id + " is crashed, but a message for it is still circulating");
                    this.tellWithDelay(getSender(), getSelf(), new AckElectionMessage(oldAckIdentifier));
                    return;
                }
                // Generate new Election message with the same attribute as before but different Ack id 
                electionMessage = new ElectionMessage(electionMessage.quorumState);
                this.forwardElectionMessageWithAck(electionMessage, oldAckIdentifier);
            }

        } else {
            // The received message does not contain my id, it means it's the first time I see this message.
            // The idea here is to keep forwarding only the messages that contain a replica which could win the election.
            boolean won = haveWonTheElection(electionMessage);
            if (won) {
                log("Not forwarding because can't win, waiting for my message to do the second round " + electionMessage.quorumState.toString());  
                this.tellWithDelay(getSender(), getSelf(), new AckElectionMessage(electionMessage.ackIdentifier));
                if (crash_type == Crash.REPLICA_AFTER_ACK_ELECTION_MESSAGE) { // 2 node failure crash
                    crash();
                    return;
                }
            } else {
                UUID oldAckIdentifier = electionMessage.ackIdentifier;
                // I would lose the election, so I add my state to the message and forward it to the next replica
                electionMessage = electionMessage.addState(id, this.getLastUpdate().getMessageIdentifier(), electionMessage.quorumState);
                this.forwardElectionMessageWithAck(electionMessage, oldAckIdentifier);
            }

        }
    }

    /**
     * Handler method for the AckElectionMessage.
     * Once a replica receives an ack, it cancels the timeout associated with the ack
     * @param ackElectionMessage the ack election message
     */
    private void onAckElectionMessage(AckElectionMessage ackElectionMessage) {
        log("Received election ack from " + getSender().path().name() + " removing ack with id: "
                + ackElectionMessage.id);

        Cancellable toCancel = this.acksElectionTimeout.get(ackElectionMessage.id);
        if (toCancel == null) {
            log("PROBLEMIH PROBLEMIH PROBLEMIH " + ackElectionMessage.id+"  ack election timeout  "+acksElectionTimeout);//it enter here when we want to remove an ack that has already been removed
        } else {
            toCancel.cancel();
            this.acksElectionTimeout.remove(ackElectionMessage.id);
        }
    }

    /**
     * Handler for the SynchronizationMessage.
     * This message is sent by the new coordinator to all replicas to notify them the end of the election
     * and the new coordinator.
     * It cancels the election timeout and starts the heartbeat timer
     * @param synchronizationMessage the synchronization message
     */
    private void onSynchronizationMessage(SynchronizationMessage synchronizationMessage) {
        getContext().become(createReceive()); // Election is finished, so I switch back to the normal behavior
        this.coordinatorIsEmptyingQueue = true;
        this.coordinatorRef = synchronizationMessage.getCoordinatorRef();
        log("Received synchronization message from " + coordinatorRef.path().name());
        this.lastUpdate = this.lastUpdate.incrementEpoch();
        // Election is finished, so I cancel the election timeout
        if (this.electionTimeout != null) {
            this.electionTimeout.cancel();
        }

        // If
        if (this.heartbeatTimeout != null) {
            this.heartbeatTimeout.cancel();
        }

        this.heartbeatTimeout = timeoutScheduler(coordinatorHeartbeatTimeoutDuration, new CoordinatorCrashedMessage());

        // // Send the update received by the previous coordinator but never delivered
        // List<MessageIdentifier> buffer = this.temporaryBuffer.keySet().stream().collect(Collectors.toList());
        // buffer.sort((o1, o2) -> o1.compareTo(o2));

        // for (MessageIdentifier key : buffer) {
        //     Data data = this.temporaryBuffer.get(key);
        //     this.temporaryBuffer.put(lastUpdate, data);
        //     this.temporaryBuffer.remove(key);
        //     AcknowledgeUpdate ack = new AcknowledgeUpdate(lastUpdate, this.id);
        //     this.tellWithDelay(coordinatorRef, getSelf(), ack);

        //     this.afterUpdateTimeout.add(
        //             this.timeoutScheduler(
        //                     afterUpdateTimeoutDuration,
        //                     new StartElectionMessage("didn't receive writeOK message from coordinator for message "
        //                             + lastUpdate + " value: " + data.value)));
        //     lastUpdate = lastUpdate.incrementSequenceNumber();
        // }

        // log("Added the temporary buffer: " + buffer.toString() + " to the write request message queue"
        //         + writeRequestMessageQueue.toString());

    }

    /**
     * Method to handle the CoordinatorCrashedMessage.
     * This message is sent by the replica to itself when the coordinator crashes.
     * It removes the coordinator from the peers list and starts a new election
     * @param message the coordinator crashed message
     */
    private void onCoordinatorCrashed(CoordinatorCrashedMessage message) {
        this.totalCrash++;
        // remove crashed replica from the peers list
        this.removePeer(coordinatorRef);
        StartElectionMessage startElectionMessage = new StartElectionMessage("Didn't receive heartbeat from coordinator");
        this.startElection(startElectionMessage);
    }

    /**
     * Handler method for the CrashedNextReplicaMessage.
     * When the next replica doesn't send an ack, the current replica sends the election message to the next replica
     * It removes the next replica from the peers list and cancels all the acks relative to the next replica
     * @param message the crashed next replica message
     */
    private void onNextReplicaCrashed(CrashedNextReplicaMessage message) {
        log("Didn't receive ACK, sending election message to the next replica");
        // Remove nextRef from the peers list and cancel all the acks relative to nextRef
        this.removePeer(message.nextRef);
        // this.acksElectionTimeout.remove(message.electionMessage.ackIdentifier);
        for (Cancellable timer : this.acksElectionTimeout.values()) {
            timer.cancel();
        }
        this.acksElectionTimeout.clear();
        // No need to ack again, since im not crashed and i have already sent the ack to the previous node
        this.forwardElectionMessageWithoutAck(message.electionMessage);
    }

    /**
     * Start the heartbeat mechanism, the coordinator sends a heartbeat message to
     * all replicas every 5 seconds.
     * @param message the send heartbeat message
     */
    private void onSendHeartbeat(SendHeartbeatMessage message) {
        if (this.coordinatorRef != getSelf()) {
            log("Im no longer the coordinator");

            this.sendHeartbeat.cancel();
        } else {
            // this crash seems to work
            // if (this.heartbeatCounter == 1 && this.id == 4) {
            //     this.heartbeatCounter = 0;
            //     crash(4);
            //     return;
            // }

            //this is used to make maxCrash coordinator crash
            // if (heartbeatCounter == 1 && totalCrash < maxCrash) {
            //     heartbeatCounter = 0;
            //     int currentCoordId = Integer.parseInt(getSelf().path().name().split("_")[1]);
            //     crash(currentCoordId);
            //     return;
            // }

            // if (heartbeatCounter == 1
            // && Replica.this.coordinatorRef.path().name().equals("replica_3")) {
            // heartbeatCounter = 0;
            // crash(3);
            // return;
            // }
            heartbeatCounter++;
            multicast(new ReceiveHeartbeatMessage());

            if (this.crash_type == Crash.COORDINATOR_AFTER_HEARTBEAT && this.coordinatorRef.equals(getSelf())) {
                this.crash();
            }
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
     * Method used to create a Cancellation object for the ackElection timeout
     * @param electionMessage the election message, that could be sent to the next next replica in case of timeout
     * @param nextRef the next replica used to refer to it in case of timeout to eliminate it from the peers list
     * @return the Cancellation object
     */
    private Cancellable scheduleElectionTimeout(final ElectionMessage electionMessage, final ActorRef nextRef) {
        log("creating election timeout for " + nextRef.path().name()+ " with ACK id: " + electionMessage.ackIdentifier);
        Cancellable temp = timeoutScheduler(ackElectionMessageDuration, new CrashedNextReplicaMessage(electionMessage, nextRef));
        return temp;
    }

    /**
     * Handler for the UpdateHistoryMessage.
     * Such message is sent by the coordinator to update the history of the replicas
     * @param updateHistoryMessage the update history message, containing all the missing updates of the current replica
     */
    private void onUpdateHistory(UpdateHistoryMessage updateHistoryMessage) {

        List<Update> updates = updateHistoryMessage.getUpdates();
        log("my history" + this.history.toString() + "\nReceived update history message from "
                + getSender().path().name() + " " + updates.toString());
        for (Update update : updates) {
            if (this.temporaryBuffer.containsKey(update.getMessageIdentifier())) {
                this.deliverUpdate(update.getMessageIdentifier());
            } else {
                this.replicaVariable = update.getValue();
                this.lastUpdate = update.getMessageIdentifier();
                history.add(update);
                log(this.getLastUpdate().toString());
            }
        }
        // ack the message that are still pending in the buffer (also need to change their epoch and sequence number , they will be the first one to be processed)
        List<MessageIdentifier> buffer = this.temporaryBuffer.keySet().stream().collect(Collectors.toList());
        buffer.sort((o1, o2) -> o1.compareTo(o2));

        for (MessageIdentifier key : buffer) {
            Data data = this.temporaryBuffer.get(key);
            this.temporaryBuffer.put(lastUpdate, data);
            this.temporaryBuffer.remove(key);
            AcknowledgeUpdate ack = new AcknowledgeUpdate(lastUpdate, this.id);
            this.tellWithDelay(coordinatorRef, getSelf(), ack);

            this.afterUpdateTimeout.add(
                    this.timeoutScheduler(
                            afterUpdateTimeoutDuration,
                            new StartElectionMessage("didn't receive writeOK message from coordinator for message "
                                    + lastUpdate + " value: " + data.value)));
            lastUpdate = lastUpdate.incrementSequenceNumber();
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
        this.replicaVariable = temporaryBuffer.get(messageIdentifier).value;
        temporaryBuffer.remove(messageIdentifier);
        history.add(new Update(messageIdentifier, this.replicaVariable));
        log(this.getLastUpdate().toString());
    }

    /**
     * Utility method used to send a message to all replicas except the one that sent the message
     * @param message the message to send
     */
    private void multicast(Serializable message) {
        for (ActorRef peer : peers) {
            if (peer != getSelf()) {
                peer.tell(message, getSelf());
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

        String s = "";
        if (this.id == 1) {
            for (ActorRef p : this.peers) {
                s += "" + p.path().name() + " | ";
            }
            log("Alive peers: " + s);
        }
        int myIndex = peers.indexOf(getSelf());
        this.nextRef = peers.get((myIndex + 1) % peers.size());
        log("Removed peer " + peer.path().name() + " my new next replica is " + this.nextRef.path().name());
    }

    /**
     * Wrapper around the forwardElectionMessage method.
     * This method sends the election message to the next replica and sends an ack to the previous replica
     * @param electionMessage the election message
     * @param oldAckIdentifier the ack identifier of the previous replica
     */
    private void forwardElectionMessageWithAck(ElectionMessage electionMessage, UUID oldAckIdentifier) {
        forwardElectionMessage(electionMessage, oldAckIdentifier);
    }

    /**
     * Wrapper around the forwardElectionMessage method.
     * This method sends the election message to the next replica without sending an ack to the previous replica
     * @param electionMessage the election message
     */
    private void forwardElectionMessageWithoutAck(ElectionMessage electionMessage) {
        forwardElectionMessage(electionMessage, null);
    }

    /**
     * Method used to forward the election message to the next replica
     * @param electionMessage the election message
     * @param oldAckIdentifier the ack identifier of the previous replica
     */
    private void forwardElectionMessage(ElectionMessage electionMessage, UUID oldAckIdentifier) {

        if (crash_type == Crash.REPLICA_BEFORE_FORWARD_ELECTION_MESSAGE) {
            crash();
            return;
        }

        tellWithDelay(this.nextRef, getSelf(), electionMessage);
        log("Sent election message to " + this.nextRef.path().name() + " : " + electionMessage.toString());
        if (oldAckIdentifier != null) {
            log("Sent ACK to previous " + getSender().path().name() + " with ACK id: " + oldAckIdentifier);
            tellWithDelay(getSender(), getSelf(), new AckElectionMessage(oldAckIdentifier));
        }
        Cancellable ackElectionTimeout = scheduleElectionTimeout(electionMessage, this.nextRef);
        this.acksElectionTimeout.put(electionMessage.ackIdentifier, ackElectionTimeout);
    }
    
    /**
     * Check if the replica has won the election
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
            return maxId <= this.id;// == if I'm in the quorum state, < is needed when i have to add myself
        }      
        // int max_id = getWinnerId(electionMessage);
        // return max_id == this.id;   
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
        maxUpdate = maxUpdate.compareTo(this.getLastUpdate().getMessageIdentifier()) > 0 ? maxUpdate : this.getLastUpdate().getMessageIdentifier();
        int max_id = -1;
        for (var entry : electionMessage.quorumState.entrySet()) {
                if ( entry.getValue().compareTo(maxUpdate) == 0) {
                    if (entry.getKey() > max_id) {
                        max_id = entry.getKey();
                    }
                }
            }   
        if (max_id == -1) {
            max_id = this.id;
        }

        return max_id;
    }

    /**
     * Generic method to schedule a timeout
     * @param ms the duration of the timeout
     * @param message the message to send if the timer expires
     * @return the Cancellation object
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
     */
    private void crash() {
        this.cancelAllTimeouts();
        this.isCrashed = true;
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

        // Here we first cancel the timers to avoid unwanted behavior
        // Thus, we clear the list of timers because otherwise problems occur
        for (Cancellable timer : this.acksElectionTimeout.values()) {
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
            writer.write(msg + System.lineSeparator());
            writer.flush();    
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Empty the queue of write requests if not empty
     */
    private void emptyQueue() {
        if (!this.writeRequestMessageQueue.isEmpty()) {
            log("Emptying the queue" + this.writeRequestMessageQueue.toString());
            for (WriteRequest writeRequest : this.writeRequestMessageQueue) {
                this.tellWithDelay(this.coordinatorRef, getSelf(), writeRequest);
            }
        }
    }
    
    // Once the election is finished, the coordinator will first empty the queue of its write requests
    // The writeRequestMessageQueue will contain also the unstable messages that were stored in the temporary buffer
    /**
     * Method used to empty the queue of write requests of the coordinator.
     * This method is called after the election is finished and the coordinator has won.
     * Then the coordinator sends a message to the replicas to empty their queue
     */
    private void emptyCoordinatorQueue() {
         // First empty the coordinator queue, then empty the other replica queue to ensure sequential consistency (the messages in the write buffer are older)
         this.emptyQueue();
        multicast(new EmptyReplicaWriteMessageQueue());
    }

    // When the coordinator has finished emptying the queue of write requests, it will send a message to the replicas to empty their queue
    /**
     * Method used to empty the queue of write requests of the replicas.
     * @param EmptyReplicaWriteMessageQueue the empty replica write message queue
     */
    private void emptyReplicaQueue(EmptyReplicaWriteMessageQueue EmptyReplicaWriteMessageQueue) {
        this.coordinatorIsEmptyingQueue = false;
        this.emptyQueue();
    }

    /**
     * Method used to update the outdated replicas with the missing updates.
     * The coordinator sends the missing updates to the replicas
     * @param quorumState the state of the replicas
     */
    private void updateOutdatedReplicas(Map<Integer, MessageIdentifier> quorumState) {
        // Not multicasting because each replica may have different updates
        for (var entry : quorumState.entrySet()) {
            if (entry.getKey() == this.id) { // skip myself
                continue;
            }
            MessageIdentifier replicaLastUpdate = entry.getValue();
            List<Update> listOfUpdates = this.history.stream()
                    .filter(update -> update.getMessageIdentifier().compareTo(replicaLastUpdate) > 0)
                .collect(Collectors.toList());
            
            UpdateHistoryMessage updateHistoryMessage = new UpdateHistoryMessage(listOfUpdates);
            ActorRef replica = getReplicaActorRefById(entry.getKey());
            log(listOfUpdates.toString() + "Sending updates to " + replica.path().name());
            if (replica != null) {
                this.tellWithDelay(replica, getSelf(), updateHistoryMessage);
            }

        }
        
        // // Send the update received by the previous coordinator but never delivered
        // List<MessageIdentifier> buffer = this.temporaryBuffer.keySet().stream().collect(Collectors.toList());
        // buffer.sort((o1, o2) -> o1.compareTo(o2));
        // // Convert the temporary buffer to a list of write requests
        // List<WriteRequest> tmp_to_wr = buffer.stream().map(key -> new WriteRequest(this.temporaryBuffer.get(key).value)).collect(Collectors.toList());
        // tmp_to_wr.addAll(writeRequestMessageQueue); // Add the element in the temporary buffer in the head of the write reqeust message queue
        // this.writeRequestMessageQueue = tmp_to_wr;
        // log("Added the temporary buffer: " + buffer.toString() +" to the write request message queue" + writeRequestMessageQueue.toString());
        // this.temporaryBuffer.clear();
    }

    /**
     * Method used to get the ActorRef of a replica by its id
     * @param id the id of the replica
     * @return the ActorRef of the replica
     */
    private ActorRef getReplicaActorRefById(int id) {
        for (ActorRef peer : peers) {
            if (peer.path().name().equals("replica_" + id)) {
                return peer;
            }
        }
        return null;
    }
    // --------------------------- END ----------------------------
}