package ra.i2p;

import net.i2p.client.I2PClient;
import net.i2p.client.I2PSession;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterLaunch;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;
import net.i2p.util.OrderedProperties;
import net.i2p.util.SystemVersion;
import ra.common.Client;
import ra.common.DLC;
import ra.common.Envelope;
import ra.common.messaging.MessageProducer;
import ra.common.network.*;
import ra.common.route.Route;
import ra.common.service.ServiceStatus;
import ra.common.service.ServiceStatusListener;
import ra.notification.NotificationService;
import ra.util.Config;
import ra.util.SystemSettings;
import ra.util.Wait;
import ra.util.tasks.TaskRunner;

import java.io.*;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;

/**
 * Provides an API for I2P Router as a Service.
 */
public final class I2PService extends NetworkService {

    private static final Logger LOG = Logger.getLogger(I2PService.class.getName());

    public static final String OPERATION_SEND = "SEND";
    public static final String OPERATION_CHECK_ROUTER_STATUS = "CHECK_ROUTER_STATUS";
    public static final String OPERATION_LOCAL_PEER_COUNTRY = "LOCAL_PEER_COUNTRY";
    public static final String OPERATION_REMOTE_PEER_COUNTRY = "REMOTE_PEER_COUNTRY";
    public static final String OPERATION_IN_STRICT_COUNTRY = "IN_STRICT_COUNTRY";
    public static final String OPERATION_UPDATE_HIDDEN_MODE = "UPDATE_HIDDEN_MODE";
    public static final String OPERATION_UPDATE_SHARE_PERCENTAGE = "UPDATE_SHARE_PERCENTAGE";
    public static final String OPERATION_UPDATE_GEOIP_ENABLEMENT = "UPDATE_GEOIP_ENABLEMENT";
    public static final String OPERATION_ACTIVE_PEERS_COUNT = "ACTIVE_PEERS_COUNT";

    /**
     * 1 = ElGamal-2048 / DSA-1024
     * 2 = ECDH-256 / ECDSA-256
     * 3 = ECDH-521 / ECDSA-521
     * 4 = NTRUEncrypt-1087 / GMSS-512
     */
    protected static int ElGamal2048DSA1024 = 1;
    protected static int ECDH256ECDSA256 = 2;
    protected static int ECDH521EDCSA521 = 3;
    protected static int NTRUEncrypt1087GMSS512 = 4;

    // I2P Router and Context
    private File i2pDir;
    RouterContext routerContext;
    Router router;
    protected CommSystemFacade.Status i2pRouterStatus;

    private Thread taskRunnerThread;
    private Long startTimeBlockedMs = 0L;
    private static final Long BLOCK_TIME_UNTIL_RESTART = 3 * 60 * 1000L; // 4 minutes
    private Integer restartAttempts = 0;
    private static final Integer RESTART_ATTEMPTS_UNTIL_HARD_RESTART = 3;
    private boolean embedded = true;
    private boolean isTest = false;
    private TaskRunner taskRunner;
    private Map<String, I2PSessionBase> sessions = new HashMap<>();

    final Map<String,Long> inflightTimers = new HashMap<>();

    public I2PService() {
        super(Network.I2P.name());
    }

    public I2PService(MessageProducer messageProducer, ServiceStatusListener listener) {
        super(Network.I2P.name(), messageProducer, listener);
    }

    @Override
    public void handleDocument(Envelope e) {
        super.handleDocument(e);
        Route r = e.getRoute();
        switch(r.getOperation()) {
            case OPERATION_SEND: {
                sendOut(e);
                break;
            }
            case OPERATION_CHECK_ROUTER_STATUS: {
                checkRouterStats();
                break;
            }
            case OPERATION_LOCAL_PEER_COUNTRY: {
                NetworkPeer localPeer = getNetworkState().localPeer;
                if(localPeer==null) {
                    DLC.addNVP("country", "NoLocalPeer", e);
                } else {
                    DLC.addNVP("country", country(localPeer), e);
                }
                break;
            }
            case OPERATION_REMOTE_PEER_COUNTRY: {
                NetworkPeer remotePeer = (NetworkPeer)DLC.getValue("remotePeer", e);
                if(remotePeer==null) {
                    DLC.addNVP("country", "NoRemotePeer", e);
                } else {
                    DLC.addNVP("country", country(remotePeer), e);
                }
                break;
            }
            case OPERATION_IN_STRICT_COUNTRY: {
                NetworkPeer peer = (NetworkPeer)DLC.getValue("peer", e);
                if(peer==null) {
                    DLC.addNVP("localPeerCountry", inStrictCountry(), e);
                } else {
                    DLC.addNVP("peerCountry", inStrictCountry(peer), e);
                }
                break;
            }
            case OPERATION_UPDATE_HIDDEN_MODE: {
                Object hiddenModeObj = DLC.getValue("hiddenMode", e);
                if(hiddenModeObj!=null) {
                    updateHiddenMode((((String)hiddenModeObj).toLowerCase()).equals("true"));
                }
                break;
            }
            case OPERATION_UPDATE_SHARE_PERCENTAGE: {
                Object sharePerc = DLC.getValue("sharePercentage", e);
                if(sharePerc!=null) {
                    updateSharePercentage(Integer.parseInt((String)sharePerc));
                }
                break;
            }
            case OPERATION_UPDATE_GEOIP_ENABLEMENT: {
                Object sharePerc = DLC.getValue("enableGeoIP", e);
                if(sharePerc!=null) {
                    updateGeoIPEnablement((((String)sharePerc).toLowerCase()).equals("true"));
                }
                break;
            }
            case OPERATION_ACTIVE_PEERS_COUNT: {
                Integer count = activePeersCount();
                DLC.addNVP("activePeersCount", count, e);
                break;
            }
            default: {
                LOG.warning("Operation ("+r.getOperation()+") not supported. Sending to Dead Letter queue.");
                deadLetter(e);
            }
        }
    }

    private I2PSessionBase establishSession(String address, Boolean autoConnect) {
        if(address==null) {
            address = "default";
        }
        if(sessions.get(address)==null) {
            I2PSessionBase session = embedded ? new I2PSessionEmbedded(this) : new I2PSessionLocal(this);
            session.init(config);
            session.open(null);
            if (autoConnect) {
                session.connect();
            }
            sessions.put(address, session);
        }
        return sessions.get(address);
    }

    /**
     * Sends UTF-8 content to a Destination using I2P.
     * @param envelope Envelope containing Envelope as data.
     *                 To DID must contain base64 encoded I2P destination key.
     * @return boolean was successful
     */
    public Boolean sendOut(Envelope envelope) {
        LOG.fine("Send out Envelope over I2P...");
        NetworkClientSession session = establishSession(null, true);
        return session.send(envelope);
    }

    public File getDirectory() {
        return i2pDir;
    }

    private void updateHiddenMode(boolean hiddenMode) {
        String hiddenModeStr = hiddenMode?"true":"false";
        if(!(getNetworkState().params.get(Router.PROP_HIDDEN)).equals(hiddenModeStr)) {
            // Hidden mode changed so change for Router and restart
            this.getNetworkState().params.put(Router.PROP_HIDDEN, hiddenModeStr);
            if (router.saveConfig(Router.PROP_HIDDEN, hiddenModeStr)) {
                restart();
            } else {
                LOG.warning("Unable to update " + Router.PROP_HIDDEN);
            }
        }
    }

    private void updateSharePercentage(int sharePercentage) {
        if(!(getNetworkState().params.get("router.sharePercentage")).equals(String.valueOf(sharePercentage))) {
            // Share Percentage changed so change for Router and restart
            this.getNetworkState().params.put(Router.PROP_HIDDEN, String.valueOf(sharePercentage));
            if (router.saveConfig(Router.PROP_HIDDEN, String.valueOf(sharePercentage))) {
                restart();
            } else {
                LOG.warning("Unable to update router.sharePercentage");
            }
        }
    }

    private void updateGeoIPEnablement(boolean enableGeoIP) {
        String enableGeoIPStr = enableGeoIP?"true":"false";
        if(!(getNetworkState().params.get("routerconsole.geoip.enable")).equals(enableGeoIPStr)) {
            // Hidden mode changed so change for Router and restart
            this.getNetworkState().params.put("routerconsole.geoip.enable", enableGeoIPStr);
            if (router.saveConfig("routerconsole.geoip.enable", enableGeoIPStr)) {
                restart();
            } else {
                LOG.warning("Unable to update routerconsole.geoip.enable");
            }
        }
    }

    public boolean start(Properties p) {
        LOG.info("Starting I2P Service...");
        updateStatus(ServiceStatus.INITIALIZING);
        LOG.info("Loading I2P properties...");
        try {
            config = Config.loadFromClasspath("i2p-client.config", p, false);
        } catch (Exception e) {
            LOG.severe(e.getLocalizedMessage());
            return false;
        }
        if(config.getProperty("ra.i2p.maxKnownPeers")!=null) {
            maxKnownPeers = Integer.parseInt(config.getProperty("ra.i2p.maxKnownPeers"));
        }
        isTest = "true".equals(config.getProperty("ra.i2p.isTest"));
        // Look for another instance installed
        if(System.getProperty("i2p.dir.base")==null) {
            // Set up I2P Directories within RA Services Directory
            File homeDir = SystemSettings.getUserHomeDir();
            File raDir = new File(homeDir, ".ra");
            if(!raDir.exists() && !raDir.mkdir()) {
                LOG.severe("Unable to create home/.ra directory.");
                return false;
            }
            File servicesDir = new File(raDir, "services");
            if(!servicesDir.exists() && !servicesDir.mkdir()) {
                LOG.severe("Unable to create services directory in home/.ra");
                return false;
            }
            i2pDir = new File(servicesDir, I2PService.class.getName());
            if(!i2pDir.exists() && !i2pDir.mkdir()) {
                LOG.severe("Unable to create "+I2PService.class.getName()+" directory in home/.ra/services");
                return false;
            }
            System.setProperty("i2p.dir.base", i2pDir.getAbsolutePath());
            embedded = true;
        } else {
            i2pDir = new File(System.getProperty("i2p.dir.base"));
            embedded = false;
        }

        // Config Directory
        File i2pConfigDir = new File(i2pDir, "config");
        if(!i2pConfigDir.exists())
            if(!i2pConfigDir.mkdir())
                LOG.warning("Unable to create I2P config directory: " +i2pConfigDir);
        if(i2pConfigDir.exists()) {
            System.setProperty("i2p.dir.config",i2pConfigDir.getAbsolutePath());
            config.setProperty("i2p.dir.config",i2pConfigDir.getAbsolutePath());
        }
        // Router Directory
        File i2pRouterDir = new File(i2pDir,"router");
        if(!i2pRouterDir.exists())
            if(!i2pRouterDir.mkdir())
                LOG.warning("Unable to create I2P router directory: "+i2pRouterDir);
        if(i2pRouterDir.exists()) {
            System.setProperty("i2p.dir.router",i2pRouterDir.getAbsolutePath());
            config.setProperty("i2p.dir.router",i2pRouterDir.getAbsolutePath());
        }
        // PID Directory
        File i2pPIDDir = new File(i2pDir, "pid");
        if(!i2pPIDDir.exists())
            if(!i2pPIDDir.mkdir())
                LOG.warning("Unable to create I2P PID directory: "+i2pPIDDir.getAbsolutePath());
        if(i2pPIDDir.exists()) {
            System.setProperty("i2p.dir.pid",i2pPIDDir.getAbsolutePath());
            config.setProperty("i2p.dir.pid",i2pPIDDir.getAbsolutePath());
        }
        // Log Directory
        File i2pLogDir = new File(i2pDir,"log");
        if(!i2pLogDir.exists())
            if(!i2pLogDir.mkdir())
                LOG.warning("Unable to create I2P log directory: "+i2pLogDir.getAbsolutePath());
        if(i2pLogDir.exists()) {
            System.setProperty("i2p.dir.log",i2pLogDir.getAbsolutePath());
            config.setProperty("i2p.dir.log",i2pLogDir.getAbsolutePath());
        }
        // App Directory
        File i2pAppDir = new File(i2pDir,"app");
        if(!i2pAppDir.exists())
            if(!i2pAppDir.mkdir())
                LOG.warning("Unable to create I2P app directory: "+i2pAppDir.getAbsolutePath());
        if(i2pAppDir.exists()) {
            System.setProperty("i2p.dir.app", i2pAppDir.getAbsolutePath());
            config.setProperty("i2p.dir.app", i2pAppDir.getAbsolutePath());
        }

        // Running Internal I2P Router
        System.setProperty(I2PClient.PROP_TCP_HOST, "internal");
        System.setProperty(I2PClient.PROP_TCP_PORT, "internal");

        // Merge router.config files
        mergeRouterConfig(null);

        // Certificates
        File certDir = new File(i2pDir, "certificates");
        if(!certDir.exists())
            if(!certDir.mkdir()) {
                LOG.severe("Unable to create certificates directory in: "+certDir.getAbsolutePath()+"; exiting...");
                return false;
            }
        File seedDir = new File(certDir, "reseed");
        if(!seedDir.exists())
            if(!seedDir.mkdir()) {
                LOG.severe("Unable to create "+seedDir.getAbsolutePath()+" directory; exiting...");
                return false;
            }
        File sslDir = new File(certDir, "ssl");
        if(!sslDir.exists())
            if(!sslDir.mkdir()) {
                LOG.severe("Unable to create "+sslDir.getAbsolutePath()+" directory; exiting...");
                return false;
            }

        File seedCertificates = new File(certDir, "reseed");
//        File[] allSeedCertificates = seedCertificates.listFiles();
//        if ( allSeedCertificates != null) {
//            for (File f : allSeedCertificates) {
//                LOG.info("Deleting old seed certificate: " + f);
//                FileUtil.rmdir(f, false);
//            }
//        }

        File sslCertificates = new File(certDir, "ssl");
//        File[] allSSLCertificates = sslCertificates.listFiles();
//        if ( allSSLCertificates != null) {
//            for (File f : allSSLCertificates) {
//                LOG.info("Deleting old ssl certificate: " + f);
//                FileUtil.rmdir(f, false);
//            }
//        }

        if(!copyCertificatesToBaseDir(seedCertificates, sslCertificates))
            return false;

        // Set dependent services
//        addDependentService(NotificationService.class);

        // TODO: Load multiple seeds from a seeds.json file
        NetworkPeer seedA = new NetworkPeer(Network.I2P.name());
        seedA.getDid().getPublicKey().setAddress("I7SBNbVvrKB3thzOW6g49Mh6GpGZW~SiCwP~SgavJjy7lOWau2G2e71hgM1t7ymTRPIm9qfjP6g1tuzoP6eN3KRnnfYniISkvgvu5MU27Bvnf2BnIpiDGCfvmgIltUefX3ZVa7GSFtnTJobTlxFa0JEjfMSupuhEOnsApobo~Ux8DfSuoFfD0Fx9IdeBvMi~4nJHK7bGAx~LiNwdYVTGVwIEW0lGlEi8sLpymb0VhCxl8yo79AUWH-gD4LUJwy8ZVvovp0C2-BnWAwuIVPSWNepHB7Z6a0v6TF70lVZoXmJICDKho72uejYVgptZ~ugSdZRrXS6OiraMq1G39eLSSkxKQGgxL4G3-L~Mm5AYYg49G48KN1XJdROOjQSCxp3cRD1tbsjCVvB4xkjbmv-TbHF9OmrDzqwlT6WWigxxPMv~EyHmGJmanz80aOf3cJOHAd7OjK2sDfVPoqFW1NCt4vq4Nbu4wzUQeakwbB~eZS7NkuINqlVc06ke34MXgjYEAAAA");
        seedA.getDid().getPublicKey().setFingerprint("WLlzrHpbI2ABJShBCFJF5f1nh1CI6U2iT6~HS2Al~~U=");
        seedPeers.put(seedA.getDid().getPublicKey().getFingerprint(), seedA);

        updateStatus(ServiceStatus.STARTING);
        // Start I2P Router
        LOG.info("Launching I2P Router...");
        RouterLaunch.main(null);
        List<RouterContext> routerContexts = RouterContext.listContexts();
        routerContext = routerContexts.get(0);
        router = routerContext.router();
        // TODO: Give end users ability to change this
//            if(config.params.get(Router.PROP_HIDDEN)!=null) {
//                router.saveConfig(Router.PROP_HIDDEN, (String)config.params.get(Router.PROP_HIDDEN));
//            }
//            router.saveConfig(Router.PROP_HIDDEN, "false");
        LOG.info("I2P Router - Hidden Mode: "+router.getConfigSetting(Router.PROP_HIDDEN));
        for(String param : router.getConfigMap().keySet()) {
            getNetworkState().params.put(param, router.getConfigSetting(param));
        }
        router.setKillVMOnEnd(false);
//        routerContext.addShutdownTask(this::shutdown);
        // TODO: Hard code to INFO for now for troubleshooting; need to move to configuration
        routerContext.logManager().setDefaultLimit(Log.STR_INFO);
        routerContext.logManager().setFileSize(100000000); // 100 MB

        Wait.aMs(500); // Give the router a bit of breathing room before launching tasks

        if(taskRunner==null) {
            taskRunner = new TaskRunner(2, 2);
            taskRunner.setPeriodicity(1000L); // Default check every second
            CheckRouterStatus statusChecker = new CheckRouterStatus(this, taskRunner);
            statusChecker.setPeriodicity(30 * 1000L); // Check status every 30 seconds
            taskRunner.addTask(statusChecker);
            I2PNetworkDiscovery discovery = new I2PNetworkDiscovery(this, seedPeers, taskRunner);
            discovery.setPeriodicity(120 * 1000L); // Set periodicity to 30 seconds longer than I2P network timeout (90 seconds).
            taskRunner.addTask(discovery);
        }

        taskRunnerThread = new Thread(taskRunner);
        taskRunnerThread.setDaemon(true);
        taskRunnerThread.setName("I2PService-TaskRunnerThread");
        taskRunnerThread.start();

        return true;
    }

    @Override
    public boolean pause() {
        return false;
    }

    @Override
    public boolean unpause() {
        return false;
    }

    @Override
    public boolean restart() {
        if(router==null) {
            router = routerContext.router();
            if(router==null) {
                LOG.severe("Unable to restart I2P Router. Router instance not found in RouterContext.");
                return false;
            }
        }
        if(restartAttempts.equals(RESTART_ATTEMPTS_UNTIL_HARD_RESTART)) {
            LOG.info("Full restart of I2P Router...");
            if(!shutdown()) {
                LOG.warning("Issues shutting down I2P Router. Will attempt to start regardless...");
            }
            if(!start(config)) {
                LOG.warning("Issues starting I2P Router.");
                return false;
            } else {
                LOG.info("Hard restart of I2P Router completed.");
            }
        } else {
            LOG.info("Soft restart of I2P Router...");
            updateStatus(ServiceStatus.RESTARTING);
            router.restart();
            int maxWaitSec = 10 * 60; // 10 minutes
            int currentWait = 0;
            while(!routerContext.router().isAlive()) {
                Wait.aSec(10);
                currentWait+=10;
                if(currentWait > maxWaitSec) {
                    LOG.warning("Restart failed.");
                    return false;
                }
            }
            LOG.info("Router hiddenMode="+router.isHidden());
            LOG.info("I2P Router soft restart completed.");
        }
        return true;
    }

    @Override
    public boolean shutdown() {
        updateStatus(ServiceStatus.SHUTTING_DOWN);
        LOG.info("I2P router stopping...");
        taskRunner.shutdown();
        if(taskRunnerThread!=null) {
            taskRunnerThread.interrupt();
        }
        taskRunner = null;
        taskRunnerThread = null;
        for(NetworkClientSession s : sessions.values()) {
            s.disconnect();
            s.close();
        }
        sessions.clear();
        if(router != null) {
            router.shutdown(Router.EXIT_HARD);
        }
        router = null;
        updateStatus(ServiceStatus.SHUTDOWN);
        LOG.info("I2P router stopped.");
        return true;
    }

    @Override
    public boolean gracefulShutdown() {
        updateStatus(ServiceStatus.GRACEFULLY_SHUTTING_DOWN);
        LOG.info("I2P router gracefully stopping...");
        taskRunner.shutdown();
        if(taskRunnerThread!=null) {
            taskRunnerThread.interrupt();
        }
        taskRunner = null;
        taskRunnerThread = null;
        for(NetworkClientSession s : sessions.values()) {
            s.disconnect();
            s.close();
        }
        sessions.clear();
        if(router != null) {
            router.shutdownGracefully(Router.EXIT_GRACEFUL);
        }
        router = null;
        updateStatus(ServiceStatus.GRACEFULLY_SHUTDOWN);
        LOG.info("I2P router gracefully stopped.");
        return true;
    }

    public void reportRouterStatus() {
        switch (i2pRouterStatus) {
            case UNKNOWN:
                LOG.info("Testing I2P Network...");
                updateNetworkStatus(NetworkStatus.CONNECTING);
                break;
            case IPV4_DISABLED_IPV6_UNKNOWN:
                LOG.info("IPV4 Disabled but IPV6 Testing...");
                updateNetworkStatus(NetworkStatus.CONNECTING);
                break;
            case IPV4_FIREWALLED_IPV6_UNKNOWN:
                LOG.info("IPV4 Firewalled but IPV6 Testing...");
                updateNetworkStatus(NetworkStatus.CONNECTING);
                break;
            case IPV4_SNAT_IPV6_UNKNOWN:
                LOG.info("IPV4 SNAT but IPV6 Testing...");
                updateNetworkStatus(NetworkStatus.CONNECTING);
                break;
            case IPV4_UNKNOWN_IPV6_FIREWALLED:
                LOG.info("IPV6 Firewalled but IPV4 Testing...");
                updateNetworkStatus(NetworkStatus.CONNECTING);
                break;
            case OK:
                LOG.info("Connected to I2P Network. We are able to receive unsolicited connections.");
                restartAttempts = 0; // Reset restart attempts
                updateNetworkStatus(NetworkStatus.CONNECTED);
                break;
            case IPV4_DISABLED_IPV6_OK:
                LOG.info("IPV4 Disabled but IPV6 OK: Connected to I2P Network.");
                restartAttempts = 0; // Reset restart attempts
                updateNetworkStatus(NetworkStatus.CONNECTED);
                break;
            case IPV4_FIREWALLED_IPV6_OK:
                LOG.info("IPV4 Firewalled but IPV6 OK: Connected to I2P Network.");
                restartAttempts = 0; // Reset restart attempts
                updateNetworkStatus(NetworkStatus.CONNECTED);
                break;
            case IPV4_SNAT_IPV6_OK:
                LOG.info("IPV4 SNAT but IPV6 OK: Connected to I2P Network.");
                restartAttempts = 0; // Reset restart attempts
                updateNetworkStatus(NetworkStatus.CONNECTED);
                break;
            case IPV4_UNKNOWN_IPV6_OK:
                LOG.info("IPV4 Testing but IPV6 OK: Connected to I2P Network.");
                restartAttempts = 0; // Reset restart attempts
                updateNetworkStatus(NetworkStatus.CONNECTED);
                break;
            case IPV4_OK_IPV6_FIREWALLED:
                LOG.info("IPV6 Firewalled but IPV4 OK: Connected to I2P Network.");
                restartAttempts = 0; // Reset restart attempts
                updateNetworkStatus(NetworkStatus.CONNECTED);
                break;
            case IPV4_OK_IPV6_UNKNOWN:
                LOG.info("IPV6 Testing but IPV4 OK: Connected to I2P Network.");
                restartAttempts = 0; // Reset restart attempts
                updateNetworkStatus(NetworkStatus.CONNECTED);
                break;
            case IPV4_DISABLED_IPV6_FIREWALLED:
                LOG.warning("IPV4 Disabled but IPV6 Firewalled. Connected to I2P network.");
                restartAttempts = 0; // Reset restart attempts
                updateNetworkStatus(NetworkStatus.CONNECTED);
                break;
            case REJECT_UNSOLICITED:
                LOG.info("We are able to talk to peers that we initiate communication with, but cannot receive unsolicited connections. Connected to I2P network.");
                restartAttempts = 0; // Reset restart attempts
                updateNetworkStatus(NetworkStatus.CONNECTED);
                break;
            case DISCONNECTED:
                LOG.info("Disconnected from I2P Network.");
                updateNetworkStatus(NetworkStatus.DISCONNECTED);
                restart();
                break;
            case DIFFERENT:
                LOG.warning("Symmetric NAT: We are behind a symmetric NAT which will make our 'from' address look differently when we talk to multiple people.");
                updateNetworkStatus(NetworkStatus.BLOCKED);
                break;
            case HOSED:
                LOG.warning("Unable to open UDP port for I2P - Port Conflict. Verify another instance of I2P is not running.");
                updateNetworkStatus(NetworkStatus.PORT_CONFLICT);
                break;
            default: {
                LOG.warning("Not connected to I2P Network.");
                updateNetworkStatus(NetworkStatus.DISCONNECTED);
            }
        }
        if(getNetworkState().networkStatus==NetworkStatus.CONNECTED && sessions.size()==0) {
            LOG.info("Network Connected and no Sessions.");
            if(routerContext.commSystem().isInStrictCountry()) {
                LOG.warning("This peer is in a 'strict' country defined by I2P.");
            }
            if(routerContext.router().isHidden()) {
                LOG.warning("I2P Router is in Hidden mode. I2P Service setting for hidden mode: "+config.getProperty("ra.i2p.hidden"));
            }
            LOG.info("Establishing Session to speed up future outgoing messages...");
            establishSession(null, true);
        }
    }

    private CommSystemFacade.Status getRouterStatus() {
        return routerContext.commSystem().getStatus();
    }

    public void checkRouterStats() {
        if(routerContext==null)
            return; // Router not yet established
        CommSystemFacade.Status reportedStatus = getRouterStatus();
        if(i2pRouterStatus != reportedStatus) {
            // Status changed
            i2pRouterStatus = reportedStatus;
            LOG.info("I2P Router Status changed to: "+i2pRouterStatus.name());
            reportRouterStatus();
        }
    }

    private Integer activePeersCount() {
        return routerContext.commSystem().countActivePeers();
    }

    private Boolean unreachable(NetworkPeer networkPeer) {
        if(networkPeer==null || networkPeer.getDid().getPublicKey().getAddress()==null) {
            LOG.warning("Network Peer with address is required to determine if peer is unreachable.");
            return false;
        }
        I2PSessionBase session = establishSession("default", true);
        Destination dest = session.lookupDest(networkPeer.getDid().getPublicKey().getAddress());
        return routerContext.commSystem().wasUnreachable(dest.getHash());
    }

    private Boolean inStrictCountry() {
        return routerContext.commSystem().isInStrictCountry();
    }

    private Boolean inStrictCountry(NetworkPeer networkPeer) {
        if(networkPeer==null || networkPeer.getDid().getPublicKey().getAddress()==null) {
            LOG.warning("Network Peer with address is required to determine if peer is in strict country.");
            return false;
        }
        I2PSessionBase session = establishSession("default", true);
        Destination dest = session.lookupDest(networkPeer.getDid().getPublicKey().getAddress());
        return routerContext.commSystem().isInStrictCountry(dest.getHash());
    }

    private Boolean backlogged(NetworkPeer networkPeer) {
        if(networkPeer==null || networkPeer.getDid().getPublicKey().getAddress()==null) {
            LOG.warning("Network Peer with address is required to determine if peer is backlogged.");
            return false;
        }
        I2PSessionBase session = establishSession("default", true);
        Destination dest = session.lookupDest(networkPeer.getDid().getPublicKey().getAddress());
        return routerContext.commSystem().isBacklogged(dest.getHash());
    }

    private Boolean established(NetworkPeer networkPeer) {
        if(networkPeer==null || networkPeer.getDid().getPublicKey().getAddress()==null) {
            LOG.warning("Network Peer with address is required to determine if peer is established.");
            return false;
        }
        I2PSessionBase session = establishSession("default", true);
        Destination dest = session.lookupDest(networkPeer.getDid().getPublicKey().getAddress());
        return routerContext.commSystem().isEstablished(dest.getHash());
    }

    private String country(NetworkPeer networkPeer) {
        if(networkPeer==null || networkPeer.getDid().getPublicKey().getAddress()==null) {
            LOG.warning("Network Peer with address is required to determine country of peer.");
            return "NoPeer";
        }
        I2PSessionBase session = establishSession("default", true);
        Destination dest = session.lookupDest(networkPeer.getDid().getPublicKey().getAddress());
        return routerContext.commSystem().getCountry(dest.getHash());
    }

    /**
     *  Load defaults from internal router.config on classpath,
     *  then add props from i2pDir/router.config overriding any from internal router.config,
     *  then override these with the supplied overrides if not null which would likely come from 3rd party app (not yet supported),
     *  then write back to i2pDir/router.config.
     *
     *  @param overrides local overrides or null
     */
    private void mergeRouterConfig(Properties overrides) {
        Properties props = new OrderedProperties();
        File f = new File(i2pDir,"router.config");
        boolean i2pBaseRouterConfigIsNew = false;
        if(!f.exists()) {
            if(!f.mkdir()) {
                LOG.warning("While merging router.config files, unable to create router.config in i2pBaseDirectory: "+i2pDir.getAbsolutePath());
            } else {
                i2pBaseRouterConfigIsNew = true;
            }
        }
        InputStream i2pBaseRouterConfig = null;
        try {
            props.putAll(Config.loadFromClasspath("router.config"));

            if(!i2pBaseRouterConfigIsNew) {
                i2pBaseRouterConfig = new FileInputStream(f);
                DataHelper.loadProps(props, i2pBaseRouterConfig);
            }

            // override with user settings
            if (overrides != null)
                props.putAll(overrides);

            DataHelper.storeProps(props, f);
        } catch (Exception e) {
            LOG.warning("Exception caught while merging router.config properties: "+e.getLocalizedMessage());
        } finally {
            if (i2pBaseRouterConfig != null) try {
                i2pBaseRouterConfig.close();
            } catch (IOException ioe) {
            }
        }
    }

    /**
     *  Copy all certificates found in certificates on classpath
     *  into i2pDir/certificates
     *
     *  @param reseedCertificates destination directory for reseed certificates
     *  @param sslCertificates destination directory for ssl certificates
     */
    private boolean copyCertificatesToBaseDir(File reseedCertificates, File sslCertificates) {
        if(!SystemVersion.isAndroid()) {
            String jarPath = getClass().getProtectionDomain().getCodeSource().getLocation().getPath();
            final File jarFile = new File(jarPath);
            if (jarFile.isFile()) {
                try {
                    final JarFile jar = new JarFile(jarFile);
                    JarEntry entry;
                    File f = null;
                    final Enumeration<JarEntry> entries = jar.entries(); //gives ALL entries in jar
                    while (entries.hasMoreElements()) {
                        entry = entries.nextElement();
                        final String name = entry.getName();
                        if (name.startsWith("certificates/reseed/")) { //filter according to the path
                            if (!name.endsWith("/")) {
                                String fileName = name.substring(name.lastIndexOf("/") + 1);
                                LOG.info("fileName to save: " + fileName);
                                f = new File(reseedCertificates, fileName);
                            }
                        }
                        if (name.startsWith("certificates/ssl/")) {
                            if (!name.endsWith("/")) {
                                String fileName = name.substring(name.lastIndexOf("/") + 1);
                                LOG.info("fileName to save: " + fileName);
                                f = new File(sslCertificates, fileName);
                            }
                        }
                        if (f != null) {
                            boolean fileReadyToSave = false;
                            if (!f.exists() && f.createNewFile()) fileReadyToSave = true;
                            else if (f.exists() && f.delete() && f.createNewFile()) fileReadyToSave = true;
                            if (fileReadyToSave) {
                                FileOutputStream fos = new FileOutputStream(f);
                                byte[] byteArray = new byte[1024];
                                int i;
                                InputStream is = getClass().getClassLoader().getResourceAsStream(name);
                                //While the input stream has bytes
                                while ((i = is.read(byteArray)) > 0) {
                                    //Write the bytes to the output stream
                                    fos.write(byteArray, 0, i);
                                }
                                //Close streams to prevent errors
                                is.close();
                                fos.close();
                                f = null;
                            } else {
                                LOG.warning("Unable to save file from 1M5 jar and is required: " + name);
                                return false;
                            }
                        }
                    }
                    jar.close();
                } catch (IOException e) {
                    LOG.warning(e.getLocalizedMessage());
                    return false;
                }
            } else {
                // called while testing in an IDE
                URL resource = I2PService.class.getClassLoader().getResource(".");
                File file = null;
                try {
                    file = new File(resource.toURI());
                } catch (URISyntaxException e) {
                    LOG.warning("Unable to access I2P resource directory.");
                    return false;
                }
                File[] resFolderFiles = file.listFiles();
                File certResFolder = null;
                for (File f : resFolderFiles) {
                    if ("certificates".equals(f.getName())) {
                        certResFolder = f;
                        break;
                    }
                }
                if (certResFolder != null) {
                    File[] folders = certResFolder.listFiles();
                    for (File folder : folders) {
                        if ("reseed".equals(folder.getName())) {
                            File[] reseedCerts = folder.listFiles();
                            for (File reseedCert : reseedCerts) {
                                FileUtil.copy(reseedCert, reseedCertificates, true, false);
                            }
                        } else if ("ssl".equals(folder.getName())) {
                            File[] sslCerts = folder.listFiles();
                            for (File sslCert : sslCerts) {
                                FileUtil.copy(sslCert, sslCertificates, true, false);
                            }
                        }
                    }
                    return true;
                }
                return false;
            }
        }
        return true;
    }

    public static void main(String[] args) {
        MessageProducer messageProducer = new MessageProducer() {
            @Override
            public boolean send(Envelope envelope) {
                LOG.info(envelope.toJSON());
                return true;
            }

            @Override
            public boolean send(Envelope envelope, Client client) {
                LOG.info(envelope.toJSON());
                return true;
            }
        };
        I2PService service = new I2PService(messageProducer, null);
        service.start(null);
        while(true) {
            Wait.aSec(1);
        }
    }

}
