package ra.i2p;

import ra.common.DLC;
import ra.common.Envelope;
import ra.common.network.NetworkPeer;
import ra.common.network.NetworkStatus;
import ra.util.tasks.BaseTask;
import ra.util.tasks.TaskRunner;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class I2PNetworkDiscovery extends BaseTask {

    private static final Logger LOG = Logger.getLogger(I2PNetworkDiscovery.class.getName());

    private I2PService service;
    private Map<String,NetworkPeer> seeds;

    public I2PNetworkDiscovery(I2PService service, Map<String,NetworkPeer> seeds, TaskRunner taskRunner) {
        super(I2PNetworkDiscovery.class.getSimpleName(), taskRunner);
        this.service = service;
        this.seeds = seeds;
    }

    @Override
    public Boolean execute() {
        if(service.getNetworkState().networkStatus == NetworkStatus.CONNECTED
                && service.getNumberPeers() < service.getMaxPeers()) {
            if(service.inflightTimers.size()>0) {
                LOG.warning(service.inflightTimers.size()+" in-flight timer(s) timed out.");
                synchronized (service.inflightTimers) {
                    service.inflightTimers.clear();
                }
            }
            if(service.getNumberPeers()==0) {
                List<NetworkPeer> seedPeers = new ArrayList<>(seeds.values());
                for(NetworkPeer seed : seedPeers) {
                    // Do not send it to yourself if you are the seed
                    if(!service.getNetworkState().localPeer.getDid().getPublicKey().getFingerprint().equals(seed.getDid().getPublicKey().getFingerprint())) {
                        Envelope e = Envelope.documentFactory();
                        service.inflightTimers.put(e.getId(), new Date().getTime());
                        DLC.addContent(service.getPeers(), e);
                        DLC.addExternalRoute(I2PService.class, I2PService.OPERATION_SEND, e, service.getNetworkState().localPeer, seed);
                        DLC.mark("NetOpReq", e);
                        e.ratchet();
                        service.sendOut(e);
                    }
                }
            } else {
                NetworkPeer toPeer = service.getRandomPeer();
                Envelope e = Envelope.documentFactory();
                service.inflightTimers.put(e.getId(), new Date().getTime());
                DLC.addContent(service.getPeers(), e);
                DLC.addExternalRoute(I2PService.class, I2PService.OPERATION_SEND, e, service.getNetworkState().localPeer, toPeer);
                DLC.mark("NetOpReq", e);
                e.ratchet();
                service.sendOut(e);
            }
        }
        return true;
    }


}
