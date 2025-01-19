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
     * This test ensures that the current coordinator is able to multicast the missing updates to each replica.
     * In particular, the represented scenario avoids on purpose 3 replicas (replica_0, replica_1, replica_3) to deliver more than 1 update.
     * The initial coordinator is replica_4. It will eventually deliver N_WRITE_OK messages before crashing.
     * The new coordinator will be replica_2. Once elected, it should multicast the Synchronization message containing the missing updates.
     * The test uses N_WRITE_OK = 3, so after replica_4 sends the third WriteOK, it crashes.
     * At this point, replica_2 will have its history up to date. Instead replica_0, replica_1, and replica_3 will miss the last updates (<0:0> 10, <0:1> 11, <0:2> 12).
     * They will be provided by replica_2 as soon as it becomes the new coordinator.
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