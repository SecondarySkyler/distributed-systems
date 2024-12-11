package it.unitn.ds1;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import it.unitn.ds1.Replicas.types.Crash;
import it.unitn.ds1.SimulationController.SimulationController;

public class ReplicaCrashAfterElectionMessage {
    
    /**
     * This test checks if the election algorithm works correctly.
     * After replica_3 has received an election message, it crashes.
     * Replica_2 won't receive the election ack from replica_3 and will start forward
     * the election message to the replica_4.
     * At the end, replica_4 should be the new leader.
     */
    @Test
    void testReplicaCrashAfterElectionMessage() {
        Crash[] crashes = {Crash.NO_CRASH, Crash.NO_CRASH, Crash.NO_CRASH, Crash.REPLICA_ON_ELECTION_MESSAGE, Crash.NO_CRASH};
        SimulationController simulationController = new SimulationController(0, 5, crashes, "crash_after_election_message");

        simulationController.runSimulation(10000);

        String folderName = simulationController.logFolderName;
        File folder = new File(folderName);

        if (folder.isDirectory()) {
            File[] files = folder.listFiles();
            for (File file : files) {
                if (file.getName().contains("replica_2")) {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(), new ArrayList<>(List.of("Sent election message to replica_3", "Didn't receive ACK", "Received HB from coordinator replica_4")))) {
                        assertTrue(false);
                    }
                } else if (file.getName().contains("replica_3")) {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(), new ArrayList<>(List.of("Received election message from replica_2", "I'm crashed, I cannot process messages")))) {
                        assertTrue(false);
                    }
                } else if (file.getName().contains("replica_4")) {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(), new ArrayList<>(List.of("multicasting sychronization, i won this electionElectionMessage")))){
                        assertTrue(false);
                    } 
                } else {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(), new ArrayList<>(List.of("Received HB from coordinator replica_4")))){
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
