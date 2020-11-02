package ra.i2p;

import ra.common.DLC;
import ra.common.Envelope;
import ra.common.network.Network;
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
        // TODO: Load multiple seeds from a seeds.json file
        NetworkPeer seedA = new NetworkPeer(Network.I2P.name());
        seedA.getDid().getPublicKey().setAddress("I7SBNbVvrKB3thzOW6g49Mh6GpGZW~SiCwP~SgavJjy7lOWau2G2e71hgM1t7ymTRPIm9qfjP6g1tuzoP6eN3KRnnfYniISkvgvu5MU27Bvnf2BnIpiDGCfvmgIltUefX3ZVa7GSFtnTJobTlxFa0JEjfMSupuhEOnsApobo~Ux8DfSuoFfD0Fx9IdeBvMi~4nJHK7bGAx~LiNwdYVTGVwIEW0lGlEi8sLpymb0VhCxl8yo79AUWH-gD4LUJwy8ZVvovp0C2-BnWAwuIVPSWNepHB7Z6a0v6TF70lVZoXmJICDKho72uejYVgptZ~ugSdZRrXS6OiraMq1G39eLSSkxKQGgxL4G3-L~Mm5AYYg49G48KN1XJdROOjQSCxp3cRD1tbsjCVvB4xkjbmv-TbHF9OmrDzqwlT6WWigxxPMv~EyHmGJmanz80aOf3cJOHAd7OjK2sDfVPoqFW1NCt4vq4Nbu4wzUQeakwbB~eZS7NkuINqlVc06ke34MXgjYEAAAA");
        seedA.getDid().getPublicKey().setFingerprint("WLlzrHpbI2ABJShBCFJF5f1nh1CI6U2iT6~HS2Al~~U=");
        seeds.put(seedA.getDid().getPublicKey().getFingerprint(), seedA);
    }

    @Override
    public Boolean execute() {
        if(service.getNetworkState().networkStatus == NetworkStatus.CONNECTED
                && service.getNumberKnownPeers() < service.getMaxKnownPeers()) {
            if(service.getNumberKnownPeers()==0) {
                // Use Seeds
                List<NetworkPeer> seedPeers = new ArrayList<>(seeds.values());
                for(NetworkPeer seed : seedPeers) {
                    Envelope e = Envelope.documentFactory();
                    DLC.addContent(service.getKnownPeers(), e);
                    DLC.addExternalRoute(I2PService.class, I2PService.OPERATION_SEND, e, service.getNetworkState().localPeer, seed);
                    DLC.mark("NetOpReq", e);
                    // Ratchet
                    e.setRoute(e.getDynamicRoutingSlip().nextRoute());
                    service.sendOut(e);
                }
            }
        }
        return true;
    }


}
