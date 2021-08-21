package ra.i2p;

import net.i2p.I2PException;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSessionException;
import net.i2p.client.I2PSessionMuxedListener;
import net.i2p.client.datagram.I2PDatagramDissector;
import net.i2p.client.datagram.I2PDatagramMaker;
import net.i2p.client.datagram.I2PInvalidDatagramException;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.SecureFile;
import net.i2p.util.SecureFileOutputStream;
import ra.common.Envelope;
import ra.common.identity.DID;
import ra.common.network.*;
import ra.common.route.ExternalRoute;
import ra.common.route.Route;
import ra.common.JSONParser;

import java.io.*;
import java.util.*;
import java.util.logging.Logger;

class I2PSession extends BaseClientSession implements I2PSessionMuxedListener {

    private static final Logger LOG = Logger.getLogger(I2PSession.class.getName());

    // I2CP parameters allowed in the config file
    // Undefined parameters use the I2CP defaults
    private static final String PARAMETER_I2CP_DOMAIN_SOCKET = "i2cp.domainSocket";
    private static final List<String> I2CP_PARAMETERS = Arrays.asList(new String[] {
            PARAMETER_I2CP_DOMAIN_SOCKET,
            "inbound.length",
            "inbound.lengthVariance",
            "inbound.quantity",
            "inbound.backupQuantity",
            "outbound.length",
            "outbound.lengthVariance",
            "outbound.quantity",
            "outbound.backupQuantity",
    });

    private I2PSocketManager socketManager;
    private boolean isTest = false;

    protected I2PEmbeddedService service;
    protected net.i2p.client.I2PSession i2pSession;
    protected boolean connected = false;
    protected String address;

    public I2PSession(I2PEmbeddedService service) {
        this.service = service;
    }

    public String getAddress() {
        return address;
    }

    public Destination lookupDest(String address) {
        Destination destination = null;
        try {
            destination = i2pSession.lookupDest(address);
        } catch (I2PSessionException e) {
            e.printStackTrace();
        }
        return destination;
    }

    /**
     * Initializes session properties
     */
    @Override
    public boolean init(Properties p) {
        super.init(p);
        LOG.info("Initializing I2P Session....");
        // set tunnel names
        properties.setProperty("inbound.nickname", "I2PService");
        properties.setProperty("outbound.nickname", "I2PService");
        LOG.info("I2P Session initialized.");
        return true;
    }

    /**
     * Open a Socket with internal peer.
     * I2P Service currently uses only one internal I2P address thus ignoring any address passed to this method.
     */
    @Override
    public boolean open(String i2pAddress) {
        LOG.info("Opening connection...");
        NetworkPeer localI2PPeer = service.getNetworkState().localPeer;
        // read the local destination key from the key file if it exists
        String alias = "anon";
        if(localI2PPeer!=null && localI2PPeer.getDid().getUsername()!=null) {
            alias = localI2PPeer.getDid().getUsername();
        }

        File destinationKeyFile = new File(service.getDirectory(), alias);
        FileReader fileReader = null;
        try {
            fileReader = new FileReader(destinationKeyFile);
            char[] destKeyBuffer = new char[(int)destinationKeyFile.length()];
            fileReader.read(destKeyBuffer);
            byte[] localDestinationKey = Base64.decode(new String(destKeyBuffer));
            ByteArrayInputStream inputStream = new ByteArrayInputStream(localDestinationKey);
            socketManager = I2PSocketManagerFactory.createDisconnectedManager(inputStream, null, 0, properties);
        } catch (IOException e) {
            LOG.info("Destination key file doesn't exist or isn't readable." + e);
        } catch (I2PSessionException e) {
            // Won't happen, inputStream != null
            LOG.warning(e.getLocalizedMessage());
        } finally {
            if (fileReader != null) {
                try {
                    fileReader.close();
                } catch (IOException e) {
                    LOG.warning("Error closing file: " + destinationKeyFile.getAbsolutePath() + ": " + e);
                }
            }
        }

        // if the local destination key can't be read or is invalid, create a new one
        if (socketManager == null) {
            LOG.info("Creating new local destination key");
            try {
                ByteArrayOutputStream arrayStream = new ByteArrayOutputStream();
                I2PClientFactory.createClient().createDestination(arrayStream, SigType.DSA_SHA1);
                byte[] localDestinationKey = arrayStream.toByteArray();

                LOG.info("Creating I2P Socket Manager...");
                ByteArrayInputStream inputStream = new ByteArrayInputStream(localDestinationKey);
                socketManager = I2PSocketManagerFactory.createDisconnectedManager(inputStream, null, 0, properties);
                LOG.info("I2P Socket Manager created.");

                destinationKeyFile = new SecureFile(destinationKeyFile.getAbsolutePath());
                if (destinationKeyFile.exists()) {
                    File oldKeyFile = new File(destinationKeyFile.getPath() + "_backup");
                    if (!destinationKeyFile.renameTo(oldKeyFile)) {
                        LOG.warning("Cannot rename destination key file <" + destinationKeyFile.getAbsolutePath() + "> to <" + oldKeyFile.getAbsolutePath() + ">");
                        return false;
                    }
                } else if (!destinationKeyFile.createNewFile()) {
                    LOG.warning("Cannot create destination key file: <" + destinationKeyFile.getAbsolutePath() + ">");
                    return false;
                }

                BufferedWriter fileWriter = new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(destinationKeyFile)));
                try {
                    fileWriter.write(Base64.encode(localDestinationKey));
                }
                finally {
                    fileWriter.close();
                }
            } catch (I2PException e) {
                LOG.warning("Error creating local destination key: " + e.getLocalizedMessage());
                return false;
            } catch (IOException e) {
                LOG.warning("Error writing local destination key to file: " + e.getLocalizedMessage());
                return false;
            }
        }
        i2pSession = socketManager.getSession();
        if(localI2PPeer.getDid().getPublicKey().getAddress()==null
                || localI2PPeer.getDid().getPublicKey().getAddress().isEmpty()) {
            Destination localDestination = i2pSession.getMyDestination();
            address = localDestination.toBase64();
            String fingerprint = localDestination.calculateHash().toBase64();
            String algorithm = localDestination.getPublicKey().getType().getAlgorithmName();
            // Ensure network is correct
            localI2PPeer.setNetwork(Network.I2P);
            // Add destination to PK and update DID info
            localI2PPeer.getDid().setStatus(DID.Status.ACTIVE);
            localI2PPeer.getDid().setDescription("DID for I2PSensorSession");
            localI2PPeer.getDid().setAuthenticated(true);
            localI2PPeer.getDid().setVerified(true);
            localI2PPeer.getDid().getPublicKey().setAlias(alias);
            localI2PPeer.getDid().getPublicKey().isIdentityKey(true);
            localI2PPeer.getDid().getPublicKey().setAddress(address);
            localI2PPeer.getDid().getPublicKey().setBase64Encoded(true);
            localI2PPeer.getDid().getPublicKey().setFingerprint(fingerprint);
            localI2PPeer.getDid().getPublicKey().setType(algorithm);

            // Only for testing; remove for production
            String country = service.routerContext.commSystem().getCountry(localDestination.getHash());
            if(country==null)
                LOG.info("Local I2P Peer in country: unknown");
            else
                LOG.info("Local I2P Peer in country: "+country);
        }
        if(service.router.getConfigSetting("i2np.udp.port") != null) {
            service.getNetworkState().virtualPort = Integer.parseInt(service.router.getConfigSetting("i2np.udp.port"));
        }
        service.getNetworkState().localPeer = localI2PPeer;
        LOG.info("Local I2P Peer Address in base64: " + localI2PPeer.getDid().getPublicKey().getAddress());
        LOG.info("Local I2P Peer Fingerprint (hash) in base64: " + localI2PPeer.getDid().getPublicKey().getFingerprint());
        // Update Peer Manager
//        Envelope pEnv = Envelope.documentFactory();
//        DLC.addContent(localI2PPeer, pEnv);
//        DLC.addRoute("ra.peermanager.PeerManagerService","UPDATE_PEER", pEnv);
//        service.send(pEnv);
        return true;
    }

    /**
     * Connect to I2P network
     * @return
     */
    @Override
    public boolean connect() {
        if(!isOpen()) {
            LOG.info("No Socket Manager open.");
            open(null);
        }
        i2pSession = socketManager.getSession();
        LOG.info("I2P Session connecting...");
        long start = System.currentTimeMillis();
        try {
            // Throws I2PSessionException if the connection fails
            i2pSession.connect();
            connected = true;
        } catch (I2PSessionException e) {
            LOG.warning(e.getLocalizedMessage());
            return false;
        }
        long end = System.currentTimeMillis();
        long durationMs = end - start;
        LOG.info("I2P Session connected. Took "+(durationMs/1000)+" seconds.");

        i2pSession.addMuxedSessionListener(this, net.i2p.client.I2PSession.PROTO_ANY, net.i2p.client.I2PSession.PORT_ANY);

        return true;
    }

    @Override
    public boolean disconnect() {
        if(i2pSession!=null) {
            try {
                i2pSession.destroySession();
                connected = false;
            } catch (I2PSessionException e) {
                LOG.warning(e.getLocalizedMessage());
                return false;
            }
        }
        return true;
    }

    public boolean isOpen() {
        return socketManager!=null;
    }

    @Override
    public boolean isConnected() {
        boolean isConnected = i2pSession != null && connected && !i2pSession.isClosed();
        if(!isConnected) connected = false;
        return isConnected;
    }

    @Override
    public boolean close() {
        disconnect();
        socketManager.destroySocketManager();
        return true;
    }

    @Override
    public Boolean send(Envelope envelope) {
        if (envelope == null) {
            LOG.warning("No Envelope.");
            return false;
        }
        if(!(envelope.getRoute() instanceof ExternalRoute)) {
            LOG.warning("Not an external route.");
            envelope.getMessage().addErrorMessage("Route must be external.");
            return false;
        }
        ExternalRoute er = (ExternalRoute)envelope.getRoute();
        if (er.getDestination() == null) {
            LOG.warning("No Destination Peer for I2P found in while sending to I2P.");
            envelope.getMessage().addErrorMessage("Code:" + ExternalRoute.DESTINATION_PEER_REQUIRED+", Destination Peer Required.");
            return false;
        }
        if (!Network.I2P.name().equals(er.getDestination().getNetwork())) {
            LOG.warning("Not an envelope for I2P.");
            envelope.getMessage().addErrorMessage("Code:" + ExternalRoute.DESTINATION_PEER_WRONG_NETWORK+", Not meant for I2P Network.");
            return false;
        }

        LOG.info("Sending Envelope id: "+envelope.getId().substring(0,7)+"... to: "+er.getDestination().getDid().getPublicKey().getFingerprint().substring(0,7)+"...");
        String content = envelope.toJSON();
        LOG.fine("Content to send: \n\t" + content);
        if (content.length() > 31500) {
            // Just warn for now
            // TODO: Split into multiple serialized packets
            LOG.warning("Content longer than 31.5kb. May have issues.");
        }
        try {
            Destination destination = i2pSession.lookupDest(er.getDestination().getDid().getPublicKey().getAddress());
            if(destination == null) {
                LOG.warning("I2P Destination Peer not found.");
                envelope.getMessage().addErrorMessage("Code:" + ExternalRoute.DESTINATION_PEER_NOT_FOUND+", I2P Destination Peer not found.");
                return false;
            }
            I2PDatagramMaker m = new I2PDatagramMaker(i2pSession);
            byte[] payload = m.makeI2PDatagram(content.getBytes());
            if(i2pSession.sendMessage(destination, payload, net.i2p.client.I2PSession.PROTO_UNSPECIFIED, net.i2p.client.I2PSession.PORT_ANY, net.i2p.client.I2PSession.PORT_ANY)) {
                LOG.fine("I2P Message sent.");
                return true;
            } else {
                LOG.warning("I2P Message sending failed.");
                envelope.getMessage().addErrorMessage("I2P Message sending failed.");
                return false;
            }
        } catch (I2PSessionException e) {
            String errMsg = "Exception while sending I2P message: " + e.getLocalizedMessage();
            LOG.warning(errMsg);
            envelope.getMessage().addErrorMessage(errMsg);
            if("Already closed".equals(e.getLocalizedMessage())) {
                LOG.info("I2P Connection closed. Could be no internet access, getting blocked, or forced shutdown of I2P router. Assume blocked for re-route. If not blocked, I2P will automatically re-establish connection when network access returns.");
                service.getNetworkState().networkStatus = NetworkStatus.BLOCKED;
                service.restart();
            }
            return false;
        }
    }

    /**
     * Will be called only if you register via
     * setSessionListener() or addSessionListener().
     * And if you are doing that, just use I2PSessionListener.
     *
     * If you register via addSessionListener(),
     * this will be called only for the proto(s) and toport(s) you register for.
     *
     * After this is called, the client should call receiveMessage(msgId).
     * There is currently no method for the client to reject the message.
     * If the client does not call receiveMessage() within a timeout period
     * (currently 30 seconds), the session will delete the message and
     * log an error.
     *
     * @param session session to notify
     * @param msgId message number available
     * @param size size of the message - why it's a long and not an int is a mystery
     */
    @Override
    public void messageAvailable(net.i2p.client.I2PSession session, int msgId, long size) {
        LOG.fine("Message received by I2P Service...");
        long end = new Date().getTime();
        byte[] msg;
        try {
            msg = session.receiveMessage(msgId);
        } catch (I2PSessionException e) {
            LOG.warning("Can't get new message from I2PSession: " + e.getLocalizedMessage());
            return;
        }
        if (msg == null) {
            LOG.warning("I2PSession returned a null message: msgId=" + msgId + ", size=" + size + ", " + session);
            return;
        }
//        if(sensor.getStatus()==SensorStatus.NETWORK_CONNECTED) {
//            sensor.updateStatus(SensorStatus.NETWORK_VERIFIED);
//        }
        try {
            LOG.fine("Loading I2P Datagram...");
            I2PDatagramDissector d = new I2PDatagramDissector();
            d.loadI2PDatagram(msg);
            LOG.fine("I2P Datagram loaded.");
            byte[] payload = d.getPayload();
            String strPayload = new String(payload);
            Map<String, Object> pm = (Map<String, Object>) JSONParser.parse(strPayload);
            Envelope envelope = Envelope.documentFactory();
            envelope.fromMap(pm);
            LOG.fine("Getting sender as I2P Destination...");
            Route r = envelope.getRoute();
            if(!(r instanceof ExternalRoute)) {
                // Received external message without an External Route. Ignoring.
                return;
            }
            ExternalRoute er = (ExternalRoute)r;
            NetworkPeer origination = er.getOrigination();
            Destination sender = d.getSender();
            // Ensure origination provided correct address and fingerprint
            String address = sender.toBase64();
            origination.getDid().getPublicKey().setAddress(address);
            String fingerprint = sender.getHash().toBase64();
            origination.getDid().getPublicKey().setFingerprint(fingerprint);

            // Update local cache
            service.addPeer(origination);
            if(envelope.markerPresent("NetOpRes")) {
                List<NetworkPeer> recommendedPeers = (List<NetworkPeer>) envelope.getContent();
                if (recommendedPeers != null) {
                    LOG.info(recommendedPeers.size() + " Known Peers Received.");
                    service.addPeers(recommendedPeers);
                }
                Long start = service.inflightTimers.get(envelope.getId());
                long diff = 0L;
                if(start!=null) {
                    diff = end-start;
                    synchronized (service.inflightTimers) {
                        service.inflightTimers.remove(envelope.getId());
                    }
                }
                LOG.info("Received NetOpRes id: "+envelope.getId().substring(0,7)+"... from: "+fingerprint.substring(0,7) + (diff > 0L ? ("... in " + diff + " ms roundtrip; ") : "..." )+" total peers known: "+service.getNumberPeers());
            } else if(envelope.markerPresent("NetOpReq")) {
                List<NetworkPeer> recommendedPeers = (List<NetworkPeer>) envelope.getContent();
                if (recommendedPeers != null) {
                    LOG.info(recommendedPeers.size() + " Known Peers Received.");
                    service.addPeers(recommendedPeers);
                }
                envelope.mark("NetOpRes");
                envelope.addContent(service.getPeers());
                envelope.addExternalRoute(I2PEmbeddedService.class, I2PEmbeddedService.OPERATION_SEND, service.getNetworkState().localPeer, origination);
                envelope.ratchet();
                LOG.info("Received NetOpReq id: "+envelope.getId().substring(0,7)+"... from: "+fingerprint.substring(0,7)+"... total peers known: "+service.getNumberPeers());
                send(envelope);
            } else {
                LOG.info("Received Envelope id: "+envelope.getId().substring(0,7)+"... from: "+fingerprint.substring(0,7)+"...");
                if (!service.send(envelope)) {
                    LOG.warning("Unsuccessful sending of Envelope to bus.");
                }
            }
            LOG.fine("Content Received: \n\t"+strPayload);
        } catch (DataFormatException e) {
            LOG.warning("Invalid datagram received: " + e.getLocalizedMessage());
        } catch (I2PInvalidDatagramException e) {
            LOG.warning("Datagram failed verification: " + e.getLocalizedMessage());
        } catch (Exception e) {
            LOG.severe("Error processing datagram: " + e.getLocalizedMessage());
        }
    }

    /**
     * Instruct the client that the given session has received a message
     *
     * Will be called only if you register via addMuxedSessionListener().
     * Will be called only for the proto(s) and toport(s) you register for.
     *
     * After this is called, the client should call receiveMessage(msgId).
     * There is currently no method for the client to reject the message.
     * If the client does not call receiveMessage() within a timeout period
     * (currently 30 seconds), the session will delete the message and
     * log an error.
     *
     * Only one listener is called for a given message, even if more than one
     * have registered. See I2PSessionDemultiplexer for details.
     *
     * @param session session to notify
     * @param msgId message number available
     * @param size size of the message - why it's a long and not an int is a mystery
     * @param proto 1-254 or 0 for unspecified
     * @param fromPort 1-65535 or 0 for unspecified
     * @param toPort 1-65535 or 0 for unspecified
     */
    @Override
    public void messageAvailable(net.i2p.client.I2PSession session, int msgId, long size, int proto, int fromPort, int toPort) {
//        if (proto == I2PSession.PROTO_DATAGRAM || proto == I2PSession.PROTO_STREAMING)
        messageAvailable(session, msgId, size);
//        else
//            LOG.warning("Received unhandled message with proto="+proto+" and id="+msgId);
    }

    /**
     * Instruct the client that the session specified seems to be under attack
     * and that the client may wish to move its destination to another router.
     * All registered listeners will be called.
     *
     * Unused. Not fully implemented.
     *
     * @param i2PSession session to report abuse to
     * @param severity how bad the abuse is
     */
    @Override
    public void reportAbuse(net.i2p.client.I2PSession i2PSession, int severity) {
        LOG.warning("I2P Session reporting abuse. Severity="+severity);
        service.reportRouterStatus();
    }

    /**
     * Notify the client that the session has been terminated.
     * All registered listeners will be called.
     *
     * @param session session to report disconnect to
     */
    @Override
    public void disconnected(net.i2p.client.I2PSession session) {
        LOG.warning("I2P Session reporting disconnection.");
        service.reportRouterStatus();
    }

    /**
     * Notify the client that some throwable occurred.
     * All registered listeners will be called.
     *
     * @param session session to report error occurred
     * @param message message received describing error
     * @param throwable throwable thrown during error
     */
    @Override
    public void errorOccurred(net.i2p.client.I2PSession session, String message, Throwable throwable) {
        LOG.severe("Router says: "+message+": "+throwable.getLocalizedMessage());
        service.reportRouterStatus();
    }

    private Properties getI2CPOptions() {
        Properties opts = new Properties();
        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            if (I2CP_PARAMETERS.contains(entry.getKey()))
                opts.put(entry.getKey(), entry.getValue());
        }
        return opts;
    }

}
