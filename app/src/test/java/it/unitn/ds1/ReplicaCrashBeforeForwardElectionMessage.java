package it.unitn.ds1;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import it.unitn.ds1.Replicas.types.Crash;
import it.unitn.ds1.SimulationController.SimulationController;

public class ReplicaCrashBeforeForwardElectionMessage {

    /**
     * This test checks if the election algorithm works correctly.
     * Before replica_4 forward an election message, it crashes.
     * Replica_3 won't receive the election ack from replica_4 and will start forward the election message to the replica_0.
     * At the end, replica_3 should be the new leader.
     */
    @Test
    void testReplicaCrashBeforeForwardElectionMessage() {
        Crash[] crashes = { Crash.NO_CRASH, Crash.NO_CRASH, Crash.NO_CRASH, Crash.NO_CRASH,
                Crash.REPLICA_BEFORE_FORWARD_ELECTION_MESSAGE };
        SimulationController simulationController = new SimulationController(0, 5, crashes,
                "replica_crash_before_forward_election_message");

        simulationController.runSimulation(10000);

        String folderName = simulationController.logFolderName;
        File folder = new File(folderName);

        if (folder.isDirectory()) {
            File[] files = folder.listFiles();
            for (File file : files) {
                if (file.getName().contains("replica_3")) {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(),
                            new ArrayList<>(List.of("Sent election message to replica_4", "Didn't receive ACK",
                                    "multicasting sychronization, i won this electionElectionMessage")))) {
                        assertTrue(false);
                    }
                } else if (file.getName().contains("replica_4")) {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(),
                            new ArrayList<>(List.of("I'm crashed, I cannot process messages")))) {
                        assertTrue(false);
                    }
                } else {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(),
                            new ArrayList<>(List.of("Received HB from coordinator replica_3")))) {
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
