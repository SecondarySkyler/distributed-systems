package it.unitn.ds1.SimulationController;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import it.unitn.ds1.Client.Client;
import it.unitn.ds1.Replicas.Replica;
import it.unitn.ds1.Replicas.types.Crash;

public class SimulationController {
    ActorSystem clientSystem;
    ActorSystem replicaSystem;
    String logFolderName;
    List<ActorRef> replicas;
    List<ActorRef> clients;

    public SimulationController() {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String baseDir = "logs";
        this.logFolderName = baseDir + File.separator + "run_" + timestamp;

        this.clientSystem = ActorSystem.create("clientSystem");
        this.replicaSystem = ActorSystem.create("replicaSystem");

    }

    public void start() {
        // GroupInfo groupInfo = new GroupInfo(replicas);
        // for (ActorRef replica : replicas) {
        //     replica.tell(groupInfo, ActorRef.noSender());
        // }

        // for (ActorRef client : clients) {
        //     client.tell(groupInfo, ActorRef.noSender());
        // }
    }

    public List<ActorRef> createClients(int numClients) {
        for (int i = 0; i < numClients; i++) {
            clients.add(clientSystem.actorOf(Client.props(i, logFolderName), "client_" + i));
        }
        return clients;
    }

    public List<ActorRef> createReplicas(int numReplicas, Crash[] crashList) {
        List<ActorRef> replicas = new ArrayList<>();
        for (int i = 0; i < numReplicas; i++) {
            replicas.add(replicaSystem.actorOf(Replica.props(i, crashList[i], logFolderName), "replica_" + i));
        }
        return replicas;
    }

    public void sendReadRequest(int clientId) {

    }

}