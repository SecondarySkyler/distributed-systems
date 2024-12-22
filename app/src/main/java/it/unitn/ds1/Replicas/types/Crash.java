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
    /*
     when this flag is used, it will ignore the write ok message e won't write the value
     */
    NO_WRITE,
    /*
    leader crashes after N write ok messages
     */
    AFTER_N_WRITE_OK,
    
    /**
    Coordinator will crash after sending heartbeat message
    */
    COORDINATOR_AFTER_HEARTBEAT,

    /**
     * Replica will crash after receiving an election message
     */
    REPLICA_ON_ELECTION_MESSAGE,

    /**
     * Replica will crash after forwarding election message but before acking the sender
     */
    REPLICA_BEFORE_ACK_ELECTION_MESSAGE,

    /**
     * Replica will crash after acking the sender of the election message
     */
    REPLICA_AFTER_ACK_ELECTION_MESSAGE,

    /**
     * Replica will crash before forwarding election message
     * !!! This crash is a temporary solution to make the TwoConsecutiveReplicasCrash test work
     */
    REPLICA_BEFORE_FORWARD_ELECTION_MESSAGE,

    /**
     * Replica will crash after receiving an uodate message
    */
    REPLICA_ON_UPDATE_MESSAGE,



}
