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

public class I2PNetworkDiscovery extends BaseTask {

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
                && service.getNumberKnownPeers() < service.getMaxKnownPeers()) {
            if(service.getNumberKnownPeers()==0) {
                List<NetworkPeer> seedPeers = new ArrayList<>(seeds.values());
                for(NetworkPeer seed : seedPeers) {
                    Envelope e = Envelope.documentFactory();
                    service.inflightTimers.put(e.getId(), new Date().getTime());
                    DLC.addContent(service.getKnownPeers(), e);
                    DLC.addExternalRoute(I2PService.class, I2PService.OPERATION_SEND, e, service.getNetworkState().localPeer, seed);
                    DLC.mark("NetOpReq", e);
                    e.ratchet();
                    service.sendOut(e);
                }
            } else {
                NetworkPeer toPeer = service.getRandomKnownPeer();
                Envelope e = Envelope.documentFactory();
                service.inflightTimers.put(e.getId(), new Date().getTime());
                DLC.addContent(service.getKnownPeers(), e);
                DLC.addExternalRoute(I2PService.class, I2PService.OPERATION_SEND, e, service.getNetworkState().localPeer, toPeer);
                DLC.mark("NetOpReq", e);
                e.ratchet();
                service.sendOut(e);
            }
        }
        return true;
    }


}
