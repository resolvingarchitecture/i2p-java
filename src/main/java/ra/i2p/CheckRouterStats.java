package ra.i2p;


import ra.util.tasks.TaskRunner;

public class CheckRouterStats extends NetworkTask {

    public CheckRouterStats(TaskRunner taskRunner, I2P sensor) {
        super(CheckRouterStats.class.getName(), taskRunner, sensor);
    }

    @Override
    public Boolean execute() {
        ((I2P)sensor).checkRouterStats();
        return true;
    }
}
