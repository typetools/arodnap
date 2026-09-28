package com.arodnap.fixture;

public class BaselineSmoke {

    public DirectLeakExample directLeak() {
        return new DirectLeakExample();
    }

    public TryCatchLeakExample tryCatchLeak() {
        return new TryCatchLeakExample();
    }
}
