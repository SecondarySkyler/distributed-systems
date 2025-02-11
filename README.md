# Distributed Systems
Project for the course Distributed Systems 2023/24.

## Table of Contents
1. [Project Overview](#project-overview)
   - [System Components](#system-components)
   - [Two-Phase Broadcast Protocol](#two-phase-broadcast-protocol)
   - [Total Order Broadcast](#total-order-broadcast)
   - [Coordinator Election](#coordinator-election)
   - [Key Features](#key-features)
2. [Repository Structure](#file-structure)
3. [Usage](#usage)
4. [Log Files](#log-files)
5. [Authors](#authors)
## Project Overview
The project is inspired by Apache ZooKeeper, which consist into building a system that allows to reliably update the state of nodes in
the distributed system by means of a coordinator relying on a Quorum.

### System Components

- **Replicas**: Each replica is an Akka actor responsible for maintaining a shared integer variable `v` and handling client requests.
- **Clients**: External actors that can issue **read** and **write** requests to any replica in the system.
- **Coordinator**: A special replica responsible for managing the two-phase broadcast protocol to ensure updates are applied in total order across all replicas.

### Two-Phase Broadcast Protocol
- **Phase 1**: The coordinator broadcasts an `UPDATE` message with the new value to all replicas and waits for `ACKs` from a quorum (majority of nodes).
- **Phase 2**: Once the quorum is reached, the coordinator broadcasts a `WRITEOK` message, and replicas apply the update.
- **Update Identification**: Each update is identified by a pair `⟨e, i⟩`, where `e` is the epoch (incremented on coordinator change) and `i` is a sequence number (resets to 0 for each new epoch).

### Total Order Broadcast
- Ensures that all correct processes deliver messages in the same order.
- **Properties**:
  - **Validity**: If the sender is correct, the message will eventually be delivered.
  - **Uniform Agreement**: If any process delivers a message, all correct processes will deliver it.
  - **Total Order**: If a correct process delivers message `m` before `m'`, all correct processes deliver `m` before `m'`.

### Coordinator Election
- **Ring-Based Protocol**: When the coordinator crashes, replicas elect a new coordinator using a ring-based election protocol.
- **Election Criteria**: The replica with the most recent update becomes the new coordinator. If multiple replicas are equally up-to-date, the one with the highest ID is chosen.
- **Synchronization**: The new coordinator broadcasts a `SYNCHRONIZATION` message to ensure all replicas are up-to-date.

### Key Features
- **Quorum-Based Update Protocol**: Ensures that updates are applied in the same order across all replicas, even in the presence of failures.
- **Coordinator Election**: Handles the election of a new coordinator using a simple ring-based protocol if the current coordinator crashes.
- **Crash Detection**: Implements a timeout-based mechanism to detect node crashes, ensuring system resilience.
- **Crash Management**: The system can handle crashes at any point and continue to operate, ensuring updates are still applied correctly.
- **Sequential Consistency**: Guarantees that clients interacting with the same replica always see updates in a consistent order.
- **Logging**: Records key protocol steps and events, such as updates, read requests, and coordinator elections, for debugging and verification.
- **Testing**: Includes multiple test cases to ensure all functionalities, including fault tolerance and consistency, are working correctly.

## Repository structure 
```
distributed-systems
│
├──── app/
│    ├── build/                            # Compiled files and build outputs
│    ├── logs/                             # Log files generated during execution
│    ├── src/                              # Source code directory
│    │    ├── main/java/it/unitn/ds1/
│    │    │   ├── Client/                  # Client-side logic
│    │    │   ├── Messages/                # Message related to both client and replica
│    │    │   ├── Replicas/                # Replica logic
│    │    │   ├── SimulationController/    # Controls the simulation flow
│    │    │   │
│    │    │   ├── TestMessages/            # Messagges used for testing
│    │    │   │         
│    │    │   ├── App.java                 # Main application entry point
│    │    │ 
│    │    └── test/java/it/unitn/ds1/      # Unit and integration tests
│    │
├──── gradle/                              # Gradle build configuration
├──── report/                              # Report of this project
├──── README.md project documentation
```

## Usage

### Installation and Dependencies
To run this project, you need the following dependencies installed on your system:
- **Java Development Kit (JDK)**: Version 17 or higher.
- **Gradle**: A build automation tool used for building and running the project. Ensure Gradle is installed and configured on your system.
- To install this project:
```
git clone git@github.com:SecondarySkyler/distributed-systems.git
```
### Running the Application
To run the application with the with the default setting, just run in the root directory:
```
gradle run
```
If you want to run one of the scenario we created just go in the `App.java` file and change the `current_crash` variable with one of the already provided _Crash[]_ arrays.
```
Crash[] current_crash = noCrashes; // Change scenario here
```


To the test:
```
gradle clean test
```

If you want to test a specific test case, you can run:
```
gradle clean test --tests NameOfTheTestClass
```
where _NameOfTheTestClass_ can be one of the classes's name provided in _/app/src/test/java/it/unitn/ds1/_

## Log files
Each time the executable is run (either with gradle run or gradle test) a set of log files are generated under the _/app/logs/_ folder.  
These can either be inspected manually or by using the Python script provided in the _/app/logs/checker.py_, which provide information about the consistency, equality and write request coverage.
```
python3 ./app/logs/checker.py
```
`N.B.` The checker will prompt a message asking you the folder you want to analyze (the default is the last folder in _app/logs_).  
For example:
```
python3 app/logs/checker.py
Specify a folder (or press Enter to use the most recent): run_normal_run_20250119_123520
```
Will execute the checks of the specific normal run.


## Authors

- [@Cristian Murtas](https://github.com/SecondarySkyler)
- [@Marco Wang](https://github.com/marco3724)