package com.sparrowwallet.sparrow.net;

import com.github.arteam.simplejsonrpc.client.Transport;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface ElectrumServerRpc {
    void ping(Transport transport);

    List<String> getServerVersion(Transport transport, String clientName, String[] supportedVersions);

    String getServerBanner(Transport transport);

    BlockHeaderTip subscribeBlockHeaders(Transport transport);

    Map<String, ScriptHashTx[]> getScriptHashHistory(Transport transport, Map<String, String> pathScriptHashes, boolean failOnError);

    Map<String, ScriptHashTx[]> getScriptHashMempool(Transport transport, Map<String, String> pathScriptHashes, boolean failOnError);

    Map<String, String> subscribeScriptHashes(Transport transport, Map<String, String> pathScriptHashes);

    Map<Integer, String> getBlockHeaders(Transport transport, Set<Integer> blockHeights);

    Map<String, String> getTransactions(Transport transport, Set<String> txids);

    Map<String, VerboseTransaction> getVerboseTransactions(Transport transport, Set<String> txids, String scriptHash);

    Map<Integer, Double> getFeeEstimates(Transport transport, List<Integer> targetBlocks);

    Double getMinimumRelayFee(Transport transport);

    String broadcastTransaction(Transport transport, String txHex);
}
