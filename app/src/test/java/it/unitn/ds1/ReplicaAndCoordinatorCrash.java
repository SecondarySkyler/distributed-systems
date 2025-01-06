package it.unitn.ds1;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import it.unitn.ds1.Replicas.types.Crash;
import it.unitn.ds1.SimulationController.SimulationController;

public class ReplicaAndCoordinatorCrash {
    String folderName;

    /**
     * This test ensures that the system is able to serialize a write request after two crashes.
     * In particular:
     * 1. A replica should forward the write request to the coordinator.
     * 2. Two replicas should crash.
     * 3. The coordinator should multicast an update.
     * 4. The coordinator should crash.
     * 5. The new coordinator should handle the pending update and send the writeOK message.
     */
    @Test
    void testReplicaAndCoordinatorCrash() {
        Crash[] crashes = {Crash.NO_CRASH, Crash.REPLICA_AFTER_FORWARD_MESSAGE, Crash.NO_CRASH, Crash.NO_CRASH, Crash.COORDINATOR_AFTER_UPDATE_MESSAGE};
        SimulationController simulationController = new SimulationController(1, 5, crashes, "replicas_coord_crash");

        simulationController.runWithoutStop();
        simulationController.tellClientSendWriteRequest(0, 1, 10);
        simulationController.stopAfter(20000);

        folderName = simulationController.logFolderName;
        File folder = new File(this.folderName);

        if (folder.isDirectory()) {
            for (File file : folder.listFiles()) {
                if (file.getName().contains("client")) {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(), new ArrayList<>(List.of("write req to replica replica_1 with value 10")))){
                        assertTrue(false);
                    }
                } else if (file.getName().contains("replica_4")) {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(), new ArrayList<>(List.of("Received write request from, replica_1 with value: 10")))){
                        assertTrue(false);
                    }   
                } else if (file.getName().contains("replica_3") ) {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(), new ArrayList<>(
                        List.of("Received update <0:0> with value: 10 from the coordinator replica_4",
                        "multicasting sychronization, i won this electionElectionMessage",
                        "update <1:0> 10")))){
                        assertTrue(false);
                    } 
                } else if (file.getName().contains("replica_1")) {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(), new ArrayList<>(
                        List.of("Received synchronization message from replica_4",
                        "Emptying the queue[WriteRequest(10)]",
                        "I'm crashed, I cannot process messages")))){
                        assertTrue(false);
                    }
                } else {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(), new ArrayList<>(
                        List.of("update <1:0> 10")))){
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
