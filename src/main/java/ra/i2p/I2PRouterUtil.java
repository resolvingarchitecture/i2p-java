package ra.i2p;

import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import ra.common.SystemSettings;

import java.io.File;
import java.io.IOException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * TODO: Add Description
 */
class I2PRouterUtil {

    private static final Logger LOG = Logger.getLogger(I2PRouterUtil.class.getName());

    public static Router getGlobalI2PRouter(Properties properties, boolean autoStart) {
        Router globalRouter = null;
        RouterContext routerContext = RouterContext.listContexts().get(0);
        if(routerContext != null) {
            globalRouter = routerContext.router();
            if(globalRouter == null) {
                LOG.info("Instantiating I2P Router...");
                File baseDir = null;
                try {
                    baseDir = SystemSettings.getUserAppHomeDir(".ra","i2p", true);
                } catch (IOException e) {
                    LOG.severe(e.getLocalizedMessage());
                    return null;
                }
                String baseDirPath = baseDir.getAbsolutePath();
                System.setProperty("i2p.dir.base", baseDirPath);
                System.setProperty("i2p.dir.config", baseDirPath);
                System.setProperty("wrapper.logfile", baseDirPath + "/wrapper.log");
                globalRouter = new Router(properties);
            }
            if(autoStart && !globalRouter.isAlive()) {
                LOG.info("Starting I2P Router...");
                globalRouter.setKillVMOnEnd(false);
                globalRouter.runRouter();
            }
        }
        return globalRouter;
    }

}
