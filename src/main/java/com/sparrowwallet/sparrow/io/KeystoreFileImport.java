package com.sparrowwallet.sparrow.io;

import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.protocol.Network;
import com.sparrowwallet.drongo.wallet.Keystore;

import java.io.InputStream;

public interface KeystoreFileImport extends KeystoreImport, FileImport {
    Keystore getKeystore(Network network, ScriptType scriptType, InputStream inputStream, String password) throws ImportException;
}
