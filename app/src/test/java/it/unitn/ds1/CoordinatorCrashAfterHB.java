/*
 * This Java source file was generated by the Gradle 'init' task.
 */
package it.unitn.ds1;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import it.unitn.ds1.Replicas.types.Crash;
import it.unitn.ds1.SimulationController.SimulationController;


public class CoordinatorCrashAfterHB {
    String folderName;
    /**
     * This test checks if the election algorithm works correctly.
     * After the first heartbeat, the leader crashes.
     * The replicas should detect the crash and start a new election.
     * By assuming 5 replicas, initially the leader should be replica_4.
     * After the crash, the new leader should be replica_3.
     */
    @Test
    void testCrashAfterHeartbeat() {
        Crash[] crashes = {Crash.NO_CRASH, Crash.NO_CRASH, Crash.NO_CRASH, Crash.NO_CRASH, Crash.COORDINATOR_AFTER_HEARTBEAT};
        SimulationController simulationController = new SimulationController(0, 5, crashes, "crash_after_one_heartbeat", false);

        simulationController.runSimulation(15000);

    
        folderName = simulationController.logFolderName;

        File folder = new File(this.folderName);
    
        if (folder.isDirectory()) {
            File[] files = folder.listFiles();
            for (File file : files) {
                if (file.getName().contains("client")) {
                    continue;
                }
                if (file.getName().contains("replica_4")) {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(), new ArrayList<>(List.of("i'm crashing", "multicasting sychronization, i won this electionElectionMessage")))){
                        assertTrue(false);
                    }   
                } else if (file.getName().contains("replica_3")) {
                     if (!SimulationController.checkStringsInFile(file.getAbsolutePath(), new ArrayList<>(List.of("Received HB from coordinator replica_4", "multicasting sychronization, i won this electionElectionMessage")))){
                        assertTrue(false);
                    } 
                } else {
                    if (!SimulationController.checkStringsInFile(file.getAbsolutePath(), new ArrayList<>(List.of("Received HB from coordinator replica_4", "Received HB from coordinator replica_3")))){
                        assertTrue(false);
                    }
                }
            }
            assertTrue(true);
        } else {
            System.out.println("The folder does not exist");
            assertTrue(false);
        }

    }
    
   
}