/*
 * Copyright (c) 2011, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @bug 7031830
 * @summary bad_record_mac failure on TLSv1.2 enabled connection with SSLEngine
 * @library /lib/security
 * @run main/othervm SSLEngineBadBufferArrayAccess
 *
 *     SunJSSE does not support dynamic system properties, no way to re-use
 *     system properties in samevm/agentvm mode.
 */

/**
 * A SSLSocket/SSLEngine interop test case.  This is not the way to
 * code SSLEngine-based servers, but works for what we need to do here,
 * which is to make sure that SSLEngine/SSLSockets can talk to each other.
 * SSLEngines can use direct or indirect buffers, and different code
 * is used to get at the buffer contents internally, so we test that here.
 *
 * The test creates one SSLSocket (client) and one SSLEngine (server).
 * The SSLSocket talks to a raw ServerSocket, and the server code
 * does the translation between byte [] and ByteBuffers that the SSLEngine
 * can use.  The "transport" layer consists of a Socket Input/OutputStream
 * and two byte buffers for the SSLEngines:  think of them
 * as directly connected pipes.
 *
 * Again, this is a *very* simple example: real code will be much more
 * involved.  For example, different threading and I/O models could be
 * used, transport mechanisms could close unexpectedly, and so on.
 *
 * When this application runs, notice that several messages
 * (wrap/unwrap) pass before any application data is consumed or
 * produced.  (For more information, please see the SSL/TLS
 * specifications.)  There may several steps for a successful handshake,
 * so it's typical to see the following series of operations:
 *
 *      client          server          message
 *      ======          ======          =======
 *      write()         ...             ClientHello
 *      ...             unwrap()        ClientHello
 *      ...             wrap()          ServerHello/Certificate
 *      read()         ...             ServerHello/Certificate
 *      write()         ...             ClientKeyExchange
 *      write()         ...             ChangeCipherSpec
 *      write()         ...             Finished
 *      ...             unwrap()        ClientKeyExchange
 *      ...             unwrap()        ChangeCipherSpec
 *      ...             unwrap()        Finished
 *      ...             wrap()          ChangeCipherSpec
 *      ...             wrap()          Finished
 *      read()          ...             ChangeCipherSpec
 *      read()          ...             Finished
 *
 * This particular bug had a problem where byte buffers backed by an
 * array didn't offset correctly, and we got bad MAC errors.
 */
import javax.net.ssl.*;
import javax.net.ssl.SSLEngineResult.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.nio.*;

public class SSLEngineBadBufferArrayAccess {

    /*
     * Enables logging of the SSL/TLS operations.
     */
    private static boolean logging = true;

    /*
     * Enables the JSSE system debugging system property:
     *
     *     -Djavax.net.debug=all
     *
     * This gives a lot of low-level information about operations underway,
     * including specific handshake messages, and might be best examined
     * after gaining some familiarity with this application.
     */
    private static boolean debug = false;
    private SSLContext sslc;
    private SSLEngine serverEngine;     // server-side SSLEngine
    private SSLSocket sslSocket;        // client-side socket
    private ServerSocket serverSocket;  // server-side Socket, generates the...
    private Socket socket;              // server-side socket that will read

    private final byte[] serverMsg = "Hi there Client, I'm a Server".getBytes();
    private final byte[] clientMsg = "Hello Server, I'm a Client".getBytes();

    private ByteBuffer serverOut;       // write side of serverEngine
    private ByteBuffer serverIn;        // read side of serverEngine

    private volatile Exception clientException;
    private volatile Exception serverException;

    /*
     * For data transport, this example uses local ByteBuffers.
     */
    private ByteBuffer cTOs;            // "reliable" transport client->server
    private ByteBuffer sTOc;            // "reliable" transport server->client

    /*
     * The following is to set up the keystores/trust material.
     */
    private static final String pathToStores = "../../../../../../../etc/";
    private static final String keyStoreFile = "keystore";
    private static final String trustStoreFile = "truststore";
    private static final String passwd = "passphrase";
    private static String keyFilename =
            System.getProperty("test.src", ".") + "/" + pathToStores
            + "/" + keyStoreFile;
    private static String trustFilename =
            System.getProperty("test.src", ".") + "/" + pathToStores
            + "/" + trustStoreFile;

    /*
     * Main entry point for this test.
     */
    public static void main(String args[]) throws Exception {
        if (debug) {
            System.setProperty("javax.net.debug", "all");
        }

        // Re-enable TLSv1 and TLSv1.1 since test depends on them.
        SecurityUtils.removeFromDisabledTlsAlgs("TLSv1", "TLSv1.1");

        String [] protocols = new String [] {
            "SSLv3", "TLSv1", "TLSv1.1", "TLSv1.2" };

        for (String protocol : protocols) {
            /*
             * Run the tests with direct and indirect buffers.
             */
            log("Testing " + protocol + ":true");
            new SSLEngineBadBufferArrayAccess(protocol).runTest(true);

            log("Testing " + protocol + ":false");
            new SSLEngineBadBufferArrayAccess(protocol).runTest(false);
        }

        System.out.println("Test Passed.");
    }

    /*
     * Create an initialized SSLContext to use for these tests.
     */
    public SSLEngineBadBufferArrayAccess(String protocol) throws Exception {

        KeyStore ks = KeyStore.getInstance("JKS");
        KeyStore ts = KeyStore.getInstance("JKS");

        char[] passphrase = "passphrase".toCharArray();

        ks.load(new FileInputStream(keyFilename), passphrase);
        ts.load(new FileInputStream(trustFilename), passphrase);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(ks, passphrase);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
        tmf.init(ts);

        SSLContext sslCtx = SSLContext.getInstance(protocol);

        sslCtx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        sslc = sslCtx;
    }

    /*
     * Run the test.
     *
     * Sit in a tight loop, with the server engine calling wrap/unwrap
     * regardless of whether data is available or not.  We do this until
     * we get the application data.  Then we shutdown and go to the next one.
     *
     * The main loop handles all of the I/O phases of the SSLEngine's
     * lifetime:
     *
     *     initial handshaking
     *     application data transfer
     *     engine closing
     *
     * One could easily separate these phases into separate
     * sections of code.
     */
    private void runTest(boolean direct) throws Exception {
        boolean serverClose = direct;

        serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        Thread thread = createClientThread(port, serverClose);

        socket = serverSocket.accept();
        socket.setSoTimeout(500);
        serverSocket.close();

        createSSLEngine();
        createBuffers(direct);

        try {
            boolean closed = false;

            InputStream is = socket.getInputStream();
            OutputStream os = socket.getOutputStream();

            SSLEngineResult serverResult;   // results from last operation

            /*
             * Examining the SSLEngineResults could be much more involved,
             * and may alter the overall flow of the application.
             *
             * For example, if we received a BUFFER_OVERFLOW when trying
             * to write to the output pipe, we could reallocate a larger
             * pipe, but instead we wait for the peer to drain it.
             */
            byte[] inbound = new byte[8192];
            byte[] outbound = new byte[8192];

            while (!isEngineClosed(serverEngine)) {
                int len = 0;

                // Inbound data
                log("================");

                // Read from the Client side.
                try {
                    len = is.read(inbound);
                    if (len == -1) {
                        throw new Exception("Unexpected EOF");
                    }
                    cTOs.put(inbound, 0, len);
                } catch (SocketTimeoutException ste) {
                    // swallow.  Nothing yet, probably waiting on us.
                }

                cTOs.flip();

                serverResult = serverEngine.unwrap(cTOs, serverIn);
                log("server unwrap: ", serverResult);
                runDelegatedTasks(serverResult, serverEngine);
                cTOs.compact();

                // Outbound data
                log("----");

                serverResult = serverEngine.wrap(serverOut, sTOc);
                log("server wrap: ", serverResult);
                runDelegatedTasks(serverResult, serverEngine);

                sTOc.flip();

                if ((len = sTOc.remaining()) != 0) {
                    sTOc.get(outbound, 0, len);
                    os.write(outbound, 0, len);
                    // Give the other side a chance to process
                }

                sTOc.compact();

                if (!closed && (serverOut.remaining() == 0)) {
                    closed = true;

                    /*
                     * We'll alternate initiatating the shutdown.
                     * When the server initiates, it will take one more
                     * loop, but tests the orderly shutdown.
                     */
                    if (serverClose) {
                        serverEngine.closeOutbound();
                    }
                }

                if (closed && isEngineClosed(serverEngine)) {
                    serverIn.flip();

                    /*
                     * A sanity check to ensure we got what was sent.
                     */
                    if (serverIn.remaining() != clientMsg.length) {
                        throw new Exception("Client: Data length error -" +
                            " IF THIS FAILS, PLEASE REPORT THIS TO THE" +
                            " SECURITY TEAM.  WE HAVE BEEN UNABLE TO" +
                            " RELIABLY DUPLICATE.");
                    }

                    for (int i = 0; i < clientMsg.length; i++) {
                        if (clientMsg[i] != serverIn.get()) {
                            throw new Exception("Client: Data content error -" +
                            " IF THIS FAILS, PLEASE REPORT THIS TO THE" +
                            " SECURITY TEAM.  WE HAVE BEEN UNABLE TO" +
                            " RELIABLY DUPLICATE.");
                        }
                    }
                    serverIn.compact();
                }
            }
            return;
        } catch (Exception e) {
            serverException = e;
        } finally {
            socket.close();

            // Wait for the client to join up with us.
            thread.join();
            if (serverException != null) {
                throw serverException;
            }
            if (clientException != null) {
                throw clientException;
            }
        }
    }

    /*
     * Create a client thread which does simple SSLSocket operations.
     * We'll write and read one data packet.
     */
    private Thread createClientThread(final int port,
            final boolean serverClose) throws Exception {

        Thread t = new Thread("ClientThread") {

            @Override
            public void run() {
                try {
                    Thread.sleep(1000);  // Give server time to finish setup.

                    sslSocket = (SSLSocket) sslc.getSocketFactory().
                            createSocket("localhost", port);
                    OutputStream os = sslSocket.getOutputStream();
                    InputStream is = sslSocket.getInputStream();

                    // write(byte[]) goes in one shot.
                    os.write(clientMsg);

                    byte[] inbound = new byte[2048];
                    int pos = 0;

                    int len;
done:
                    while ((len = is.read(inbound, pos, 2048 - pos)) != -1) {
                        pos += len;
                        // Let the client do the closing.
                        if ((pos == serverMsg.length) && !serverClose) {
                            sslSocket.close();
                            break done;
                        }
                    }

                    if (pos != serverMsg.length) {
                        throw new Exception("Client:  Data length error");
                    }

                    for (int i = 0; i < serverMsg.length; i++) {
                        if (inbound[i] != serverMsg[i]) {
                            throw new Exception("Client:  Data content error");
                        }
                    }
                } catch (Exception e) {
                    clientException = e;
                }
            }
        };
        t.start();
        return t;
    }

    /*
     * Using the SSLContext created during object creation,
     * create/configure the SSLEngines we'll use for this test.
     */
    private void createSSLEngine() throws Exception {
        /*
         * Configure the serverEngine to act as a server in the SSL/TLS
         * handshake.
         */
        serverEngine = sslc.createSSLEngine();
        serverEngine.setUseClientMode(false);
        serverEngine.getNeedClientAuth();
    }

    /*
     * Create and size the buffers appropriately.
     */
    private void createBuffers(boolean direct) {

        SSLSession session = serverEngine.getSession();
        int appBufferMax = session.getApplicationBufferSize();
        int netBufferMax = session.getPacketBufferSize();

        /*
         * We'll make the input buffers a bit bigger than the max needed
         * size, so that unwrap()s following a successful data transfer
         * won't generate BUFFER_OVERFLOWS.
         *
         * We'll use a mix of direct and indirect ByteBuffers for
         * tutorial purposes only.  In reality, only use direct
         * ByteBuffers when they give a clear performance enhancement.
         */
        if (direct) {
            serverIn = ByteBuffer.allocateDirect(appBufferMax + 50);
            cTOs = ByteBuffer.allocateDirect(netBufferMax);
            sTOc = ByteBuffer.allocateDirect(netBufferMax);
        } else {
            serverIn = ByteBuffer.allocate(appBufferMax + 50);
            cTOs = ByteBuffer.allocate(netBufferMax);
            sTOc = ByteBuffer.allocate(netBufferMax);
        }

        serverOut = ByteBuffer.wrap(serverMsg);
    }

    /*
     * If the result indicates that we have outstanding tasks to do,
     * go ahead and run them in this thread.
     */
    private static void runDelegatedTasks(SSLEngineResult result,
            SSLEngine engine) throws Exception {

        if (result.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
            Runnable runnable;
            while ((runnable = engine.getDelegatedTask()) != null) {
                log("\trunning delegated task...");
                runnable.run();
            }
            HandshakeStatus hsStatus = engine.getHandshakeStatus();
            if (hsStatus == HandshakeStatus.NEED_TASK) {
                throw new Exception(
                        "handshake shouldn't need additional tasks");
            }
            log("\tnew HandshakeStatus: " + hsStatus);
        }
    }

    private static boolean isEngineClosed(SSLEngine engine) {
        return (engine.isOutboundDone() && engine.isInboundDone());
    }

    /*
     * Logging code
     */
    private static boolean resultOnce = true;

    private static void log(String str, SSLEngineResult result) {
        if (!logging) {
            return;
        }
        if (resultOnce) {
            resultOnce = false;
            System.out.println("The format of the SSLEngineResult is: \n"
                    + "\t\"getStatus() / getHandshakeStatus()\" +\n"
                    + "\t\"bytesConsumed() / bytesProduced()\"\n");
        }
        HandshakeStatus hsStatus = result.getHandshakeStatus();
        log(str
                + result.getStatus() + "/" + hsStatus + ", "
                + result.bytesConsumed() + "/" + result.bytesProduced()
                + " bytes");
        if (hsStatus == HandshakeStatus.FINISHED) {
            log("\t...ready for application data");
        }
    }

    private static void log(String str) {
        if (logging) {
            System.out.println(str);
        }
    }
}
