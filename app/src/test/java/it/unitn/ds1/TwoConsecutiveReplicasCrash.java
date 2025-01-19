package it.unitn.ds1;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import it.unitn.ds1.Replicas.types.Crash;
import it.unitn.ds1.SimulationController.SimulationController;

public class TwoConsecutiveReplicasCrash {
    
    /**
     * This test checks if the election algorithm works correctly.
     * Specifically, when two replicas crash consecutively.
     * The tested scenario is the following:
     * Replica_2 sends an election message to replica_3;
     * Replica_3 forwards the election message to replica_4, acks replica_2 and crashes;
     * Replica_4 receives the election message from replica_3 and crashes.
     * In this way we should have that the election is "stuck", the global timer should expire.
     * A new election should start and replica_2 should be the new leader.
     */
    @Test
    void testTwoConsecutiveCrash() {
        Crash[] crashes = {Crash.NO_CRASH, Crash.NO_CRASH, Crash.NO_CRASH, Crash.REPLICA_AFTER_ACK_ELECTION_MESSAGE, Crash.REPLICA_ON_ELECTION_MESSAGE};
        SimulationController simulationController = new SimulationController(0, 5, crashes, "two_consecutive_crash");

        simulationController.runSimulation(10000);

        String folderName = simulationController.logFolderName;
        File folder = new File(folderName);

        if (folder.isDirectory()) {
            File[] files = folder.listFiles();
            for (File file : files) {
                if (file.getName().contains("replica_2")) {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(), new ArrayList<>(
                        List.of("Sent election message to replica_3", 
                        "Starting election, reason: Global election timer expired",
                        "multicasting sychronization, i won this electionElectionMessage"
                        )))) {
                        assertTrue(false);
                    }
                } else if (file.getName().contains("replica_3")) {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(), new ArrayList<>(List.of("Received election message from replica_2", "I'm crashed, I cannot process messages")))) {
                        assertTrue(false);
                    }
                } else if (file.getName().contains("replica_4")) {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(), new ArrayList<>(List.of("Received election message from replica_3", "I'm crashed, I cannot process messages")))){
                        assertTrue(false);
                    } 
                } else {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(), new ArrayList<>(List.of("Starting election, reason: Global election timer expired", "Received HB from coordinator replica_2")))){
                        assertTrue(false);
                    }
                }
            }            
        } else {
            System.out.println("The folder does not exist");
            assertTrue(false);
        }
    }
}
