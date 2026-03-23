package conres.data;

import conres.interfaces.ICredentialStore;
import conres.model.UserID;

import java.io.*;
import java.util.*;

/**
 * Loads credentials from a text file. Format: username,ID (one per line).
 * Supports legacy format (username only) for backward compatibility.
 * Security (password hashing, encryption) explicitly out of scope per scenario.
 *
 * Satisfies: FR1 (pre-assigned username and ID), FR2 (credential store).
 */
public class FileBackedCredentialStore implements ICredentialStore {

    private final Map<String, UserID> credentials;         // keyed by lowercase username
    private final Map<String, UserID> credentialsById;     // keyed by ID for reverse lookup

    public FileBackedCredentialStore(String filePath) throws IOException {
        Map<String, UserID> loaded = new LinkedHashMap<>();
        Map<String, UserID> loadedById = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    String username, id;
                    if (trimmed.contains(",")) {
                        // New format: username,ID
                        String[] parts = trimmed.split(",", 2);
                        username = parts[0].trim();
                        id = parts[1].trim();
                    } else {
                        // Legacy format: username only (backward compat for tests)
                        username = trimmed;
                        id = trimmed;
                    }
                    UserID uid = new UserID(username, id);
                    loaded.put(username.toLowerCase(), uid);
                    loadedById.put(id, uid);
                }
            }
        }
        this.credentials = Collections.unmodifiableMap(loaded);
        this.credentialsById = Collections.unmodifiableMap(loadedById);
    }

    @Override
    public Optional<UserID> validate(String username) {
        if (username == null || username.isBlank()) return Optional.empty();
        return Optional.ofNullable(credentials.get(username.trim().toLowerCase()));
    }

    public int size() { return credentials.size(); }

    /** Returns usernames for the login dropdown. */
    public List<String> getAllUsernames() {
        List<String> names = new ArrayList<>();
        for (UserID uid : credentials.values()) names.add(uid.getUsername());
        return names;
    }

    /** Returns display strings in format "username (ID)" for UI panels. */
    public Map<String, String> getDisplayMap() {
        Map<String, String> map = new LinkedHashMap<>();
        for (UserID uid : credentials.values()) {
            map.put(uid.getId(), uid.getUsername() + " (" + uid.getId() + ")");
        }
        return map;
    }

    /** Returns username -> ID mapping for dropdown filtering. */
    public Map<String, String> getUsernameToIdMap() {
        Map<String, String> map = new LinkedHashMap<>();
        for (UserID uid : credentials.values()) {
            map.put(uid.getUsername(), uid.getId());
        }
        return map;
    }
}
