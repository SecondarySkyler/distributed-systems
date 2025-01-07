package it.unitn.ds1;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import it.unitn.ds1.Replicas.types.Crash;
import it.unitn.ds1.SimulationController.SimulationController;

public class CoordinatorCrashDuringUpdateMulticast {
    String folderName;

    /**
     * This test ensures that the system is able to serialize a write request.
     * In particular:
     * 1. replica_0 should forward the write request to the coordinator.
     * 2. The coordinator should multicast an update and crash after the first one is sent.
     * (Technically by doing so, only replica_0 should receive the update)
     * The new coordinator will be elected (won't be replica_0) and should be able to reach the quorum and send the writeOK message.
     */
    @Test
    void testCoordinatorCrashDuringUpdateMulticast() {
        Crash[] crashes = { Crash.NO_CRASH, Crash.NO_CRASH, Crash.NO_CRASH, Crash.NO_CRASH, Crash.COORDINATOR_CRASH_MULTICASTING_UPDATE };
        SimulationController simulationController = new SimulationController(1, 5, crashes,"coordinator_crash_update_multicast");

        simulationController.runWithoutStop();
        simulationController.tellClientSendWriteRequest(0, 0, 10);
        simulationController.stopAfter(10000);

        folderName = simulationController.logFolderName;
        File folder = new File(this.folderName);

        if (folder.isDirectory()) {
            for (File file : folder.listFiles()) {
                if (file.getName().contains("client")) {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(), new ArrayList<>(List.of("write req to replica replica_0 with value 10")))){
                        assertTrue(false);
                    }
                } else if (file.getName().contains("replica_4")) {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(), new ArrayList<>(
                        List.of("Received write request from, replica_0 with value: 10",
                        "I'm crashed, I cannot process messages")))){
                        assertTrue(false);
                    }   
                } else if (file.getName().contains("replica_3") ) {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(), new ArrayList<>(
                        List.of("multicasting sychronization, i won this electionElectionMessage",
                        "Adjusted Pending updates",
                                    "update <0:0> 10")))) {
                        assertTrue(false);
                    } 
                } else if (file.getName().contains("replica_2") || file.getName().contains("replica_1")) {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(), new ArrayList<>(
                            List.of("Adjusted Pending updates", "update <0:0> 10")))) {
                        assertTrue(false);
                    }
                } else {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(), new ArrayList<>(
                            List.of("update <0:0> 10")))) {
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
