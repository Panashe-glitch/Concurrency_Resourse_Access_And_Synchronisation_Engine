package conres.model;

import java.util.Objects;

/**
 * Immutable value object representing a user identity.
 * Maps a username to a unique ID as per FR1.
 */
public final class UserID {
    private final String username;
    private final String id;

    public UserID(String username, String id) {
        this.username = Objects.requireNonNull(username);
        this.id = Objects.requireNonNull(id);
    }

    public String getUsername() { return username; }
    public String getId() { return id; }

    @Override
    public String toString() { return id; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserID other)) return false;
        return id.equals(other.id);
    }

    @Override
    public int hashCode() { return id.hashCode(); }
}
