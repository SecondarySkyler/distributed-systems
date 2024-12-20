package it.unitn.ds1.SimulationController;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import it.unitn.ds1.Client.Client;
import it.unitn.ds1.Messages.GroupInfo;
import it.unitn.ds1.Replicas.Replica;
import it.unitn.ds1.Replicas.messages.PrintHistory;
import it.unitn.ds1.Replicas.types.Crash;
import it.unitn.ds1.TestMessages.SendWriteRequestMessage;

public class SimulationController {
    private final ActorSystem clientSystem;
    private final ActorSystem replicaSystem;
    public final String logFolderName;
    public List<ActorRef> replicas;
    public List<ActorRef> clients;

    public SimulationController(int numClients, int numReplicas, Crash[] crashList, String test_name, boolean isTestMode) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String baseDir = "logs";
        this.logFolderName = baseDir + File.separator + "run_"+ test_name +" " + timestamp;

        this.clientSystem = ActorSystem.create("clientSystem");
        this.replicaSystem = ActorSystem.create("replicaSystem");
        this.replicas = new ArrayList<>();  
        this.clients = new ArrayList<>();
        
        for (int i = 0; i < numClients; i++) {
            this.clients.add(this.clientSystem.actorOf(Client.props(i, logFolderName, isTestMode), "client_" + i));
        }

        for (int i = 0; i < numReplicas; i++) {
            this.replicas.add(replicaSystem.actorOf(Replica.props(i, logFolderName, crashList[i]), "replica_" + i));
        }
    }

    public SimulationController(int numClients, int numReplicas, Crash[] crashList, String test_name) {
        this(numClients, numReplicas, crashList, test_name, true);
    }

    public void run() {
        GroupInfo groupInfo = new GroupInfo(replicas);
        for (ActorRef replica : replicas) {
            replica.tell(groupInfo, ActorRef.noSender());
        }

        for (ActorRef client : clients) {
            client.tell(groupInfo, ActorRef.noSender());
        }

        try {
            System.out.println("--- Press Enter to stop the simulation ---");
            System.in.read(); // Waits for Enter key press
        } catch (IOException ioe) {
        } finally {
            PrintHistory printHistory = new PrintHistory();
            for (ActorRef replica : replicas) {
                replica.tell(printHistory, ActorRef.noSender());
            }
            replicaSystem.terminate();
            clientSystem.terminate();
        }
    }

    public void runWithoutStop() {
        GroupInfo groupInfo = new GroupInfo(replicas);
        for (ActorRef replica : replicas) {
            replica.tell(groupInfo, ActorRef.noSender());
        }

        for (ActorRef client : clients) {
            client.tell(groupInfo, ActorRef.noSender());
        }
    }

    /**
     * Method used to tell a specific client to send a write request to a specific replica with a given value
     * @param clientIndex the index of the client in this.clients
     * @param replicaIndex the index of the replica in this.replicas
     * @param value the value to write
     */
    public void tellClientSendWriteRequest(int clientIndex, int replicaIndex, int value) {
        if (clientIndex < this.clients.size() && replicaIndex < this.replicas.size()) {
            ActorRef client = this.clients.get(clientIndex);
            client.tell(new SendWriteRequestMessage(value, replicaIndex), ActorRef.noSender());
        }
    }

    /**
     * Start the simulation by sending to the replicas and clients the list of replicas
     * This will trigger the OnGroupInfo message handler which will start the election process
     * 
     * @param waitTime the time to wait before stopping the simulation
     * 
     */
    public void runSimulation(int waitTime) {
        GroupInfo groupInfo = new GroupInfo(replicas);
        for (ActorRef replica : replicas) {
            replica.tell(groupInfo, ActorRef.noSender());
        }

        for (ActorRef client : clients) {
            client.tell(groupInfo, ActorRef.noSender());
        }

        try {
            Thread.sleep(waitTime); // This ensure that the system complete what it has to do
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            PrintHistory printHistory = new PrintHistory();
            for (ActorRef replica : replicas) {
                replica.tell(printHistory, ActorRef.noSender());
            }
            this.clientSystem.terminate();
            this.replicaSystem.terminate();
        }
    }

    public void stopAfter(int waitTime) {
        try {
            Thread.sleep(waitTime); // This ensure that the system complete what it has to do
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            PrintHistory printHistory = new PrintHistory();
            for (ActorRef replica : replicas) {
                replica.tell(printHistory, ActorRef.noSender());
            }
            this.clientSystem.terminate();
            this.replicaSystem.terminate();
        }
    }
    
    static public boolean checkStringsInFile(String filePath, ArrayList<String> searchString) {

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                searchString.removeIf(line::contains);
                if (searchString.isEmpty()) {
                    
                    return true;
                }       
            }
            return false;

        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    } 

}