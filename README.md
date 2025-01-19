# Distributed Systems
The project is inspired by Apache ZooKeeper, which consist into building a system that allows to reliably update the state of nodes in
the distributed system by means of a coordinator rerlying on a Quorum.
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

## Usage
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