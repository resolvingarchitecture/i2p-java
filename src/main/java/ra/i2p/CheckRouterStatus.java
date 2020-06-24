package ra.i2p;

import ra.util.tasks.BaseTask;
import ra.util.tasks.TaskRunner;

class CheckRouterStatus extends BaseTask {

    private I2PService service;

    public CheckRouterStatus(I2PService service, TaskRunner taskRunner) {
        super(CheckRouterStatus.class.getSimpleName(), taskRunner);
        this.service = service;
    }

    @Override
    public Boolean execute() {
        service.checkRouterStats();
        return true;
    }
}
