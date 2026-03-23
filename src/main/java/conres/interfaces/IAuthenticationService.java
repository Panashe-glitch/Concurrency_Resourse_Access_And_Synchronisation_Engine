package conres.interfaces;

import conres.model.UserID;
import java.util.Optional;

/**
 * CW1: LocalAuthenticationService (delegates to ICredentialStore).
 * CW2: RemoteAuthenticationService (delegates via RPC/socket to server).
 */
public interface IAuthenticationService {
    Optional<UserID> validate(String username);
}
