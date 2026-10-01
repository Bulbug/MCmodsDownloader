package app.instance;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Where instances are stored. Lets the rest of the app stay independent of the file layout. */
public interface InstanceRepository {

    List<Instance> findAll();

    Optional<Instance> find(String id);

    boolean exists(String id);

    /** Writes instance.json, creating the instance folder and standard sub-folders if missing. */
    void save(Instance instance);

    /** Folder of an existing or future instance. Throws if the id is not a safe folder name. */
    Path directoryOf(String id);

    /** Copies all files of one instance folder into a new (not yet existing) folder. */
    void copyDirectory(String fromId, String toId);

    /** Deletes the whole instance folder. */
    void delete(String id);
}
