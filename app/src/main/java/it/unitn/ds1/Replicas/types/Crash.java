package it.unitn.ds1.Replicas.types;

public enum Crash {
    NO_CRASH,

    /**
     * The replica won't crash due to missing heartbeats
     * (this is done to test other timers)
     */
    NO_HEARTBEAT,
    //leader crashes
    /**
     * Leader crashes before sending the update message, the replica that forwarded the message, should timeout and start an election.
     * Once a new coordinator is elected, the replica (that could also be the new coordinator) should take care of the unstable write request.
     */
    COORDINATOR_BEFORE_UPDATE_MESSAGE,
          /**
     * Leader crashes after sending the update message, the replica that forwarded the message, should timeout and start an election.
     * Once a new coordinator is elected, the replica (that could also be the new coordinator) should take care of the unstable write request.
     */
    COORDINATOR_AFTER_UPDATE_MESSAGE,  
    /**
    * Leader crashes before sending the writeok message, a replica that has sent an ack should timeout and start an election
    */
    COORDINATOR_BEFORE_WRITEOK_MESSAGE,

    /**
    * Leader crashes after N write ok messages
    */
    COORDINATOR_AFTER_N_WRITE_OK,
        
    /**
    * Coordinator will crash after sending heartbeat message
    */
    COORDINATOR_AFTER_HEARTBEAT,

    /**
     * Coordinator will crash while multicasting the update message
     */
    COORDINATOR_CRASH_MULTICASTING_UPDATE,

    /**
    * When this flag is used, it will ignore the write ok message e won't write the value
    */
    NO_WRITE,

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
     * Replica will crash after receiving an update message
     */
    REPLICA_ON_UPDATE_MESSAGE,
    
    /** 
     * Replica will crash after forwarding the write request message
     */
    REPLICA_AFTER_FORWARD_MESSAGE
    
}
