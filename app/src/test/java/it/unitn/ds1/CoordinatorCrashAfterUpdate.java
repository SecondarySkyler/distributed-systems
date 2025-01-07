package it.unitn.ds1;
import org.junit.jupiter.api.Test;

import it.unitn.ds1.Replicas.types.Crash;
import it.unitn.ds1.SimulationController.SimulationController;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
public class CoordinatorCrashAfterUpdate {
    String folderName;

    /**
     * This test ensures that the system is able to recover from a crash after an update.
     * In particular, the represented scenario wants to check the serialization of a write request.
     * A replica should forward the write request to the coordinator.
     * The coordinator should multicast an update and crash.
     * The replicas should detect the crash and start a new election.
     * When the new leader is elected, it should handle the unstable update.
     */
    @Test
    void testCrashAfterUpdate() {
        Crash[] crashes = { Crash.NO_CRASH, Crash.NO_CRASH, Crash.NO_CRASH, Crash.NO_CRASH,
                Crash.COORDINATOR_AFTER_UPDATE_MESSAGE };
        SimulationController simulationController = new SimulationController(1, 5, crashes, "crash_after_update");

        simulationController.runWithoutStop(); // This is needed because I have to interact with the system (non-blocking)

        // Client 0 will send a write request to replica 2 with value 10
        simulationController.tellClientSendWriteRequest(0, 2, 10);

        simulationController.stopAfter(10000); // Stops after 10 secs

        folderName = simulationController.logFolderName;
        File folder = new File(this.folderName);
        boolean restartedDueToMissingWriteOK = false;

        if (folder.isDirectory()) {
            File[] files = folder.listFiles();
            for (File file : files) {
                if (SimulationController.checkStringsInFile(file.getAbsolutePath(), new ArrayList<>(List.of("didn't receive writeOK message from coordinator for message <0:0> value: 10")))) {
                    restartedDueToMissingWriteOK = true;
                }

                if (file.getName().contains("client")) {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(), new ArrayList<>(List.of("write req to replica replica_2 with value 10")))){
                        assertTrue(false);
                    }
                } else if (file.getName().contains("replica_4")) {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(), new ArrayList<>(List.of("Received write request from, replica_2 with value: 10", "i'm crashing")))){
                        assertTrue(false);
                    }   
                } else if (file.getName().contains("replica_3")) {
                     if (!SimulationController.checkStringsInFile(file.getAbsolutePath(), new ArrayList<>(
                             List.of("Received synchronization message from replica_4", "update <0:0> 10")))) {
                        assertTrue(false);
                    } 
                } else {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(), new ArrayList<>(
                        List.of("Received synchronization message from replica_4", 
                                    "Received synchronization message from replica_3", "update <0:0> 10")))) {
                        assertTrue(false);
                    }
                }
            }
            assertTrue(restartedDueToMissingWriteOK);
        } else {
            System.out.println("The folder does not exist");
            assertTrue(false);
        }
    }
}
