package it.unitn.ds1;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import it.unitn.ds1.Replicas.types.Crash;
import it.unitn.ds1.SimulationController.SimulationController;

public class CoordinatorCrashBeforeUpdate {
    String folderName;

    /**
     * Test the case where the coordinator crashes before sending the update to the replicas
     * The replica that forwarded the write request to the coordinator should expect an update
     * message from the coordinator.
     * The coordinator is going to crash before sending the update to the replicas.
     * The replica should detect the crash, start a new election and then send the write request to the new coordinator.
     */
    @Test
    void testCoordinatorCrashBeforeUpdate() {
        Crash[] crashes = { Crash.NO_CRASH, Crash.NO_CRASH, Crash.NO_CRASH, Crash.NO_CRASH,
                Crash.COORDINATOR_BEFORE_UPDATE_MESSAGE };
        SimulationController simulationController = new SimulationController(1, 5, crashes,
                "coordinator_before_update");

        simulationController.runWithoutStop();
        simulationController.tellClientSendWriteRequest(0, 3, 10);
        simulationController.stopAfter(10000);

        folderName = simulationController.logFolderName;
        File folder = new File(this.folderName);

        if (folder.isDirectory()) {
            for (File file : folder.listFiles()) {
                if (file.getName().contains("client")) {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(),
                            new ArrayList<>(List.of("write req to replica replica_3 with value 10")))) {
                        assertTrue(false);
                    }
                } else if (file.getName().contains("replica_4")) {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(),
                            new ArrayList<>(
                                    List.of("multicasting sychronization, i won this electionElectionMessage",
                                            "Received write request from, replica_3 with value: 10",
                                            "i'm crashing")))) {
                        assertTrue(false);
                    }
                } else if (file.getName().contains("replica_3")) {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(),
                            new ArrayList<>(
                                    List.of("multicasting sychronization, i won this electionElectionMessage")))) {
                        assertTrue(false);
                    }
                } else {
                    if (!SimulationController.checkUpdateinHistory(file.getAbsolutePath(),
                            new ArrayList<>(List.of("update <1:0> 10")))) {
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