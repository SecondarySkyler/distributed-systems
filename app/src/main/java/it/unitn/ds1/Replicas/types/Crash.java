package it.unitn.ds1.Replicas.types;

public enum Crash {
    NO_CRASH,
    //leader crashes
    /**
     * Leader crashes before sending the update message, the replica that forwarded the message, should timeout and start an election
     */
    BEFORE_UPDATE_MESSAGE,
    /**
     * Leader crashes before sending the writeok message, a replica that has sent an ack should timeout and start an election
     */
    BEFORE_WRITEOK_MESSAGE,
    AFTER_WRITEOK_MESSAGE,
    // //leader crashes
    // public final boolean afterReceiveForwardMessage;
    // public final boolean afterSentUpdateMessage;
    // public final boolean afterReceiveAckMessage;
    // public final boolean afterWriteOKMessage;

    // public final boolean afterHeartbeatMessage;

    // public final boolean beforeElectionMessage;
    // public final boolean afterForwardElectionMessageBeforeAck;
    // public final boolean afterElectionMessage;

    /**
     * Replica will crash after receiving an election message
     */
    REPLICA_ON_ELECTION_MESSAGE,

    /**
     * Replica will crash after forwarding election message but before acking the sender
     */
    REPLICA_BEFORE_ACK_ELECTION_MESSAGE,

    /**
     * Coordinator will crash after sending heartbeat message
     */
    COORDINATOR_AFTER_HEARTBEAT,

}
