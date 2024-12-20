import os
import itertools

def main():
    path = "./app/logs/"
    folders = [f for f in os.listdir(path) if os.path.isdir(os.path.join(path, f))]
    most_recent_folder = max(folders)
    path = path + most_recent_folder + "/"
    print("Recent folder:", path)
    
    list_of_files = []
    files = os.listdir(path)
    for file in files:
        if file.startswith("replica_"):
            list_of_files.append(file)

    list_of_files.sort()
    list_of_updates = []
    for file in list_of_files:
        list_of_updates.append([line.strip().split(" ")[2] for line in open(path + file, "r") if line.startswith("update")])
    
    print('\033[4m' + "--- TESTING HISTORY EQUALITY ---" + '\033[0m')
    for quadruple in itertools.zip_longest(*list_of_updates):
        quadruple_no_none = list(filter(lambda x: x is not None, quadruple)) # Remove all None values
        first = quadruple_no_none[0] # Pick first non-None value
        quadruple_check = [item for item in quadruple if item is not None and item != first] # Check if all other values are the same
        if len(quadruple_check) == 0:
            print('\033[92m' + "True" + '\033[0m', quadruple)
        else:
            print('\033[91m' + "False" + '\033[0m', quadruple)
    

    
    list_of_client_files = []
    for file in files:
        if file.startswith("client_"):
            list_of_client_files.append(file)
    
    list_of_client_files.sort()
    list_of_clients_read = []
    for file in list_of_client_files:
        client_read = {}
        with open(path + file, "r") as f:
            for line in f:
                if "read complete" in line and "value not initialized" not in line:
                    
                    stripped_line = line.strip().split(" ")
                    print(stripped_line)
                    replica_id = stripped_line[5].split("_")[1]
                    read_value = stripped_line[3]
                    client_read.setdefault(replica_id, []).append(read_value)
        list_of_clients_read.append(client_read)

    # print(list_of_clients_read)
    # print("list updates", list_of_updates)
    print("\n\n" + '\033[4m' + "--- TESTING SEQUENTIAL CONSISTENCY ---" + '\033[0m')
    for client_id, set_reads in enumerate(list_of_clients_read):
        for replica_id, read_values in set_reads.items():
            replica_history = list_of_updates[int(replica_id)]
            read_index = 0
            history_index = 0
            while read_index < len(read_values):
                if read_values[read_index] == replica_history[history_index]:
                    read_index += 1
                else:
                    if history_index + 1 > len(replica_history) - 1:
                        print('\033[91m' + "Error")
                        return False
                    history_index += 1
            
            print("Client:", client_id, "is sequentially consistent with replica", replica_id)
    
    print("\n\n" + '\033[4m' + "--- TESTING WRITE REQUEST COVERAGE---" + '\033[0m')
    write_values = []
    for file in list_of_client_files:
        with open(path + file, "r") as f:
            for line in f:
                if "write req to replica" in line:
                    
                    stripped_line = line.strip().split(" value ")
                    value = stripped_line[1]
                    client = line.strip().split(":")[0]
                    replica = line.strip().split(" ")[5]
                    write_values.append((value,client,replica))
    longest_history = sorted(list_of_updates,key=lambda x: -len(x))[0]
    missing_values = []
    print("Write values", [c for c,r,v in write_values])
    print("Longest history", longest_history)    

    for val,client,replica in write_values:
        if val not in longest_history:
            missing_values.append((val,client,replica))
        else:
            longest_history.remove(val)
    
    if len(longest_history) == 0 and len(missing_values) == 0:
        print("All and ONLY the write value are covered")
        
    if len(longest_history) == 0:
        print("No duplicates")
    if len(longest_history) > 0:
        print("Duplicates are present:", longest_history)
    if len(missing_values) > 0:
        print("missing values" + str([x for x,_,_ in missing_values]))
        

       
        
    # for replica_id, replica_history in enumerate(list_of_updates):
    #     for write_value in write_values:
    #         if write_value not in replica_history:
                
    #             print('\033[91m' + "Error")
    #             return False
    
    
    print('\033[92m' + "All good")
    return True
if __name__ == "__main__":
    main()
