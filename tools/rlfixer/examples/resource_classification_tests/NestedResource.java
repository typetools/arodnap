/* Warning: 3 types of warnings possible
NestedResource.java,11,null#
NestedResource.java,12,null
NestedResource.java,11,null#NestedResource.java,12,null
*/

import java.io.FileWriter;  
import java.io.IOException;  
import java.io.Closeable;
public class NestedResource {
	public static void main(String[] args){
		try {
	        FileWriter a = new FileWriter("filename.txt");
	        NestedResourceWrapper rw = new NestedResourceWrapper(a);
	        a.write("Files in Java might be tricky!");
	    } catch (IOException e) {
	        e.printStackTrace();
	    }
	}
}

class NestedResourceWrapper implements Closeable {
	FileWriter fw;

	public NestedResourceWrapper(FileWriter a){
		fw = a;
	}

	@Override
	public void close() throws IOException {
		fw.close();
	}
}