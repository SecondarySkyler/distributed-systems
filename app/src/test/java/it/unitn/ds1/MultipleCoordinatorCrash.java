package it.unitn.ds1;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import it.unitn.ds1.Replicas.types.Crash;
import it.unitn.ds1.SimulationController.SimulationController;

public class MultipleCoordinatorCrash {

    String folderName;

    /**
     * This test ensures that the system is able to write correctly in presence of multiple (in this case 3) crashes.
     * Three coordinator will crash.
     * The first coordinator will be able to send three writeOk message, while 2 of them won't be able to send the writeOk message
     * So the last coordinator will handle the writeOk of the remaining messages in the buffer.
     * At the end all the value sent by the client will be stored in the replica following a sequential consistency.
     */
    @Test
    void testMultipleCoordinatorCrash() {
        Crash[] crashes = { Crash.NO_CRASH, Crash.NO_CRASH, Crash.NO_CRASH, Crash.NO_CRASH, Crash.NO_CRASH,
                Crash.COORDINATOR_BEFORE_WRITEOK_MESSAGE,
                Crash.COORDINATOR_BEFORE_WRITEOK_MESSAGE, Crash.COORDINATOR_AFTER_N_WRITE_OK };
        SimulationController simulationController = new SimulationController(1, 8, crashes,
                "multiple_coordinator_crash");

        simulationController.runWithoutStop();
        simulationController.tellClientSendWriteRequest(0, 1, 10);
        simulationController.tellClientSendWriteRequest(0, 1, 11);
        simulationController.tellClientSendWriteRequest(0, 1, 12);
        simulationController.tellClientSendWriteRequest(0, 1, 13);
        simulationController.tellClientSendWriteRequest(0, 1, 14);
        simulationController.tellClientSendWriteRequest(0, 1, 15);
        simulationController.tellClientSendWriteRequest(0, 1, 16);
        simulationController.stopAfter(30000);

        folderName = simulationController.logFolderName;
        File folder = new File(this.folderName);

        if (folder.isDirectory()) {
            for (File file : folder.listFiles()) {
                if (file.getName().contains("client")) {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(),
                            new ArrayList<>(List.of("write req to replica replica_1 with value 13",
                                    "write req to replica replica_1 with value 10",
                                    "write req to replica replica_1 with value 11",
                                    "write req to replica replica_1 with value 12",
                                    "write req to replica replica_1 with value 14",
                                    "write req to replica replica_1 with value 15",
                                    "write req to replica replica_1 with value 16")))) {
                        assertTrue(false);
                    }
                } else if (this.replicaIsCoordinator(file.getName())) {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(),
                            new ArrayList<>(List.of("multicasting sychronization, i won this election")))) {
                        assertTrue(false);
                    }
                } else {
                    if (!SimulationController.checkUpdateinHistory(file.getAbsolutePath(),
                            new ArrayList<>(List.of("10", "11", "12",
                                    "13", "14", "15", "16")))) {
                        assertTrue(false);
                    }
                }

            }
        } else {
            System.out.println("The folder does not exist");
            assertTrue(false);
        }
    }

    private boolean replicaIsCoordinator(String replicaName) {
        String[] coordinatorName = { "replica_7", "replica_6", "replica_5", "replica_4" };
        for (String name : coordinatorName) {
            if (replicaName.contains(name)) {
                return true;
            }
        }
        return false;
    }

}
