package it.unitn.ds1;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import it.unitn.ds1.Replicas.types.Crash;
import it.unitn.ds1.SimulationController.SimulationController;

public class ReplicasCrashBeforeAck {
    String folderName;

    /**
     * This test ensures that the system is able to reach the quorum after multiple crashes.
     * In particular, the represented scenario wants to check the serialization of a write request.
     * A replica should forward the write request to the coordinator.
     * The coordinator should multicast an update.
     * 2 designated replicas should crash and won't be able to send an ack.
     * The coordinator should be able to reach the quorum and send the writeOK message.
     */
    @Test
    void testReplicasCrashBeforeAck() {
        Crash[] crashes = {Crash.NO_CRASH, Crash.NO_CRASH, Crash.REPLICA_ON_UPDATE_MESSAGE, Crash.REPLICA_ON_UPDATE_MESSAGE, Crash.NO_CRASH};
        SimulationController simulationController = new SimulationController(1, 5, crashes, "replicas_crash_after_update");

        simulationController.runWithoutStop(); 
        simulationController.tellClientSendWriteRequest(0, 1, 10);
        simulationController.stopAfter(10000);

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
                } else if (file.getName().contains("replica_3") || file.getName().contains("replica_2")) {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(), new ArrayList<>(
                        List.of("Received update <0:0> with value: 10 from the coordinator replica_4", "i'm crashing")))){
                        assertTrue(false);
                    } 
                } else {
                    if (!SimulationController.checkUpdateinHistory(file.getAbsolutePath(), new ArrayList<>(
                        List.of("update <0:0> 10")))){
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
