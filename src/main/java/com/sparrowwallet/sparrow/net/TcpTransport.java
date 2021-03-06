package com.sparrowwallet.sparrow.net;

import com.github.arteam.simplejsonrpc.client.Transport;
import com.github.arteam.simplejsonrpc.server.JsonRpcServer;
import com.google.common.net.HostAndPort;
import com.sparrowwallet.sparrow.io.Config;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.SocketFactory;
import java.io.*;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class TcpTransport implements Transport, Closeable {
    private static final Logger log = LoggerFactory.getLogger(TcpTransport.class);

    public static final int DEFAULT_PORT = 50001;

    protected final HostAndPort server;
    protected final SocketFactory socketFactory;

    private Socket socket;

    private String response;

    private final ReentrantLock readLock = new ReentrantLock();
    private final Condition readingCondition = readLock.newCondition();

    private final ReentrantLock clientRequestLock = new ReentrantLock();
    private boolean running = false;
    private boolean reading = true;
    private boolean firstRead = true;

    private final JsonRpcServer jsonRpcServer = new JsonRpcServer();
    private final SubscriptionService subscriptionService = new SubscriptionService();

    private Exception lastException;

    public TcpTransport(HostAndPort server) {
        this.server = server;
        this.socketFactory = SocketFactory.getDefault();
    }

    @Override
    public @NotNull String pass(@NotNull String request) throws IOException {
        clientRequestLock.lock();
        try {
            writeRequest(request);
            return readResponse();
        } finally {
            clientRequestLock.unlock();
        }
    }

    private void writeRequest(String request) throws IOException {
        PrintWriter out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())));
        out.println(request);
        out.flush();
    }

    private String readResponse() throws IOException {
        try {
            if(!readLock.tryLock(60, TimeUnit.SECONDS)) {
                throw new IOException("No response from server");
            }
        } catch(InterruptedException e) {
            throw new IOException("Read thread interrupted");
        }

        try {
            if(firstRead) {
                readingCondition.signal();
                firstRead = false;
            }

            while(reading) {
                try {
                    readingCondition.await();
                } catch(InterruptedException e) {
                    //Restore interrupt status and continue
                    Thread.currentThread().interrupt();
                }
            }

            if(lastException != null) {
                throw new IOException("Error reading response: " + lastException.getMessage(), lastException);
            }

            reading = true;

            readingCondition.signal();
            return response;
        } finally {
            readLock.unlock();
        }
    }

    public void readInputLoop() throws ServerException {
        readLock.lock();

        try {
            try {
                //Don't start reading until first RPC request is sent
                readingCondition.await();
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            while(running) {
                try {
                    String received = readInputStream();
                    if(received.contains("method")) {
                        //Handle subscription notification
                        jsonRpcServer.handle(received, subscriptionService);
                    } else {
                        //Handle client's response
                        response = received;
                        reading = false;
                        readingCondition.signal();
                        readingCondition.await();
                    }
                } catch(InterruptedException e) {
                    //Restore interrupt status and continue
                    Thread.currentThread().interrupt();
                } catch(Exception e) {
                    log.debug("Connection error while reading", e);
                    if(running) {
                        lastException = e;
                        reading = false;
                        readingCondition.signal();
                        //Allow this thread to terminate as we will need to reconnect with a new transport anyway
                        running = false;
                    }
                }
            }
        } finally {
            readLock.unlock();
        }
    }

    protected String readInputStream() throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        String response = in.readLine();

        if(response == null) {
            throw new IOException("Could not connect to server at " + Config.get().getElectrumServer());
        }

        return response;
    }

    public void connect() throws ServerException {
        try {
            socket = createSocket();
            running = true;
        } catch(IOException e) {
            throw new ServerException(e);
        }
    }

    public boolean isConnected() {
        return socket != null && running;
    }

    protected Socket createSocket() throws IOException {
        return socketFactory.createSocket(server.getHost(), server.getPortOrDefault(DEFAULT_PORT));
    }

    @Override
    public void close() throws IOException {
        if(socket != null) {
            running = false;
            socket.close();
        }
    }
}
