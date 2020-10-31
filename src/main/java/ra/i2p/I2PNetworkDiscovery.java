package ra.i2p;

import ra.common.DLC;
import ra.common.Envelope;
import ra.common.network.NetworkPeer;
import ra.common.network.NetworkStatus;
import ra.util.tasks.BaseTask;
import ra.util.tasks.TaskRunner;

import java.util.ArrayList;
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
                && service.getNumberKnownPeers() < service.maxKnownPeers) {
            if(service.getNumberKnownPeers()==0) {
                // Use Seeds
                List<NetworkPeer> seedPeers = new ArrayList<>(seeds.values());
                for(NetworkPeer seed : seedPeers) {
                    Envelope e = Envelope.documentFactory();
                    DLC.addExternalRoute(I2PService.class, I2PService.OPERATION_SEND, e, service.getNetworkState().localPeer, seed);
                    DLC.mark("NetOpReq", e);
                    service.sendOut(e);
                }
            }
        }
        return true;
    }


}
