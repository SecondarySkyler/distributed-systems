# Distributed Systems
project is inspired by Apache ZooKeeper, which consist into building a system that allows to reliably update the state of nodes in
the distributed system by means of a coordinator
## Authors

- [@Cristian Murtas](https://github.com/SecondarySkyler)
- [@Marco Wang](https://github.com/marco3724)

## File structure 
```
distributed-systems
│
├──── app/
│    ├── build/                   # Compiled files and build outputs
│    ├── logs/                    # Log files generated during execution
│    ├── src/                     # Source code directory
│    │    ├── main/java/it/unitn/ds1/
│    │    │   ├── Client/           # Client-side logic
│    │    │   ├── Messages/         # Message related to both client and replica
│    │    │   ├── Replicas/         # Replica logic
│    │    │   ├── SimulationController/  # Controls the simulation flow
│    │    │   │
│    │    │   ├── TestMessages/     # Messagges used for testing
│    │    │   │         
│    │    │   ├── App.java                        # Main application entry point
│    │    │ 
│    │    └── test/java/it/unitn/ds1/             # Unit and integration tests
│    │
├──── gradle                                # Gradle build configuration
├──── README.md project documentation
```