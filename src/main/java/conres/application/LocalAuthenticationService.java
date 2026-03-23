package conres.application;

import conres.interfaces.IAuthenticationService;
import conres.interfaces.ICredentialStore;
import conres.model.UserID;

import java.util.Optional;

/**
 * Local authentication -- delegates to ICredentialStore.
 * CW2 replacement: RemoteAuthenticationService (RPC/socket to server).
 *
 * Satisfies: FR1, FR2, C8 (authentication before admission).
 */
public class LocalAuthenticationService implements IAuthenticationService {

    private final ICredentialStore credentialStore;

    public LocalAuthenticationService(ICredentialStore credentialStore) {
        this.credentialStore = credentialStore;
    }

    @Override
    public Optional<UserID> validate(String username) {
        return credentialStore.validate(username);
    }
}
