package it.unitn.ds1;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import it.unitn.ds1.Replicas.types.Crash;
import it.unitn.ds1.SimulationController.SimulationController;

class CoordinatorAlignAllReplicas{
    String folderName;

    /**
     * This test ensures that the system is able to reach the quorum after multiple crashes.
     * In particular, the represented scenario wants to check the serialization of a write request.
     * A replica should forward the write request to the coordinator.
     * The coordinator should multicast an update.
     * 2 designated replicas should crash.
     * The coordinator should be able to reach the quorum and send the writeOK message.
     */
    @Test
    void testCoordinatorAlignAllReplicas() {
        Crash[] crashes = { Crash.NO_WRITE, Crash.NO_WRITE, Crash.NO_CRASH, Crash.NO_WRITE,
                Crash.COORDINATOR_AFTER_N_WRITE_OK };
        SimulationController simulationController = new SimulationController(1, 5, crashes,
                "coordinator_align_all_replica");

        simulationController.runWithoutStop(); 
        simulationController.tellClientSendWriteRequest(0, 1, 10);
        simulationController.tellClientSendWriteRequest(0, 1, 11);
        simulationController.tellClientSendWriteRequest(0, 1, 12);
        simulationController.stopAfter(15000);

        folderName = simulationController.logFolderName;
        File folder = new File(this.folderName);

        if (folder.isDirectory()) {
            for (File file : folder.listFiles()) {
                if (file.getName().contains("client")) {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(), new ArrayList<>(List.of("write req to replica replica_1 with value 10","write req to replica replica_1 with value 10","write req to replica replica_1 with value 11","write req to replica replica_1 with value 12")))){
                        assertTrue(false);
                    }
                } else if (file.getName().contains("replica_4")) {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(),
                            new ArrayList<>(List.of("Reached quorum for message <0:0>",
                                    "Reached quorum for message <0:1>", "Reached quorum for message <0:2>")))) {
                        assertTrue(false);
                    }
                } else if (file.getName().contains("replica_2")) {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(),
                            new ArrayList<>(List.of("Sending updates to")))) {
                        assertTrue(false);
                    }
                    if (!SimulationController.checkUpdateinHistory(file.getAbsolutePath(),
                            new ArrayList<>(List.of("update <0:0> 10", "update <0:1> 11", "update <0:2> 12")))) {
                        assertTrue(false);
                    }
                } else {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(),
                            new ArrayList<>(List.of(
                                    "Received update history message from replica_2 [update <0:1> 11, update <0:2> 12]")))) {
                        assertTrue(false);
                    }
                    if (!SimulationController.checkUpdateinHistory(file.getAbsolutePath(),
                            new ArrayList<>(List.of("update <0:0> 10", "update <0:1> 11", "update <0:2> 12")))) {
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