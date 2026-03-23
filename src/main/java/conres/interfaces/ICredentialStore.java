package conres.interfaces;

import conres.model.UserID;
import java.util.Optional;

/**
 * CW1: FileBackedCredentialStore (local file).
 * CW2: RemoteCredentialStore (network database query via RPC/socket).
 */
public interface ICredentialStore {
    Optional<UserID> validate(String username);
}
