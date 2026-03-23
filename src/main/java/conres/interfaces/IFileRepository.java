package conres.interfaces;

import conres.model.Result;

/**
 * Data access boundary for shared resources.
 * CW1: LocalFileRepository (local file IO).
 * CW2: RemoteFileRepository (remote file access via RPC/socket).
 */
public interface IFileRepository {
    String readContents(String resourceId) throws java.io.IOException;
    Result<Void> writeContents(String resourceId, String data);
}
