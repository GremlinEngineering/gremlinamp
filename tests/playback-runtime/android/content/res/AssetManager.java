package android.content.res;

import java.io.FileNotFoundException;
import java.io.InputStream;

public class AssetManager {
    public InputStream open(String name) throws FileNotFoundException {
        throw new FileNotFoundException(name);
    }
}
