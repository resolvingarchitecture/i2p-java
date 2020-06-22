package ra.i2p;

import io.onemfive.data.*;
import io.onemfive.network.NetworkPacket;
import io.onemfive.network.NetworkState;
import io.onemfive.network.peers.NetworkPeerDiscovery;
import io.onemfive.network.sensors.*;
import io.onemfive.util.Config;
import io.onemfive.util.Wait;
import io.onemfive.util.tasks.TaskRunner;
import net.i2p.client.I2PClient;
import net.i2p.data.DataHelper;
import net.i2p.router.CommSystemFacade;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.RouterLaunch;
import net.i2p.util.FileUtil;
import net.i2p.util.Log;
import net.i2p.util.OrderedProperties;
import net.i2p.util.SystemVersion;

import java.io.*;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;

/**
 * Provides an API for I2P Router as a Sensor.
 * I2P in 1M5 is used as Message-Oriented-Middleware (MOM)
 * supporting real-time anonymous messaging.
 *
 */
public final class I2P extends BaseSensor {

    private static final Logger LOG = Logger.getLogger(io.onemfive.network.sensors.i2p.I2PSensor.class.getName());

    public static final String I2P_ROUTER_EMBEDDED = "settings.network.i2p.routerEmbedded";

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

    public static final NetworkPeer seedAI2P;

    static {
        seedAI2P = new NetworkPeer(Network.I2P);
        seedAI2P.setId("+sKVViuz2FPsl/XQ+Da/ivbNfOI=");
        seedAI2P.getDid().getPublicKey().setAddress("ygfTZm-Cwhs9FI05gwHC3hr360gpcp103KRUSubJ2xvaEhFXzND8emCKXSAZLrIubFoEct5lmPYjXegykkWZOsjdvt8ZWZR3Wt79rc3Ovk7Ev4WXrgIDHjhpr-cQdBITSFW8Ay1YvArKxuEVpIChF22PlPbDg7nRyHXOqmYmrjo2AcwObs--mtH34VMy4R934PyhfEkpLZTPyN73qO4kgvrBtmpOxdWOGvlDbCQjhSAC3018xpM0qFdFSyQwZkHdJ9sG7Mov5dmG5a6D6wRx~5IEdfufrQi1aR7FEoomtys-vAAF1asUyX1UkxJ2WT2al8eIuCww6Nt6U6XfhN0UbSjptbNjWtK-q4xutcreAu3FU~osZRaznGwCHez5arT4X2jLXNfSEh01ICtT741Ki4aeSrqRFPuIove2tmUHZPt4W6~WMztvf5Oc58jtWOj08HBK6Tc16dzlgo9kpb0Vs3h8cZ4lavpRen4i09K8vVORO1QgD0VH3nIZ5Ql7K43zAAAA");
        seedAI2P.getDid().getPublicKey().setFingerprint("bl4fi-lFyTPQQkKOPuxlF9zPGEdgtAhtKetnyEwj8t0=");
        seedAI2P.getDid().getPublicKey().setType("ElGamal/None/NoPadding");
        seedAI2P.getDid().getPublicKey().isIdentityKey(true);
        seedAI2P.getDid().getPublicKey().setBase64Encoded(true);
    }

    protected Properties properties;

    // I2P Router and Context
    private File i2pDir;
    RouterContext routerContext;
    Router router;
    protected CommSystemFacade.Status i2pRouterStatus;

    private String i2pBaseDir;
    protected String i2pAppDir;

    private Thread taskRunnerThread;
    private Long startTimeBlockedMs = 0L;
    private static final Long BLOCK_TIME_UNTIL_RESTART = 3 * 60 * 1000L; // 4 minutes
    private Integer restartAttempts = 0;
    private static final Integer RESTART_ATTEMPTS_UNTIL_HARD_RESTART = 3;
    private boolean embedded = true;
    private boolean isTest = false;

    // Tasks
    private CheckRouterStats checkRouterStats;
    private NetworkPeerDiscovery discovery;

    public I2P() {
        super(Network.I2P);
        taskRunner = new TaskRunner(1, 2);
    }

    public I2P(SensorManager sensorManager) {
        super(sensorManager, Network.I2P);
        taskRunner = new TaskRunner(1, 2);
    }

    @Override
    public String[] getOperationEndsWith() {
        return new String[]{".i2p"};
    }

    @Override
    public String[] getURLBeginsWith() {
        return new String[]{"i2p"};
    }

    @Override
    public String[] getURLEndsWith() {
        return new String[]{".i2p"};
    }

    @Override
    public SensorSession establishSession(String address, Boolean autoConnect) {
        if(address==null) {
            address = "default";
        }
        if(sessions.get(address)==null) {
            SensorSession sensorSession = embedded ? new I2PSensorSessionEmbedded(this) : new io.onemfive.network.sensors.i2p.I2PSensorSessionExternal(this);
            sensorSession.init(properties);
            sensorSession.open(null);
            if (autoConnect) {
                sensorSession.connect();
            }
            sessions.put(address, sensorSession);
        }
        return sessions.get(address);
    }

    /**
     * Sends UTF-8 content to a Destination using I2P.
     * @param packet Packet containing Envelope as data.
     *                 To DID must contain base64 encoded I2P destination key.
     * @return boolean was successful
     */
    @Override
    public boolean sendOut(NetworkPacket packet) {
        LOG.info("Send I2P Message Out Packet received...");
        SensorSession sensorSession = establishSession(null, true);
        return sensorSession.send(packet);
    }

    public File getDirectory() {
        if(i2pDir==null) {
            i2pDir = new File(i2pBaseDir);
        }
        return i2pDir;
    }

    @Override
    public void updateState(NetworkState networkState) {
        if(this.networkState.params.get(Router.PROP_HIDDEN)!=null
                && networkState.params.get(Router.PROP_HIDDEN)!=null
                && this.networkState.params.get(Router.PROP_HIDDEN)!= networkState.params.get(Router.PROP_HIDDEN)) {
            // Hidden mode changed so change for Router and restart
            this.networkState.params.put(Router.PROP_HIDDEN, networkState.params.get(Router.PROP_HIDDEN));
            router.saveConfig(Router.PROP_HIDDEN, (String) this.networkState.params.get(Router.PROP_HIDDEN));
            restart();
        }
    }

    @Override
    public boolean start(Properties p) {
        LOG.info("Starting I2P Sensor...");
        updateStatus(SensorStatus.INITIALIZING);
        // I2P Sensor Starting
        LOG.info("Loading I2P properties...");
        properties = p;
        String isEmbeddedParam = p.getProperty(I2P_ROUTER_EMBEDDED);
        embedded = "true".equals(isEmbeddedParam);
        networkState.params.put(I2P_ROUTER_EMBEDDED, isEmbeddedParam);
        isTest = "true".equals(properties.getProperty("1m5.sensors.i2p.isTest"));
        // Look for another instance installed
        if(System.getProperty("i2p.dir.base")==null) {
            // Set up I2P Directories within sensors directory
            i2pBaseDir = properties.getProperty("1m5.dir.sensors") + "/i2p";
            System.setProperty("i2p.dir.base", i2pBaseDir);
        } else {
            i2pBaseDir = System.getProperty("i2p.dir.base");
        }
        i2pDir = new File(i2pBaseDir);
        if (!i2pDir.exists()) {
            if (!i2pDir.mkdir()) {
                LOG.severe("Unable to create I2P base directory: " + i2pBaseDir + "; exiting...");
                return false;
            }
        }
        properties.setProperty("i2p.dir.base", i2pBaseDir);
        properties.setProperty("1m5.dir.sensors.i2p", i2pBaseDir);
        // Config Directory
        String i2pConfigDir = i2pBaseDir + "/config";
        File i2pConfigFolder = new File(i2pConfigDir);
        if(!i2pConfigFolder.exists())
            if(!i2pConfigFolder.mkdir())
                LOG.warning("Unable to create I2P config directory: " +i2pConfigDir);
        if(i2pConfigFolder.exists()) {
            System.setProperty("i2p.dir.config",i2pConfigDir);
            properties.setProperty("i2p.dir.config",i2pConfigDir);
        }
        // Router Directory
        String i2pRouterDir = i2pBaseDir + "/router";
        File i2pRouterFolder = new File(i2pRouterDir);
        if(!i2pRouterFolder.exists())
            if(!i2pRouterFolder.mkdir())
                LOG.warning("Unable to create I2P router directory: "+i2pRouterDir);
        if(i2pRouterFolder.exists()) {
            System.setProperty("i2p.dir.router",i2pRouterDir);
            properties.setProperty("i2p.dir.router",i2pRouterDir);
        }
        // PID Directory
        String i2pPIDDir = i2pBaseDir + "/pid";
        File i2pPIDFolder = new File(i2pPIDDir);
        if(!i2pPIDFolder.exists())
            if(!i2pPIDFolder.mkdir())
                LOG.warning("Unable to create I2P PID directory: "+i2pPIDDir);
        if(i2pPIDFolder.exists()) {
            System.setProperty("i2p.dir.pid",i2pPIDDir);
            properties.setProperty("i2p.dir.pid",i2pPIDDir);
        }
        // Log Directory
        String i2pLogDir = i2pBaseDir + "/log";
        File i2pLogFolder = new File(i2pLogDir);
        if(!i2pLogFolder.exists())
            if(!i2pLogFolder.mkdir())
                LOG.warning("Unable to create I2P log directory: "+i2pLogDir);
        if(i2pLogFolder.exists()) {
            System.setProperty("i2p.dir.log",i2pLogDir);
            properties.setProperty("i2p.dir.log",i2pLogDir);
        }
        // App Directory
        i2pAppDir = i2pBaseDir + "/app";
        File i2pAppFolder = new File(i2pAppDir);
        if(!i2pAppFolder.exists())
            if(!i2pAppFolder.mkdir())
                LOG.warning("Unable to create I2P app directory: "+i2pAppDir);
        if(i2pAppFolder.exists()) {
            System.setProperty("i2p.dir.app", i2pAppDir);
            properties.setProperty("i2p.dir.app", i2pAppDir);
        }

        // Running Internal I2P Router
        System.setProperty(I2PClient.PROP_TCP_HOST, "internal");
        System.setProperty(I2PClient.PROP_TCP_PORT, "internal");

        // Merge router.config files
        mergeRouterConfig(null);

        // Certificates
        File certDir = new File(i2pBaseDir, "certificates");
        if(!certDir.exists())
            if(!certDir.mkdir()) {
                LOG.severe("Unable to create certificates directory in: "+i2pBaseDir+"; exiting...");
                return false;
            }
        File seedDir = new File(certDir, "reseed");
        if(!seedDir.exists())
            if(!seedDir.mkdir()) {
                LOG.severe("Unable to create "+i2pBaseDir+"/certificates/reseed directory; exiting...");
                return false;
            }
        File sslDir = new File(certDir, "ssl");
        if(!sslDir.exists())
            if(!sslDir.mkdir()) {
                LOG.severe("Unable to create "+i2pBaseDir+"/certificates/ssl directory; exiting...");
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

        Wait.aMs(500); // Give the infrastructure a bit of breathing room before saving seeds
        sensorManager.getPeerManager().savePeer(seedAI2P, true);
        networkState.seeds.add(seedAI2P);

        updateStatus(SensorStatus.STARTING);
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
            networkState.params.put(param, router.getConfigSetting(param));
        }
        router.setKillVMOnEnd(false);
//        routerContext.addShutdownTask(this::shutdown);
        // TODO: Hard code to INFO for now for troubleshooting; need to move to configuration
        routerContext.logManager().setDefaultLimit(Log.STR_INFO);
        routerContext.logManager().setFileSize(100000000); // 100 MB

        if(taskRunner==null) {
            taskRunner = new TaskRunner(2, 2);
        }

        checkRouterStats = new io.onemfive.network.sensors.i2p.CheckRouterStats(taskRunner, this);
        checkRouterStats.setPeriodicity(3 * 1000L);
        taskRunner.addTask(checkRouterStats);

        taskRunnerThread = new Thread(taskRunner);
        taskRunnerThread.setDaemon(true);
        taskRunnerThread.setName("I2PSensor-TaskRunnerThread");
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
        }
        if(router != null) {
            if(restartAttempts.equals(RESTART_ATTEMPTS_UNTIL_HARD_RESTART)) {
                LOG.info("Full restart of I2P Router...");
                if(!shutdown()) {
                    LOG.warning("Issues shutting down I2P Router. Will attempt to start regardless...");
                }
                if(!start(properties)) {
                    LOG.warning("Issues starting I2P Router.");
                    return false;
                } else {
                    LOG.info("Hard restart of I2P Router completed.");
                }
            } else {
                LOG.info("Soft restart of I2P Router...");
                updateStatus(SensorStatus.RESTARTING);
                router.restart();
                updateModelListeners();
                LOG.info("Router hiddenMode="+router.isHidden());
                LOG.info("I2P Router soft restart completed.");
            }
            return true;
        } else {
            LOG.warning("Unable to restart I2P Router. Router instance not found in RouterContext.");
        }
        return false;
    }

    @Override
    public boolean shutdown() {
        updateStatus(SensorStatus.SHUTTING_DOWN);
        LOG.info("I2P router stopping...");
        checkRouterStats.forceStop();
        taskRunner.shutdown();
        if(taskRunnerThread!=null) {
            taskRunnerThread.interrupt();
        }
        taskRunner = null;
        taskRunnerThread = null;
        for(SensorSession s : sessions.values()) {
            s.disconnect();
            s.close();
        }
        sessions.clear();
        if(router != null) {
            router.shutdown(Router.EXIT_HARD);
        }
        router = null;
        updateStatus(SensorStatus.SHUTDOWN);
        LOG.info("I2P router stopped.");
        return true;
    }

    @Override
    public boolean gracefulShutdown() {
        updateStatus(SensorStatus.GRACEFULLY_SHUTTING_DOWN);
        LOG.info("I2P router gracefully stopping...");
        checkRouterStats.forceStop();
        taskRunner.shutdown();
        if(taskRunnerThread!=null) {
            taskRunnerThread.interrupt();
        }
        taskRunner = null;
        taskRunnerThread = null;
        for(SensorSession s : sessions.values()) {
            s.disconnect();
            s.close();
        }
        sessions.clear();
        if(router != null) {
            router.shutdownGracefully(Router.EXIT_GRACEFUL);
        }
        router = null;
        updateStatus(SensorStatus.GRACEFULLY_SHUTDOWN);
        LOG.info("I2P router gracefully stopped.");
        return true;
    }

    public void reportRouterStatus() {
        switch (i2pRouterStatus) {
            case UNKNOWN:
                LOG.info("Testing I2P Network...");
                updateStatus(SensorStatus.NETWORK_CONNECTING);
                break;
            case IPV4_DISABLED_IPV6_UNKNOWN:
                LOG.info("IPV4 Disabled but IPV6 Testing...");
                updateStatus(SensorStatus.NETWORK_CONNECTING);
                break;
            case IPV4_FIREWALLED_IPV6_UNKNOWN:
                LOG.info("IPV4 Firewalled but IPV6 Testing...");
                updateStatus(SensorStatus.NETWORK_CONNECTING);
                break;
            case IPV4_SNAT_IPV6_UNKNOWN:
                LOG.info("IPV4 SNAT but IPV6 Testing...");
                updateStatus(SensorStatus.NETWORK_CONNECTING);
                break;
            case IPV4_UNKNOWN_IPV6_FIREWALLED:
                LOG.info("IPV6 Firewalled but IPV4 Testing...");
                updateStatus(SensorStatus.NETWORK_CONNECTING);
                break;
            case OK:
                LOG.info("Connected to I2P Network.");
                restartAttempts = 0; // Reset restart attempts

                updateStatus(SensorStatus.NETWORK_CONNECTED);
                break;
            case IPV4_DISABLED_IPV6_OK:
                LOG.info("IPV4 Disabled but IPV6 OK: Connected to I2P Network.");
                restartAttempts = 0; // Reset restart attempts
                updateStatus(SensorStatus.NETWORK_CONNECTED);
                break;
            case IPV4_FIREWALLED_IPV6_OK:
                LOG.info("IPV4 Firewalled but IPV6 OK: Connected to I2P Network.");
                restartAttempts = 0; // Reset restart attempts
                updateStatus(SensorStatus.NETWORK_CONNECTED);
                break;
            case IPV4_SNAT_IPV6_OK:
                LOG.info("IPV4 SNAT but IPV6 OK: Connected to I2P Network.");
                restartAttempts = 0; // Reset restart attempts
                updateStatus(SensorStatus.NETWORK_CONNECTED);
                break;
            case IPV4_UNKNOWN_IPV6_OK:
                LOG.info("IPV4 Testing but IPV6 OK: Connected to I2P Network.");
                restartAttempts = 0; // Reset restart attempts
                updateStatus(SensorStatus.NETWORK_CONNECTED);
                break;
            case IPV4_OK_IPV6_FIREWALLED:
                LOG.info("IPV6 Firewalled but IPV4 OK: Connected to I2P Network.");
                restartAttempts = 0; // Reset restart attempts
                updateStatus(SensorStatus.NETWORK_CONNECTED);
                break;
            case IPV4_OK_IPV6_UNKNOWN:
                LOG.info("IPV6 Testing but IPV4 OK: Connected to I2P Network.");
                restartAttempts = 0; // Reset restart attempts
                updateStatus(SensorStatus.NETWORK_CONNECTED);
                break;
            case IPV4_DISABLED_IPV6_FIREWALLED:
                LOG.warning("IPV4 Disabled but IPV6 Firewalled. Connected to I2P network.");
                updateStatus(SensorStatus.NETWORK_CONNECTED);
                break;
            case DISCONNECTED:
                LOG.info("Disconnected from I2P Network.");
                updateStatus(SensorStatus.NETWORK_STOPPED);
                restart();
                break;
            case DIFFERENT:
                LOG.warning("Symmetric NAT: Error connecting to I2P Network.");
                updateStatus(SensorStatus.NETWORK_ERROR);
                break;
            case HOSED:
                LOG.warning("Unable to open UDP port for I2P - Port Conflict. Verify another instance of I2P is not running.");
                updateStatus(SensorStatus.NETWORK_PORT_CONFLICT);
                break;
            case REJECT_UNSOLICITED:
                LOG.warning("Blocked. Unable to connect to I2P network.");
                if(startTimeBlockedMs==0) {
                    startTimeBlockedMs = System.currentTimeMillis();
                    updateStatus(SensorStatus.NETWORK_BLOCKED);
                } else if((System.currentTimeMillis() - startTimeBlockedMs) > BLOCK_TIME_UNTIL_RESTART) {
                    restart();
                    startTimeBlockedMs = 0L; // Restart the clock to give it some time to connect
                } else {
                    updateStatus(SensorStatus.NETWORK_BLOCKED);
                }
                break;
            default: {
                LOG.warning("Not connected to I2P Network.");
                updateStatus(SensorStatus.NETWORK_STOPPED);
            }
        }
        if(getStatus()==SensorStatus.NETWORK_CONNECTED && sessions.size()==0) {
            LOG.info("Network Connected and no Sessions.");
            if(routerContext.commSystem().isInStrictCountry()) {
                LOG.warning("This peer is in a 'strict' country defined by I2P.");
            }
            if(routerContext.router().isHidden()) {
                LOG.warning("Router was placed in Hidden mode. 1M5 setting for hidden mode: "+properties.getProperty("1m5.sensors.i2p.hidden"));
            }
            LOG.info("Establishing Session to speed up future outgoing messages...");
            establishSession(null, true);
            if(discovery==null) {
                LOG.info("I2P NetworkPeerDiscovery not instantiated; adding to TaskRunner...");
                discovery = new NetworkPeerDiscovery(taskRunner, this);
                // ensure id set
                seedAI2P.setId(sensorManager.getPeerManager().getLocalNode().getNetworkPeer().getId());
                sensorManager.getPeerManager().savePeer(seedAI2P, true);
//                taskRunner.addTask(discovery);
            }
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
        File f = new File(i2pBaseDir,"router.config");
        boolean i2pBaseRouterConfigIsNew = false;
        if(!f.exists()) {
            if(!f.mkdir()) {
                LOG.warning("While merging router.config files, unable to create router.config in i2pBaseDirectory: "+i2pBaseDir);
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
     *  Copy all certificates found in io/onemfive/network/sensors/i2p/certificates on classpath
     *  into i2pBaseDir/certificates
     *
     *  @param reseedCertificates destination directory for reseed certificates
     *  @param sslCertificates destination directory for ssl certificates
     */
    private boolean copyCertificatesToBaseDir(File reseedCertificates, File sslCertificates) {
        final String path = "io/onemfive/network/sensors/i2p";
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
                        if (name.startsWith(path + "/certificates/reseed/")) { //filter according to the path
                            if (!name.endsWith("/")) {
                                String fileName = name.substring(name.lastIndexOf("/") + 1);
                                LOG.info("fileName to save: " + fileName);
                                f = new File(reseedCertificates, fileName);
                            }
                        }
                        if (name.startsWith(path + "/certificates/ssl/")) {
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
                URL resource = io.onemfive.network.sensors.i2p.I2PSensor.class.getClassLoader().getResource(path);
                File file = null;
                try {
                    file = new File(resource.toURI());
                } catch (URISyntaxException e) {
                    LOG.warning("Unable to access I2P resource directory.");
                    return false;
                }
                File[] boteResFolderFiles = file.listFiles();
                File certResFolder = null;
                for (File f : boteResFolderFiles) {
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
        File f = new File(args[0]);
        if(!f.exists() && !f.mkdir()) {
            System.out.println("Unable to create directory "+args[0]);
            System.exit(-1);
        }
        Properties p = new Properties();
        p.setProperty("1m5.dir.base",args[0]);
        p.setProperty("1m5.sensors.i2p.isTest","true");
        io.onemfive.network.sensors.i2p.I2PSensor s = new io.onemfive.network.sensors.i2p.I2PSensor();
        s.start(p);
    }

}
