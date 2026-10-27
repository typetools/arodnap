package wrapper;

import java.io.IOException;

/** Uses the wrapper and never releases it: only visible once the wrapper can be closed. */
public class WrapperClient {
    public int first(String path) throws IOException {
        Wrapper wrapper = new Wrapper(path);
        return wrapper.read();
    }
}
