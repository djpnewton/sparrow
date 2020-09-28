package com.sparrowwallet.sparrow.io;

import com.google.common.io.CharStreams;
import com.google.gson.Gson;
import com.sparrowwallet.drongo.ExtendedKey;
import com.sparrowwallet.drongo.KeyDerivation;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.policy.Policy;
import com.sparrowwallet.drongo.policy.PolicyType;
import com.sparrowwallet.drongo.protocol.Network;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.wallet.Keystore;
import com.sparrowwallet.drongo.wallet.KeystoreSource;
import com.sparrowwallet.drongo.wallet.Wallet;
import com.sparrowwallet.drongo.wallet.WalletModel;

import java.io.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ColdcardMultisig implements WalletImport, KeystoreFileImport, WalletExport {
    private final Gson gson = new Gson();

    @Override
    public String getName() {
        return "Coldcard Multisig";
    }

    @Override
    public WalletModel getWalletModel() {
        return WalletModel.COLDCARD;
    }

    @Override
    public Keystore getKeystore(ScriptType scriptType, InputStream inputStream, String password) throws ImportException {
        InputStreamReader reader = new InputStreamReader(inputStream);
        ColdcardKeystore cck = Storage.getGson().fromJson(reader, ColdcardKeystore.class);

        Keystore keystore = new Keystore("Coldcard");
        keystore.setSource(KeystoreSource.HW_AIRGAPPED);
        keystore.setWalletModel(WalletModel.COLDCARD);

        if(scriptType.equals(ScriptType.P2SH)) {
            keystore.setKeyDerivation(new KeyDerivation(cck.xfp, cck.p2sh_deriv));
            keystore.setExtendedPublicKey(ExtendedKey.fromDescriptor(cck.p2sh));
        } else if(scriptType.equals(ScriptType.P2SH_P2WSH)) {
            keystore.setKeyDerivation(new KeyDerivation(cck.xfp, cck.p2wsh_p2sh_deriv));
            keystore.setExtendedPublicKey(ExtendedKey.fromDescriptor(cck.p2wsh_p2sh));
        } else if(scriptType.equals(ScriptType.P2WSH)) {
            keystore.setKeyDerivation(new KeyDerivation(cck.xfp, cck.p2wsh_deriv));
            keystore.setExtendedPublicKey(ExtendedKey.fromDescriptor(cck.p2wsh));
        } else {
            throw new ImportException("Correct derivation not found for script type: " + scriptType);
        }

        return keystore;
    }

    private static class ColdcardKeystore {
        public String p2sh_deriv;
        public String p2sh;
        public String p2wsh_p2sh_deriv;
        public String p2wsh_p2sh;
        public String p2wsh_deriv;
        public String p2wsh;
        public String xfp;
    }

    @Override
    public String getKeystoreImportDescription() {
        return "Import file created by using the Settings > Multisig Wallets > Export XPUB feature on your Coldcard.";
    }

    @Override
    public String getExportFileExtension() {
        return "txt";
    }

    @Override
    public Wallet importWallet(InputStream inputStream, String password) throws ImportException {
        //TODO: get from settings or user input
        Wallet wallet = new Wallet(Network.BITCOIN);
        wallet.setPolicyType(PolicyType.MULTI);

        int threshold = 2;
        ScriptType scriptType = null;
        String derivation = null;

        try {
            List<String> lines = CharStreams.readLines(new InputStreamReader(inputStream));
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                String[] keyValue = line.split(":");
                if (keyValue.length == 2) {
                    String key = keyValue[0].trim();
                    String value = keyValue[1].trim();

                    switch (key) {
                        case "Name":
                            wallet.setName(value.trim());
                            break;
                        case "Policy":
                            threshold = Integer.parseInt(value.split(" ")[0]);
                            break;
                        case "Derivation":
                        case "# derivation":
                            derivation = value;
                            break;
                        case "Format":
                            scriptType = ScriptType.valueOf(value.replace("P2WSH-P2SH", "P2SH_P2WSH"));
                            break;
                        default:
                            if (key.length() == 8 && Utils.isHex(key)) {
                                Keystore keystore = new Keystore("Coldcard");
                                keystore.setSource(KeystoreSource.HW_AIRGAPPED);
                                keystore.setWalletModel(WalletModel.COLDCARD);
                                keystore.setKeyDerivation(new KeyDerivation(key, derivation));
                                keystore.setExtendedPublicKey(ExtendedKey.fromDescriptor(value));
                                wallet.makeLabelsUnique(keystore);
                                wallet.getKeystores().add(keystore);
                            }
                    }
                }
            }


            Policy policy = Policy.getPolicy(PolicyType.MULTI, scriptType, wallet.getKeystores(), threshold);
            wallet.setDefaultPolicy(policy);
            wallet.setScriptType(scriptType);

            if(!wallet.isValid()) {
                throw new IllegalStateException("This file does not describe a valid wallet. Please use the Settings > Multisig Wallets > Export XPUB feature on your Coldcard.");
            }

            return wallet;
        } catch(Exception e) {
            throw new ImportException(e);
        }
    }

    @Override
    public String getWalletImportDescription() {
        return "Import file created by using the Settings > Multisig Wallets > [Wallet Detail] > Coldcard Export feature on your Coldcard.";
    }

    @Override
    public void exportWallet(Wallet wallet, OutputStream outputStream) throws ExportException {
        if(!wallet.isValid()) {
            throw new ExportException("Cannot export an incomplete wallet");
        }

        if(!wallet.getPolicyType().equals(PolicyType.MULTI)) {
            throw new ExportException("Coldcard multisig import requires a multisig wallet");
        }

        boolean multipleDerivations = false;
        Set<String> derivationSet = new HashSet<>();
        for(Keystore keystore : wallet.getKeystores()) {
            derivationSet.add(keystore.getKeyDerivation().getDerivationPath());
        }
        if(derivationSet.size() > 1) {
            multipleDerivations = true;
        }

        try {
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream));
            writer.append("# Coldcard Multisig setup file (created by Sparrow)\n");
            writer.append("#\n");
            writer.append("Name: ").append(wallet.getName()).append("\n");
            writer.append("Policy: ").append(Integer.toString(wallet.getDefaultPolicy().getNumSignaturesRequired())).append(" of ").append(Integer.toString(wallet.getKeystores().size())).append("\n");
            if(!multipleDerivations) {
                writer.append("Derivation: ").append(wallet.getKeystores().get(0).getKeyDerivation().getDerivationPath()).append("\n");
            }
            writer.append("Format: ").append(wallet.getScriptType().toString().replace("P2SH-P2WSH", "P2WSH-P2SH")).append("\n");
            writer.append("\n");

            for(Keystore keystore : wallet.getKeystores()) {
                if(multipleDerivations) {
                    writer.append("# derivation: ").append(keystore.getKeyDerivation().getDerivationPath()).append("\n");
                }
                writer.append(keystore.getKeyDerivation().getMasterFingerprint().toUpperCase()).append(": ").append(keystore.getExtendedPublicKey().toString()).append("\n");
                if(multipleDerivations) {
                    writer.append("\n");
                }
            }

            writer.flush();
            writer.close();
        } catch(Exception e) {
            throw new ExportException(e);
        }
    }

    @Override
    public String getWalletExportDescription() {
        return "Export file that can be read by your Coldcard using the Settings > Multisig Wallets > Import from SD feature.";
    }

    @Override
    public boolean isEncrypted(File file) {
        return false;
    }
}
