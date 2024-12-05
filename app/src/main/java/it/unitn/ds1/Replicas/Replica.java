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
import it.unitn.ds1.Replicas.messages.ReceiveHeartbeatMessage;
import it.unitn.ds1.Replicas.messages.StartElectionMessage;
import it.unitn.ds1.Replicas.messages.PrintHistory;
import it.unitn.ds1.Replicas.messages.SendHeartbeatMessage;
import it.unitn.ds1.Replicas.messages.SynchronizationMessage;
import it.unitn.ds1.Replicas.messages.UpdateHistoryMessage;
import it.unitn.ds1.Replicas.messages.UpdateVariable;
import it.unitn.ds1.Messages.GroupInfo;

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
    private static final int afterUpdateTimeoutDuration = 2000;// if the replica doesn't receive  confirm update message from the coordinator(waiting writeOK mes)
    private static final int coordinatorHeartbeatTimeoutDuration = 3000; //if the replica doesn't receive a heartbeat from the coordinator

    private static final int messageMaxDelay = 150;
    static Random rnd = new Random();

    private int id;
    private int replicaVariable;
    private List<ActorRef> peers = new ArrayList<>();
    @SuppressWarnings("unused")
    private boolean isCrashed = false;
    private ActorRef nextRef = null;
    private List<WriteRequest> writeRequestMessageQueue = new ArrayList<>(); //message that i have to send to the coordinator

    private MessageIdentifier lastUpdate = new MessageIdentifier(-1, 0);;

    private boolean isElectionRunning = false;
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

    // USED TO TEST THE CRASH
    @SuppressWarnings("unused")
    private int heartbeatCounter = 0;
    @SuppressWarnings("unused")
    private int maxCrash = 2;
    @SuppressWarnings("unused")
    private int totalCrash = 0;

    // -------------------------- REPLICA ---------------------------
    public Replica(int id, String logFolderName) throws IOException {
        this.replicaVariable = -1;
        this.id = id;
        // this.history.add(new Update(new MessageIdentifier(0, 0),
        // this.replicaVariable));
        String directoryPath = logFolderName;
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
                .match(ElectionMessage.class, this::onFirstElectionMessage) // This will trigger the behavior change to inElection
                //.match(SynchronizationMessage.class, this::onSynchronizationMessage)//remove handler
                .match(ReceiveHeartbeatMessage.class, this::onReceiveHeartbeatMessage)
                .match(AckElectionMessage.class, this::onAckElectionMessage) // To keep because the coordinator multicast the synchronization message, which trigger the normal state, and then send the ack, which will be received by the previous replica in the normal state and not the election state
                .match(PrintHistory.class, this::onPrintHistory)
                .match(StartElectionMessage.class, this::startElection)
                // .match(CoordinatorCrashedMessage.class, this::onCoordinatorCrashed)
                //.match(CrashedNextReplicaMessage.class, this::onNextReplicaCrashed) //remove handler
                .match(SendHeartbeatMessage.class, this::onSendHeartbeat)
                .match(UpdateHistoryMessage.class, this::onUpdateHistory)
                .build();
    }

    final AbstractActor.Receive crashed() {
        return receiveBuilder()
                .match(PrintHistory.class, this::onPrintHistory)
                .matchAny(msg -> {
                    log("I'm crashed, I cannot process messages");
                })
                .build();
    }

    final AbstractActor.Receive inElection() {
        return receiveBuilder()
                .match(ReadRequest.class, this::onReadRequest)
                .match(WriteRequest.class, this::onWriteRequestOnElection) 
                .match(ElectionMessage.class, this::onElectionMessage)//may be revisited
                .match(AckElectionMessage.class, this::onAckElectionMessage)
                .match(SynchronizationMessage.class, this::onSynchronizationMessage)
                .match(CrashedNextReplicaMessage.class, this::onNextReplicaCrashed)
                .matchAny(msg -> {
                    log("I'm in election, I cannot process messages");
                })
                .build();
    }

    static public Props props(int id, String logFolderName) {
        return Props.create(Replica.class, () -> new Replica(id, logFolderName));
    }

    
    // ----------------------- BASIC HANDLERS -----------------------
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

    private void onReadRequest(ReadRequest request) {
        log("received read request");
        // getSender().tell(new ReadResponse(replicaVariable), getSelf());
        tellWithDelay(getSender(), getSelf(), new ReadResponse(replicaVariable));
    }

    private void onPrintHistory(PrintHistory printHistory) {
        String historyMessage = "\n#################HISTORY########################\n";
        for (Update update : history) {
            historyMessage += update.toString() + "\n";
        }
        historyMessage += "################################################\n";
        log(historyMessage + "\n" + temporaryBuffer.toString());

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
    
    private void onWriteRequest(WriteRequest request) {
        if (this.coordinatorRef == null) {
            String reasonMessage ="coordinator is null";
            log(reasonMessage + ", adding the write request to the queue");
            writeRequestMessageQueue.add(request);
            return;
        }
        // this is needed to handle the case in which the replica is emptying the queue, and the a message arrive from the client, so we maintain the order
        if (request.addToQueue) { // in any case once i put on the queue i also need to forward it
            log("Received write request from client, adding to the queue");
            writeRequestMessageQueue.add(request);
        }
        // crash(2);
        // if (isCrashed)
        // return;
        //taking from the queue, so we have one truth
        log("write request queue: " + writeRequestMessageQueue.toString());
        if (writeRequestMessageQueue.size() < 1) {// TODO: maybe removed if we understand how some extra write request are done
            log("Received write request: " + getSender() + "but the queue is empty, reqeust type is "
                    + request.addToQueue + " value is " + request.value);
            return;
        }
        WriteRequest writeMessage = writeRequestMessageQueue.remove(0);// TODO: the message may be lost if the coordinator crashes before receiving it, (let see if we need to handle it by removing the messange only when a write ok messge is received)
        int value = writeMessage.value;
        if (getSelf().equals(coordinatorRef)) {
            log("Received write request from client, starting 2 phase broadcast protocol");
            // step 1 of 2 phase broadcast protocol
            lastUpdate = lastUpdate.incrementSequenceNumber();
            UpdateVariable update = new UpdateVariable(lastUpdate, value);
            multicast(update);

            // initialize the toBeDelivered list and set the coordinator as received
            temporaryBuffer.put(lastUpdate, new Data(value, this.peers.size()));
            temporaryBuffer.get(lastUpdate).ackBuffers.add(id);
            log("acknowledged message id " + lastUpdate.toString());

        } else {
            // forward the write request to the coordinator
            log("forwarding write request to coordinator " + coordinatorRef.path().name());
            // coordinatorRef.tell(writeMessage, getSelf());
            tellWithDelay(coordinatorRef, getSelf(), writeMessage);
            // TODO: if the coordinator crashes before receving my, the value, it means that this value is lost. 
            //if i dont recevie the ack, i have to resend the message and also start a new election, maybe we can use a message queue, for everything, and dequeeu only when the final ack is received
            this.afterForwardTimeout
                    .add(this.timeoutScheduler(afterForwardTimeoutDuration, new StartElectionMessage(
                            "forwarded message, but didn't receive update from the coordinator")));


        }
    }

    private void onUpdateVariable(UpdateVariable update) {
        // if (id == 1) {
        //     try {
        //         log("waiting 1.5s");
        //         Thread.sleep(1500);
        //     } catch (InterruptedException e) {
        //         e.printStackTrace();
        //     }
        // }
        if (this.afterForwardTimeout.size() > 0) {
            log("canceling afterForwardTimeout because received update from coordinator");
            this.afterForwardTimeout.get(0).cancel(); // the coordinator is alive
            this.afterForwardTimeout.remove(0);
        }
        //this.lastUpdate = update.messageIdentifier;
        log("Received update " + update.messageIdentifier + " from the coordinator " + coordinatorRef.path().name());

        temporaryBuffer.put(update.messageIdentifier, new Data(update.value, this.peers.size()));
        AcknowledgeUpdate ack = new AcknowledgeUpdate(update.messageIdentifier, this.id);
        // coordinatorRef.tell(ack, getSelf());
        tellWithDelay(coordinatorRef, getSelf(), ack);

        afterUpdateTimeout.add(this.timeoutScheduler(afterUpdateTimeoutDuration,
                new StartElectionMessage("didn't receive writeOK message from coordinator")));
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
        temporaryBuffer.get(ack.messageIdentifier).ackBuffers.add(ack.senderId);
        boolean reachedQuorum = temporaryBuffer.get(ack.messageIdentifier).ackBuffers.size() >= quorumSize;
        if (reachedQuorum) {
            // send confirm to the other replicas
            log("Reached quorum for message " + ack.messageIdentifier);
            WriteOK confirmDelivery = new WriteOK(ack.messageIdentifier);
            multicast(confirmDelivery);

            // deliver the message
            this.deliverUpdate(ack.messageIdentifier);

        }
    }

    private void onWriteOK(WriteOK confirmMessage) {
        // TEsting with 5 replicas, and 2 crashes so the coordinator shoul be 4,2 and then 1
        if (afterUpdateTimeout.size() > 0) { // 0, the assumption is that the communication channel is fifo, so whenever
            // arrive,i have to delete the oldest
            log("canceling afterUpdateTimeout because received confirm from coordinator");
            afterUpdateTimeout.get(0).cancel();// the coordinator is alive
            afterUpdateTimeout.remove(0);
        }
        if (id == 3 && history.size() >= 1) {
            return;
        }
        log("Received confirm to deliver from the coordinator");
        this.deliverUpdate(confirmMessage.messageIdentifier);
        // request.client.tell("ack", getSelf());
    }


    // ----------------------- ELECTION HANDLERS -----------------------
    private void startElection(StartElectionMessage startElectionMessage) {
        // this.isElectionRunning = true;
        getContext().become(inElection()); // Switch to the inElection behavior
        this.cancelAllTimeouts(); // Cancel all the timeouts
        log("Starting election, reason: " + startElectionMessage.reason);
        ElectionMessage electionMessage = new ElectionMessage(id, this.getLastUpdate().getMessageIdentifier());
        
        this.electionTimeout = this.timeoutScheduler(electionTimeoutDuration, new StartElectionMessage("Global election timer expired"));
        this.forwardElectionMessageWithoutAck(electionMessage); // I don't ack the previous replica, since I'm the one starting the election
    }


    private void onFirstElectionMessage(ElectionMessage electionMessage) {
        getContext().become(inElection()); // Switch to the inElection behavior
        log("Received first election message from " + getSender().path().name() + " electionMessage: " + electionMessage.toString());
        this.cancelAllTimeouts();

        // Add myself in the state and forward the message
        UUID oldAckIdentifier = electionMessage.ackIdentifier;
        electionMessage = electionMessage.addState(this.id, this.getLastUpdate().getMessageIdentifier(), electionMessage.quorumState);
        this.forwardElectionMessageWithAck(electionMessage,oldAckIdentifier);

        // Create the gloabl election timeout
        this.electionTimeout = this.timeoutScheduler(electionTimeoutDuration, new StartElectionMessage("Global timer expired"));
    }
    
    private void onElectionMessage(ElectionMessage electionMessage) {
        log("Received election message from " + getSender().path().name() + " electionMessage: "
                + electionMessage.toString());
        

        // if (this.id == 2) {
        //     crash(2);
        //     return;
        // }

        if (this.coordinatorRef != null && this.coordinatorRef.equals(getSelf())) {
            log("I'm the coordinator, sending synchronization message again, thee eleciton is running"  + ", " + this.electionTimeout.isCancelled());
            // this.isElectionRunning = false;
            // SynchronizationMessage synchronizationMessage = new SynchronizationMessage(id, getSelf());
            // multicast(synchronizationMessage);
            // // To keep the cancellation of the election timeout
            if (this.electionTimeout != null) {
                log("Somebody restarted the election " + electionMessage.quorumState.toString()+"  "+getSender().path().name());
                this.electionTimeout.cancel();
            }
            // emptyQueue();// TODO: REMOVE ONCE WE FINISH THE MESSAGEQUE TASK (depend on the prof answer)

            // // getSender().tell(new AckElectionMessage(electionMessage.ackIdentifier), getSelf());
            // tellWithDelay(getSender(), getSelf(), new AckElectionMessage(electionMessage.ackIdentifier));
            // return;
        }
        
        // if (this.isElectionRunning == false) {
        //     electionMessage = electionMessage.addState(id, this.getLastUpdate().getMessageIdentifier(), electionMessage.quorumState);
        //     forwardElectionMessage(electionMessage);
        //     this.isElectionRunning = true;
        //     if (this.electionTimeout != null) {
        //         this.electionTimeout.cancel();
        //     }
        //     this.electionTimeout = this.timeoutScheduler(electionTimeoutDuration,
        //             new StartElectionMessage("Global timer expired"));
        //     return;
        // }

        // If the received message contains my id, it is the second round for me, so I need to check wether I would become the coordinator or not
        // If not, I forward the message that will eventually reach the new coordinator
        if (electionMessage.quorumState.containsKey(id)) {
            UUID oldAckIdentifier = electionMessage.ackIdentifier;
            boolean won = haveWonTheElection(electionMessage);
            if (won){
                // Here we know that we are the most updated replica, so i become the LEADER
                SynchronizationMessage synchronizationMessage = new SynchronizationMessage(id, getSelf());
                multicast(synchronizationMessage); // Send to all replicas (except me) the Sync message
                log("multicasting sychronization, i won this election" + electionMessage.toString());
                this.updateOutdatedReplicas(electionMessage.quorumState);//TODO MAYBE MOVED 

                // Send the ack to the previous replica
                this.tellWithDelay(getSender(), getSelf(), new AckElectionMessage(oldAckIdentifier));
                this.coordinatorRef = getSelf(); // Set myself as the coordinator
                getContext().become(createReceive()); // Switch back to the normal behavior
                
                // Cancel the global election timeout
                if (this.electionTimeout != null) {
                    this.electionTimeout.cancel();
                }
                
                // TODO: REMOVE ONCE WE FINISH THE MESSAGEQUE TASK (depend on the prof answer)
                this.emptyQueue(); // Send all the write requests to were stored in the queue

                this.tellWithDelay(getSelf(), getSelf(), new SendHeartbeatMessage()); // Start the heartbeat mechanism
                this.lastUpdate = this.lastUpdate.incrementEpoch(); // Increment the epoch
            } else{
                // Generate new Election message with the same attribute as before but different Ack id 
                electionMessage = new ElectionMessage(electionMessage.quorumState);
                this.forwardElectionMessageWithAck(electionMessage, oldAckIdentifier);
            }
            // I need to check if I have the most recent update and the highest id
            // MessageIdentifier maxUpdate = Collections.max(electionMessage.quorumState.values());
            // MessageIdentifier lastUpdate = this.getLastUpdate().getMessageIdentifier();
            // int amIMoreUpdated = lastUpdate.compareTo(maxUpdate);

            // // If Im not the most updated replica, I forward the election message
            // if (amIMoreUpdated < 0) {
            //     // I would lose the election, so I forward to the next replica
            //     this.forwardElectionMessage(electionMessage);
            // } else if (amIMoreUpdated == 0) { // TODO: replace the else if with else statement and remove the else below (line ~435)
            //     // The updates are equal, so I check the id
            //     ArrayList<Integer> ids = new ArrayList<>();
            //     electionMessage.quorumState.forEach((k, v) -> {
            //         if (maxUpdate.compareTo(v) == 0) {
            //             ids.add(k);
            //         }
            //     });
            //     int maxId = Collections.max(ids);

            //     if (maxId > this.id) {
            //         // I would lose the election, so I forward to the next replica
            //         this.forwardElectionMessage(electionMessage);

            //     } else {
            //         // Here we know that we are the most updated replica, so i become the LEADER
            //         SynchronizationMessage synchronizationMessage = new SynchronizationMessage(id, getSelf());
            //         multicast(synchronizationMessage); // Send to all replicas (except me) the Sync message
            //         log("multicasting sychronization, i won this election" + electionMessage.toString());
            //         this.updateOutdatedReplicas(electionMessage.quorumState);//TODO MAYBE MOVED 

            //         // Send the ack to the previous replica
            //         this.tellWithDelay(getSender(), getSelf(), new AckElectionMessage(electionMessage.ackIdentifier));
            //         this.coordinatorRef = getSelf(); // Set myself as the coordinator
            //         getContext().become(createReceive()); // Switch back to the normal behavior
                    
            //         // Cancel the global election timeout
            //         if (this.electionTimeout != null) {
            //             this.electionTimeout.cancel();
            //         }
                    
            //         // TODO: REMOVE ONCE WE FINISH THE MESSAGEQUE TASK (depend on the prof answer)
            //         this.emptyQueue(); // Send all the write requests to were stored in the queue

                    
            //         this.tellWithDelay(getSelf(), getSelf(), new SendHeartbeatMessage()); // Start the heartbeat mechanism
            //         this.lastUpdate = this.lastUpdate.incrementEpoch(); // Increment the epoch
            //     }
            // } else {
            //     // Technically this case is not possible
            //     // Since the received message contains my id, I must be either the most updated replica or not
            //     log("AAAAAAAAAAAAAAAAAA volte finisco anche qui");
            // }

        } else {
            // The received message does not contain my id, it means it's the first time I see this message.
            // The idea here is to keep forwarding only the messages that contain a replica which could win the election.
            boolean wouldWin = haveWonTheElection(electionMessage);
            if (wouldWin) {
                log("Not forwarding because can't win, waiting for my message to do the second round " + electionMessage.quorumState.toString());  
                this.tellWithDelay(getSender(), getSelf(), new AckElectionMessage(electionMessage.ackIdentifier));
            } else {
                UUID oldAckIdentifier = electionMessage.ackIdentifier;
                // I would lose the election, so I add my state to the message and forward it to the next replica
                electionMessage = electionMessage.addState(id, this.getLastUpdate().getMessageIdentifier(), electionMessage.quorumState);
                this.forwardElectionMessageWithAck(electionMessage, oldAckIdentifier);
            }
            // MessageIdentifier maxUpdate = Collections.max(electionMessage.quorumState.values());
            // MessageIdentifier lastUpdate = this.getLastUpdate().getMessageIdentifier();
            // int amIMoreUpdated = lastUpdate.compareTo(maxUpdate); // Here we compare my last update with the max update in the received message

            // // If Im not the most updated replica, I forward the election message
            // if (amIMoreUpdated < 0) {
            //     // I would lose the election, so I forward to the next replica
            //     electionMessage = electionMessage.addState(id, this.getLastUpdate().getMessageIdentifier(),
            //             electionMessage.quorumState);
            //     this.forwardElectionMessage(electionMessage);
            // } else if (amIMoreUpdated == 0) {
            //     // the updates are equal, so I check the id
            //     ArrayList<Integer> ids = new ArrayList<>();
            //     electionMessage.quorumState.forEach((k, v) -> {
            //         if (maxUpdate.compareTo(v) == 0) {
            //             ids.add(k);
            //         }
            //     });
            //     int maxId = Collections.max(ids);

            //     if (maxId > this.id) {
            //         electionMessage = electionMessage.addState(id, this.getLastUpdate().getMessageIdentifier(),
            //                 electionMessage.quorumState);
            //         // I would lose the election, so I forward to the next replica
            //         this.forwardElectionMessage(electionMessage);
            //     } else {
            //         // I might win the election, so I "stop" the received message
            //         log("Not forwarding because can't win " + electionMessage.quorumState.toString());
                    
            //         this.tellWithDelay(getSender(), getSelf(), new AckElectionMessage(electionMessage.ackIdentifier));

            //         // this crash allows replica 3 to receive the election message from replica 2, ack it and then crash
            //         // if (this.id == 3) {
            //         //     crash(3);
            //         //     return;
            //         // }
            //     }
            // } else {
            //     // Here I know that Im the most updated replica, based on the received message (avoid flooding)
            //     // getSender().tell(new AckElectionMessage(electionMessage.ackIdentifier), getSelf());
            //     tellWithDelay(getSender(), getSelf(), new AckElectionMessage(electionMessage.ackIdentifier));

            // }
        }
    }

    private void onAckElectionMessage(AckElectionMessage ackElectionMessage) {
        log("Received election ack from " + getSender().path().name() + " removing ack with id: "
                + ackElectionMessage.id);

        Cancellable toCancel = this.acksElectionTimeout.get(ackElectionMessage.id);
        // TODO remove, here for debugging
        if (toCancel == null) {
            log("PROBLEMIH PROBLEMIH PROBLEMIH" + ackElectionMessage.id);
        } else {
            toCancel.cancel();
            this.acksElectionTimeout.remove(ackElectionMessage.id);
        }
        // acksElectionTimeout.get(ackElectionMessage.id).cancel();
        // acksElectionTimeout.remove(ackElectionMessage.id);
    }

    private void onSynchronizationMessage(SynchronizationMessage synchronizationMessage) {
        getContext().become(createReceive());
        this.coordinatorRef = synchronizationMessage.getCoordinatorRef();
        log("Received synchronization message from " + coordinatorRef.path().name());
        // Election is finished, so I cancel the election timeout
        if (this.electionTimeout != null) {
            this.electionTimeout.cancel();
        }

        // If
        if (this.heartbeatTimeout != null) {
            this.heartbeatTimeout.cancel();
        }

        this.heartbeatTimeout = timeoutScheduler(coordinatorHeartbeatTimeoutDuration, new StartElectionMessage("Didn't receive heartbeat from coordinator"));

        // // send all the message store while the coordinator was down 
        this.emptyQueue();// TODO: REMOVE ONCE WE FINISH THE MESSAGEQUE TASK (depend on the prof answer)

    }

    // private void onCoordinatorCrashed(CoordinatorCrashedMessage message) {
    //     log("Coordinator is dead, starting election");
    //     this.totalCrash++;
    //     // remove crashed replica from the peers list
    //     removePeer(coordinatorRef);
    //     // no need to ack achain, since im not crashed and i have already sent the ack
    //     // to the previous node
    //     StartElectionMessage startElectionMessage = new StartElectionMessage(
    //             );
    //     this.startElection(startElectionMessage);
    // }

    private void onNextReplicaCrashed(CrashedNextReplicaMessage message) {
        log("Didn't receive ACK, sending election message to the next replica");
        // Remove nextRef from the peers list and cancel all the acks relative to nextRef
        this.removePeer(message.nextRef);
        this.acksElectionTimeout.remove(message.electionMessage.ackIdentifier);
        // No need to ack again, since im not crashed and i have already sent the ack to the previous node
        this.forwardElectionMessageWithoutAck(message.electionMessage);
    }

    /**
     * Start the heartbeat mechanism, the coordinator sends a heartbeat message to
     * all replicas every 5 seconds
     */
    private void onSendHeartbeat(SendHeartbeatMessage message) {
        // log(Replica.this.coordinatorRef.path().name() + " is sending heartbeat message");
        if (Replica.this.coordinatorRef != getSelf()) {
            log("Im no longer the coordinator");
            Replica.this.sendHeartbeat.cancel();
        } else {
            // this crash seems to work
            // if (heartbeatCounter == 1 && id == 3) {
            // heartbeatCounter = 0;
            // crash(3);
            // return;
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
        }

        this.sendHeartbeat = timeoutScheduler(coordinatorHeartbeatFrequency, new SendHeartbeatMessage());
    }

    private void onReceiveHeartbeatMessage(ReceiveHeartbeatMessage heartbeatMessage) {
        String message = "Received HB from coordinator " + getSender().path().name()
                + " coordinator is " + this.coordinatorRef.path().name();

        log(message);
        
        if (this.heartbeatTimeout != null) {
            this.heartbeatTimeout.cancel();
        }

        this.heartbeatTimeout = timeoutScheduler(coordinatorHeartbeatTimeoutDuration, new CoordinatorCrashedMessage());
    }

    private Cancellable scheduleElectionTimeout(final ElectionMessage electionMessage, final ActorRef nextRef) {
        log("creating election timeout for " + nextRef.path().name()+ " with ACK id: " + electionMessage.ackIdentifier);
        Cancellable temp = timeoutScheduler(ackElectionMessageDuration, new CrashedNextReplicaMessage(electionMessage, nextRef));
        return temp;
    }

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
                history.add(update);//TODO maybe need to create a new object like this
                log(this.getLastUpdate().toString());
            } // TODO mmh, maybe we should check if the update is already in the history, just to be sure 
        }
    }

    // --------------------- UTILITY FUNCTION ---------------------
    private Update getLastUpdate() {
        // if (this.id == 3) {
        //     return new Update(new MessageIdentifier(0, 3), -1);
        // }

        if (history.size() == 0) {
            return new Update(new MessageIdentifier(0, 0), -1);
        }

        return history.get(history.size() - 1);
    }

    private void deliverUpdate(MessageIdentifier messageIdentifier) {
        this.replicaVariable = temporaryBuffer.get(messageIdentifier).value;
        this.lastUpdate = messageIdentifier;
        temporaryBuffer.remove(messageIdentifier);
        history.add(new Update(messageIdentifier, this.replicaVariable));
        log(this.getLastUpdate().toString());
    }

    private void multicast(Serializable message) {

        for (ActorRef peer : peers) {
            if (peer != getSelf()) {
                peer.tell(message, getSelf());
            }
        }
    }

    private void removePeer(ActorRef peer) {
        boolean removed = this.peers.remove(peer);
        if (!removed) {
            log("Peer " + peer.path().name() + " already removed");
            return;
        }
        // for (Cancellable ack : this.acksElectionTimeout) {
        // log("canceling ack for " + peer.path().name() + " since it is crashed");
        // ack.cancel();
        // }
        // this.acksElectionTimeout.clear();
        String s = "";
        if (this.id == 1) {
            for (ActorRef p : this.peers) {
                s += "" + p.path().name() + " | ";
            }
            log("Alive peers: " + s);
        }
        // this.quorumSize = (int) Math.floor(peers.size() / 2) + 1;
        int myIndex = peers.indexOf(getSelf());
        this.nextRef = peers.get((myIndex + 1) % peers.size());
        log("Removed peer " + peer.path().name() + " my new next replica is " + this.nextRef.path().name());


    }

    private void forwardElectionMessageWithAck(ElectionMessage electionMessage, UUID oldAckIdentifier) {
        forwardElectionMessage(electionMessage, oldAckIdentifier);
    }
    private void forwardElectionMessageWithoutAck(ElectionMessage electionMessage) {
        forwardElectionMessage(electionMessage, null);
    }

    private void forwardElectionMessage(ElectionMessage electionMessage, UUID oldAckIdentifier) {

        // node 4 should receive the election message from node 3 and crash before processing it
        // so the entire election process should be blocked until the election timeout
        // if (this.id == 4) {
        //     crash(4);
        //     return;
        // }

        // this.nextRef.tell(electionMessage, getSelf());
        tellWithDelay(this.nextRef, getSelf(), electionMessage);
        log("Sent election message to " + this.nextRef.path().name() + " : " + electionMessage.toString());
        if (oldAckIdentifier != null) {
            log("Sent ACK to previous " + getSender().path().name() + " with ACK id: " + oldAckIdentifier);
            tellWithDelay(getSender(), getSelf(), new AckElectionMessage(oldAckIdentifier));
        }
        Cancellable electionTimeout = scheduleElectionTimeout(electionMessage, this.nextRef);
        this.acksElectionTimeout.put(electionMessage.ackIdentifier, electionTimeout);
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

        if (this.id == 1) {
            log ("lastUpdate: " + lastUpdate + " maxUpdate: " + maxUpdate + " amIMoreUpdated: " + amIMoreUpdated);
        }

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
    }

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

        cancelAllTimeouts();

        isCrashed = true;
        log("i'm crashing " + id);
        getContext().become(crashed());

    }
    private void cancelAllTimeouts() {
        // Replica timer to receive the heartbeat from the coordinator
        if (this.heartbeatTimeout != null) {
            this.heartbeatTimeout.cancel();
        }
        
        // if it is a coordinator cancel the heartbeat
        if (this.sendHeartbeat != null && this.coordinatorRef.equals(getSelf())) {
            this.sendHeartbeat.cancel();
        }

        for (Cancellable timer : this.acksElectionTimeout.values()) {
            timer.cancel();
        }
        for (Cancellable timer : this.afterForwardTimeout) {
            timer.cancel();
        }
        for (Cancellable timer : this.afterUpdateTimeout) {
            timer.cancel();
        }

        // Global election timer
        if (this.electionTimeout != null) {
            this.electionTimeout.cancel();
        }
    }
    private void tellWithDelay(ActorRef receiver, ActorRef sender, Serializable message) {
        try {
            Thread.sleep(rnd.nextInt(messageMaxDelay));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        receiver.tell(message, sender);
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

    private void emptyQueue() {
        if (writeRequestMessageQueue.isEmpty()) {
            return;
        }
        log("emptying the queue" + writeRequestMessageQueue.toString());
        for (int i = 0; i < writeRequestMessageQueue.size(); i++) {
            //just to trigger the write request to write the value that is in the queue
            //int value = messageQueue.remove(0);
            WriteRequest writeRequest = new WriteRequest(-1, false); // here it is mandatory to trigger, because otherwise a value sent by the client could be in the middle of these reqeust, and the order is not preserved anymore
            tellWithDelay(getSelf(), getSelf(), writeRequest);
        }
    }

    private void updateOutdatedReplicas(Map<Integer, MessageIdentifier> quorumState) {
        // not multicasting because each replica may have different updates
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
                // replica.tell(updateHistoryMessage, getSelf());
                this.tellWithDelay(replica, getSelf(), updateHistoryMessage);
            }

        }

    }

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

//scenario
/*
now the sequential consistency is guaranteed also during the leader election, since we store all the messages in a queue, 
and we alway process the message in that queue, so the order is preserved


 */
// TODO share the crash of a replica with all the other replicas
// during election only the previous node of the crashed one will modify the peer list
// is this a problem for other replicas? (multicast/quorum)  // SOLVED: during a leader election we do not care about a reoplica that is not a leader and crash

// TODO once the coordinator is elected, we need to provide other replicas with the missing updates
// or we are already doing this? DONE
// if a client X send (a,b,c) to replica Y, every replica's history need to have that order (or they can have another order, but all the same)? Now if a client is interacting with a replica we have guaranteed the sequential consistency (even during a leader election)

//QUESTION
// do we need that an message received by any replica is eventually delivered (to all the replicas) (same as the scenario during the elader election)
// for instance, when a replica receive a message, forward to the coordinator, and the coordinator crashes, the message is lost forever, should we able to guarantee that that message will eventually de delivered? (should we able to retrieve it?)

// TODO do we need the inElection behavior? or we can just use the isElectionRunning flag?


// do we need to drop the message whiel in leader election if they are not already writte in the history???
// the problem is that if we drop: if no one committed (writeok) that message, that message is lost forever
// if we don't drop we may have a duplicate
